package com.teamfp.aistock.infra.marketdata;

import java.util.List;

import org.springframework.stereotype.Component;

import com.teamfp.aistock.infra.marketdata.dto.ForeignInstitutionalTrendDto;

import lombok.RequiredArgsConstructor;

/**
 * 종목별 외국인·기관·개인 순매수 동향과 외국인 보유 소진율(AI 재무설계사 상담 도구). local-market-data-generator가
 * 종목 객체의 {@code investorTrend}(최근 거래일 최신순, 오늘 행은 5초마다 누적)에 담는 모의 값을 읽는다
 * (fix/local-market-data-stable — 이전에는 외부 시세 데이터 t1716을 호출했다).
 */
@Component
@RequiredArgsConstructor
public class InvestorTrendApiClient {

    private static final int MAX_TREND_ITEMS = 5;
    // 장기간 요청은 개별 행을 그대로 넘기고 요약은 호출부(AiPlanningService)가 한다 — 2년치(약 500거래일) 상한.
    private static final int MAX_LONG_PERIOD_ITEMS = 500;

    private final LocalMarketDataReader localMarketDataReader;

    /** 최근 {@value #MAX_TREND_ITEMS}거래일 동향. 데이터가 없으면 빈 목록. */
    public List<ForeignInstitutionalTrendDto> getRecentTrend(String stockCode) {
        return getTrend(stockCode, null);
    }

    /**
     * periodMonths가 없으면 최근 {@value #MAX_TREND_ITEMS}거래일, 있으면 그 기간(최대 24개월) 안의 거래일을 모두
     * 돌려준다(로컬 데이터에 있는 만큼).
     */
    public List<ForeignInstitutionalTrendDto> getTrend(String stockCode, Integer periodMonths) {
        return MarketDataPeriod.select(
                localMarketDataReader.getStockList(stockCode, "investorTrend", ForeignInstitutionalTrendDto.class),
                ForeignInstitutionalTrendDto::getDate, periodMonths, MAX_TREND_ITEMS, MAX_LONG_PERIOD_ITEMS);
    }
}
