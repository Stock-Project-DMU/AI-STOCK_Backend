package com.teamfp.aistock.infra.ls;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.infra.ls.dto.LsForeignInstitutionalTrendDto;

import lombok.extern.slf4j.Slf4j;

/**
 * LS증권 Open API 외인기관종목별동향(t1716, {@code /stock/frgr-itt})을 조회하는 클라이언트.
 * AI 상담의 get_foreign_institutional_trend 도구 전용이며, {@link LsMarketDataApiClient}와
 * 동일하게 {@code ls.mode} 조건 없이 항상 빈으로 생성되고 매 요청마다 독립적으로 호출한다.
 *
 * <p>t1716은 KRX 거래소 기준과 금융감독원(FSC) 기준 외국인/기관 순매수를 함께 주는데, 이
 * 클라이언트는 그중 KRX 기준 순매수(krx_0008/krx_0018/krx_0009)와 FSC 기준 소진율(fsc_sjrate)만
 * 뽑아 쓴다 — "요즘 외국인들 사고 있는지"를 답하는 데는 실제 체결 기준 순매수 하나면 충분하고,
 * 소진율은 "얼마나 채워져 있는지"를 보여주는 보조 지표로 함께 얹는다. t1702/t1717처럼 투자자
 * 유형을 12~15개로 세분화한 TR도 있지만, 재무설계 상담에서 "사모펀드가 얼마나 샀는지"까지는
 * 필요하지 않다고 판단해 t1716 하나만 쓴다.</p>
 */
@Slf4j
@Component
public class LsInvestorTrendApiClient extends LsApiClientSupport {

    private static final String FOREIGN_INSTITUTIONAL_TREND_TR_CD = "t1716";
    private static final String OUT_BLOCK_KEY = "t1716OutBlock";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // 일간(gubun=0) 기준으로 최근 며칠 치 동향을 볼지 — "요즘"이라는 질문 뉘앙스에 맞춰 최근
    // 1~2주 정도(주말·공휴일을 감안해 여유 있게 10일)를 기본으로 잡는다. DART의 자본변동 조회
    // (CAPITAL_CHANGE_LOOKBACK_YEARS)와 달리 이건 "최근"이 짧은 기간을 의미하는 질문이라 훨씬
    // 짧게 잡았다.
    private static final int LOOKBACK_DAYS = 10;
    // 응답을 최신순 몇 건까지만 Gemini에 넘길지 — DART DISCLOSURE 조회의 MAX_DISCLOSURE_ITEMS와
    // 동일한 이유(프롬프트가 과도하게 길어지지 않도록).
    private static final int MAX_TREND_ITEMS = 5;
    // 2026-08-13 추가 — "6개월간 외국인 순매수 얼마나 됐어?"처럼 장기간을 묻는 질문은 10일치로는
    // 답할 수 없었다(뉴스 검색과 동일한 2년 상한으로 통일, CLAUDE.md 팀 합의). 장기간 조회는
    // 일별 원본을 그대로 다 돌려주고(AiPlanningService가 합계·최고/최저를 계산), 개별 항목
    // 5건으로 자르지 않는다 — 단, 하루 단위로 최대 2년치(약 500거래일)까지 갈 수 있어 응답이
    // 지나치게 커지지 않도록 안전상 상한을 하나 더 둔다.
    private static final int MAX_PERIOD_MONTHS = 24;
    private static final int MAX_LONG_PERIOD_ITEMS = 500;

    private final LsAccessTokenProvider accessTokenProvider;

    @Value("${ls.frgr-itt-url}")
    private String frgrIttUrl;

    public LsInvestorTrendApiClient(LsAccessTokenProvider accessTokenProvider, RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
    }

    /**
     * 종목코드로 최근 {@value #LOOKBACK_DAYS}일간의 일별 외국인·기관·개인 순매수 동향(KRX 기준)과
     * 외국인 보유한도 소진율(FSC 기준)을 조회한다. 실패하거나 데이터가 없으면 빈 리스트를
     * 반환한다 — 다른 LS/DART 조회들과 동일하게 예외를 던지지 않는다.
     */
    public List<LsForeignInstitutionalTrendDto> getRecentTrend(String stockCode) {
        return getTrend(stockCode, null);
    }

