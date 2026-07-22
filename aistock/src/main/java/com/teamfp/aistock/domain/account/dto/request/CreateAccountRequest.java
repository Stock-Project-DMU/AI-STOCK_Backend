package com.teamfp.aistock.domain.account.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 계좌 개설 요청 DTO. 유저 1명이 성향별로 계좌를 나눠 쓸 수 있도록(최대 3개)
 * 계좌 이름만 입력받는다. 잔고(balance/baseBalance)는 항상 서버가 고정 1000만원으로 채운다.
 */
public record CreateAccountRequest(

        @NotBlank(message = "계좌 이름은 필수입니다.")
        String accountName
) {
}
