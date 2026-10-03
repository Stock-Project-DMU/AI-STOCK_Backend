package com.teamfp.aistock.domain.account.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.account.dto.request.ChargeBalanceRequest;
import com.teamfp.aistock.domain.account.dto.request.ChargeRequestCreateRequest;
import com.teamfp.aistock.domain.account.dto.request.CreateAccountRequest;
import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.account.dto.response.AccountTransactionResponse;
import com.teamfp.aistock.domain.account.dto.response.ChargeHistoryResponse;
import com.teamfp.aistock.domain.account.dto.response.ChargeRequestResponse;
import com.teamfp.aistock.domain.account.dto.response.ProfitResponse;
import com.teamfp.aistock.domain.account.service.AccountService;
import com.teamfp.aistock.domain.account.service.AccountTransactionService;
import com.teamfp.aistock.domain.account.service.ChargeRequestService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final ChargeRequestService chargeRequestService;
    private final AccountTransactionService accountTransactionService;

    @GetMapping
    public ApiResponse<List<AccountInfoResponse>> getMyAccounts() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(accountService.getMyAccounts(userId));
    }

    @PostMapping
    public ApiResponse<AccountInfoResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("계좌가 개설되었습니다.", accountService.createAccount(userId, request));
    }

    // 사용자 직접 충전 — 계좌당 3회(Account.MAX_CHARGE_COUNT)까지 관리자 승인 없이 입력한 금액이
    // 바로 충전된다. 3회를 다 쓴 뒤에는 아래 충전 요청(charge-requests)으로 관리자 승인을 받는다.
    @PostMapping("/{accountId}/charge")
    public ApiResponse<AccountInfoResponse> chargeBalance(
            @PathVariable Long accountId,
            @Valid @RequestBody ChargeBalanceRequest request
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("가상캐시가 충전되었습니다.", accountService.chargeBalance(userId, accountId, request));
    }

    @GetMapping("/{accountId}/profit")
    public ApiResponse<ProfitResponse> getProfit(@PathVariable Long accountId) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(accountService.getProfit(userId, accountId));
    }

    // 추가 충전 요청(ADMIN_API_BACKEND_HANDOFF.md 4.2) — 직접 충전(POST .../charge) 3회 한도를
    // 다 쓴 사용자만 관리자 승인을 받기 위해 요청을 남길 수 있다(남아 있으면 CHARGE_REQUEST_NOT_ALLOWED).
    @PostMapping("/{accountId}/charge-requests")
    public ApiResponse<ChargeRequestResponse> createChargeRequest(
            @PathVariable Long accountId,
            @Valid @RequestBody ChargeRequestCreateRequest request
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("충전 요청이 접수되었습니다.", chargeRequestService.createRequest(userId, accountId, request));
    }

    @GetMapping("/{accountId}/charge-requests")
    public ApiResponse<Page<ChargeRequestResponse>> getMyChargeRequests(
            @PathVariable Long accountId,
            @PageableDefault(size = 20, sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(chargeRequestService.getMyRequests(userId, accountId, pageable));
    }

    // 충전 이력 — 직접 충전(셀프)·관리자 충전 요청·관리자 지급을 합친 목록(source로 구분, 최신순).
    @GetMapping("/{accountId}/charge-history")
    public ApiResponse<List<ChargeHistoryResponse>> getMyChargeHistory(@PathVariable Long accountId) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(chargeRequestService.getChargeHistory(userId, accountId));
    }

    @GetMapping("/{accountId}/charge-requests/{requestId}")
    public ApiResponse<ChargeRequestResponse> getMyChargeRequestDetail(
            @PathVariable Long accountId,
            @PathVariable Long requestId
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(chargeRequestService.getMyRequestDetail(userId, accountId, requestId));
    }

    // 잔고 변동 원장 조회(ADMIN_API_BACKEND_HANDOFF.md 4.3). 소유권 검증은 getOwnedAccount()로
    // 먼저 확인한 뒤 조회를 위임한다 — AccountTransactionService 자체는 계좌 소유자가 누구인지
    // 모르므로(admin 조회 API와 공유하는 계층) 이 컨트롤러가 사용자 조회 경로에서만 검증한다.
    @GetMapping("/{accountId}/transactions")
    public ApiResponse<Page<AccountTransactionResponse>> getMyTransactions(
            @PathVariable Long accountId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        accountService.getOwnedAccount(userId, accountId);
        return ApiResponse.success(accountTransactionService.getTransactions(accountId, pageable));
    }
}