    /**
     * periodMonths가 없으면 기존과 동일하게 최근 {@value #LOOKBACK_DAYS}일치(최대
     * {@value #MAX_TREND_ITEMS}건)만 반환한다. periodMonths가 있으면(2026-08-13 추가) 조회
     * 시작일을 그만큼 과거로 넓히고(최대 {@value #MAX_PERIOD_MONTHS}개월=2년), 요약 계산은
     * 호출부(AiPlanningService)가 하도록 개별 항목을 5건으로 자르지 않고 그대로 반환한다.
     */
    public List<LsForeignInstitutionalTrendDto> getTrend(String stockCode, Integer periodMonths) {
        boolean longPeriod = periodMonths != null && periodMonths > 0;
        try {
            String token = accessTokenProvider.issueAccessToken();
            LocalDate today = LocalDate.now();
            LocalDate fromDate = longPeriod
                    ? today.minusMonths(Math.min(periodMonths, MAX_PERIOD_MONTHS))
                    : today.minusDays(LOOKBACK_DAYS);
            Map<String, Object> requestBody = Map.of("t1716InBlock", buildT1716RequestBody(stockCode, fromDate, today));

            Map<String, Object> response = call(frgrIttUrl, FOREIGN_INSTITUTIONAL_TREND_TR_CD, requestBody, token, "LS 외국인/기관 매매동향 조회 실패");

            return parseTrend(stockCode, response, longPeriod);
        } catch (CustomException e) {
            log.warn("LS 외국인/기관 매매동향 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> buildT1716RequestBody(String stockCode, LocalDate fromDate, LocalDate today) {
        Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
        inBlock.put("shcode", stockCode);
        inBlock.put("gubun", "0"); // 0:일간순매수
        inBlock.put("fromdt", fromDate.format(DATE_FORMAT));
        inBlock.put("todt", today.format(DATE_FORMAT));
        inBlock.put("prapp", 0);
        inBlock.put("prgubun", "0");  // 프로그램매매 감산 미적용
        inBlock.put("orggubun", "0"); // 기관 특수 적용 미적용
        inBlock.put("frggubun", "0"); // 외국인 특수 적용 미적용
        inBlock.put("exchgubun", "U"); // 통합(KRX+NXT)
        return inBlock;
    }

    @SuppressWarnings("unchecked")
    private List<LsForeignInstitutionalTrendDto> parseTrend(String stockCode, Map<String, Object> response, boolean longPeriod) {
        if (response == null) {
            return List.of();
        }
        Object outBlockObj = response.get(OUT_BLOCK_KEY);
        if (!(outBlockObj instanceof List)) {
            log.warn("LS 외국인/기관 매매동향 응답에서 {}을 찾지 못함 - stockCode: {}, 응답: {}", OUT_BLOCK_KEY, stockCode, response);
            return List.of();
        }
        List<Map<String, Object>> outBlock = (List<Map<String, Object>>) outBlockObj;

        List<LsForeignInstitutionalTrendDto> items = outBlock.stream()
                .map(this::toDto)
                .toList();
        int cap = longPeriod ? MAX_LONG_PERIOD_ITEMS : MAX_TREND_ITEMS;
        return items.size() > cap ? items.subList(0, cap) : items;
    }

    private LsForeignInstitutionalTrendDto toDto(Map<String, Object> row) {
        return LsForeignInstitutionalTrendDto.builder()
                .date(stringOf(row.get("date")))
                .closePrice(parseLongOrZero(row.get("close")))
                .individualNetBuyKrx(parseLongOrZero(row.get("krx_0008")))
                .institutionNetBuyKrx(parseLongOrZero(row.get("krx_0018")))
                .foreignNetBuyKrx(parseLongOrZero(row.get("krx_0009")))
                .programTradingVolume(parseLongOrZero(row.get("pgmvol")))
                .foreignHoldingShares(parseLong(row.get("fsc_listing")))
                .foreignExhaustionRate(parseDouble(row.get("fsc_sjrate")))
                .shortSellingVolume(parseLongOrZero(row.get("gm_volume")))
                .shortSellingValue(parseLongOrZero(row.get("gm_value")))
                .build();
    }

    private long parseLongOrZero(Object value) {
        Long parsed = parseLong(value);
        return parsed != null ? parsed : 0L;
    }

    // 이 파일만 실패/누락 시 null을 돌려주는 버전을 쓴다(다른 9개 LsApiClientSupport 상속
    // 클라이언트는 실패 시 0.0을 돌려주는 parseDoubleOrZero를 씀) — 이 파일의 호출부가
    // "값 없음"과 "0"을 구분해야 해서, 이름과 계약이 다른 이 로컬 버전을 그대로 남겨둔다
    // (코드리뷰 반영 — LsApiClientSupport로 통합하지 않은 유일한 예외).
    private Double parseDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
