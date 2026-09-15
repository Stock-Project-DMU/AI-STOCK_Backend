package com.teamfp.aistock.domain.admin.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.event.AdminOrderCancelledEvent;

import lombok.RequiredArgsConstructor;

/**
 * order 도메인이 발행하는 관리자 작업 이벤트를 구독해 감사 로그로 남긴다(코드리뷰 반영,
 * 2026-09) — OrderService가 admin의 AuditLogService를 직접 호출하던 순환 의존(order↔admin
 * 양방향 패키지 참조)을 없애기 위해 이벤트 기반으로 바꿨다. admin이 order 도메인의 이벤트
 * 타입을 구독하는 이 방향은 CLAUDE.md 4번이 정한 "admin이 다른 도메인을 조합한다"는 의도된
 * 방향과 일치한다(다른 admin 서비스가 OrderRepository/OrderService를 주입받아 쓰는 것과
 * 동일한 방향).
 *
 * @EventListener는 기본적으로 이벤트를 발행한 스레드·트랜잭션 안에서 동기 호출된다 —
 * OrderService.adminCancelOrder()가 직접 record()를 부르던 이전 동작과 동일하게, 감사 로그
 * 저장이 실패하면 주문취소 트랜잭션 전체가 롤백된다(트랜잭션 경계를 바꾸지 않았다).
 */
@Component
@RequiredArgsConstructor
public class AdminAuditEventListener {

    private final AuditLogService auditLogService;

    @EventListener
    public void onOrderCancelledByAdmin(AdminOrderCancelledEvent event) {
        auditLogService.record(event.adminUserId(), AuditLogService.ACTION_ORDER_CANCEL, AuditLogService.TARGET_ORDER,
                event.orderId(), OrderStatus.PENDING.name(), OrderStatus.CANCELLED.name(), event.reason());
    }
}
