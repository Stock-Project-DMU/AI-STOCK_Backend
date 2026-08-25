package com.teamfp.aistock.domain.ai.dto.response;

import java.time.LocalDate;
import java.util.List;

import com.teamfp.aistock.domain.ai.dto.NewsSourceLinkDto;
import com.teamfp.aistock.domain.ai.entity.NewsBriefing;
import com.teamfp.aistock.global.util.NewsRelevanceMatcher;

/**
 * "오늘의 브리핑" 조회 응답 — GET /api/ai/news/briefings/today. sources는 요약의 근거가 된
 * 기사 목록으로, 프론트가 각 기사를 원문 링크로 바로 이동시킬 수 있게 link를 포함한다
 * (2026-08-24 사용자 요청). source_links JSON 컬럼 파싱은 AiNewsService(SimulationService의
 * scenario_data 파싱과 동일한 패턴)가 미리 해서 넘겨준다 — DTO는 이미 파싱된 값만 담는다.
 */
public record NewsBriefingResponse(String outletDomain, String outletName, LocalDate briefingDate, String content,
                                    List<NewsSourceLinkDto> sources) {

    public static NewsBriefingResponse from(NewsBriefing briefing, List<NewsSourceLinkDto> sources) {
        String outletName = NewsRelevanceMatcher.OUTLET_NAMES.getOrDefault(briefing.getOutletDomain(), "확인된 매체");
        return new NewsBriefingResponse(briefing.getOutletDomain(), outletName, briefing.getBriefingDate(), briefing.getContent(), sources);
    }
}
