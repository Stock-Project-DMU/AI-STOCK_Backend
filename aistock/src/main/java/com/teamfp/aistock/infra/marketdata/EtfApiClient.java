package com.teamfp.aistock.infra.marketdata;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.EtfConstituentDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 외부 시세 데이터 제공사 Open API [주식] ETF 카테고리({@code /stock/etf})를 조회하는 클라이언트.
 * ETF현재가(시세)조회(t1901)/ETF구성종목조회(t1904) 2개 TR을 다룬다(2026-08-11 추가).
 */
@Slf4j
@Component
public class EtfApiClient extends MarketDataApiClientSupport {

    private static final int MAX_CONSTITUENT_ITEMS = 10;

    private final MarketDataAccessTokenProvider accessTokenProvider;
    // market-data.mode=mock일 때만 존재. getCurrentPrice()만 mock 분기를 탄다 —
    // getConstituents()(구성종목)는 market_data.json에 대응 데이터가 없어 이번 범위 밖이다
    // (ETF 시세 mock 지원 추가, 2026-09-21).
    private final Optional<LocalMarketDataReader> localMarketDataReader;

    @Value("${market-data.etf-url}")
    private String etfUrl;

    public EtfApiClient(
            MarketDataAccessTokenProvider accessTokenProvider,
            Optional<LocalMarketDataReader> localMarketDataReader,
            @org.springframework.beans.factory.annotation.Qualifier("marketDataRestClientBuilder") RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
        this.localMarketDataReader = localMarketDataReader;
    }

    /**
     * ETF현재가(시세)조회(t1901) — NAV·52주 최고저 포함 현재가.
     *
     * market-data.mode=mock이면 MarketDataApiClient.getCurrentPrice()와 동일한 패턴으로
     * LocalMarketDataReader를 직접 읽는다. stocks.json에 isEtf:true로 등록된 종목만 mock
     * 데이터가 있다 — 등록되지 않은 ETF 코드는(t1901 전용 필드인 NAV 등은 애초에 mock에
     * 없으므로) 다른 mock 분기와 동일하게 빈 값을 반환한다. local-market-data-generator가
     * ETF 종목에만 채워 넣는 exchgubun("K"=KRX)도 CurrentPriceDetailDto 그대로를 반환하므로
     * 별도 매핑 없이 함께 딸려온다(ETF exchgubun 신규 필드 반영, #04, 2026-09-23).
     */
    public Optional<CurrentPriceDetailDto> getCurrentPrice(String stockCode) {
        if (localMarketDataReader.isPresent()) {
            return localMarketDataReader.get().getCurrentPrice(stockCode).filter(CurrentPriceDetailDto::isEtf);
        }
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
        // change(등락액)가 부호 없는 크기로 오고 방향은 sign 필드로 오는 t1102와 동일한
        // 버그가 여기(t1901)도 있었다 — MarketDataApiClientSupport.signedLong으로 수정(2026-09-11).
        Long changeAmount = signedLong(outBlock.get("change"), outBlock.get("sign"));
        Long volume = parseLong(outBlock.get("volume"));
        // per/high52wdate/low52wdate/listing/exhratio는 t1901OutBlock에 실제로 내려오는
        // 필드인데(외부 시세 데이터 제공사 API 정리.html t1901 섹션), MarketDataApiClient(t1102)와 달리
        // 지금까지 매핑이 안 돼 있어서 describeCurrentPrice()가 이 값들을 항상 빈 값으로만
        // 보여주고 있었다(코드리뷰 지적 반영, 2026-09). pbr은 t1901에 대응 필드가 없어(ETF는
        // PBR 개념이 없음) 계속 null로 둔다 — MarketDataApiClient.getCurrentPrice()와
        // 동일한 파싱 패턴(parseNullableDouble 등)을 그대로 따른다.
        return Optional.of(CurrentPriceDetailDto.builder()
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
    }

    /** ETF구성종목조회(t1904) — 구성종목 최대 10개(비중 큰 순 응답 그대로). */
    public List<EtfConstituentDto> getConstituents(String stockCode) {
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
                .map(row -> EtfConstituentDto.builder()
                        .stockCode(stringOf(row.get("shcode")))
                        .stockName(stringOf(row.get("hname")))
                        .price(parseLong(row.get("price")))
                        .changeRate(parseDoubleOrZero(row.get("diff")))
                        .weight(parseDoubleOrZero(row.get("weight")))
                        .build())
                .toList();
    }

    private Map<String, Object> call(String trCd, Map<String, Object> requestBody, String token) {
        return call(etfUrl, trCd, requestBody, token, "외부 시세 데이터 ETF(" + trCd + ") 조회 실패");
    }
}
