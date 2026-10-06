package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.user.entity.InvestmentLevel;
import com.teamfp.aistock.domain.user.entity.InvestmentProfile;

/**
 * 관리자 회원 상세의 투자 성향 요약(feat/admin-improvements) — 성향 점수·자금 성향·투자 수준과 마지막 설문 시각.
 * 설문 응답 원문(surveyAnswers)은 보기 번호뿐이라 관리자 화면에 의미가 없어 내려주지 않는다. 설문을 안 한 회원이면
 * 상세의 이 필드는 null이다.
 */
public record AdminInvestmentProfileResponse(
        int investmentTendency,
        int fundTendency,
        InvestmentLevel investmentLevel,
        LocalDateTime updatedAt
) {

    public static AdminInvestmentProfileResponse from(InvestmentProfile profile) {
        return new AdminInvestmentProfileResponse(profile.getInvestmentTendency(), profile.getFundTendency(),
                profile.getInvestmentLevel(), profile.getUpdatedAt());
    }
}
