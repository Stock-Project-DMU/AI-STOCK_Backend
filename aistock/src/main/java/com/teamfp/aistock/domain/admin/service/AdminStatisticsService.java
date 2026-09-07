package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.admin.dto.StatisticsInterval;
import com.teamfp.aistock.domain.admin.dto.response.StatisticsPointResponse;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 기간별 통계(ADMIN_API_BACKEND_HANDOFF.md 6.1). admin 도메인은 자체
 * Entity/Repository를 두지 않고 user/order 도메인의 Repository 네이티브 집계 쿼리를 그대로
 * 재사용한다(CLAUDE.md 4번).
 */
@Service
@RequiredArgsConstructor
public class AdminStatisticsService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public List<StatisticsPointResponse> getUserStatistics(LocalDateTime from, LocalDateTime to, StatisticsInterval interval) {
        return userRepository.aggregateUserSignups(interval.getMysqlDateFormatPattern(), from, to).stream()
                .map(StatisticsPointResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StatisticsPointResponse> getOrderStatistics(LocalDateTime from, LocalDateTime to, StatisticsInterval interval) {
        return orderRepository.aggregateOrderCounts(interval.getMysqlDateFormatPattern(), from, to).stream()
                .map(StatisticsPointResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StatisticsPointResponse> getAmountStatistics(LocalDateTime from, LocalDateTime to, StatisticsInterval interval) {
        return orderRepository.aggregateExecutedAmounts(interval.getMysqlDateFormatPattern(), from, to).stream()
                .map(StatisticsPointResponse::from)
                .toList();
    }
}
