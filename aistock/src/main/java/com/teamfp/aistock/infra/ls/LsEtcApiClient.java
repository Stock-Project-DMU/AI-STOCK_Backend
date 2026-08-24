package com.teamfp.aistock.infra.ls;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.infra.ls.dto.LsNewListingDto;
import com.teamfp.aistock.infra.ls.dto.LsShortSellingTrendDto;
import com.teamfp.aistock.infra.ls.dto.LsStockCreditInfoDto;
import com.teamfp.aistock.infra.ls.dto.LsStockMasterInfoDto;

import lombok.extern.slf4j.Slf4j;

/**
 * LS증권 Open API [주식] 기타 카테고리({@code /stock/etc})를 조회하는 클라이언트.
 * 예탁담보융자가능종목현황조회(CLNAQ00100)/증거금율별종목조회(t1411)/신용거래동향(t1921)/
 * 종목별대차거래일간추이(t1941)/신규상장종목조회(t1403)/공매도일별추이(t1927)/
 * 주식종목조회API용(t8436) 7개 TR을 다룬다(2026-08-11 추가).
 */
@Slf4j
@Component
public class LsEtcApiClient extends LsApiClientSupport {

    private static final int MAX_TREND_ITEMS = 5;
    // 2026-08-13 추가 — 장기간(6개월/1년 등) 공매도 추이를 물으면 최근 며칠치로는 답이 안
    // 되던 문제 대응, 뉴스 검색과 동일한 2년 상한. 장기간 조회는 요약 계산을 위해 5건으로
    // 자르지 않고 넘겨준다(과도한 응답 방지용 안전 상한만 별도로 둠).
    private static final int MAX_PERIOD_MONTHS = 24;
    private static final int MAX_LONG_PERIOD_ITEMS = 500;
    // 신규상장종목은 (다른 4개 트렌드 도구와 달리) 합계·요약이 아니라 회사명 목록 자체를 그대로
    // 보여줘야 의미가 있어, 500개까지 열거하면 프롬프트가 과도하게 커진다 — 더 낮은 상한을 둔다.
    private static final int MAX_LONG_PERIOD_LISTING_ITEMS = 50;
    private static final int MAX_LISTING_ITEMS = 10;

    private final LsAccessTokenProvider accessTokenProvider;

    @Value("${ls.etc-url}")
    private String etcUrl;

    public LsEtcApiClient(LsAccessTokenProvider accessTokenProvider, RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
    }

    /** 예탁담보융자가능종목현황조회(CLNAQ00100) — 이 종목을 담보로 대출 가능한지. */
    public Optional<LsStockCreditInfoDto> getCollateralLoanEligibility(String stockCode) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
            inBlock.put("QryTp", "0");
            inBlock.put("IsuNo", "A" + stockCode);
            inBlock.put("SecTpCode", "1");
            inBlock.put("LoanIntrstGrdCode", "00");
            inBlock.put("LoanTp", "1");
            Map<String, Object> requestBody = Map.of("CLNAQ00100InBlock1", inBlock);

