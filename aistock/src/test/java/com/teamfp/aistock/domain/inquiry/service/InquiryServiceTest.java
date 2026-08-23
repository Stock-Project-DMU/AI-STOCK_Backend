package com.teamfp.aistock.domain.inquiry.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.teamfp.aistock.domain.inquiry.dto.request.CreateInquiryRequest;
import com.teamfp.aistock.domain.inquiry.dto.response.InquiryResponse;
import com.teamfp.aistock.domain.inquiry.entity.Inquiry;
import com.teamfp.aistock.domain.inquiry.entity.InquiryStatus;
import com.teamfp.aistock.domain.inquiry.repository.InquiryRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature/inquiry — InquiryService 단위 테스트.
 * 소유권 검증(findByInquiryIdAndUserId)과 상태 전이 자체는 InquiryTest/
 * InquiryRepositoryIntegrationTest에서 이미 검증하므로, 여기서는 Repository/UserRepository를
 * 모킹해 Service가 그 결과를 올바르게 조합/변환하는지만 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class InquiryServiceTest {

    @Mock
    private InquiryRepository inquiryRepository;

    @Mock
    private UserRepository userRepository;

    private InquiryService inquiryService;

    private static final Long USER_ID = 1L;
    private static final Long INQUIRY_ID = 100L;

    private User user;

    @BeforeEach
    void setUp() {
        inquiryService = new InquiryService(inquiryRepository, userRepository);

        user = User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .isActive(true)
                .build();
    }

    private Inquiry newInquiry(InquiryStatus status) {
        Inquiry inquiry = Inquiry.builder()
                .user(user)
                .title("제목")
                .content("내용")
                .build();
        if (status == InquiryStatus.ANSWERED) {
            User admin = User.builder()
                    .loginId("admin")
                    .name("관리자")
                    .role(Role.ADMIN)
                    .isActive(true)
                    .build();
            inquiry.answer("답변입니다", admin);
        }
        return inquiry;
    }

    @Nested
    @DisplayName("문의 작성")
    class CreateInquiry {

        @Test
        @DisplayName("성공 시 PENDING 상태의 문의가 생성되어 저장된다")
        void success() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            CreateInquiryRequest request = new CreateInquiryRequest("제목", "내용");

            InquiryResponse response = inquiryService.createInquiry(USER_ID, request);

            assertThat(response.title()).isEqualTo("제목");
            assertThat(response.content()).isEqualTo("내용");
            assertThat(response.status()).isEqualTo(InquiryStatus.PENDING);
            assertThat(response.answer()).isNull();
            assertThat(response.answeredAt()).isNull();

            ArgumentCaptor<Inquiry> captor = ArgumentCaptor.forClass(Inquiry.class);
            verify(inquiryRepository).save(captor.capture());
            assertThat(captor.getValue().getUser()).isEqualTo(user);
        }

        @Test
        @DisplayName("사용자를 찾을 수 없으면 USER_NOT_FOUND 예외를 던지고 저장하지 않는다")
        void fail_userNotFound() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
            CreateInquiryRequest request = new CreateInquiryRequest("제목", "내용");

            assertThatThrownBy(() -> inquiryService.createInquiry(USER_ID, request))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);

            verify(inquiryRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("내 문의 목록 조회")
    class GetMyInquiries {

        @Test
        @DisplayName("Repository가 반환한 순서 그대로 응답 DTO 목록으로 변환한다")
        void success() {
            Inquiry answered = newInquiry(InquiryStatus.ANSWERED);
            Inquiry pending = newInquiry(InquiryStatus.PENDING);
            when(inquiryRepository.findAllByUserIdOrderByCreatedAtDesc(USER_ID))
                    .thenReturn(List.of(pending, answered));

            List<InquiryResponse> responses = inquiryService.getMyInquiries(USER_ID);

            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).status()).isEqualTo(InquiryStatus.PENDING);
            assertThat(responses.get(1).status()).isEqualTo(InquiryStatus.ANSWERED);
        }

        @Test
        @DisplayName("작성한 문의가 없으면 빈 리스트를 반환한다")
        void success_empty() {
            when(inquiryRepository.findAllByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());

            List<InquiryResponse> responses = inquiryService.getMyInquiries(USER_ID);

            assertThat(responses).isEmpty();
        }
    }

    @Nested
    @DisplayName("내 문의 상세 조회")
    class GetMyInquiryDetail {

        @Test
        @DisplayName("답변완료 문의는 answer/answeredAt이 채워진 채로 반환된다")
        void success() {
            Inquiry inquiry = newInquiry(InquiryStatus.ANSWERED);
            when(inquiryRepository.findByInquiryIdAndUserId(INQUIRY_ID, USER_ID)).thenReturn(Optional.of(inquiry));

            InquiryResponse response = inquiryService.getMyInquiryDetail(USER_ID, INQUIRY_ID);

            assertThat(response.status()).isEqualTo(InquiryStatus.ANSWERED);
            assertThat(response.answer()).isEqualTo("답변입니다");
            assertThat(response.answeredAt()).isNotNull();
        }

        @Test
        @DisplayName("미답변 문의는 answer/answeredAt이 null인 채로 반환된다")
        void success_pending() {
            Inquiry inquiry = newInquiry(InquiryStatus.PENDING);
            when(inquiryRepository.findByInquiryIdAndUserId(INQUIRY_ID, USER_ID)).thenReturn(Optional.of(inquiry));

            InquiryResponse response = inquiryService.getMyInquiryDetail(USER_ID, INQUIRY_ID);

            assertThat(response.status()).isEqualTo(InquiryStatus.PENDING);
            assertThat(response.answer()).isNull();
            assertThat(response.answeredAt()).isNull();
        }

        @Test
        @DisplayName("본인 문의가 아니거나 존재하지 않으면 INQUIRY_NOT_FOUND 예외를 던진다")
        void fail_inquiryNotFound() {
            when(inquiryRepository.findByInquiryIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> inquiryService.getMyInquiryDetail(USER_ID, INQUIRY_ID))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INQUIRY_NOT_FOUND);
        }
    }
}
