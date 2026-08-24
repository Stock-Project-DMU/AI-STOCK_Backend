package com.teamfp.aistock.domain.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.request.AdminInquiryAnswerRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminInquiryResponse;
import com.teamfp.aistock.domain.admin.service.AdminInquiryService;
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

    @GetMapping
    public ApiResponse<Page<AdminInquiryResponse>> getInquiries(@PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(adminInquiryService.getInquiries(pageable));
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
