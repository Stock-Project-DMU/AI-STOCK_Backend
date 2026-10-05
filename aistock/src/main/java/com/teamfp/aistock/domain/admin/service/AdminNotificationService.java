package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.admin.dto.request.AdminNotificationRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminNotificationSendRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminNotificationTargetType;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchMatchType;
import com.teamfp.aistock.domain.admin.dto.response.AdminNotificationSendResponse;
import com.teamfp.aistock.domain.notification.entity.Notice;
import com.teamfp.aistock.domain.notification.entity.NoticeTargetType;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.repository.NoticeRepository;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 — 사용자 알림 발송(ADMIN_API_BACKEND_HANDOFF.md 4.4). admin 도메인은 자체
 * Entity/Repository를 두지 않고 notification 도메인의 NotificationService.notify()를 그대로
 * 재사용한다(CLAUDE.md 4번) — DB 저장 + 커밋 후 STOMP 유니캐스팅까지 이미 처리해주므로
 * 알림 저장 로직을 여기서 중복 구현하지 않는다.
 *
 * feat/admin-improvements: 회원을 검색·체크해서 보내는 선택 발송(send)을 추가했고, 세 발송 모두 공지(notices) 한 건을
 * 남기고(관리자 알림 관리 — AdminNoticeService) 공지 번호·발송 대상 수·실제 저장된 수(AdminNotificationSendResponse)를
 * 돌려준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    static final String NO_TARGET_MESSAGE = "알림을 보낼 회원을 한 명 이상 선택해 주세요.";
    static final String NO_RECIPIENT_MESSAGE = "보낼 회원이 없습니다. 받는 회원 조건을 확인해 주세요.";
    static final String INVALID_POPUP_END_DATE_MESSAGE = "팝업 게시 종료일(popupEndDate)은 오늘 이후 날짜로 입력해 주세요.";

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final NoticeRepository noticeRepository;

    // 경로의 userId를 그대로 믿지 않고 존재·활성 여부를 검증한다(CLAUDE.md 7번 공통 보안 요구사항).
    // AdminUserService.findUser()와 동일하게 findByUserIdAndIsActiveTrue를 쓴다.
    // 세 발송 모두 일부러 @Transactional을 걸지 않는다 — sendTo() 참고.
    public AdminNotificationSendResponse notifyUser(Long adminUserId, Long userId, AdminNotificationRequest request) {
        LocalDate popupEndDate = popupEndDate(request.popup(), request.popupEndDate());
        userRepository.findByUserIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        Notice notice = createNotice(adminUserId, NoticeTargetType.SINGLE, request.type(), request.title(),
                request.content(), popupEndDate);
        return sendTo(notice, List.of(userId));
    }

    /**
     * 전체 발송(탈퇴하지 않은 회원 전체, 검색·제외 없음). NotificationService가 유저 1명당 DB insert 1건 +
     * 커밋 후 STOMP 유니캐스팅 1건을 처리하므로, 대상자 수만큼 순차 반복 호출한다. handoff 문서 4.4는
     * "대량 insert·비동기 처리 여부를 검토"하라고 남겨뒀지만, 이 서비스의 활성 유저 규모(모의투자
     * 학습 서비스, 수백~수천 단위)에서는 순차 처리로도 충분하다고 판단해 별도 배치/비동기
     * 처리는 넣지 않았다 — 이후 유저 규모가 실제로 커지면 그때 배치 insert로 전환한다.
     */
    public AdminNotificationSendResponse broadcast(Long adminUserId, AdminNotificationRequest request) {
        LocalDate popupEndDate = popupEndDate(request.popup(), request.popupEndDate());
        List<Long> targetIds = requireRecipients(userRepository.findAllActiveUserIds());
        Notice notice = createNotice(adminUserId, NoticeTargetType.ALL, request.type(), request.title(),
                request.content(), popupEndDate);
        return sendTo(notice, targetIds);
    }

    /**
     * 공지 선택 발송(feat/admin-improvements) — 회원관리 목록처럼 검색·체크한 회원에게 보낸다.
     * - ALL("전체 선택", 모든 페이지): 회원 목록 화면과 같은 검색 조건(query/field/matchType/status)에 맞는 회원
     *   전체에서 excludedUserIds(전체 선택 후 체크를 푼 회원)를 뺀다. 목록과 결과가 같도록
     *   UserRepository.searchUsers()를 그대로 쓴다.
     * - SELECTED(개별 체크·"현 페이지 선택"): userIds 중 탈퇴하지 않은 회원에게만 보낸다(없는·탈퇴한 번호는 건너뛴다).
     * 받을 회원이 한 명도 없으면 공지를 남기지 않고 INVALID_INPUT(NO_RECIPIENT_MESSAGE)으로 막는다.
     */
    public AdminNotificationSendResponse send(Long adminUserId, AdminNotificationSendRequest request) {
        LocalDate popupEndDate = popupEndDate(request.popup(), request.popupEndDate());
        boolean searchAll = request.targetType() == AdminNotificationTargetType.ALL;
        List<Long> targetIds = requireRecipients(searchAll ? searchTargetIds(request) : selectedTargetIds(request.userIds()));
        Notice notice = createNotice(adminUserId, searchAll ? NoticeTargetType.SEARCH : NoticeTargetType.SELECTED,
                request.type(), request.title(), request.content(), popupEndDate);
        return sendTo(notice, targetIds);
    }

    private List<Long> searchTargetIds(AdminNotificationSendRequest request) {
        AdminSearchConditionDto search = AdminSearchConditionDto.of(request.query(), request.field(),
                request.matchType() == null ? AdminSearchMatchType.CONTAINS : request.matchType());
        Set<Long> excluded = request.excludedUserIds() == null ? Set.of() : new HashSet<>(request.excludedUserIds());
        return userRepository.searchUsers(search.query(), search.pattern(), search.queryId(), search.field(),
                        search.exact(), request.status(), null, Pageable.unpaged())
                .stream()
                .map(User::getUserId)
                .filter(userId -> !excluded.contains(userId))
                .toList();
    }

    private List<Long> selectedTargetIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, NO_TARGET_MESSAGE);
        }
        return userRepository.findAllById(new LinkedHashSet<>(userIds)).stream()
                .filter(User::isActive)
                .map(User::getUserId)
                .toList();
    }

    private List<Long> requireRecipients(List<Long> targetIds) {
        if (targetIds.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, NO_RECIPIENT_MESSAGE);
        }
        return targetIds;
    }

    // 공지 팝업 옵션 — popup=true면 게시 종료일이 오늘 이후(포함)여야 하고, 아니면 팝업 없이(null) 보낸다.
    private LocalDate popupEndDate(Boolean popup, LocalDate popupEndDate) {
        if (!Boolean.TRUE.equals(popup)) {
            return null;
        }
        if (popupEndDate == null || popupEndDate.isBefore(LocalDate.now())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, INVALID_POPUP_END_DATE_MESSAGE);
        }
        return popupEndDate;
    }

    // 관리자 "알림 관리"에 남는 공지 한 건(feat/admin-improvements). 보낸 관리자 아이디는 보낸 시점 값을 남긴다.
    private Notice createNotice(Long adminUserId, NoticeTargetType targetType, NotificationType type, String title,
            String content, LocalDate popupEndDate) {
        String adminLoginId = userRepository.findById(adminUserId).map(User::getLoginId).orElse(null);
        return noticeRepository.save(Notice.builder()
                .type(type)
                .title(title)
                .content(content)
                .targetType(targetType)
                .popupEndDate(popupEndDate)
                .createdBy(adminUserId)
                .createdByLoginId(adminLoginId)
                .build());
    }

    /**
     * 실패한 회원만 빼고 보낸다(feat/admin-improvements 코드리뷰 반영). 예전에는 발송 전체를 한 트랜잭션으로 묶고
     * 회원별 try-catch만 했는데, NotificationService(@Transactional)에서 난 예외가 바깥 트랜잭션을 "롤백 예정"으로
     * 만들어 결국 전체가 취소되고 500이 났다. 그래서 바깥 트랜잭션을 없애고
     * - 공지는 먼저 저장(커밋)해 두고 — 받은 알림이 이 공지를 가리키므로 공지가 먼저 있어야 한다
     * - 회원마다 NotificationService.notifyNotice()가 각자 트랜잭션으로 저장·커밋한다 — 한 명이 실패해도 그 회원만 빠진다
     * - 끝나면 대상 수와 실제 저장된 수를 공지에 다시 저장한다
     */
    private AdminNotificationSendResponse sendTo(Notice notice, List<Long> userIds) {
        int sentCount = 0;
        for (Long userId : userIds) {
            try {
                notificationService.notifyNotice(userId, notice);
                sentCount++;
            } catch (RuntimeException e) {
                log.warn("알림 발송 중 유저 하나 실패, 나머지는 계속 진행 - userId={}", userId, e);
            }
        }
        notice.recordSendResult(userIds.size(), sentCount);
        noticeRepository.save(notice);
        return new AdminNotificationSendResponse(notice.getNoticeId(), userIds.size(), sentCount);
    }
}
