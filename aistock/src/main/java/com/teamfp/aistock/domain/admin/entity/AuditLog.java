package com.teamfp.aistock.domain.admin.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 작업 감사 로그(ADMIN_API_BACKEND_HANDOFF.md 5.2, feature/admin-api-p0 — 새 테이블 3개
 * 중 마지막, 2026-09-07 사용자 승인으로 신규 생성). 문서 요구사항대로 관리자도 수정·삭제할 수
 * 없다 — 이 Entity에는 상태를 바꾸는 메서드가 없다(append-only, AccountTransaction과 동일한
 * 설계 원칙).
 *
 * **admin 도메인 원칙 예외**: CLAUDE.md 4번/NAMING.md 8-14~8-18은 "admin 도메인은 자체
 * Entity/Repository를 두지 않고 기존 도메인의 Repository를 조합한다"는 원칙을 여러 번 명시하지만,
 * 감사 로그는 성격상 다른 도메인에 자연스러운 소속처가 없는 admin 고유 데이터라 이번만 예외로
 * admin 도메인이 직접 Entity/Repository를 갖는다. order 도메인(OrderService.adminCancelOrder())
 * 등 다른 도메인이 이 admin 도메인의 AuditLogService를 호출하는 구조가 되는데, 이는
 * domain/notification의 NotificationService를 여러 도메인이 호출하는 것과 동일한 패턴
 * (CLAUDE.md 4번 "도메인 간 직접 참조 대신 서비스 계층을 통해 호출")이라 방향만 다를 뿐
 * 원칙 위반은 아니라고 판단했다.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_log_admin", columnList = "admin_user_id"),
        @Index(name = "idx_audit_log_target", columnList = "target_type, target_id"),
        @Index(name = "idx_audit_log_created", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_log_id")
    private Long auditLogId;

    // FK가 아니라 단순 참조 ID + 스냅샷이다 — 관리자가 나중에 탈퇴/정지되어도 로그 자체는
    // "그 시점에 누가 했는지" 그대로 읽을 수 있어야 하므로(감사 로그의 존재 목적), loginId를
    // 조회 시점에 조인하지 않고 기록 시점에 값 그대로 저장해둔다.
    @Column(name = "admin_user_id", nullable = false)
    private Long adminUserId;

    @Column(name = "admin_login_id", length = 50, nullable = false)
    private String adminLoginId;

    @Column(name = "action", length = 50, nullable = false)
    private String action;

    @Column(name = "target_type", length = 30, nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "before_value", length = 500)
    private String beforeValue;

    @Column(name = "after_value", length = 500)
    private String afterValue;

    @Column(name = "reason", length = 500)
    private String reason;

    // AuditLogService.record()가 SecurityContextHolder(JwtAuthenticationFilter가 인증 시점에
    // 채워둔 WebAuthenticationDetails)에서 꺼내 채운다(코드리뷰 반영, 2026-09 — 최초 구현 때는
    // "컨트롤러→서비스 시그니처 변경이 여러 곳에 필요하다"고 판단해 항상 null로 남겨뒀었는데,
    // 실제로는 표준 Spring Security 방식으로 서비스 메서드 시그니처를 하나도 안 바꾸고 채울 수
    // 있었다). 인증 컨텍스트가 없는 경로(배치 작업 등)에서는 여전히 null일 수 있다.
    @Column(name = "request_ip", length = 45)
    private String requestIp;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private AuditLog(Long adminUserId, String adminLoginId, String action, String targetType, Long targetId,
            String beforeValue, String afterValue, String reason, String requestIp) {
        this.adminUserId = adminUserId;
        this.adminLoginId = adminLoginId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.reason = reason;
        this.requestIp = requestIp;
    }
}
