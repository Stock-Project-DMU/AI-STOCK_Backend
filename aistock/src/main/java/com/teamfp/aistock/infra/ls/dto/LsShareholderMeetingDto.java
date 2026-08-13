package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LS증권 Open API 종목별증시일정(t3202) 응답 중 주주총회(upgu=09) 항목만 걸러낸 DTO.
 *
 * t3202는 유상증자·무상증자·배당·감자·합병분할 등 14가지 일정을 함께 주지만, 그중 배당·증자·
 * 감자·합병분할은 이미 DART의 정기보고서/주요사항보고서 도구가 담당하고 있어(DartApiClient의
 * "배당사항"/"증자감자현황"/"감자결정"/"회사분할결정" 등) 그대로 노출하면 Gemini가 같은 주제를
 * 두 도구 중 어느 쪽으로 답해야 할지 헷갈릴 수 있다. DART가 구조화된 형태로 다루지 않는
 * "주주총회 날짜"만 서버 단에서 필터링해 남긴다(LsInvestInfoApiClient 참고).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LsShareholderMeetingDto {

    private String date;    // 기준일(YYYYMMDD)
    private String eventName; // 업무명(항상 "주주총회")
}
