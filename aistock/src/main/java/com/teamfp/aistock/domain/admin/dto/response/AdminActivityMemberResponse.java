package com.teamfp.aistock.domain.admin.dto.response;

/**
 * 전체 활동 기록 — 회원이력 한 줄의 상세(feat/admin-improvements). 가입·탈퇴는 값이 없고(null), 회원 정지·해제,
 * 계좌(거래) 정지·해제, 관리자 계정 생성은 감사 로그의 변경 전후 값·사유·처리 관리자를 담는다. accountNumber는
 * 계좌 정지·해제일 때만 채워진다.
 */
public record AdminActivityMemberResponse(
        String beforeValue,
        String afterValue,
        String reason,
        String adminLoginId,
        String accountNumber
) {
}
