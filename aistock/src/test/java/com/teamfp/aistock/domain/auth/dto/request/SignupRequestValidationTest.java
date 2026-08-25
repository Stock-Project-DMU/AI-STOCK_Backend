package com.teamfp.aistock.domain.auth.dto.request;

import java.time.LocalDate;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SignupRequest.password에 붙인 @MaxByteSize(max=72)가 실제 Bean Validation 파이프라인에
 * 올바르게 연결돼 있는지 확인한다(코드리뷰 반영). MaxByteSizeValidatorTest가 바이트 길이
 * 계산 로직 자체를 검증한다면, 이 테스트는 애노테이션이 필드에 제대로 붙어 ConstraintViolation을
 * 실제로 발생시키는지(@Target/@Constraint 연결 실수가 없는지)를 확인하는 배선(wiring) 테스트다.
 */
class SignupRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private SignupRequest requestWithPassword(String password) {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "loginId", "tester");
        ReflectionTestUtils.setField(request, "password", password);
        ReflectionTestUtils.setField(request, "name", "테스터");
        ReflectionTestUtils.setField(request, "email", "tester@example.com");
        ReflectionTestUtils.setField(request, "birthdate", LocalDate.of(2000, 1, 1));
        return request;
    }

    @Test
    @DisplayName("72바이트 이하 비밀번호는 password 관련 위반이 없다")
    void password_withinByteLimit_noViolation() {
        SignupRequest request = requestWithPassword("a1" + "a".repeat(70)); // 영문+숫자 조건 충족, 총 72바이트

        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("password");
    }

    @Test
    @DisplayName("글자 수는 72자 미만이지만 바이트 수가 72를 넘는 비밀번호는 MaxByteSize 위반이 발생한다")
    void password_underCharLimitButOverByteLimit_violatesMaxByteSize() {
        // 한글 24자(72바이트) + 영문 1자 = 글자 수 25(72자 미만)지만 바이트 수는 73바이트.
        // @Pattern이 요구하는 영문+숫자 조건도 만족시키기 위해 숫자 1자를 덧붙인다.
        String password = "가".repeat(24) + "a1";
        SignupRequest request = requestWithPassword(password);

        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("password");
    }
}
