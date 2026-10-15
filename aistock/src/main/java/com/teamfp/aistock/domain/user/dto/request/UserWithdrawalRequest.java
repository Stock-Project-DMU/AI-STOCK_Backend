package com.teamfp.aistock.domain.user.dto.request;

import jakarta.validation.constraints.Email;

/**
 * 회원 탈퇴 확인 — 일반 계정은 비밀번호, 소셜 계정은 등록된 이메일로 본인을 확인한다. 이메일이 없는 소셜 계정(카카오 이메일
 * 제공 미동의 등)은 확인할 이메일이 없어 confirmText에 "탈퇴하겠습니다"를 정확히 입력해 확인한다(2026-10-13).
 * 관리자 계정은 "계정 폐기"(feat/admin-improvements)라 adminCode(관리자 인증 코드 — 최초 관리자 생성 때 쓰는
 * ADMIN_SIGNUP_CODE)도 맞아야 한다. 일반 회원은 adminCode를 쓰지 않는다.
 */
public record UserWithdrawalRequest(
        String password,
        @Email(message = "올바른 이메일을 입력해 주세요.") String email,
        String adminCode,
        String confirmText
) {
    public UserWithdrawalRequest(String password, String email) {
        this(password, email, null, null);
    }

    public UserWithdrawalRequest(String password, String email, String adminCode) {
        this(password, email, adminCode, null);
    }
}
