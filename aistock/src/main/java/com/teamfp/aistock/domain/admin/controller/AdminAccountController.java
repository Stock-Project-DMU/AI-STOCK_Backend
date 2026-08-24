package com.teamfp.aistock.domain.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.request.AdminAccountStatusRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminAccountDetailResponse;
import com.teamfp.aistock.domain.admin.service.AdminAccountService;
import com.teamfp.aistock.global.response.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 계좌 상세 조회 및 거래 정지상태 변경 API. SecurityConfig에서 "/api/admin/**"는
 * hasRole("ADMIN")로 이미 제한되어 있어 여기서는 별도의 권한 검사를 하지 않는다.
 */
@RestController
@RequestMapping("/api/admin/accounts")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminAccountService adminAccountService;

    @GetMapping("/{accountId}")
    public ApiResponse<AdminAccountDetailResponse> getAccountDetail(@PathVariable Long accountId) {
        return ApiResponse.success(adminAccountService.getAccountDetail(accountId));
    }

    @PatchMapping("/{accountId}/status")
    public ApiResponse<AdminAccountDetailResponse> updateAccountStatus(
            @PathVariable Long accountId,
            @Valid @RequestBody AdminAccountStatusRequest request
    ) {
        return ApiResponse.success("계좌 상태가 변경되었습니다.", adminAccountService.updateAccountStatus(accountId, request));
    }
}
