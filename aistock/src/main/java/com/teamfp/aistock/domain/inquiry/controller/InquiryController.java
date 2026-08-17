package com.teamfp.aistock.domain.inquiry.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.inquiry.dto.request.CreateInquiryRequest;
import com.teamfp.aistock.domain.inquiry.dto.response.InquiryResponse;
import com.teamfp.aistock.domain.inquiry.service.InquiryService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/inquiries")
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryService inquiryService;

    @PostMapping
    public ApiResponse<InquiryResponse> createInquiry(@Valid @RequestBody CreateInquiryRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("문의가 등록되었습니다.", inquiryService.createInquiry(userId, request));
    }

    @GetMapping
    public ApiResponse<List<InquiryResponse>> getMyInquiries() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(inquiryService.getMyInquiries(userId));
    }

    @GetMapping("/{inquiryId}")
    public ApiResponse<InquiryResponse> getMyInquiryDetail(@PathVariable Long inquiryId) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(inquiryService.getMyInquiryDetail(userId, inquiryId));
    }
}
