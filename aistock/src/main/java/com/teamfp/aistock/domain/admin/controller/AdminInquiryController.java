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

import com.teamfp.aistock.domain.admin.dto.request.AdminInquiryAnswerRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminInquirySearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminInquirySortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminInquirySortType;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchMatchType;
import com.teamfp.aistock.domain.admin.dto.response.AdminInquiryResponse;
import com.teamfp.aistock.domain.admin.service.AdminInquiryService;
import com.teamfp.aistock.domain.inquiry.entity.InquiryStatus;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 문의 목록·상세 조회 및 답변 API. SecurityConfig에서 "/api/admin/**"는
 * hasRole("ADMIN")로 이미 제한되어 있어 여기서는 별도의 권한 검사를 하지 않는다.
 */
@RestController
@RequestMapping("/api/admin/inquiries")
@RequiredArgsConstructor
public class AdminInquiryController {

    private final AdminInquiryService adminInquiryService;

    // 문의 목록(feat/admin-improvements) — query/field/matchType은 다른 관리자 목록과 같은 검색, status는 대기/답변 완료
    // 필터, sortBy(정렬 선택칸, 기본 답변 대기 먼저)와 sortColumn + direction(열 제목 클릭, 있으면 우선)으로 정렬한다.
    @GetMapping
    public ApiResponse<Page<AdminInquiryResponse>> getInquiries(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "ALL") AdminInquirySearchField field,
            @RequestParam(defaultValue = "CONTAINS") AdminSearchMatchType matchType,
            @RequestParam(required = false) InquiryStatus status,
            @RequestParam(defaultValue = "PENDING_FIRST") AdminInquirySortType sortBy,
            @RequestParam(required = false) AdminInquirySortColumn sortColumn,
            @RequestParam(defaultValue = "DESC") Sort.Direction direction,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(adminInquiryService.getInquiries(AdminSearchConditionDto.of(query, field, matchType),
                status, AdminSortSupport.inquiries(pageable, sortBy, sortColumn, direction)));
    }

    @GetMapping("/{inquiryId}")
    public ApiResponse<AdminInquiryResponse> getInquiryDetail(@PathVariable Long inquiryId) {
        return ApiResponse.success(adminInquiryService.getInquiryDetail(inquiryId));
    }

    @PatchMapping("/{inquiryId}/answer")
    public ApiResponse<AdminInquiryResponse> answerInquiry(
            @PathVariable Long inquiryId,
            @Valid @RequestBody AdminInquiryAnswerRequest request
    ) {
        Long adminUserId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("답변이 등록되었습니다.", adminInquiryService.answerInquiry(adminUserId, inquiryId, request));
    }
}
