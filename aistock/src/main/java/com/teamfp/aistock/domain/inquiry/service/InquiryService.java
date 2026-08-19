package com.teamfp.aistock.domain.inquiry.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.inquiry.dto.request.CreateInquiryRequest;
import com.teamfp.aistock.domain.inquiry.dto.response.InquiryResponse;
import com.teamfp.aistock.domain.inquiry.entity.Inquiry;
import com.teamfp.aistock.domain.inquiry.repository.InquiryRepository;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InquiryService {

    private final InquiryRepository inquiryRepository;
    private final UserRepository userRepository;

    @Transactional
    public InquiryResponse createInquiry(Long userId, CreateInquiryRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Inquiry inquiry = Inquiry.builder()
                .user(user)
                .title(request.title())
                .content(request.content())
                .build();
        inquiryRepository.save(inquiry);

        return InquiryResponse.from(inquiry);
    }

    @Transactional(readOnly = true)
    public List<InquiryResponse> getMyInquiries(Long userId) {
        return inquiryRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(InquiryResponse::from)
                .toList();
    }

    /**
     * 문의 상세 조회. findByInquiryIdAndUserId로 소유권을 함께 검증하여
     * 다른 사용자의 문의를 조회할 수 없도록 한다.
     */
    @Transactional(readOnly = true)
    public InquiryResponse getMyInquiryDetail(Long userId, Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findByInquiryIdAndUserId(inquiryId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.INQUIRY_NOT_FOUND));

        return InquiryResponse.from(inquiry);
    }
}
