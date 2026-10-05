package com.teamfp.aistock.domain.user.dto.request;

import jakarta.validation.constraints.Email;

/**
 * 회원 탈퇴 확인 — 일반 계정은 비밀번호, 소셜 계정은 등록된 이메일로 본인을 확인한다.
 * 관리자 계정은 "계정 폐기"(feat/admin-improvements)라 adminCode(관리자 인증 코드 — 최초 관리자 생성 때 쓰는
 * ADMIN_SIGNUP_CODE)도 맞아야 한다. 일반 회원은 adminCode를 쓰지 않는다.
 */
public record UserWithdrawalRequest(
        String password,
        @Email(message = "올바른 이메일을 입력해 주세요.") String email,
        String adminCode
) {
    public UserWithdrawalRequest(String password, String email) {
        this(password, email, null);
    }
}
