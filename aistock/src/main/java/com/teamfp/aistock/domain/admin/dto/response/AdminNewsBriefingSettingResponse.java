package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;
import java.time.LocalTime;

import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;

/**
 * 관리자 회원 상세의 뉴스 브리핑 설정(feat/admin-improvements) — 구독 언론사·브리핑 시각·마지막 생성 시도 시각.
 * 브리핑을 설정하지 않은 회원이면 상세의 이 필드는 null이다.
 */
public record AdminNewsBriefingSettingResponse(
        String outletDomain,
        LocalTime briefingTime,
        LocalDateTime lastAttemptAt
) {

    public static AdminNewsBriefingSettingResponse from(NewsBriefingSetting setting) {
        return new AdminNewsBriefingSettingResponse(setting.getOutletDomain(), setting.getBriefingTime(),
                setting.getLastAttemptAt());
    }
}
