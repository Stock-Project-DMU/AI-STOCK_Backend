package com.teamfp.aistock.domain.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.admin.dto.request.AdminNotificationRequest;
import com.teamfp.aistock.domain.notification.service.NotificationService;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    private final UserRepository userRepository;
    private final NotificationService notificationService;

    // 경로의 userId를 그대로 믿지 않고 존재·활성 여부를 검증한다(CLAUDE.md 7번 공통 보안 요구사항).
    // AdminUserService.findUser()와 동일하게 findByUserIdAndIsActiveTrue를 쓴다.
    @Transactional
    public void notifyUser(Long userId, AdminNotificationRequest request) {
        userRepository.findByUserIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        notificationService.notify(userId, request.type(), request.title(), request.content());
    }

    /**
     * 전체 발송. NotificationService.notify()가 유저 1명당 DB insert 1건 + 커밋 후 STOMP
     * 유니캐스팅 1건을 처리하므로, 대상자 수만큼 순차 반복 호출한다. handoff 문서 4.4는
     * "대량 insert·비동기 처리 여부를 검토"하라고 남겨뒀지만, 이 서비스의 활성 유저 규모(모의투자
     * 학습 서비스, 수백~수천 단위)에서는 순차 처리로도 충분하다고 판단해 별도 배치/비동기
     * 처리는 넣지 않았다 — 이후 유저 규모가 실제로 커지면 그때 배치 insert로 전환한다.
     *
     * 유저 한 명 처리(notify())를 개별 try-catch로 감싼다(코드리뷰 반영, 2026-09) — 감싸지
     * 않으면 목록 중간의 한 명에서 예외(일시적 DB 오류 등)가 나는 순간 이 메서드 전체가
     * @Transactional이라 트랜잭션 전체가 롤백되어, 이미 처리된 앞쪽 유저들의 알림까지 전부
     * 취소된다. OrderExecutionService.checkAndExecute()가 "하나의 주문 실패가 나머지를 막으면
     * 안 된다"며 쓴 것과 동일한 패턴 — 전체 발송은 실패한 한 명 때문에 나머지 전원이 못 받는
     * 것보다 가능한 한 많은 사람에게 도착하는 게 맞다.
     */
    @Transactional
    public void broadcast(AdminNotificationRequest request) {
        List<Long> activeUserIds = userRepository.findAllActiveUserIds();
        for (Long userId : activeUserIds) {
            try {
                notificationService.notify(userId, request.type(), request.title(), request.content());
            } catch (RuntimeException e) {
                log.warn("전체 알림 발송 중 유저 하나 실패, 나머지는 계속 진행 - userId={}", userId, e);
            }
        }
    }
}
