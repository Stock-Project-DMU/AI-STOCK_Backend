package com.teamfp.aistock.global.util;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 문자열의 "글자 수"가 아니라 "바이트 수"로 상한을 검증하는 커스텀 Bean Validation 제약.
 *
 * {@code @Size(max = ...)}는 문자 개수를 기준으로 검증해서, 한글처럼 UTF-8 기준 3바이트를
 * 차지하는 멀티바이트 문자가 섞이면 글자 수는 상한 이내인데도 실제 바이트 수는 상한을 넘는
 * 경우를 막지 못한다(예: SignupRequest.password — BCrypt는 72바이트를 넘는 입력을 뒷부분부터
 * 조용히 잘라버리므로, 글자 수가 아니라 실제 바이트 수 기준으로 막아야 한다).
 */
@Documented
@Constraint(validatedBy = MaxByteSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxByteSize {

    int max();

    // 대부분의 경우 인코딩을 지정할 일이 없어 UTF-8을 기본값으로 둔다.
    String charset() default "UTF-8";

    String message() default "허용된 바이트 길이를 초과했습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
