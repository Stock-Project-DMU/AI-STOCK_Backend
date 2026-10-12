package com.teamfp.aistock.domain.stock.dto.response;

import com.teamfp.aistock.infra.dart.dto.DartCompanyResponse;
import com.teamfp.aistock.infra.marketdata.HighItemApiClient;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;

/**
 * 종목 리서치 "개요" 탭(fix/stock-detail-review) — DART 기업개황·사업보고서 기반 재무 지표(실제)와 시세 기반 지표(모의 시세)를 함께
 * 담는다. 현재가·등락은 화면 상단 시세와 겹쳐 넣지 않는다.
 *
 * @param company   DART 기업개황. ETF처럼 DART 공시 회사가 아니면 null
 * @param financial 최근 사업보고서 한 해의 재무 지표(ROE·ROA·영업이익률·순이익률·EPS·부채비율 등 — 재무제표 탭의 최신 연도와 같다).
 *                  사업보고서가 없으면 null
 * @param market    "KOSPI" 또는 "KOSDAQ"
 * @param marketCap 시가총액(원) = 모의 현재가 × 상장주식수. 상장주식수가 없으면(ETF) null
 * @param per       모의 시세 기준 PER. 0(우선주 등 미계산)이면 null
 */
public record StockOverviewResponse(
        DartCompanyResponse company,
        StockFinancialYearResponse financial,
        String market,
        Long marketCap,
        Double per,
        Double pbr,
        Long high52w,
        String high52wDate,
        Long low52w,
        String low52wDate,
        Double foreignExhaustionRate,
        Long listingShares
) {

    public static StockOverviewResponse of(DartCompanyResponse company, StockFinancialYearResponse financial,
                                           CurrentPriceDetailDto price) {
        // listingShares는 천주 단위라 1,000을 곱한다(HighItemApiClient의 시가총액 계산과 같다).
        if (price == null) {
            // 시세가 없으면(생성기 중단 등) DART 정보만 담고 시세 지표는 비운다(2026-10-12 코드리뷰).
            return new StockOverviewResponse(company, financial, null, null, null, null, null, null, null, null, null, null);
        }
        Long marketCap = price.getListingShares() != null ? price.getCurrentPrice() * price.getListingShares() * 1000 : null;
        return new StockOverviewResponse(company, financial, price.getMarket(), marketCap,
                HighItemApiClient.valuation(price.getPer()), HighItemApiClient.valuation(price.getPbr()),
                price.getHigh52w(), price.getHigh52wDate(), price.getLow52w(), price.getLow52wDate(),
                price.getForeignExhaustionRate(), price.getListingShares());
    }
}
