package com.teamfp.aistock.domain.user.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feature/mypage-profit — InvestmentProfile.updateSurvey() 엔티티 단위 테스트.
 */
class InvestmentProfileTest {

    private User newUser() {
        return User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("investmentLevel을 지정하지 않으면 기본값 BEGINNER로 생성된다")
    void defaultInvestmentLevelIsBeginner() {
        InvestmentProfile profile = InvestmentProfile.builder()
                .user(newUser())
                .investmentTendency(3)
                .fundTendency(2)
                .surveyAnswers("[1,2,3]")
                .build();

        assertThat(profile.getInvestmentLevel()).isEqualTo(InvestmentLevel.BEGINNER);
    }

    @Test
    @DisplayName("updateSurvey() 호출 시 성향/설문 응답만 바뀌고 investmentLevel은 그대로다")
    void updateSurvey_changesTendencyAndAnswersOnly() {
        InvestmentProfile profile = InvestmentProfile.builder()
                .user(newUser())
                .investmentTendency(1)
                .fundTendency(1)
                .surveyAnswers("[1,1,1]")
                .build();

        profile.updateSurvey(5, 4, "[5,4,3]");

        assertThat(profile.getInvestmentTendency()).isEqualTo(5);
        assertThat(profile.getFundTendency()).isEqualTo(4);
        assertThat(profile.getSurveyAnswers()).isEqualTo("[5,4,3]");
        assertThat(profile.getInvestmentLevel()).isEqualTo(InvestmentLevel.BEGINNER);
    }
}
