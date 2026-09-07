package com.teamfp.aistock.domain.admin.controller;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.response.AuditLogResponse;
import com.teamfp.aistock.domain.admin.service.AuditLogService;
import com.teamfp.aistock.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 작업 감사 로그 조회 전용 API(ADMIN_API_BACKEND_HANDOFF.md 5.2). 조회만 있고 수정·삭제
 * API는 의도적으로 없다 — "감사 로그는 관리자도 수정·삭제할 수 없게 한다"는 요구사항.
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public ApiResponse<Page<AuditLogResponse>> getAuditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long adminId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(auditLogService.search(action, adminId, targetType, targetId, from, to, pageable));
    }

    @GetMapping("/{auditLogId}")
    public ApiResponse<AuditLogResponse> getAuditLogDetail(@PathVariable Long auditLogId) {
        return ApiResponse.success(auditLogService.getDetail(auditLogId));
    }
}
