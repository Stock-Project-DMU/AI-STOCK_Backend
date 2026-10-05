package com.teamfp.aistock.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.admin.dto.request.AdminInquirySearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchMatchType;
import com.teamfp.aistock.domain.admin.dto.response.AdminInquiryResponse;
import com.teamfp.aistock.domain.inquiry.entity.Inquiry;
import com.teamfp.aistock.domain.inquiry.entity.InquiryStatus;
import com.teamfp.aistock.domain.inquiry.repository.InquiryRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;

/**
 * 관리자 문의 목록 검색·상태 필터·정렬(feat/admin-improvements)이 실제 쿼리에서 동작하는지 확인한다.
 * @Transactional로 넣은 행은 롤백된다. 이 테스트가 만든 회원 아이디로만 검색해서 검증한다.
 */
@SpringBootTest
@Transactional
class AdminInquiryServiceIntegrationTest {

    @Autowired
    private AdminInquiryService adminInquiryService;
    @Autowired
    private InquiryRepository inquiryRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("아이디 검색 + 상태 필터 + 기본 정렬(답변 대기 먼저)")
    void searchFilterAndPendingFirst() {
        String loginId = "inq" + System.nanoTime();
        User writer = userRepository.save(User.builder().loginId(loginId).name("문의자").role(Role.USER).isActive(true).build());
        User admin = userRepository.save(User.builder().loginId("inqadm" + System.nanoTime()).name("답변관리자")
                .role(Role.ADMIN).isActive(true).build());
        Inquiry answered = inquiryRepository.save(Inquiry.builder().user(writer).title("환불 문의").content("내용").build());
        answered.answer("처리했습니다.", admin);
        Inquiry pending = inquiryRepository.save(Inquiry.builder().user(writer).title("로그인 문의").content("내용").build());
        inquiryRepository.flush();

        AdminSearchConditionDto byLoginId = AdminSearchConditionDto.of(loginId, AdminInquirySearchField.LOGIN_ID, AdminSearchMatchType.EXACT);
        List<AdminInquiryResponse> all = adminInquiryService.getInquiries(byLoginId, null,
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("status"), Sort.Order.desc("createdAt")))).getContent();
        assertThat(all).extracting(AdminInquiryResponse::inquiryId).containsExactly(pending.getInquiryId(), answered.getInquiryId());
        assertThat(all.get(1).answeredByName()).isEqualTo("답변관리자");

        assertThat(adminInquiryService.getInquiries(byLoginId, InquiryStatus.ANSWERED, PageRequest.of(0, 20)).getContent())
                .extracting(AdminInquiryResponse::inquiryId).containsExactly(answered.getInquiryId());

        AdminSearchConditionDto byTitle = AdminSearchConditionDto.of("환불", AdminInquirySearchField.TITLE, AdminSearchMatchType.CONTAINS);
        assertThat(adminInquiryService.getInquiries(byTitle, null, PageRequest.of(0, 100)).getContent())
                .extracting(AdminInquiryResponse::inquiryId).contains(answered.getInquiryId()).doesNotContain(pending.getInquiryId());
    }
}
