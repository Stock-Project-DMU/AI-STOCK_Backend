package com.teamfp.aistock.domain.admin.controller;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.request.AdminOrderCancelRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminTradeResponse;
import com.teamfp.aistock.domain.admin.service.AdminTradeService;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 전체 거래(주문) 목록·상세 조회 API. SecurityConfig에서 "/api/admin/**"는
 * hasRole("ADMIN")로 이미 제한되어 있어 여기서는 별도의 권한 검사를 하지 않는다.
 */
@RestController
@RequestMapping("/api/admin/trades")
@RequiredArgsConstructor
public class AdminTradeController {

    private final AdminTradeService adminTradeService;

    // query(주문번호/회원 아이디/계좌번호/종목코드/종목명 통합검색), status, orderType, priceType,
    // stockCode(정확일치), from~to(orderedAt 구간)는 전부 선택 파라미터다. 기본 정렬은 orderedAt
    // 내림차순 — 클라이언트가 sort를 직접 지정하면 그 값이 우선 적용된다.
    @GetMapping
    public ApiResponse<Page<AdminTradeResponse>> getTrades(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) OrderType orderType,
            @RequestParam(required = false) PriceType priceType,
            @RequestParam(required = false) String stockCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 20, sort = "orderedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(adminTradeService.getTrades(query, status, orderType, priceType, stockCode, from, to, pageable));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<AdminTradeResponse> getTradeDetail(@PathVariable Long orderId) {
        return ApiResponse.success(adminTradeService.getTradeDetail(orderId));
    }

    // 주문 강제취소(3.3, "구현 전 결정이 필요한 정책" 2번 — 정책 확정 전 우선 구현).
    @PatchMapping("/{orderId}/cancel")
    public ApiResponse<AdminTradeResponse> cancelTrade(
            @PathVariable Long orderId,
            @Valid @RequestBody AdminOrderCancelRequest request
    ) {
        Long adminUserId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("주문이 취소되었습니다.", adminTradeService.cancelTrade(adminUserId, orderId, request));
    }

    // 화면과 동일한 검색·필터 조건을 그대로 받는다(6.2 요구사항). handoff 문서는
    // /api/admin/orders/export를 제안했지만 AdminTradeService.exportTradesCsv() Javadoc과 동일한
    // 이유로 /api/admin/trades/export로 둔다.
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportTrades(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) OrderType orderType,
            @RequestParam(required = false) PriceType priceType,
            @RequestParam(required = false) String stockCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "orderedAt,desc") String sort
    ) {
        byte[] csv = adminTradeService.exportTradesCsv(query, status, orderType, priceType, stockCode, from, to, parseSort(sort));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=admin-trades.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    private Sort parseSort(String sort) {
        String[] parts = sort.split(",", 2);
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1])
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, parts[0]);
    }
}
