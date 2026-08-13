package com.teamfp.aistock.global.util;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MaxByteSize 제약의 실제 검증 로직(바이트 수 계산) 단위 테스트.
 *
 * SignupRequest.password에 원래 붙이려던 @Size(max=72)는 "글자 수" 기준이라, 한글처럼
 * UTF-8에서 3바이트를 차지하는 멀티바이트 문자가 섞이면 글자 수는 상한 이내인데도 실제
 * 바이트 수는 BCrypt의 72바이트 상한을 넘어버릴 수 있었다(코드리뷰 반영). 이 테스트는 그
 * 경계를 문자 수가 아니라 바이트 수로 정확히 판정하는지를 확인한다.
 */
class MaxByteSizeValidatorTest {

    // initialize()가 실제 애노테이션 인스턴스(@MaxByteSize(max=72)가 붙은 필드에서 리플렉션으로
    // 꺼낸 것)를 받아야 하므로, 손으로 구현체를 만드는 대신 이 더미 필드에서 그대로 가져온다.
    private static class Sample {
        @MaxByteSize(max = 72)
        private String value;
    }

    private MaxByteSizeValidator validator;

    @BeforeEach
    void setUp() throws NoSuchFieldException {
        validator = new MaxByteSizeValidator();
        Field field = Sample.class.getDeclaredField("value");
        validator.initialize(field.getAnnotation(MaxByteSize.class));
    }

    @Test
    @DisplayName("null은 통과시킨다 — 필수 입력 여부는 다른 어노테이션(@NotBlank 등)의 책임이다")
    void isValid_null_passes() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    @DisplayName("영문·숫자로만 이루어진 72바이트(=72자) 비밀번호는 통과한다")
    void isValid_asciiExactlyAtLimit_passes() {
        String password = "a".repeat(72);

        assertThat(validator.isValid(password, null)).isTrue();
    }

    @Test
    @DisplayName("영문·숫자로만 이루어진 73바이트(=73자) 비밀번호는 막는다")
    void isValid_asciiOverLimit_fails() {
        String password = "a".repeat(73);

        assertThat(validator.isValid(password, null)).isFalse();
    }

    @Test
    @DisplayName("한글은 글자당 3바이트라 24자(72바이트)까지는 통과하고 25자(75바이트)부터는 막는다")
    void isValid_multiByte_boundary() {
        String exactlyAtLimit = "가".repeat(24);
        String overLimit = "가".repeat(25);

        assertThat(validator.isValid(exactlyAtLimit, null)).isTrue();
        assertThat(validator.isValid(overLimit, null)).isFalse();
    }

    @Test
    @DisplayName("글자 수는 72자 미만이지만 멀티바이트 문자 때문에 바이트 수가 72를 넘으면 막는다" +
            " (@Size(max=72)였다면 놓쳤을 케이스)")
    void isValid_underCharLimitButOverByteLimit_fails() {
        // 한글 24자(72바이트, 경계) 뒤에 영문 1자를 더 붙이면 글자 수는 25자로 여전히 72자
        // 미만이지만, 바이트 수는 73바이트로 상한을 넘는다.
        String password = "가".repeat(24) + "a";

        assertThat(password.length()).isLessThan(72);
        assertThat(validator.isValid(password, null)).isFalse();
    }
}
