package com.teamfp.aistock.domain.auth.dto.request;

import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.global.util.MaxByteSize;
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
    @Pattern(regexp = "[A-Za-z0-9_]{4,50}", message = "아이디는 영문, 숫자, 밑줄 4~50자입니다.")
    private String loginId;

    @NotBlank(message = "비밀번호는 필수 입력 값입니다.")
    @Pattern(
            regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
            message = "비밀번호는 8자 이상, 영문과 숫자를 포함해야 합니다."
    )
    // BCrypt는 72바이트를 넘는 입력을 뒷부분부터 잘라버린다. @Size(max=72)는 "글자 수" 기준이라
    // 한글처럼 멀티바이트 문자가 섞이면 72자 미만인데도 실제로는 72바이트를 넘어 여전히 잘릴 수
    // 있으므로, 실제 바이트 수를 기준으로 검증하는 커스텀 제약(MaxByteSize)을 쓴다(코드리뷰 반영).
    @MaxByteSize(max = 72, message = "비밀번호는 72바이트를 초과할 수 없습니다.")
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
    private com.teamfp.aistock.domain.user.entity.InvestmentLevel investmentLevel;
}
