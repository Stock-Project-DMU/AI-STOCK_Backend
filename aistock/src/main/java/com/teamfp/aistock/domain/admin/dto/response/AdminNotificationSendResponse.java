package com.teamfp.aistock.domain.admin.dto.response;

/**
 * 관리자 알림 발송 결과(feat/admin-improvements) — 화면에 "○명에게 발송했습니다"를 보여주기 위한 값.
 * noticeId는 이번 발송으로 남은 공지 번호(관리자 알림 관리에서 조회).
 * targetCount는 실제 발송 대상(탈퇴·존재하지 않는 회원 제외) 수, sentCount는 그중 저장까지 성공한 수다.
 * 한 명 실패가 나머지를 막지 않으므로 두 값이 다를 수 있다.
 */
public record AdminNotificationSendResponse(
        Long noticeId,
        int targetCount,
        int sentCount
) {
}
