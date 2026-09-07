package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.admin.dto.request.AdminOrderCancelRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminTradeResponse;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.order.service.OrderService;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.util.CsvWriter;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 전체 거래 목록·상세 조회. admin 도메인은 자체 Entity/Repository를 두지 않고
 * order 도메인의 OrderRepository를 그대로 주입받아 조합한다(CLAUDE.md 4번). 목록/상세 모두
 * account, account.user까지 JOIN FETCH로 즉시 로딩하는 전용 조회 메서드
 * (findAllOrdersWithUser/findOrderWithUserById)를 쓰므로 N+1 없이 userName/loginId를 채울 수 있다.
 */
@Service
@RequiredArgsConstructor
public class AdminTradeService {

    private final OrderRepository orderRepository;
    // 주문 강제취소(cancelTrade())가 실제 취소 로직을 위임한다 — 잔고 동결 해제, Redis
    // pending:orders 정리 등 order 도메인 고유 로직을 이 서비스에서 중복 구현하지 않기 위해
    // AdminAccountService와 동일한 패턴으로 OrderService를 재사용한다(CLAUDE.md 4번).
    private final OrderService orderService;

    // 거래 검색·필터(ADMIN_API_BACKEND_HANDOFF.md 3.3). 전부 비어 있으면 searchOrdersWithUser()가
    // null 파라미터를 "조건 없음"으로 처리해 기존 findAllOrdersWithUser(pageable)와 동일하게
    // 전체 목록을 반환한다.
    @Transactional(readOnly = true)
    public Page<AdminTradeResponse> getTrades(String query, OrderStatus status, OrderType orderType,
            PriceType priceType, String stockCode, LocalDateTime from, LocalDateTime to, Pageable pageable) {
        return orderRepository.searchOrdersWithUser(blankToNull(query), status, orderType, priceType, stockCode, from, to, pageable)
                .map(AdminTradeResponse::from);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    // 주문 강제취소(ADMIN_API_BACKEND_HANDOFF.md 3.3). 실제 취소는 OrderService.
    // adminCancelOrder()에 위임하고, 이 메서드는 취소 후 최신 상태를 account.user까지 fetch
    // join된 형태로 다시 조회해 응답한다(getTradeDetail()과 동일한 조회 메서드 재사용).
    @Transactional
    public AdminTradeResponse cancelTrade(Long adminUserId, Long orderId, AdminOrderCancelRequest request) {
        orderService.adminCancelOrder(adminUserId, orderId, request.reason());
        return orderRepository.findOrderWithUserById(orderId)
                .map(AdminTradeResponse::from)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));
    }

    private static final List<String> TRADE_CSV_HEADERS = List.of(
            "주문번호", "회원 아이디", "계좌번호", "종목코드", "종목명", "주문유형", "가격유형",
            "주문가", "체결가", "수량", "상태", "주문일시", "체결일시");

    /**
     * 거래 목록 CSV 내보내기(ADMIN_API_BACKEND_HANDOFF.md 6.2). AdminUserService.exportUsersCsv()와
     * 동일한 이유로 전역 max-page-size 설정을 피해 이 메서드 안에서 직접 큰 PageRequest를 만든다.
     * URL은 handoff 문서가 /api/admin/orders/export를 제안했지만, 기존 목록·검색 API가 이미
     * /api/admin/trades로 구현돼 있어(NAMING.md 8-15) 같은 리소스 경로 아래 /export로 둔다.
     */
    @Transactional(readOnly = true)
    public byte[] exportTradesCsv(String query, OrderStatus status, OrderType orderType, PriceType priceType,
            String stockCode, LocalDateTime from, LocalDateTime to, Sort sort) {
        List<Order> orders = orderRepository.searchOrdersWithUser(
                        blankToNull(query), status, orderType, priceType, stockCode, from, to,
                        PageRequest.of(0, Integer.MAX_VALUE, sort))
                .getContent();
        List<List<String>> rows = orders.stream()
                .map(o -> List.of(
                        String.valueOf(o.getOrderId()),
                        o.getAccount().getUser().getLoginId() == null ? "" : o.getAccount().getUser().getLoginId(),
                        o.getAccount().getAccountNumber(),
                        o.getStockCode(),
                        o.getStockName(),
                        o.getOrderType().name(),
                        o.getPriceType().name(),
                        String.valueOf(o.getOrderPrice()),
                        o.getExecPrice() == null ? "" : String.valueOf(o.getExecPrice()),
                        String.valueOf(o.getQuantity()),
                        o.getStatus().name(),
                        o.getOrderedAt().toString(),
                        o.getExecutedAt() == null ? "" : o.getExecutedAt().toString()
                ))
                .toList();
        return CsvWriter.write(TRADE_CSV_HEADERS, rows);
    }

    @Transactional(readOnly = true)
    public AdminTradeResponse getTradeDetail(Long orderId) {
        return orderRepository.findOrderWithUserById(orderId)
                .map(AdminTradeResponse::from)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));
    }
}
