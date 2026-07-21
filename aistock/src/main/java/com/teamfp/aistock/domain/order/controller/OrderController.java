package com.teamfp.aistock.domain.order.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.order.dto.request.CreateOrderRequest;
import com.teamfp.aistock.domain.order.dto.response.CreateOrderResponse;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.service.OrderService;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // POST /api/orders는 NAMING.md 8-6 기준 priceType으로 시장가/지정가를 분기하는 공용
    // 엔드포인트가 될 예정이다. feature/order-limit이 아직 병합되지 않아 createLimitOrder()가
    // 없으므로, priceType 분기 지점인 이 컨트롤러에서 LIMIT 요청을 명시적으로 막는다
    // (order-limit 병합 후에는 이 if를 실제 분기 로직으로 교체한다). MARKET 전용 메서드인
    // OrderService.createMarketOrder() 안에서 이 검증을 하면 나중에 분기 로직이 추가될 때
    // 죽은 코드로 남거나 이중으로 검증될 위험이 있어, 라우팅 책임이 있는 이 계층에 둔다.
    @PostMapping
    public ResponseEntity<ApiResponse<CreateOrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request
    ) {
        if (request.priceType() != PriceType.MARKET) {
            throw new CustomException(ErrorCode.INVALID_PRICE_TYPE);
        }

        Long userId = SecurityUtil.getCurrentUserId();
        CreateOrderResponse response = orderService.createMarketOrder(userId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("주문이 체결되었습니다.", response));
    }
}
