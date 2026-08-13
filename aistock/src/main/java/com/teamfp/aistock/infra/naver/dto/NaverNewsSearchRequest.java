package com.teamfp.aistock.infra.naver.dto;

/**
 * 뉴스 검색 조건을 우선순위 3단계로 명시적으로 분리한다(TavilySearchRequest와 동일한 설계).
 *
 * <p>1단계(무조건 필요) — companyName: 근거 확인용으로 항상 요구된다. 회사명이 실제 기사
 * 제목에 없으면 그 기사는 무조건 무관한 것으로 본다.</p>
 *
 * <p>2단계(질문의 구체성에 따라 켜짐) — topic: 사용자가 구체적인 주제를 물었을 때만 채워진다.
 * null/빈 값이면 이 조건 자체가 꺼져서 회사명만으로 관련성을 판단한다.</p>
 *
 * <p>3단계(사용자가 직접 요구했을 때만) — periodDays: 사용자가 기간을 콕 짚어 말했을 때만
 * 그 값을 쓰고, 없으면 NaverNewsApiClient의 기본값(30일)을 쓴다. 네이버 뉴스 검색 API는
 * Tavily의 "days" 같은 서버 측 기간 필터 파라미터가 없어서, 응답으로 받은 각 기사의
 * pubDate를 기준으로 클라이언트에서 직접 걸러낸다.</p>
 */
public record NaverNewsSearchRequest(String companyName, String topic, Integer periodDays) {
}
