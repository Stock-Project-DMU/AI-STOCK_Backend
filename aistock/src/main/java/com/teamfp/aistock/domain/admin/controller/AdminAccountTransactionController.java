package com.teamfp.aistock.domain.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.request.AdminAccountTransactionSearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminChargeHistorySortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchMatchType;
import com.teamfp.aistock.domain.admin.dto.request.AdminSortType;
import com.teamfp.aistock.domain.admin.dto.response.AdminAccountTransactionResponse;
import com.teamfp.aistock.domain.admin.service.AdminAccountTransactionService;
import com.teamfp.aistock.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — "가상계좌 관리 → 충전·차감 이력" 전체 계좌 조회(feat/admin-improvements). 처리가 끝난 충전·차감
 * (잔고 내역 + 거절된 충전 요청)을 보여준다. type(선택)으로 유형을 거르고, query/field/matchType은 다른 관리자
 * 목록과 같은 검색 방식, sortBy는 정렬(최신순/오래된순/금액 큰순/금액 작은순)이다.
 */
@RestController
@RequestMapping("/api/admin/account-transactions")
@RequiredArgsConstructor
public class AdminAccountTransactionController {

    private final AdminAccountTransactionService adminAccountTransactionService;

    @GetMapping
    public ApiResponse<Page<AdminAccountTransactionResponse>> getChargeDeductionHistory(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "ALL") AdminAccountTransactionSearchField field,
            @RequestParam(defaultValue = "CONTAINS") AdminSearchMatchType matchType,
            @RequestParam(defaultValue = "LATEST") AdminSortType sortBy,
            @RequestParam(required = false) AdminChargeHistorySortColumn sortColumn,
            @RequestParam(defaultValue = "DESC") Sort.Direction direction,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(adminAccountTransactionService.getChargeDeductionHistory(type,
                AdminSearchConditionDto.of(query, field, matchType), sortBy, sortColumn, direction, pageable));
    }
}
