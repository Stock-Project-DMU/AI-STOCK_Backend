package com.teamfp.aistock.domain.admin.controller;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchMatchType;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserSearchField;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityResponse;
import com.teamfp.aistock.domain.admin.service.AdminActivityService;
import com.teamfp.aistock.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — "전체 활동 기록"(회원이력·거래이력·충전차감이력·문의이력) 통합 조회(feat/admin-improvements).
 * category로 탭을, query/field/matchType으로 대상 회원을(회원 목록과 같은 검색 방식), from~to(날짜)로 기간을 거른다.
 * 페이지 번호(page, 0부터)를 누를 때마다 그 페이지만 조회하고, 응답의 totalPages로 전체 페이지 번호를 그린다.
 * 정렬은 항상 발생 시각 최신순이라 sort 파라미터는 쓰지 않는다.
 */
@RestController
@RequestMapping("/api/admin/activities")
@RequiredArgsConstructor
public class AdminActivityController {

    private final AdminActivityService adminActivityService;

    @GetMapping
    public ApiResponse<Page<AdminActivityResponse>> getActivities(
            @RequestParam(defaultValue = AdminActivityService.CATEGORY_ALL) String category,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "ALL") AdminUserSearchField field,
            @RequestParam(defaultValue = "CONTAINS") AdminSearchMatchType matchType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 10) Pageable pageable
    ) {
        return ApiResponse.success(adminActivityService.getActivities(category,
                AdminSearchConditionDto.of(query, field, matchType), from, to, pageable));
    }
}
