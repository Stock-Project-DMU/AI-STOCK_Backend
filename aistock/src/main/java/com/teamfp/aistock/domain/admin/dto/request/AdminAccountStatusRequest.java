package com.teamfp.aistock.domain.admin.dto.request;

import com.teamfp.aistock.domain.account.entity.AccountStatus;

import jakarta.validation.constraints.NotNull;

/**
 * 관리자 — 계좌 거래 정지상태 변경 요청 DTO. SUSPENDED로 보내면 매수·매도 주문만 차단되고
 * 로그인은 그대로 가능하다(CLAUDE.md 8번 — accounts.status는 관리자에 의한 계좌 거래 정지 용도).
 */
public record AdminAccountStatusRequest(

        @NotNull(message = "변경할 상태값은 필수입니다.")
        AccountStatus status
) {
}
