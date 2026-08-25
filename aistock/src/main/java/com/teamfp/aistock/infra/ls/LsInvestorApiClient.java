package com.teamfp.aistock.infra.ls;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.infra.ls.dto.LsInvestorTypeSummaryDto;
import com.teamfp.aistock.infra.ls.dto.LsMarketInvestorComparisonDto;

import lombok.extern.slf4j.Slf4j;

/**
 * LS증권 Open API [주식] 투자자 카테고리({@code /stock/investor})를 조회하는 클라이언트.
 * 투자자별종합(t1601)/투자자매매종합1(t1615) 2개 TR을 다룬다(2026-08-11 추가).
 */
@Slf4j
@Component
public class LsInvestorApiClient extends LsApiClientSupport {

    private final LsAccessTokenProvider accessTokenProvider;

    @Value("${ls.investor-url}")
    private String investorUrl;

    public LsInvestorApiClient(LsAccessTokenProvider accessTokenProvider, RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
    }

    /** 투자자별종합(t1601) — 코스피 시장 전체 투자자유형별(개인/외국인/기관 등) 순매수 스냅샷. */
    public Optional<LsInvestorTypeSummaryDto> getInvestorTypeSummary() {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = Map.of("gubun1", "1", "gubun2", "1", "gubun3", "", "gubun4", "1");
            Map<String, Object> requestBody = Map.of("t1601InBlock", inBlock);

            Map<String, Object> response = call("t1601", requestBody, token);
            if (response == null || !(response.get("t1601OutBlock1") instanceof Map)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> outBlock = (Map<String, Object>) response.get("t1601OutBlock1");
            return Optional.of(LsInvestorTypeSummaryDto.builder()
                    .individualNetBuy(parseLong(outBlock.get("svolume_08")))
                    .foreignNetBuy(parseLong(outBlock.get("svolume_17")))
                    .institutionNetBuy(parseLong(outBlock.get("svolume_18")))
                    .securitiesNetBuy(parseLong(outBlock.get("svolume_01")))
                    .insuranceNetBuy(parseLong(outBlock.get("svolume_02")))
                    .investmentTrustNetBuy(parseLong(outBlock.get("svolume_03")))
                    .build());
        } catch (CustomException e) {
            log.warn("LS 투자자별종합 조회 중 오류 - 사유: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 투자자매매종합1(t1615) — 코스피/코스닥/선물/옵션 등 시장별 투자자 순매수 비교. */
    public List<LsMarketInvestorComparisonDto> getMarketComparison() {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of("t1615InBlock", Map.of("gubun1", "1", "gubun2", "1"));

            Map<String, Object> response = call("t1615", requestBody, token);
            if (response == null || !(response.get("t1615OutBlock1") instanceof List)) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t1615OutBlock1");
            return outBlock.stream()
                    .map(row -> LsMarketInvestorComparisonDto.builder()
                            .marketName(stringOf(row.get("hname")))
                            .individualNetBuy(parseLong(row.get("sv_08")))
                            .foreignNetBuy(parseLong(row.get("sv_17")))
                            .institutionNetBuy(parseLong(row.get("sv_18")))
                            .build())
                    .toList();
        } catch (CustomException e) {
            log.warn("LS 투자자매매종합1 조회 중 오류 - 사유: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> call(String trCd, Map<String, Object> requestBody, String token) {
        return call(investorUrl, trCd, requestBody, token, "LS 투자자(" + trCd + ") 조회 실패");
    }
}
