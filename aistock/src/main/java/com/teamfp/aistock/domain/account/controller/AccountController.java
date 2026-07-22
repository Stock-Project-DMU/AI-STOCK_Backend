package com.teamfp.aistock.domain.account.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.account.dto.request.CreateAccountRequest;
import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.account.service.AccountService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

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

    @PostMapping("/{accountId}/charge")
    public ApiResponse<AccountInfoResponse> chargeBalance(@PathVariable Long accountId) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("가상캐시가 충전되었습니다.", accountService.chargeBalance(userId, accountId));
    }
}
