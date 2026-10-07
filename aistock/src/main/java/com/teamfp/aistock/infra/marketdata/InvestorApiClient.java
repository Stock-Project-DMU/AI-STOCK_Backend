package com.teamfp.aistock.infra.marketdata;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.teamfp.aistock.infra.marketdata.dto.InvestorTypeSummaryDto;
import com.teamfp.aistock.infra.marketdata.dto.MarketInvestorComparisonDto;

import lombok.RequiredArgsConstructor;

/**
 * 시장 전체 투자자 매매 — 투자자유형별 순매수 스냅샷과 시장(코스피/코스닥)별 비교(AI 재무설계사 상담 도구).
 * local-market-data-generator가 market_data.json의 {@code _market.investorSummary}·{@code _market.marketComparison}에
 * 5초마다 갱신하는 모의 값을 읽는다(fix/local-market-data-stable — 이전에는 외부 시세 데이터 t1601/t1615를 호출했다).
 */
@Component
@RequiredArgsConstructor
public class InvestorApiClient {

    private final LocalMarketDataReader localMarketDataReader;

    /** 투자자유형별(개인/외국인/기관 등) 순매수 스냅샷(주). 데이터가 없으면 빈 값. */
    public Optional<InvestorTypeSummaryDto> getInvestorTypeSummary() {
        return localMarketDataReader.getMarketObject("investorSummary", InvestorTypeSummaryDto.class);
    }

    /** 시장별(코스피/코스닥) 개인·외국인·기관 순매수 비교. 데이터가 없으면 빈 목록. */
    public List<MarketInvestorComparisonDto> getMarketComparison() {
        return localMarketDataReader.getMarketList("marketComparison", MarketInvestorComparisonDto.class);
    }
}
