package com.teamfp.aistock.domain.auth.dto.request;

import com.teamfp.aistock.domain.user.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class SignupRequest {

    @NotBlank(message = "아이디는 필수 입력 값입니다.")
    private String loginId;

    @NotBlank(message = "비밀번호는 필수 입력 값입니다.")
    @Pattern(
            regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
            message = "비밀번호는 8자 이상, 영문과 숫자를 포함해야 합니다."
    )
    private String password;

    @NotBlank(message = "이름은 필수 입력 값입니다.")
    private String name;

    @NotBlank(message = "이메일은 필수 입력 값입니다.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    private String email;

    @NotNull(message = "생년월일은 필수 입력 값입니다.")
    private LocalDate birthdate;

    // 미입력 시 서비스 계층에서 Role.USER로 처리
    private Role role;

    // role=ADMIN일 때만 필수 (서비스 계층에서 ADMIN_SIGNUP_CODE와 대조)
    private String adminCode;
}
