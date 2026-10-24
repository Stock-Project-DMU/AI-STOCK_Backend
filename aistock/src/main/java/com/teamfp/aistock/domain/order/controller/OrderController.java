package com.teamfp.aistock.domain.order.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.order.dto.request.CreateOrderRequest;
import com.teamfp.aistock.domain.order.dto.response.CreateOrderResponse;
import com.teamfp.aistock.domain.order.dto.response.HoldingResponse;
import com.teamfp.aistock.domain.order.dto.response.OrderHistoryPageResponse;
import com.teamfp.aistock.domain.order.dto.response.OrderHistoryResponse;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.service.OrderService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // NAMING.md 8-6 기준 POST /api/orders는 priceType으로 시장가/지정가 주문을 함께 받는
    // 공용 엔드포인트다. order-market 단계에서는 order-limit이 아직 없어 LIMIT 요청을 여기서
    // 막아뒀지만, 이제 createLimitOrder()가 생겼으므로 실제 분기로 교체한다.
    @PostMapping
    public ResponseEntity<ApiResponse<CreateOrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request
    ) {
        Long userId = SecurityUtil.getCurrentUserId();

        if (request.priceType() == PriceType.MARKET) {
            CreateOrderResponse response = orderService.createMarketOrder(userId, request);
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(ApiResponse.success("주문이 체결되었습니다.", response));
        }

        // createLimitOrder()가 돌려준 상태는 커밋 전 PENDING이다 — 커밋 직후 즉시 체결 확인까지 끝난
        // 상태를 다시 읽어, 바로 체결된 주문을 '접수'로 안내하지 않게 한다(NAMING.md 8-34).
        CreateOrderResponse created = orderService.createLimitOrder(userId, request);
        CreateOrderResponse response = orderService.getCreatedOrderResult(created.orderId());
        String message = response.status() == OrderStatus.EXECUTED ? "주문이 체결되었습니다." : "지정가 주문이 등록되었습니다.";
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(message, response));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<ApiResponse<Void>> cancelOrder(@PathVariable Long orderId) {
        Long userId = SecurityUtil.getCurrentUserId();
        orderService.cancelOrder(userId, orderId);
        return ResponseEntity.ok(ApiResponse.success("주문이 취소되었습니다.", null));
    }

    // 계좌 A/B/C는 서로 독립된 영역이라 항상 accountId로 특정 계좌를 지정해서 조회한다
    // (유저의 전체 계좌 합산이 아님 — NAMING.md 8-8 참고).
    // page가 없으면 예전처럼 전체 목록(List<OrderHistoryResponse>), 있으면 나눠 받기(OrderHistoryPageResponse — 주문 목록
    // 영역 안 스크롤로 다음 묶음을 이어 붙이는 화면용, 2026-10-13). 기존 호출(page 없음)은 응답 모양이 그대로다.
    @GetMapping
    public ApiResponse<?> getMyOrderHistory(@RequestParam Long accountId,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        Long userId = SecurityUtil.getCurrentUserId();
        if (page == null) {
            List<OrderHistoryResponse> orders = orderService.getMyOrderHistory(userId, accountId);
            return ApiResponse.success(orders);
        }
        OrderHistoryPageResponse orderPage = orderService.getMyOrderHistoryPage(userId, accountId, page, size);
        return ApiResponse.success(orderPage);
    }

    @GetMapping("/holdings")
    public ApiResponse<List<HoldingResponse>> getMyHoldings(@RequestParam Long accountId) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(orderService.getMyHoldings(userId, accountId));
    }
}
