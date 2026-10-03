package com.teamfp.aistock.domain.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 계좌 개설 요청 DTO. 유저 1명당 계좌는 1개(2026-10-01, 원래 최대 3개)이며
 * 계좌 이름만 입력받는다. 잔고(balance/baseBalance)는 항상 서버가 고정 1000만원으로 채운다.
 */
public record CreateAccountRequest(

        @NotBlank(message = "계좌 이름은 필수입니다.")
        @Size(max = 50, message = "계좌 이름은 50자를 초과할 수 없습니다.")
        String accountName
) {
}
