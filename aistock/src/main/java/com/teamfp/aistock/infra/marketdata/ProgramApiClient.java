package com.teamfp.aistock.infra.marketdata;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.infra.marketdata.dto.ProgramTradingRankDto;
import com.teamfp.aistock.infra.marketdata.dto.ProgramTradingSnapshotDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 외부 시세 데이터 제공사 Open API [주식] 프로그램 카테고리({@code /stock/program})를 조회하는 클라이언트.
 * 종목별프로그램매매동향(t1636)/프로그램매매종합조회미니(t1640) 2개 TR을 다룬다(2026-08-11 추가).
 */
@Slf4j
@Component
public class ProgramApiClient extends MarketDataApiClientSupport {

    private static final int MAX_RANK_ITEMS = 10;

    private final MarketDataAccessTokenProvider accessTokenProvider;

    @Value("${market-data.program-url}")
    private String programUrl;

    public ProgramApiClient(MarketDataAccessTokenProvider accessTokenProvider, @org.springframework.beans.factory.annotation.Qualifier("marketDataRestClientBuilder") RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
    }

    /** 종목별프로그램매매동향(t1636) — 프로그램매매 순매수 상위 종목 랭킹. */
    public List<ProgramTradingRankDto> getTopProgramTradingStocks() {
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> inBlock = Map.of(
                "gubun", "0", "gubun1", "0", "gubun2", "1", "shcode", "", "cts_idx", 0);
        Map<String, Object> requestBody = Map.of("t1636InBlock", inBlock);

        Map<String, Object> response = call("t1636", requestBody, token);
        if (response == null || !(response.get("t1636OutBlock1") instanceof List)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t1636OutBlock1");
        return outBlock.stream()
                .limit(MAX_RANK_ITEMS)
                .map(row -> ProgramTradingRankDto.builder()
                        .rank(parseInt(row.get("rank")))
                        .stockCode(stringOf(row.get("shcode")))
                        .stockName(stringOf(row.get("hname")))
                        .price(parseLong(row.get("price")))
                        .changeRate(parseDoubleOrZero(row.get("diff")))
                        .netBuyValue(parseLong(row.get("svalue")))
                        .build())
                .toList();
    }

    /** 프로그램매매종합조회미니(t1640) — 시장 전체(거래소 전체, gubun="11") 순매수 스냅샷. */
    public Optional<ProgramTradingSnapshotDto> getMarketSnapshot() {
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> requestBody = Map.of("t1640InBlock", Map.of("gubun", "11"));

        Map<String, Object> response = call("t1640", requestBody, token);
        if (response == null || !(response.get("t1640OutBlock") instanceof Map)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> outBlock = (Map<String, Object>) response.get("t1640OutBlock");
        return Optional.of(ProgramTradingSnapshotDto.builder()
                .offerValue(parseLong(outBlock.get("offervalue")))
                .bidValue(parseLong(outBlock.get("bidvalue")))
                .netValue(parseLong(outBlock.get("value")))
                .build());
    }

    private Map<String, Object> call(String trCd, Map<String, Object> requestBody, String token) {
        return call(programUrl, trCd, requestBody, token, "외부 시세 데이터 프로그램(" + trCd + ") 조회 실패");
    }

    private int parseInt(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
