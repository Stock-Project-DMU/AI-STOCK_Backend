package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.admin.entity.AuditLog;

public record AuditLogResponse(
        Long auditLogId,
        Long adminUserId,
        String adminLoginId,
        String action,
        String targetType,
        Long targetId,
        String beforeValue,
        String afterValue,
        String reason,
        String requestIp,
        LocalDateTime createdAt
) {

    public static AuditLogResponse from(AuditLog auditLog) {
        return new AuditLogResponse(
                auditLog.getAuditLogId(),
                auditLog.getAdminUserId(),
                auditLog.getAdminLoginId(),
                auditLog.getAction(),
                auditLog.getTargetType(),
                auditLog.getTargetId(),
                auditLog.getBeforeValue(),
                auditLog.getAfterValue(),
                auditLog.getReason(),
                auditLog.getRequestIp(),
                auditLog.getCreatedAt()
        );
    }
}
