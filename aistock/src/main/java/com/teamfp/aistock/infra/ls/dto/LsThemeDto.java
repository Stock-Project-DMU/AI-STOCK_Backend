package com.teamfp.aistock.infra.ls.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LS증권 Open API 테마 관련 조회(종목별테마 t1532, 특이테마 t1533) 결과를 담는 DTO.
 * 구성종목+시세(t1537)는 종목이 여러 개 딸려오는 형태라 {@link LsThemeConstituentDto}로 별도 분리한다.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsThemeDto {

    private String themeCode;
    private String themeName;
    // 특이테마(오늘 핫테마) 조회일 때만 채워지는 부가 통계 — 종목별테마 조회에서는 null.
    private String stats;
}
