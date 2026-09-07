package com.teamfp.aistock.domain.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;
import com.teamfp.aistock.domain.admin.dto.request.AdminChargeDecisionRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminChargeRequestResponse;
import com.teamfp.aistock.domain.admin.service.AdminChargeRequestService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 충전 요청 목록·상세 조회 및 승인·거절 API(ADMIN_API_BACKEND_HANDOFF.md 4.2).
 * SecurityConfig에서 "/api/admin/**"는 hasRole("ADMIN")로 이미 제한되어 있어 여기서는 별도의
 * 권한 검사를 하지 않는다.
 */
@RestController
@RequestMapping("/api/admin/charge-requests")
@RequiredArgsConstructor
public class AdminChargeRequestController {

    private final AdminChargeRequestService adminChargeRequestService;

    @GetMapping
    public ApiResponse<Page<AdminChargeRequestResponse>> getChargeRequests(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) ChargeRequestStatus status,
            @PageableDefault(size = 20, sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(adminChargeRequestService.getRequests(query, status, pageable));
    }

    @GetMapping("/{requestId}")
    public ApiResponse<AdminChargeRequestResponse> getChargeRequestDetail(@PathVariable Long requestId) {
        return ApiResponse.success(adminChargeRequestService.getRequestDetail(requestId));
    }

    @PatchMapping("/{requestId}/decision")
    public ApiResponse<AdminChargeRequestResponse> decide(
            @PathVariable Long requestId,
            @Valid @RequestBody AdminChargeDecisionRequest request
    ) {
        Long adminUserId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("충전 요청이 처리되었습니다.", adminChargeRequestService.decide(adminUserId, requestId, request));
    }
}
