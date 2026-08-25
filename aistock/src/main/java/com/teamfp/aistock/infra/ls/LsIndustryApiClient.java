package com.teamfp.aistock.infra.ls;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.infra.ls.dto.LsExpectedIndexDto;
import com.teamfp.aistock.infra.ls.dto.LsIndustryPriceDto;
import com.teamfp.aistock.infra.ls.dto.LsIndustryTrendDto;

import lombok.extern.slf4j.Slf4j;

/**
 * LS증권 Open API [업종] 시세 카테고리({@code /indtp/market-data})를 조회하는 클라이언트.
 * 업종기간별추이(t1514)/전체업종(t8424)/업종현재가(t1511)/예상지수(t1485) 4개 TR을 다룬다
 * (2026-08-11 추가 — 기존에 전혀 구현돼 있지 않던 카테고리).
 *
 * 예상지수(t1485)는 동시호가 시간대(장전 08:30~09:00 또는 장마감전 15:20~15:30)에만 실제로
 * 의미 있는 데이터라, 호출 전 {@link com.teamfp.aistock.global.util.DateUtil#isCallAuctionTime()}로
 * 게이트를 걸어야 한다(호출부인 AiPlanningService의 책임).
 */
@Slf4j
@Component
public class LsIndustryApiClient extends LsApiClientSupport {

    // 가장 일반적으로 쓰이는 업종코드 — 코스피(종합)를 기본값으로 삼는다. 개별 업종코드가
    // 필요한 세부 조회(예: "코스닥 지수 어때요?")는 향후 전체업종(t8424) 캐시를 활용해
    // 이름→코드 매핑을 붙이면 확장 가능하다(2026-08-11 기준 코스피/코스닥 2개만 지원).
    private static final Map<String, String> INDUSTRY_CODE_BY_NAME = Map.of("코스피", "001", "코스닥", "301");
    private static final int MAX_TREND_ITEMS = 5;
    // 2026-08-13 추가 — 장기간(6개월/1년 등) 업종 추이를 물으면 5거래일로는 답이 안 되던 문제
    // 대응, 뉴스 검색과 동일한 2년 상한. t1514는 gubun2로 일(1)/주(2)/월(3)봉을 고를 수 있어,
    // t1305(기간별주가)와 동일하게 장기간은 월봉으로 전환해 24개(2년)까지 조회한다.
    private static final int MAX_PERIOD_MONTHS = 24;
    private static final String GUBUN2_DAY = "1";
    private static final String GUBUN2_MONTH = "3";

    private final LsAccessTokenProvider accessTokenProvider;

    @Value("${ls.industry-url}")
    private String industryUrl;

    public LsIndustryApiClient(LsAccessTokenProvider accessTokenProvider, RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
    }

    /** 업종현재가(t1511) — 업종지수 현재가 스냅샷. marketName은 "코스피" 또는 "코스닥". */
    public Optional<LsIndustryPriceDto> getCurrentPrice(String marketName) {
        String upcode = INDUSTRY_CODE_BY_NAME.getOrDefault(marketName, "001");
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of("t1511InBlock", Map.of("upcode", upcode));

            Map<String, Object> response = call("t1511", requestBody, token);
            if (response == null || !(response.get("t1511OutBlock") instanceof Map)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> outBlock = (Map<String, Object>) response.get("t1511OutBlock");
            return Optional.of(LsIndustryPriceDto.builder()
                    .industryCode(upcode)
                    .industryName(stringOf(outBlock.get("hname")))
                    .indexValue(parseDoubleOrZero(outBlock.get("pricejisu")))
                    .changeRate(parseDoubleOrZero(outBlock.get("diffjisu")))
                    .build());
        } catch (CustomException e) {
            log.warn("LS 업종현재가 조회 중 오류 - marketName: {}, 사유: {}", marketName, e.getMessage());
            return Optional.empty();
        }
    }

    /** 업종기간별추이(t1514) — 최근 5거래일 지수 추이. */
    public List<LsIndustryTrendDto> getRecentTrend(String marketName) {
        return getTrend(marketName, null);
    }

    /**
     * periodMonths가 없으면 기존과 동일하게 일봉(gubun2=1) 최근 5개만 반환한다. periodMonths가
     * 있으면(2026-08-13 추가) 월봉(gubun2=3)으로 전환해 최대 {@value #MAX_PERIOD_MONTHS}개월
     * (2년)까지 조회한다.
     */
    public List<LsIndustryTrendDto> getTrend(String marketName, Integer periodMonths) {
        String upcode = INDUSTRY_CODE_BY_NAME.getOrDefault(marketName, "001");
        boolean longPeriod = periodMonths != null && periodMonths > 0;
        String gubun2 = longPeriod ? GUBUN2_MONTH : GUBUN2_DAY;
        int cnt = longPeriod ? Math.min(periodMonths, MAX_PERIOD_MONTHS) : MAX_TREND_ITEMS;
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
            inBlock.put("upcode", upcode);
            inBlock.put("gubun1", " ");
            inBlock.put("gubun2", gubun2);
            inBlock.put("cts_date", " ");
            inBlock.put("cnt", cnt);
            inBlock.put("rate_gbn", "1");
            Map<String, Object> requestBody = Map.of("t1514InBlock", inBlock);

            Map<String, Object> response = call("t1514", requestBody, token);
            if (response == null || !(response.get("t1514OutBlock1") instanceof List)) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t1514OutBlock1");
            return outBlock.stream()
                    .limit(cnt)
                    .map(row -> LsIndustryTrendDto.builder()
                            .date(stringOf(row.get("date")))
                            .indexValue(parseDoubleOrZero(row.get("jisu")))
                            .changeRate(parseDoubleOrZero(row.get("diff")))
                            .foreignNetBuy(parseLong(row.get("frgsvolume")))
                            .build())
                    .toList();
        } catch (CustomException e) {
            log.warn("LS 업종기간별추이 조회 중 오류 - marketName: {}, 사유: {}", marketName, e.getMessage());
            return List.of();
        }
    }

    /**
     * 예상지수(t1485) — 동시호가 시간대 업종지수 예상치. callAuctionSession은 "장전" 또는 "장후".
     */
    public Optional<LsExpectedIndexDto> getExpectedIndex(String marketName, String callAuctionSession) {
        String upcode = INDUSTRY_CODE_BY_NAME.getOrDefault(marketName, "001");
        String gubun = "장후".equals(callAuctionSession) ? "2" : "1";
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of("t1485InBlock", Map.of("upcode", upcode, "gubun", gubun));

            Map<String, Object> response = call("t1485", requestBody, token);
            if (response == null || !(response.get("t1485OutBlock") instanceof Map)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> outBlock = (Map<String, Object>) response.get("t1485OutBlock");
            return Optional.of(LsExpectedIndexDto.builder()
                    .expectedIndexValue(parseDoubleOrZero(outBlock.get("pricejisu")))
                    .changeRate(parseDoubleOrZero(outBlock.get("change")))
                    .upperLimitStockCount(parseLongPrimitive(outBlock.get("yupjo")))
                    .lowerLimitStockCount(parseLongPrimitive(outBlock.get("ydownjo")))
                    .build());
        } catch (CustomException e) {
            log.warn("LS 예상지수 조회 중 오류 - marketName: {}, 사유: {}", marketName, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> call(String trCd, Map<String, Object> requestBody, String token) {
        return call(industryUrl, trCd, requestBody, token, "LS 업종(" + trCd + ") 조회 실패");
    }

    private long parseLongPrimitive(Object value) {
        Long parsed = parseLong(value);
        return parsed != null ? parsed : 0L;
    }
}