            Map<String, Object> response = call("CLNAQ00100", requestBody, token);
            if (response == null || !(response.get("CLNAQ00100OutBlock2") instanceof List)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("CLNAQ00100OutBlock2");
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> row = rows.get(0);
            String regType = stringOf(row.get("RegTpNm"));
            return Optional.of(LsStockCreditInfoDto.builder()
                    .stockCode(stockCode)
                    .detail("담보융자 가능 여부: %s".formatted(regType != null ? regType : "정보없음"))
                    .build());
        } catch (CustomException e) {
            log.warn("LS 담보대출가능여부 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    /** 증거금율별종목조회(t1411) — 이 종목 매수 시 증거금률. */
    public Optional<LsStockCreditInfoDto> getMarginRequirement(String stockCode) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = Map.of(
                    "gubun", "0", "jongchk", "1", "jkrate", "1", "shcode", stockCode, "idx", 0);
            Map<String, Object> requestBody = Map.of("t1411InBlock", inBlock);

            Map<String, Object> response = call("t1411", requestBody, token);
            if (response == null || !(response.get("t1411OutBlock1") instanceof List)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("t1411OutBlock1");
            return rows.stream()
                    .filter(row -> stockCode.equals(stringOf(row.get("shcode"))))
                    .findFirst()
                    .map(row -> LsStockCreditInfoDto.builder()
                            .stockCode(stockCode)
                            .detail("증거금률 %s%%".formatted(stringOf(row.get("jkrate"))))
                            .build());
        } catch (CustomException e) {
            log.warn("LS 증거금율 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    /** 신용거래동향(t1921) — 최근 신용융자 잔고 추이(최근 5일). */
    public Optional<LsStockCreditInfoDto> getMarginTradingTrend(String stockCode) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = Map.of("shcode", stockCode, "gubun", "1", "date", "", "idx", 0);
            Map<String, Object> requestBody = Map.of("t1921InBlock", inBlock);

            Map<String, Object> response = call("t1921", requestBody, token);
            if (response == null || !(response.get("t1921OutBlock1") instanceof List)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("t1921OutBlock1");
            String summary = rows.stream()
                    .limit(MAX_TREND_ITEMS)
                    .map(row -> "%s: 신용융자잔고비중 %s%%".formatted(stringOf(row.get("mmdate")), stringOf(row.get("jkrate"))))
                    .collect(java.util.stream.Collectors.joining(", "));
            if (summary.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(LsStockCreditInfoDto.builder().stockCode(stockCode).detail(summary).build());
        } catch (CustomException e) {
            log.warn("LS 신용거래동향 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    /** 종목별대차거래일간추이(t1941) — 최근 대차거래(공매도 준비 물량) 추이. */
    public Optional<LsStockCreditInfoDto> getSecuritiesLendingTrend(String stockCode) {
        return getSecuritiesLendingTrend(stockCode, null);
    }

    /**
     * periodMonths가 없으면 기존과 동일하게 최근 7일치만 조회한다. periodMonths가
     * 있으면(2026-08-13 추가) sdate를 최대 {@value #MAX_PERIOD_MONTHS}개월(2년) 전까지 넓히고,
     * 요약 문자열에 담을 항목 수도 5건으로 자르지 않는다(안전 상한만 별도로 둠).
     */
    public Optional<LsStockCreditInfoDto> getSecuritiesLendingTrend(String stockCode, Integer periodMonths) {
        boolean longPeriod = periodMonths != null && periodMonths > 0;
        try {
            String token = accessTokenProvider.issueAccessToken();
            java.time.LocalDate today = java.time.LocalDate.now();
            java.time.LocalDate fromDate = longPeriod
                    ? today.minusMonths(Math.min(periodMonths, MAX_PERIOD_MONTHS))
                    : today.minusDays(7);
            Map<String, Object> inBlock = Map.of(
                    "shcode", stockCode,
                    "sdate", fromDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                    "edate", today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
            Map<String, Object> requestBody = Map.of("t1941InBlock", inBlock);

            Map<String, Object> response = call("t1941", requestBody, token);
            if (response == null || !(response.get("t1941OutBlock1") instanceof List)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("t1941OutBlock1");
            int cap = longPeriod ? MAX_LONG_PERIOD_ITEMS : MAX_TREND_ITEMS;
            List<Map<String, Object>> limited = rows.size() > cap ? rows.subList(0, cap) : rows;
            String summary;
            if (!longPeriod) {
                summary = limited.stream()
                        .map(row -> "%s: 대차거래량 %s주".formatted(stringOf(row.get("date")), stringOf(row.get("tovolume"))))
                        .collect(java.util.stream.Collectors.joining(", "));
            } else {
                // 2026-08-13 추가 — 장기간은 일별 나열 대신 합계·최고일만 계산해서 준다.
                long total = limited.stream().mapToLong(row -> parseLongOrZero(row.get("tovolume"))).sum();
                Map<String, Object> peak = limited.stream()
                        .max(Comparator.comparingLong(row -> parseLongOrZero(row.get("tovolume"))))
                        .orElse(null);
                summary = peak == null ? "" : "최근 %d개월(실제 조회된 %d거래일 기준) 누적 대차거래량 %,d주, 가장 많았던 날은 %s(%s주)"
                        .formatted(periodMonths, limited.size(), total, stringOf(peak.get("date")), stringOf(peak.get("tovolume")));
            }
            if (summary.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(LsStockCreditInfoDto.builder().stockCode(stockCode).detail(summary).build());
        } catch (CustomException e) {
            log.warn("LS 대차거래추이 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    private long parseLongOrZero(Object value) {
        Long parsed = parseLong(value);
        return parsed != null ? parsed : 0L;
    }

    /** 신규상장종목조회(t1403) — 최근 6개월 신규상장 종목 목록. */
    public List<LsNewListingDto> getNewListings() {
        return getNewListings(null);
    }

    /**
     * periodMonths가 없으면 기존과 동일하게 최근 6개월치만 조회한다. periodMonths가
     * 있으면(2026-08-13 추가) styymm을 최대 {@value #MAX_PERIOD_MONTHS}개월(2년) 전까지
     * 넓힌다. 목록 자체를 요약하지 않고 늘어난 개수만큼(최대 안전 상한까지) 그대로 반환한다 —
     * 신규상장 종목은 회사명·날짜가 핵심이라 다른 4개(시세·수급 등)처럼 숫자 요약이 필요 없다.
     */
    public List<LsNewListingDto> getNewListings(Integer periodMonths) {
        boolean longPeriod = periodMonths != null && periodMonths > 0;
        int cap = longPeriod ? MAX_LONG_PERIOD_LISTING_ITEMS : MAX_LISTING_ITEMS;
        try {
            String token = accessTokenProvider.issueAccessToken();
            java.time.LocalDate today = java.time.LocalDate.now();
            java.time.format.DateTimeFormatter yyyyMM = java.time.format.DateTimeFormatter.ofPattern("yyyyMM");
            java.time.LocalDate fromDate = longPeriod
                    ? today.minusMonths(Math.min(periodMonths, MAX_PERIOD_MONTHS))
                    : today.minusMonths(6);
            Map<String, Object> inBlock = Map.of(
                    "gubun", "1", "styymm", fromDate.format(yyyyMM),
                    "enyymm", today.format(yyyyMM), "idx", 0);
            Map<String, Object> requestBody = Map.of("t1403InBlock", inBlock);

            Map<String, Object> response = call("t1403", requestBody, token);
            if (response == null || !(response.get("t1403OutBlock1") instanceof List)) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t1403OutBlock1");
            return outBlock.stream()
                    .limit(cap)
                    .map(row -> LsNewListingDto.builder()
                            .stockCode(stringOf(row.get("shcode")))
                            .stockName(stringOf(row.get("hname")))
                            .listedDate(stringOf(row.get("date")))
                            .price(parseLong(row.get("price")))
                            .build())
                    .toList();
        } catch (CustomException e) {
            log.warn("LS 신규상장종목 조회 중 오류 - 사유: {}", e.getMessage());
            return List.of();
        }
    }

    /** 공매도일별추이(t1927) — 최근 며칠간 공매도 거래량/비중. */
    public List<LsShortSellingTrendDto> getRecentShortSellingTrend(String stockCode) {
        return getShortSellingTrend(stockCode, null);
    }

    /**
     * periodMonths가 없으면 기존과 동일하게 최근 7일치(최대 {@value #MAX_TREND_ITEMS}건)만
     * 반환한다. periodMonths가 있으면(2026-08-13 추가) 조회 시작일을 최대
     * {@value #MAX_PERIOD_MONTHS}개월(2년) 전까지 넓히고, 요약 계산은 호출부가 하도록 5건으로
     * 자르지 않는다.
     */
    public List<LsShortSellingTrendDto> getShortSellingTrend(String stockCode, Integer periodMonths) {
        boolean longPeriod = periodMonths != null && periodMonths > 0;
        try {
            String token = accessTokenProvider.issueAccessToken();
            java.time.LocalDate today = java.time.LocalDate.now();
            java.time.LocalDate fromDate = longPeriod
                    ? today.minusMonths(Math.min(periodMonths, MAX_PERIOD_MONTHS))
                    : today.minusDays(7);
            Map<String, Object> inBlock = Map.of(
                    "shcode", stockCode, "date", "",
                    "sdate", fromDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                    "edate", today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
            Map<String, Object> requestBody = Map.of("t1927InBlock", inBlock);

            Map<String, Object> response = call("t1927", requestBody, token);
            if (response == null || !(response.get("t1927OutBlock1") instanceof List)) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t1927OutBlock1");
            int cap = longPeriod ? MAX_LONG_PERIOD_ITEMS : MAX_TREND_ITEMS;
            return outBlock.stream()
                    .limit(cap)
                    .map(row -> LsShortSellingTrendDto.builder()
                            .date(stringOf(row.get("date")))
                            .shortSellingVolume(parseLong(row.get("gm_vo")))
                            .shortSellingValue(parseLong(row.get("gm_va")))
                            .shortSellingRatio(parseDoubleOrZero(row.get("gm_per")))
                            .build())
                    .toList();
        } catch (CustomException e) {
            log.warn("LS 공매도일별추이 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return List.of();
        }
    }

    /** 주식종목조회API용(t8436) — 종목 기본정보(상하한가, 스팩여부 등). */
    public Optional<LsStockMasterInfoDto> getStockMasterInfo(String stockCode) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of("t8436InBlock", Map.of("gubun", "1"));

            Map<String, Object> response = call("t8436", requestBody, token);
            if (response == null || !(response.get("t8436OutBlock") instanceof List)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("t8436OutBlock");
            return rows.stream()
                    .filter(row -> stockCode.equals(stringOf(row.get("shcode"))))
                    .findFirst()
                    .map(row -> LsStockMasterInfoDto.builder()
                            .stockCode(stockCode)
                            .stockName(stringOf(row.get("hname")))
                            .upperLimitPrice(parseLong(row.get("uplmtprice")))
                            .lowerLimitPrice(parseLong(row.get("dnlmtprice")))
                            .isSpac("Y".equals(stringOf(row.get("spac_gubun"))))
                            .build());
        } catch (CustomException e) {
            log.warn("LS 종목마스터 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> call(String trCd, Map<String, Object> requestBody, String token) {
        return call(etcUrl, trCd, requestBody, token, "LS 기타(" + trCd + ") 조회 실패", Map.of("tr_cont_key", ""));
    }
}
