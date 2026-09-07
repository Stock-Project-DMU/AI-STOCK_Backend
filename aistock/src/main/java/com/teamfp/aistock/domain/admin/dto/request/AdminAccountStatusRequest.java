package com.teamfp.aistock.domain.admin.dto.request;

import com.teamfp.aistock.domain.account.entity.AccountStatus;

import jakarta.validation.constraints.NotNull;

/**
 * 관리자 — 계좌 거래 정지상태 변경 요청 DTO. SUSPENDED로 보내면 매수·매도 주문만 차단되고
 * 로그인은 그대로 가능하다(CLAUDE.md 8번 — accounts.status는 관리자에 의한 계좌 거래 정지 용도).
 *
 * reason은 handoff 문서 3.4 예시(`{"status": "SUSPENDED", "reason": "이상 거래 확인"}`)에는
 * 있었지만 최초 구현(NAMING.md 8-16) 때 누락됐던 필드다 — 감사 로그(5.2, feature/admin-api-p0)가
 * "처리 사유"를 남기려면 이 필드가 있어야 해서 이번에 추가했다. 선택값이라 없어도 여전히
 * 동작하지만, 감사 로그의 reason은 이 값이 없으면 null로 남는다.
 */
public record AdminAccountStatusRequest(

        @NotNull(message = "변경할 상태값은 필수입니다.")
        AccountStatus status,

        String reason
) {
}
