package com.teamfp.aistock.infra.ls;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.util.ExternalApiInvoker;
import com.teamfp.aistock.infra.ls.dto.LsProgramTradingRankDto;
import com.teamfp.aistock.infra.ls.dto.LsProgramTradingSnapshotDto;

import lombok.extern.slf4j.Slf4j;

/**
 * LS증권 Open API [주식] 프로그램 카테고리({@code /stock/program})를 조회하는 클라이언트.
 * 종목별프로그램매매동향(t1636)/프로그램매매종합조회미니(t1640) 2개 TR을 다룬다(2026-08-11 추가).
 */
@Slf4j
@Component
public class LsProgramApiClient {

    private static final int MAX_RANK_ITEMS = 10;

    private final LsAccessTokenProvider accessTokenProvider;
    private final RestClient restClient;

    @Value("${ls.program-url}")
    private String programUrl;

    public LsProgramApiClient(LsAccessTokenProvider accessTokenProvider, RestClient.Builder restClientBuilder) {
        this.accessTokenProvider = accessTokenProvider;
        this.restClient = restClientBuilder.build();
    }

    /** 종목별프로그램매매동향(t1636) — 프로그램매매 순매수 상위 종목 랭킹. */
    public List<LsProgramTradingRankDto> getTopProgramTradingStocks() {
        try {
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
                    .map(row -> LsProgramTradingRankDto.builder()
                            .rank(parseInt(row.get("rank")))
                            .stockCode(stringOf(row.get("shcode")))
                            .stockName(stringOf(row.get("hname")))
                            .price(parseLong(row.get("price")))
                            .changeRate(parseDouble(row.get("diff")))
                            .netBuyValue(parseLong(row.get("svalue")))
                            .build())
                    .toList();
        } catch (CustomException e) {
            log.warn("LS 종목별프로그램매매동향 조회 중 오류 - 사유: {}", e.getMessage());
            return List.of();
        }
    }

    /** 프로그램매매종합조회미니(t1640) — 시장 전체(거래소 전체, gubun="11") 순매수 스냅샷. */
    public Optional<LsProgramTradingSnapshotDto> getMarketSnapshot() {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of("t1640InBlock", Map.of("gubun", "11"));

            Map<String, Object> response = call("t1640", requestBody, token);
            if (response == null || !(response.get("t1640OutBlock") instanceof Map)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> outBlock = (Map<String, Object>) response.get("t1640OutBlock");
            return Optional.of(LsProgramTradingSnapshotDto.builder()
                    .offerValue(parseLong(outBlock.get("offervalue")))
                    .bidValue(parseLong(outBlock.get("bidvalue")))
                    .netValue(parseLong(outBlock.get("value")))
                    .build());
        } catch (CustomException e) {
            log.warn("LS 프로그램매매종합미니 조회 중 오류 - 사유: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> call(String trCd, Map<String, Object> requestBody, String token) {
        return ExternalApiInvoker.call(() -> restClient.post()
                        .uri(programUrl)
                        .header("Authorization", "Bearer " + token)
                        .header("tr_cd", trCd)
                        .header("tr_cont", "N")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() { }),
                "LS 프로그램(" + trCd + ") 조회 실패");
    }

    private String stringOf(Object value) {
        return value != null ? value.toString() : null;
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

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double parseDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
