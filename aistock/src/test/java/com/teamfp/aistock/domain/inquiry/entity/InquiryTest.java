package com.teamfp.aistock.domain.inquiry.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chore/admin-entity-update — Inquiry(13번째 테이블) 엔티티 단위 테스트.
 */
class InquiryTest {

    private User newUser(String loginId, Role role) {
        return User.builder()
                .loginId(loginId)
                .name(loginId)
                .role(role)
                .isActive(true)
                .build();
    }

    private Inquiry newInquiry() {
        return Inquiry.builder()
                .user(newUser("questioner", Role.USER))
                .title("문의 제목")
                .content("문의 내용")
                .build();
    }

    @Test
    @DisplayName("생성 시 status는 PENDING이고 답변 관련 필드는 비어있다")
    void newInquiry_isPendingWithoutAnswer() {
        Inquiry inquiry = newInquiry();

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.PENDING);
        assertThat(inquiry.getAnswer()).isNull();
        assertThat(inquiry.getAnsweredBy()).isNull();
        assertThat(inquiry.getAnsweredAt()).isNull();
    }

    @Test
    @DisplayName("answer() 호출 시 answer·answeredBy·answeredAt이 채워지고 status가 ANSWERED로 전환된다")
    void answer_fillsAnswerFieldsAndChangesStatus() {
        Inquiry inquiry = newInquiry();
        User admin = newUser("admin", Role.ADMIN);

        inquiry.answer("답변 내용입니다.", admin);

        assertThat(inquiry.getAnswer()).isEqualTo("답변 내용입니다.");
        assertThat(inquiry.getAnsweredBy()).isEqualTo(admin);
        assertThat(inquiry.getAnsweredAt()).isNotNull();
        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.ANSWERED);
    }
}
