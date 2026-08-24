package com.teamfp.aistock.domain.admin.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.admin.dto.request.AdminInquiryAnswerRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminInquiryResponse;
import com.teamfp.aistock.domain.inquiry.entity.Inquiry;
import com.teamfp.aistock.domain.inquiry.repository.InquiryRepository;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 문의 목록·상세 조회 및 답변. admin 도메인은 자체 Entity/Repository를 두지 않고
 * feature/inquiry의 Inquiry Entity·InquiryRepository를 그대로 주입받아 재사용한다
 * (CLAUDE.md 4번, NAMING.md 8-18). Controller/Service/DTO만 사용자 측과 분리한다.
 */
@Service
@RequiredArgsConstructor
public class AdminInquiryService {

    private final InquiryRepository inquiryRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Page<AdminInquiryResponse> getInquiries(Pageable pageable) {
        return inquiryRepository.findAllByOrderByStatusDescCreatedAtDesc(pageable)
                .map(AdminInquiryResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminInquiryResponse getInquiryDetail(Long inquiryId) {
        return AdminInquiryResponse.from(findInquiry(inquiryId));
    }

    /**
     * 문의 답변 등록. 이미 ANSWERED인 문의라도 소유권 검증 없이(관리자는 모든 문의에
     * 접근 가능) 그대로 덮어쓴다 — 오타 정정 등 재답변이 필요한 상황을 막지 않기 위함이다.
     */
    @Transactional
    public AdminInquiryResponse answerInquiry(Long adminUserId, Long inquiryId, AdminInquiryAnswerRequest request) {
        Inquiry inquiry = findInquiry(inquiryId);
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        inquiry.answer(request.answer(), admin);

        return AdminInquiryResponse.from(inquiry);
    }

    private Inquiry findInquiry(Long inquiryId) {
        return inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new CustomException(ErrorCode.INQUIRY_NOT_FOUND));
    }
}
