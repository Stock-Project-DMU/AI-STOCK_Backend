package com.teamfp.aistock.domain.order.event;

/**
 * 관리자가 주문을 강제취소했을 때 OrderService.adminCancelOrder()가 발행하는 이벤트
 * (코드리뷰 반영, 2026-09). order 도메인이 admin 도메인의 AuditLogService를 직접 호출하던
 * 것을 이벤트 발행/구독으로 바꿔, order가 admin을 몰라도 되게 한다 — CLAUDE.md 4번이 정한
 * "admin은 다른 도메인을 조합만 하고, 다른 도메인은 admin을 모른다"는 단방향 설계를 지키기
 * 위함이다. 이 이벤트 자체는 "관리자가 이 주문을 이 사유로 취소했다"는 order 도메인의 사실만
 * 담고, 그걸 감사 로그로 남길지는 구독하는 쪽(admin/service/AdminAuditEventListener)의
 * 책임이다.
 */
public record AdminOrderCancelledEvent(Long adminUserId, Long orderId, String reason) {
}
