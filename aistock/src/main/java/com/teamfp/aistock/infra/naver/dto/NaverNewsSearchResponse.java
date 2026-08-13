package com.teamfp.aistock.infra.naver.dto;

import java.util.List;

/**
 * 네이버 뉴스 검색 API는 Tavily와 달리 자체 종합요약(answer)을 주지 않는다 — 검색된 기사
 * 목록(제목·요약·링크·발행일)만 준다. 그래서 AiPlanningService가 이 결과 목록을 직접 정리해서
 * Gemini에 넘길 재료를 만든다 — 오히려 이게 Tavily의 "answer 자체를 못 믿겠다"는 문제
 * (2026-08-05 라이브 테스트로 확인)를 구조적으로 없앤다.
 */
public record NaverNewsSearchResponse(List<NaverNewsResult> results) {

    // outlet(언론사명) - 2026-08-06 추가. 사용자가 "이거 어디 기사야?"처럼 출처를 물었을 때
    // Gemini가 최종 답변에서 실제 매체명을 인용할 수 있게 하려면 결과에 매체명 자체가 있어야
    // 하는데, 그 전까지는 title/description/link/pubDate만 넘겨서 링크 URL 말고는 어느
    // 언론사인지 답변에서 밝힐 근거가 없었다(NaverNewsApiClient.resolveOutletName() 참고).
    public record NaverNewsResult(String title, String description, String link, String pubDate, String outlet) {
    }
}
