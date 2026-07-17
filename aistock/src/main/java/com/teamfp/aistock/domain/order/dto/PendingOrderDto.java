package com.teamfp.aistock.domain.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Redis {@code pending:orders:{stockCode}} 리스트에 JSON으로 저장되는
 * 지정가(LIMIT) 미체결 주문 한 건의 정보.
 *
 * DB의 Order 엔티티를 그대로 캐싱하는 게 아니라, tick 매칭 로직(RedisPendingOrderService)이
 * 필요로 하는 필드만 뽑아 담은 캐시 전용 DTO다. Jackson이 Redis에 저장할 때 JSON으로
 * 직렬화하고 꺼낼 때 역직렬화해야 하므로, 리플렉션 기반 역직렬화를 위해 기본 생성자와
 * 전체 필드 생성자를 둔다 (Lombok {@code @NoArgsConstructor}/{@code @AllArgsConstructor}).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingOrderDto {

    private Long orderId;      // 원본 주문의 orderId (DB orders.order_id)
    private Long userId;       // 주문자 사용자 ID
    private Long accountId;    // 주문에 연결된 계좌 ID
    private String orderType;  // "BUY" 또는 "SELL" (OrderType enum의 name())
    private Long limitPrice;   // 지정가 (이 가격 이하/이상이 되면 체결 조건 충족)
    private Integer quantity;  // 주문 수량
    private String stockCode;  // 종목코드
    private String stockName;  // 종목명 (알림 등에 바로 쓸 수 있도록 함께 캐싱)
}
