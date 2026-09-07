package com.teamfp.aistock.domain.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.dto.response.AccountTransactionResponse;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountAdjustmentRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountStatusRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminAccountDetailResponse;
import com.teamfp.aistock.domain.admin.service.AdminAccountService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

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

    // query(계좌 소유자 아이디/이름/계좌번호 통합검색), status는 선택 파라미터다.
    // 기본 정렬은 createdAt 내림차순 — 클라이언트가 sort를 직접 지정하면 그 값이 우선 적용된다.
    @GetMapping
    public ApiResponse<Page<AdminAccountDetailResponse>> getAccounts(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) AccountStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(adminAccountService.getAccounts(query, status, pageable));
    }

    @GetMapping("/{accountId}")
    public ApiResponse<AdminAccountDetailResponse> getAccountDetail(@PathVariable Long accountId) {
        return ApiResponse.success(adminAccountService.getAccountDetail(accountId));
    }

    @PatchMapping("/{accountId}/status")
    public ApiResponse<AdminAccountDetailResponse> updateAccountStatus(
            @PathVariable Long accountId,
            @Valid @RequestBody AdminAccountStatusRequest request
    ) {
        Long adminUserId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("계좌 상태가 변경되었습니다.", adminAccountService.updateAccountStatus(adminUserId, accountId, request));
    }

    // 잔고 변동 원장 조회(4.3).
    @GetMapping("/{accountId}/transactions")
    public ApiResponse<Page<AccountTransactionResponse>> getTransactions(
            @PathVariable Long accountId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(adminAccountService.getTransactions(accountId, pageable));
    }

    // 관리자 수동 잔고 조정(4.3).
    @PostMapping("/{accountId}/adjustments")
    public ApiResponse<AdminAccountDetailResponse> adjustBalance(
            @PathVariable Long accountId,
            @Valid @RequestBody AdminAccountAdjustmentRequest request
    ) {
        Long adminUserId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("계좌 잔고가 조정되었습니다.", adminAccountService.adjustBalance(adminUserId, accountId, request));
    }
}
