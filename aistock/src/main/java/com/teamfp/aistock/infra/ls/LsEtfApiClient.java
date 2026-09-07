package com.teamfp.aistock.infra.ls;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.infra.ls.dto.LsCurrentPriceDetailDto;
import com.teamfp.aistock.infra.ls.dto.LsEtfConstituentDto;

import lombok.extern.slf4j.Slf4j;

/**
 * LS증권 Open API [주식] ETF 카테고리({@code /stock/etf})를 조회하는 클라이언트.
 * ETF현재가(시세)조회(t1901)/ETF구성종목조회(t1904) 2개 TR을 다룬다(2026-08-11 추가).
 */
@Slf4j
@Component
public class LsEtfApiClient extends LsApiClientSupport {

    private static final int MAX_CONSTITUENT_ITEMS = 10;

    private final LsAccessTokenProvider accessTokenProvider;

    @Value("${ls.etf-url}")
    private String etfUrl;

    public LsEtfApiClient(LsAccessTokenProvider accessTokenProvider, RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
    }

    /** ETF현재가(시세)조회(t1901) — NAV·52주 최고저 포함 현재가. */
    public Optional<LsCurrentPriceDetailDto> getCurrentPrice(String stockCode) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of("t1901InBlock", Map.of("shcode", stockCode));

            Map<String, Object> response = call("t1901", requestBody, token);
            if (response == null || !(response.get("t1901OutBlock") instanceof Map)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> outBlock = (Map<String, Object>) response.get("t1901OutBlock");
            Long price = parseLong(outBlock.get("price"));
            if (price == null) {
                return Optional.empty();
            }
            Long changeAmount = parseLong(outBlock.get("change"));
            Long volume = parseLong(outBlock.get("volume"));
            // per/high52wdate/low52wdate/listing/exhratio는 t1901OutBlock에 실제로 내려오는
            // 필드인데(LS증권 API 정리.html t1901 섹션), LsMarketDataApiClient(t1102)와 달리
            // 지금까지 매핑이 안 돼 있어서 describeCurrentPrice()가 이 값들을 항상 빈 값으로만
            // 보여주고 있었다(코드리뷰 지적 반영, 2026-09). pbr은 t1901에 대응 필드가 없어(ETF는
            // PBR 개념이 없음) 계속 null로 둔다 — LsMarketDataApiClient.getCurrentPrice()와
            // 동일한 파싱 패턴(parseNullableDouble 등)을 그대로 따른다.
            return Optional.of(LsCurrentPriceDetailDto.builder()
                    .stockCode(stockCode)
                    .stockName(stringOf(outBlock.get("hname")))
                    .currentPrice(price)
                    .changeAmount(changeAmount != null ? changeAmount : 0L)
                    .changeRate(parseDoubleOrZero(outBlock.get("diff")))
                    .volume(volume != null ? volume : 0L)
                    .per(parseNullableDouble(outBlock.get("per")))
                    .high52w(parseLong(outBlock.get("high52w")))
                    .high52wDate(stringOf(outBlock.get("high52wdate")))
                    .low52w(parseLong(outBlock.get("low52w")))
                    .low52wDate(stringOf(outBlock.get("low52wdate")))
                    .listingShares(parseLong(outBlock.get("listing")))
                    .foreignExhaustionRate(parseNullableDouble(outBlock.get("exhratio")))
                    .updatedAt(java.time.LocalDateTime.now())
                    .build());
        } catch (CustomException e) {
            log.warn("LS ETF현재가 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    /** ETF구성종목조회(t1904) — 구성종목 최대 10개(비중 큰 순 응답 그대로). */
    public List<LsEtfConstituentDto> getConstituents(String stockCode) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = Map.of("shcode", stockCode, "date", "", "sgb", "1");
            Map<String, Object> requestBody = Map.of("t1904InBlock", inBlock);

            Map<String, Object> response = call("t1904", requestBody, token);
            if (response == null || !(response.get("t1904OutBlock1") instanceof List)) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t1904OutBlock1");
            return outBlock.stream()
                    .limit(MAX_CONSTITUENT_ITEMS)
                    .map(row -> LsEtfConstituentDto.builder()
                            .stockCode(stringOf(row.get("shcode")))
                            .stockName(stringOf(row.get("hname")))
                            .price(parseLong(row.get("price")))
                            .changeRate(parseDoubleOrZero(row.get("diff")))
                            .weight(parseDoubleOrZero(row.get("weight")))
                            .build())
                    .toList();
        } catch (CustomException e) {
            log.warn("LS ETF구성종목 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> call(String trCd, Map<String, Object> requestBody, String token) {
        return call(etfUrl, trCd, requestBody, token, "LS ETF(" + trCd + ") 조회 실패");
    }
}
