package com.teamfp.aistock.domain.admin.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.admin.dto.response.AdminTradeResponse;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

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

    @Transactional(readOnly = true)
    public Page<AdminTradeResponse> getTrades(Pageable pageable) {
        return orderRepository.findAllOrdersWithUser(pageable).map(AdminTradeResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminTradeResponse getTradeDetail(Long orderId) {
        return orderRepository.findOrderWithUserById(orderId)
                .map(AdminTradeResponse::from)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));
    }
}
