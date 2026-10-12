package com.teamfp.aistock.infra.marketdata.dto;

import java.util.List;

/**
 * 종목 리서치 "시가총액 상위 비교" — 보고 있는 종목과 같은 시장(KOSPI/KOSDAQ)의 시가총액 순위(fix/stock-detail-review).
 *
 * <p>items는 같은 시장 상위 10개이고, 보고 있는 종목이 10위 밖이면 그 종목을 맨 뒤에 하나 더 붙인다(각 항목의 rank는
 * 실제 순위). 순위는 등록 종목(stocks.json, ETF 제외) 범위 안에서 모의 현재가 × 상장주식수로 계산한다. 시세 데이터에 없는
 * 종목이면 market·rank가 null, ETF면 rank만 null이고 둘 다 items는 빈 목록이다.</p>
 *
 * @param rank       보고 있는 종목의 같은 시장 내 시가총액 순위(1부터)
 * @param totalCount 같은 시장의 등록 종목 수
 */
public record MarketCapPeersDto(
        String stockCode,
        String stockName,
        String market,
        Integer rank,
        int totalCount,
        List<RankingItemDto> items
) {
}
