package com.teamfp.aistock.infra.ls;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.infra.ls.dto.LsRankingItemDto;

import lombok.extern.slf4j.Slf4j;

/**
 * LS증권 Open API [주식] 상위종목 카테고리({@code /stock/high-item})를 조회하는 클라이언트.
 * 등락율상위(t1441)/시가총액상위(t1444)/거래량상위(t1452)/거래대금상위(t1463)/
 * 전일동시간대비거래급증(t1466)/시간외등락율상위(t1481)/시간외거래량상위(t1482) 7개 TR을
 * 다룬다 — 전부 "시장 전체를 조건 하나로 훑어 상위 N개 종목을 뽑는" 같은 성격이라, AI 상담
 * 도구(get_market_ranking)에서는 이 7개를 rankingType 파라미터 하나로 묶어 노출한다
 * (AiPlanningService의 DISCLOSURE_TOOL/CAPITAL_CHANGE_TOOL과 같은 "여러 TR을 종류 파라미터로
 * 묶는" 기존 패턴을 그대로 따름).
 *
 * 시간외등락율상위/시간외거래량상위 2개는 시간외 거래 시간대(15:30~18:00)에만 실제로 의미
 * 있는 데이터라, 호출 전 {@link com.teamfp.aistock.global.util.DateUtil#isAfterHoursTradingTime()}로
 * 게이트를 거는 건 이 클라이언트가 아니라 AiPlanningService(도구 실행 계층)의 책임이다 —
 * 이 클라이언트 자체는 "그 시간대가 아니면 호출하면 안 된다"를 모르고 그냥 조회만 한다.
 */
@Slf4j
@Component
public class LsHighItemApiClient extends LsApiClientSupport {

    private static final int MAX_RANKING_ITEMS = 10;

    private final LsAccessTokenProvider accessTokenProvider;

    @Value("${ls.high-item-url}")
    private String highItemUrl;

    public LsHighItemApiClient(LsAccessTokenProvider accessTokenProvider, @org.springframework.beans.factory.annotation.Qualifier("lsRestClientBuilder") RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
    }

    /** 등락율상위(t1441) — 오늘 상승률(gubun1="2") 상위 종목. */
    public List<LsRankingItemDto> getTopPriceChangeRate() {
        Map<String, Object> inBlock = Map.of(
                "gubun1", "1", "gubun2", "2", "gubun3", "1",
                "jc_num", 0, "sprice", 0, "eprice", 0, "volume", 0, "idx", 0, "jc_num2", 0);
        return callAndParse("t1441", "t1441OutBlock1", inBlock, row -> LsRankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(parseLong(row.get("change")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                );
    }

    /** 시가총액상위(t1444) — 코스피+코스닥 전체(upcode="001") 시가총액 상위. */
    public List<LsRankingItemDto> getTopMarketCap() {
        Map<String, Object> inBlock = Map.of("upcode", "001", "idx", 0);
        return callAndParse("t1444", "t1444OutBlock1", inBlock, row -> LsRankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(parseLong(row.get("change")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                .extraInfo("시가총액 비중 %s%%".formatted(stringOf(row.get("rate"))))
                );
    }

    /** 거래량상위(t1452) — 오늘(jnilgubun="1") 누적 거래량 상위. */
    public List<LsRankingItemDto> getTopVolume() {
        Map<String, Object> inBlock = Map.of(
                "gubun", "1", "jnilgubun", "1", "sdiff", 0, "ediff", 0,
                "jc_num", 0, "sprice", 0, "eprice", 0, "volume", 0, "idx", 0);
        return callAndParse("t1452", "t1452OutBlock1", inBlock, row -> LsRankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(parseLong(row.get("change")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                );
    }

    /** 거래대금상위(t1463) — 오늘(jnilgubun="1") 누적 거래대금 상위. */
    public List<LsRankingItemDto> getTopTradingValue() {
        Map<String, Object> inBlock = Map.of(
                "gubun", "1", "jnilgubun", "1", "jc_num", 0, "sprice", 0, "eprice", 0,
                "volume", 0, "idx", 0, "jc_num2", 0);
        return callAndParse("t1463", "t1463OutBlock1", inBlock, row -> LsRankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(parseLong(row.get("change")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                .extraInfo("거래대금 %s백만원".formatted(stringOf(row.get("value"))))
                );
    }

    /** 전일동시간대비거래급증(t1466) — 어제 같은 시각 대비 거래량이 급증한 종목. */
    public List<LsRankingItemDto> getSurgingVolumeVsYesterday() {
        Map<String, Object> inBlock = Map.of(
                "gubun", "1", "type1", "1", "type2", "1",
                "jc_num", 0, "sprice", 0, "eprice", 0, "volume", 0, "idx", 0, "jc_num2", 0);
        return callAndParse("t1466", "t1466OutBlock1", inBlock, row -> LsRankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(parseLong(row.get("change")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                .extraInfo("전일 동시각 대비 거래량 %s%% 증가".formatted(stringOf(row.get("voldiff"))))
                );
    }

    /**
     * 시간외등락율상위(t1481) — 시간외 거래(15:30~18:00) 시간대에만 의미 있음. 호출 전
     * 시간대 게이트는 AiPlanningService에서 처리한다.
     */
    public List<LsRankingItemDto> getTopAfterHoursPriceChangeRate() {
        Map<String, Object> inBlock = Map.of("gubun1", "1", "gubun2", "1", "jongchk", "1", "volume", "1", "idx", 0);
        return callAndParse("t1481", "t1481OutBlock1", inBlock, row -> LsRankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(parseLong(row.get("change")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                );
    }

    /**
     * 시간외거래량상위(t1482) — 시간외 거래(15:30~18:00) 시간대에만 의미 있음. 호출 전
     * 시간대 게이트는 AiPlanningService에서 처리한다.
     */
    public List<LsRankingItemDto> getTopAfterHoursVolume() {
        Map<String, Object> inBlock = Map.of("gubun", "1", "jongchk", "1", "idx", 0, "sort_gbn", 0);
        return callAndParse("t1482", "t1482OutBlock1", inBlock, row -> LsRankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(parseLong(row.get("change")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                );
    }

    @SuppressWarnings("unchecked")
    private List<LsRankingItemDto> callAndParse(
            String trCd, String outBlockKey, Map<String, Object> inBlock,
            java.util.function.Function<Map<String, Object>, LsRankingItemDto.LsRankingItemDtoBuilder> rowMapper) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of(trCd + "InBlock", inBlock);

            Map<String, Object> response = call(highItemUrl, trCd, requestBody, token, "LS 상위종목(" + trCd + ") 조회 실패");

            if (response == null) {
                return List.of();
            }
            Object outBlockObj = response.get(outBlockKey);
            if (!(outBlockObj instanceof List)) {
                log.warn("LS 상위종목 응답에서 {}을 찾지 못함 - trCd: {}, 응답: {}", outBlockKey, trCd, response);
                return List.of();
            }
            List<Map<String, Object>> outBlock = (List<Map<String, Object>>) outBlockObj;

            List<LsRankingItemDto> items = new java.util.ArrayList<>();
            int rank = 1;
            for (Map<String, Object> row : outBlock) {
                items.add(rowMapper.apply(row).rank(rank++).build());
                if (items.size() >= MAX_RANKING_ITEMS) {
                    break;
                }
            }
            return items;
        } catch (CustomException e) {
            log.warn("LS 상위종목 조회 중 오류 - trCd: {}, 사유: {}", trCd, e.getMessage());
            return List.of();
        }
    }

}
