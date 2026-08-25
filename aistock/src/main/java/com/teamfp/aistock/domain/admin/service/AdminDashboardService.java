package com.teamfp.aistock.domain.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.admin.dto.response.AdminDashboardResponse;
import com.teamfp.aistock.domain.admin.dto.response.RecentTradeResponse;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.redis.RedisOnlineStatusService;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 대시보드 요약 조회. admin 도메인은 자체 Entity/Repository를 두지 않고
 * user/order 도메인의 Repository와 global/redis의 RedisOnlineStatusService를 그대로 주입받아
 * 조합한다(CLAUDE.md 4번). 집계 메서드(countByIsActiveTrue/countByStatus/sumExecutedAmount/
 * findTop20ByStatusOrderByExecutedAtDesc)는 전부 3주차에 만들어둔 것을 그대로 재사용한다
 * (NAMING.md 8-14 참고).
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final RedisOnlineStatusService redisOnlineStatusService;

    /**
     * recentTrades는 findTop20ByStatusOrderByExecutedAtDesc()가 account/account.user를
     * fetch join하지 않은 파생 쿼리라, RecentTradeResponse.from()이 order.getAccount().getUser()를
     * 읽을 때마다 추가 SELECT가 발생한다(N+1). 다만 이 메서드는 최대 20건으로 개수가 고정돼 있고
     * (AdminTradeController의 페이징 목록처럼 요청마다 커질 수 있는 값이 아님) 관리자 대시보드는
     * 호출 빈도도 낮아, 이미 테스트로 고정된 findTop20ByStatusOrderByExecutedAtDesc() 시그니처를
     * 바꾸면서까지 fetch join 버전을 새로 만들지는 않았다.
     */
    @Transactional(readOnly = true)
    public AdminDashboardResponse getDashboard() {
        long totalUserCount = userRepository.countByIsActiveTrue();
        long onlineUserCount = redisOnlineStatusService.countOnline();
        long totalTradeCount = orderRepository.countByStatus(OrderStatus.EXECUTED);
        long totalTradeAmount = orderRepository.sumExecutedAmount();

        List<RecentTradeResponse> recentTrades = orderRepository
                .findTop20ByStatusOrderByExecutedAtDesc(OrderStatus.EXECUTED).stream()
                .map(RecentTradeResponse::from)
                .toList();

        return AdminDashboardResponse.of(totalUserCount, onlineUserCount, totalTradeCount, totalTradeAmount, recentTrades);
    }
}
