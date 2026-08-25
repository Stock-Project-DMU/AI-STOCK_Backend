package com.teamfp.aistock.domain.ai.dto.response;

/**
 * 사용자가 언론사를 고를 때 프론트에 보여줄 선택지 하나 — GET /api/ai/news/outlets 응답.
 */
public record NewsOutletResponse(String outletDomain, String outletName) {

    public static NewsOutletResponse of(String outletDomain, String outletName) {
        return new NewsOutletResponse(outletDomain, outletName);
    }
}
