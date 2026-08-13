package com.teamfp.aistock.global.util;

import java.nio.charset.Charset;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link MaxByteSize}의 실제 검증 로직. null은 여기서 통과시킨다 — 필수 입력 여부는
 * {@code @NotBlank}/{@code @NotNull} 등 다른 어노테이션이 이미 담당하므로, 이 검증기는
 * 값이 있을 때의 바이트 길이만 책임진다(Bean Validation의 관례).
 */
public class MaxByteSizeValidator implements ConstraintValidator<MaxByteSize, String> {

    private int max;
    private Charset charset;

    @Override
    public void initialize(MaxByteSize constraintAnnotation) {
        this.max = constraintAnnotation.max();
        this.charset = Charset.forName(constraintAnnotation.charset());
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.getBytes(charset).length <= max;
    }
}
