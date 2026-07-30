package com.teamfp.aistock.domain.user.dto.response;

import com.teamfp.aistock.domain.user.entity.InvestmentLevel;
import com.teamfp.aistock.domain.user.entity.InvestmentProfile;

public record InvestmentProfileResponse(
        int investmentTendency,
        int fundTendency,
        InvestmentLevel investmentLevel
) {

    public static InvestmentProfileResponse from(InvestmentProfile profile) {
        return new InvestmentProfileResponse(
                profile.getInvestmentTendency(),
                profile.getFundTendency(),
                profile.getInvestmentLevel()
        );
    }
}
