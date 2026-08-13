package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LS증권 Open API 투자의견(t3401) 응답 한 건 — 특정 증권사가 특정 날짜에 낸 투자의견/목표주가
 * 변경 이력을 담는다. 네이버 뉴스가 "목표주가 상향" 같은 기사를 다룰 수는 있어도 그건 정성적
 * 요약(기사 텍스트)일 뿐이라, 실제 수치(옛 의견/새 의견, 옛 목표가/새 목표가)가 필요한 질문은
 * 이 도구가 전담한다 — AiPlanningService.INVESTMENT_OPINION_TOOL 설명 참고.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LsInvestmentOpinionDto {

    private String date;              // 의견 발표일(YYYYMMDD)
    private String securitiesFirm;    // 증권사명
    private String opinionBefore;     // 변경 전 투자의견
    private String opinionAfter;      // 변경 후 투자의견
    private Long targetPriceBefore;   // 변경 전 목표주가
    private Long targetPriceAfter;    // 변경 후 목표주가
    private Long closePriceOnDate;    // 의견 발표일 종가
}
