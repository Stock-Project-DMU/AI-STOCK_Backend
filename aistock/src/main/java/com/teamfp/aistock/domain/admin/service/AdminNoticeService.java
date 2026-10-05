package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.response.AdminNoticeResponse;
import com.teamfp.aistock.domain.notification.entity.Notice;
import com.teamfp.aistock.domain.notification.repository.NoticeRepository;
import com.teamfp.aistock.domain.notification.repository.NotificationRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 "알림 관리"(feat/admin-improvements) — 보낸 공지(notices)를 한 건 단위로 조회하고, 팝업 기한을 바꾸거나 바로
 * 내리고, 삭제한다. 삭제하면 받은 회원들 알림함의 그 공지 알림도 함께 지운다(잘못 보낸 공지 회수).
 * 기한 변경·종료·삭제는 감사 로그(NOTICE_POPUP_CHANGE / NOTICE_DELETE)에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class AdminNoticeService {

    static final String INVALID_POPUP_END_DATE_MESSAGE = "팝업 게시 종료일(popupEndDate)은 오늘 이후 날짜로 입력해 주세요.";
    static final String POPUP_END_REASON = "공지 팝업 바로 종료";
    static final String POPUP_CHANGE_REASON = "공지 팝업 기한 변경";
    static final String DELETE_REASON = "공지 삭제(받은 회원 알림 함께 삭제)";

    private final NoticeRepository noticeRepository;
    private final NotificationRepository notificationRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public Page<AdminNoticeResponse> getNotices(AdminSearchConditionDto search, Boolean popup, Pageable pageable) {
        LocalDate today = LocalDate.now();
        return noticeRepository.search(search.query(), search.pattern(), search.queryId(), search.field(), search.exact(),
                        popup, pageable)
                .map(notice -> AdminNoticeResponse.from(notice, today));
    }

    @Transactional(readOnly = true)
    public AdminNoticeResponse getNoticeDetail(Long noticeId) {
        Notice notice = findNotice(noticeId);
        return AdminNoticeResponse.of(notice, LocalDate.now(), notificationRepository.countByNoticeId(noticeId),
                notificationRepository.countReadByNoticeId(noticeId));
    }

    // 팝업 기한 변경 — 오늘 이후(포함)만. 일반 공지였다면 이때부터 팝업이 된다.
    @Transactional
    public AdminNoticeResponse changePopupEndDate(Long adminUserId, Long noticeId, LocalDate popupEndDate) {
        LocalDate today = LocalDate.now();
        if (popupEndDate == null || popupEndDate.isBefore(today)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, INVALID_POPUP_END_DATE_MESSAGE);
        }
        Notice notice = findNotice(noticeId);
        String before = valueOf(notice.getPopupEndDate());
        notice.changePopupEndDate(popupEndDate);
        auditLogService.record(adminUserId, AuditLogService.ACTION_NOTICE_POPUP_CHANGE, AuditLogService.TARGET_NOTICE,
                noticeId, before, valueOf(popupEndDate), POPUP_CHANGE_REASON);
        return AdminNoticeResponse.from(notice, today);
    }

    // 팝업 바로 종료 — 어제 날짜로 바꿔 오늘부터 안 뜬다. 팝업이 아니었던 공지는 그대로 둔다.
    @Transactional
    public AdminNoticeResponse endPopup(Long adminUserId, Long noticeId) {
        LocalDate today = LocalDate.now();
        Notice notice = findNotice(noticeId);
        if (notice.isPopupActive(today)) {
            String before = valueOf(notice.getPopupEndDate());
            notice.endPopup(today);
            auditLogService.record(adminUserId, AuditLogService.ACTION_NOTICE_POPUP_CHANGE, AuditLogService.TARGET_NOTICE,
                    noticeId, before, valueOf(notice.getPopupEndDate()), POPUP_END_REASON);
        }
        return AdminNoticeResponse.from(notice, today);
    }

    // 공지 삭제 — 받은 회원들 알림함의 알림부터 지우고 공지를 지운다.
    @Transactional
    public void deleteNotice(Long adminUserId, Long noticeId) {
        String title = findNotice(noticeId).getTitle();
        int deletedCount = notificationRepository.deleteByNoticeId(noticeId);
        noticeRepository.deleteById(noticeId);
        auditLogService.record(adminUserId, AuditLogService.ACTION_NOTICE_DELETE, AuditLogService.TARGET_NOTICE,
                noticeId, title, "알림 " + deletedCount + "건 삭제", DELETE_REASON);
    }

    private Notice findNotice(Long noticeId) {
        return noticeRepository.findById(noticeId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTICE_NOT_FOUND));
    }

    private String valueOf(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
