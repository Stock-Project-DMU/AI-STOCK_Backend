package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.admin.dto.response.AuditLogResponse;
import com.teamfp.aistock.domain.admin.entity.AuditLog;
import com.teamfp.aistock.domain.admin.repository.AuditLogRepository;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 작업 감사 로그 기록·조회(ADMIN_API_BACKEND_HANDOFF.md 5.2). AuditLog 클래스 Javadoc의
 * "admin 도메인 원칙 예외" 참고 — 다른 도메인 서비스(order 등)가 이 서비스를 직접 호출한다.
 *
 * action/targetType 상수는 이 클래스에 모아둔다 — 호출부마다 문자열을 따로 적으면 오타로
 * 검색·필터가 조용히 어긋날 수 있어(예: "ORDER_CANCEL" vs "OrderCancel"), 한 곳에서만 정의한다.
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    public static final String ACTION_USER_STATUS_CHANGE = "USER_STATUS_CHANGE";
    public static final String ACTION_ACCOUNT_STATUS_CHANGE = "ACCOUNT_STATUS_CHANGE";
    public static final String ACTION_ORDER_CANCEL = "ORDER_CANCEL";
    public static final String ACTION_CHARGE_REQUEST_DECISION = "CHARGE_REQUEST_DECISION";
    public static final String ACTION_ADMIN_CREATE = "ADMIN_CREATE";
    // AdminAccountService.adjustBalance() 전용(코드리뷰 반영, 2026-09) — 잔고 수동 조정은
    // ACCOUNT_STATUS_CHANGE 못지않게 민감한데도 처음엔 감사 로그 기록이 누락돼 있었다.
    public static final String ACTION_ACCOUNT_ADJUSTMENT = "ACCOUNT_ADJUSTMENT";

    public static final String TARGET_USER = "USER";
    public static final String TARGET_ACCOUNT = "ACCOUNT";
    public static final String TARGET_ORDER = "ORDER";
    public static final String TARGET_CHARGE_REQUEST = "CHARGE_REQUEST";
    public static final String TARGET_ADMIN = "ADMIN";

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    // 기록 시점의 adminLoginId를 스냅샷으로 저장한다(AuditLog Javadoc 참고) — 그 관리자가
    // 나중에 탈퇴/정보변경되어도 로그 자체의 "누가"는 그대로 남아야 하기 때문이다.
    @Transactional
    public void record(Long adminUserId, String action, String targetType, Long targetId,
            String beforeValue, String afterValue, String reason) {
        String adminLoginId = userRepository.findById(adminUserId).map(User::getLoginId).orElse("unknown");
        AuditLog auditLog = AuditLog.builder()
                .adminUserId(adminUserId)
                .adminLoginId(adminLoginId)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .beforeValue(beforeValue)
                .afterValue(afterValue)
                .reason(reason)
                .requestIp(currentRequestIp())
                .build();
        auditLogRepository.save(auditLog);
    }

    /**
     * 요청 IP를 SecurityContextHolder에서 꺼낸다(코드리뷰 반영, 2026-09) — JwtAuthenticationFilter가
     * 인증 시점에 Authentication.details에 WebAuthenticationDetails(요청 IP 포함)를 이미 채워둔다.
     * 이 서비스 메서드 시그니처를 바꾸지 않고도 IP를 채울 수 있어, order 등 다른 도메인의 기존
     * 호출부·테스트를 전혀 건드리지 않는다. 인증 컨텍스트가 없는 경로(배치 작업 등)에서 호출되면
     * null을 반환한다 — requestIp 컬럼 자체가 nullable이라 문제 없다.
     */
    private String currentRequestIp() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof WebAuthenticationDetails details) {
            return details.getRemoteAddress();
        }
        return null;
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(String action, Long adminId, String targetType, Long targetId,
            LocalDateTime from, LocalDateTime to, Pageable pageable) {
        return auditLogRepository.search(blankToNull(action), adminId, blankToNull(targetType), targetId, from, to, pageable)
                .map(AuditLogResponse::from);
    }

    @Transactional(readOnly = true)
    public AuditLogResponse getDetail(Long auditLogId) {
        return auditLogRepository.findById(auditLogId)
                .map(AuditLogResponse::from)
                .orElseThrow(() -> new CustomException(ErrorCode.AUDIT_LOG_NOT_FOUND));
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
