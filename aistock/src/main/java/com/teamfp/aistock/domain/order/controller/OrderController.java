package com.teamfp.aistock.domain.order.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.order.dto.request.CreateOrderRequest;
import com.teamfp.aistock.domain.order.dto.response.CreateOrderResponse;
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

        CreateOrderResponse response = orderService.createLimitOrder(userId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("지정가 주문이 등록되었습니다.", response));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<ApiResponse<Void>> cancelOrder(@PathVariable Long orderId) {
        Long userId = SecurityUtil.getCurrentUserId();
        orderService.cancelOrder(userId, orderId);
        return ResponseEntity.ok(ApiResponse.success("주문이 취소되었습니다.", null));
    }
}
