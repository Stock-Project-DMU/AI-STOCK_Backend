package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.teamfp.aistock.domain.admin.dto.StatisticsInterval;
import com.teamfp.aistock.global.util.StatisticsPointProjection;
import com.teamfp.aistock.domain.admin.dto.response.StatisticsPointResponse;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminStatisticsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrderRepository orderRepository;

    private AdminStatisticsService adminStatisticsService;

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 8, 31, 23, 59, 59);

    @BeforeEach
    void setUp() {
        adminStatisticsService = new AdminStatisticsService(userRepository, orderRepository);
    }

    private StatisticsPointProjection pointOf(String period, long value) {
        return new StatisticsPointProjection() {
            @Override
            public String getPeriod() {
                return period;
            }

            @Override
            public long getValue() {
                return value;
            }
        };
    }

    @Test
    @DisplayName("getUserStatistics()는 interval의 MySQL 포맷 패턴을 그대로 넘기고 결과를 응답 DTO로 변환한다")
    void getUserStatistics_convertsProjectionsToResponses() {
        when(userRepository.aggregateUserSignups("%Y-%m-%d", FROM, TO))
                .thenReturn(List.of(pointOf("2026-08-01", 3L), pointOf("2026-08-02", 5L)));

        List<StatisticsPointResponse> result = adminStatisticsService.getUserStatistics(FROM, TO, StatisticsInterval.DAY);

        assertThat(result).containsExactly(
                new StatisticsPointResponse("2026-08-01", 3L),
                new StatisticsPointResponse("2026-08-02", 5L)
        );
    }

    @Test
    @DisplayName("getOrderStatistics()는 MONTH interval이면 %Y-%m 패턴으로 조회한다")
    void getOrderStatistics_monthInterval() {
        when(orderRepository.aggregateOrderCounts("%Y-%m", FROM, TO))
                .thenReturn(List.of(pointOf("2026-08", 42L)));

        List<StatisticsPointResponse> result = adminStatisticsService.getOrderStatistics(FROM, TO, StatisticsInterval.MONTH);

        assertThat(result).containsExactly(new StatisticsPointResponse("2026-08", 42L));
    }

    @Test
    @DisplayName("getAmountStatistics()는 WEEK interval이면 %x-%v 패턴으로 조회한다")
    void getAmountStatistics_weekInterval() {
        when(orderRepository.aggregateExecutedAmounts("%x-%v", FROM, TO))
                .thenReturn(List.of(pointOf("2026-31", 900_000L)));

        List<StatisticsPointResponse> result = adminStatisticsService.getAmountStatistics(FROM, TO, StatisticsInterval.WEEK);

        assertThat(result).containsExactly(new StatisticsPointResponse("2026-31", 900_000L));
    }
}
