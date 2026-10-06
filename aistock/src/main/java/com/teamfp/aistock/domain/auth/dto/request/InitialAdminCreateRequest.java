package com.teamfp.aistock.domain.auth.dto.request;

import com.teamfp.aistock.global.util.MaxByteSize;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 최초 관리자 계정 생성 요청 DTO(feat/admin-improvements). 관리자가 한 명도 없을 때 뜨는 "최초 관리자 만들기"
 * 모달에서 보낸다. 아이디·비밀번호·이름·이메일 규칙은 관리자 페이지의 관리자 생성(AdminCreateRequest)과 같고,
 * setupCode는 서버 설정값(ADMIN_SIGNUP_CODE)과 같아야 하는 관리자 인증 코드다.
 */
public record InitialAdminCreateRequest(
        @NotBlank(message = "아이디는 필수 입력 값입니다.")
        @Size(max = 50, message = "아이디는 50자를 넘을 수 없습니다.")
        @Pattern(regexp = "[A-Za-z0-9_]{4,50}", message = "아이디는 영문, 숫자, 밑줄 4~50자입니다.")
        String loginId,

        @NotBlank(message = "비밀번호는 필수 입력 값입니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
                message = "비밀번호는 8자 이상, 영문과 숫자를 포함해야 합니다."
        )
        @MaxByteSize(max = 72, message = "비밀번호는 72바이트를 초과할 수 없습니다.")
        String password,

        @NotBlank(message = "이름은 필수 입력 값입니다.")
        @Size(max = 50, message = "이름은 50자를 넘을 수 없습니다.")
        String name,

        @NotBlank(message = "이메일은 필수 입력 값입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Size(max = 100, message = "이메일은 100자를 넘을 수 없습니다.")
        String email,

        @NotBlank(message = "관리자 인증 코드는 필수 입력 값입니다.")
        @Size(max = 100, message = "관리자 인증 코드는 100자를 넘을 수 없습니다.")
        String setupCode
) {
}
