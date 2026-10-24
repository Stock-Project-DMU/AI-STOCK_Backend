package com.teamfp.aistock.infra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.infra.marketdata.dto.ForeignInstitutionalTrendDto;
import com.teamfp.aistock.infra.marketdata.dto.MarketCapPeersDto;
import com.teamfp.aistock.infra.marketdata.dto.RankingItemDto;
import com.teamfp.aistock.infra.marketdata.dto.ThemeConstituentDto;

/**
 * 시세 부가 데이터 클라이언트들이 local-market-data-generator의 market_data.json(종목 부가 데이터 +
 * "_market")만으로 응답하는지 확인한다(fix/local-market-data-stable). 생성기 출력과 같은 구조의 파일을 임시 디렉토리에 두고
 * 실제 LocalMarketDataReader를 거친다.
 */
class LocalMarketDataClientsTest {

    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    private static final String TEN_DAYS_AGO = LocalDate.now().minusDays(10).format(DateTimeFormatter.BASIC_ISO_DATE);
    private static final String HALF_YEAR_AGO = LocalDate.now().minusMonths(6).format(DateTimeFormatter.BASIC_ISO_DATE);
    private static final String THREE_YEARS_AGO = LocalDate.now().minusYears(3).format(DateTimeFormatter.BASIC_ISO_DATE);

    @TempDir
    private Path tempDir;

    private LocalMarketDataReader reader;

    @BeforeEach
    void setUp() throws IOException {
        String trendRow = "{\"date\":\"%s\",\"closePrice\":70000,\"individualNetBuyKrx\":-30,\"institutionNetBuyKrx\":10,"
                + "\"foreignNetBuyKrx\":20,\"programTradingVolume\":5,\"foreignHoldingShares\":100,\"foreignExhaustionRate\":50.5,"
                + "\"shortSellingVolume\":3,\"shortSellingValue\":210000}";
        StringBuilder trend = new StringBuilder("[");
        for (int i = 0; i < 8; i++) {
            trend.append(trendRow.formatted(LocalDate.now().minusDays(i).format(DateTimeFormatter.BASIC_ISO_DATE))).append(i < 7 ? "," : "]");
        }
        Files.writeString(tempDir.resolve("market_data.json"), """
                {"005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":70000,"changeAmount":700,"changeRate":1.0,
                    "volume":1000,"listingShares":5000,"market":"KOSPI","etf":false,
                    "investorTrend": %s,
                    "shortSelling": [{"date":"%s","shortSellingVolume":3,"shortSellingValue":210000,"shortSellingRatio":0.3}],
                    "credit": {"collateralLoanEligible":"가능","marginRate":40,
                       "marginTrend":[{"date":"%s","balanceRatio":1.25}],
                       "lendingTrend":[{"date":"%s","volume":500},{"date":"%s","volume":900},{"date":"%s","volume":7777}]},
                    "themes": [{"themeCode":"0101","themeName":"반도체","stats":"상승 1/1종목"}],
                    "opinions": [{"date":"%s","securitiesFirm":"미래에셋증권","opinionBefore":"매수","opinionAfter":"매수",
                                  "targetPriceBefore":80000,"targetPriceAfter":90000,"closePriceOnDate":70000}],
                    "shareholderMeetings": [{"date":"20270320","eventName":"정기주주총회"}],
                    "master": {"upperLimitPrice":90000,"lowerLimitPrice":48700,"isSpac":false}},
                 "000660": {"stockCode":"000660","stockName":"SK하이닉스","currentPrice":200000,"changeRate":-2.0,"volume":3000,
                    "listingShares":700,"market":"KOSPI","etf":false,"per":12.5,"pbr":1.8,"opinionsCollectedAt":"2026-10-11"},
                 "069500": {"stockCode":"069500","stockName":"KODEX 200","currentPrice":30000,"changeRate":0.5,"volume":10,
                    "market":"KOSPI","etf":true,
                    "etfConstituents":[{"stockCode":"005930","stockName":"삼성전자","price":70000,"changeRate":1.0,"weight":60.0}]},
                 "_market": {
                    "indexes": {"코스닥": {"industryCode":"301","industryName":"코스닥","indexValue":892.27,"changeRate":-0.69}},
                    "investorSummary": {"individualNetBuy":-100,"foreignNetBuy":70,"institutionNetBuy":30},
                    "marketComparison": [{"marketName":"코스피","individualNetBuy":-100,"foreignNetBuy":70,"institutionNetBuy":30}],
                    "programSnapshot": {"offerValue":10,"bidValue":30,"netValue":20},
                    "programTop": [{"rank":1,"stockCode":"005930","stockName":"삼성전자","price":70000,"changeRate":1.0,"netBuyValue":5}],
                    "hotThemes": [{"themeCode":"0101","themeName":"반도체","stats":"상승 1/1종목"}],
                    "themeConstituents": {"반도체":[{"stockCode":"005930","stockName":"삼성전자","price":70000,"changeAmount":700,
                       "changeRate":1.0,"volume":1000}], "2차전지":[]},
                    "surgingVolume": [{"rank":1,"stockCode":"000660","stockName":"SK하이닉스","price":200000,"changeRate":-2.0,
                       "volume":3000,"extraInfo":"전일 동시각 대비 거래량 120%% 증가"}],
                    "afterHoursChange": [{"rank":1,"stockCode":"005930","stockName":"삼성전자","price":70500,"changeRate":0.7}],
                    "afterHoursVolume": [{"rank":1,"stockCode":"005930","stockName":"삼성전자","price":70500,"volume":15}],
                    "industryTrend": {"코스피": {"day":[{"date":"%s","indexValue":3200.5,"changeRate":0.3,"foreignNetBuy":10}],
                                                "month":[{"date":"%s","indexValue":3200.5,"changeRate":2.0},
                                                         {"date":"%s","indexValue":3100.0,"changeRate":1.0}]}},
                    "expectedIndex": {"코스피": {"장전":{"expectedIndexValue":3201.0,"changeRate":0.1,"upperLimitStockCount":0,
                       "lowerLimitStockCount":0}, "장후":{"expectedIndexValue":3199.0,"changeRate":-0.1,"upperLimitStockCount":1,
                       "lowerLimitStockCount":0}}},
                    "newListings": [{"stockCode":"M00001","stockName":"(모의) 신규상장 A","listedDate":"%s","price":12000},
                                    {"stockCode":"M00002","stockName":"(모의) 신규상장 B","listedDate":"%s","price":15000}],
                    "overseasIndexes": {"DJI@DJI": {"symbol":"DJI@DJI","name":"다우존스 산업평균","price":42000.5,
                       "changeAmount":120.5,"changeRate":0.29,"date":"%s"}},
                    "marketLiquidity": [{"date":"%s","customerDepositAmount":58000000,"marginLoanAmount":19500000},
                                        {"date":"%s","customerDepositAmount":57000000,"marginLoanAmount":19400000}],
                    "financialRanking": {"8": [{"rank":1,"stockCode":"000660","stockName":"SK하이닉스","roe":35.9,"per":30.4,
                       "pbr":10.9,"salesGrowthRate":31.2}]}}}"""
                .formatted(trend, TODAY, TODAY, TODAY, TEN_DAYS_AGO, THREE_YEARS_AGO, TEN_DAYS_AGO,
                        TODAY, TODAY, HALF_YEAR_AGO, TEN_DAYS_AGO, THREE_YEARS_AGO, TODAY, TODAY, HALF_YEAR_AGO));
        reader = new LocalMarketDataReader();
        ReflectionTestUtils.setField(reader, "localDataPath", tempDir.toString());
    }

    @Test
    @DisplayName("투자자 매매 — 시장 전체 스냅샷과 시장별 비교")
    void investor() {
        InvestorApiClient client = new InvestorApiClient(reader);
        assertThat(client.getInvestorTypeSummary()).get().satisfies(s -> assertThat(s.getForeignNetBuy()).isEqualTo(70L));
        assertThat(client.getMarketComparison()).singleElement().satisfies(c -> assertThat(c.getMarketName()).isEqualTo("코스피"));
    }

    @Test
    @DisplayName("종목별 외국인·기관 동향 — 기간이 없으면 최근 5거래일, 기간이 있으면 그 안의 거래일 전부")
    void investorTrend() {
        InvestorTrendApiClient client = new InvestorTrendApiClient(reader);
        assertThat(client.getRecentTrend("005930")).hasSize(5)
                .first().extracting(ForeignInstitutionalTrendDto::getForeignNetBuyKrx).isEqualTo(20L);
        assertThat(client.getTrend("005930", 6)).hasSize(8);
        assertThat(client.getRecentTrend("999999")).isEmpty();
    }

    @Test
    @DisplayName("프로그램 매매 — 순매수 상위와 시장 스냅샷")
    void program() {
        ProgramApiClient client = new ProgramApiClient(reader);
        assertThat(client.getTopProgramTradingStocks()).singleElement().satisfies(r -> assertThat(r.getNetBuyValue()).isEqualTo(5L));
        assertThat(client.getMarketSnapshot()).get().satisfies(s -> assertThat(s.getNetValue()).isEqualTo(20L));
    }

    @Test
    @DisplayName("테마 — 이름 부분일치(공백·가운뎃점 무시)로 구성 종목, 종목별 테마, 핫테마")
    void themes() {
        SectorApiClient client = new SectorApiClient(reader);
        assertThat(client.getThemeConstituentsByName("반도체 관련주")).extracting(ThemeConstituentDto::getStockCode)
                .containsExactly("005930");
        assertThat(client.getThemeConstituentsByName("반 도 체")).hasSize(1);
        assertThat(client.getThemeConstituentsByName("우주항공")).isEmpty();
        assertThat(client.getThemesForStock("005930")).singleElement().satisfies(t -> assertThat(t.getThemeName()).isEqualTo("반도체"));
        assertThat(client.getHotThemes()).hasSize(1);
    }

    @Test
    @DisplayName("신용·대주·담보·공매도·신규상장·종목 기본 정보 — 이전 응답과 같은 요약 문장")
    void etc() {
        EtcApiClient client = new EtcApiClient(reader);
        assertThat(client.getCollateralLoanEligibility("005930")).get()
                .satisfies(c -> assertThat(c.getDetail()).isEqualTo("담보융자 가능 여부: 가능"));
        assertThat(client.getMarginRequirement("005930")).get().satisfies(c -> assertThat(c.getDetail()).isEqualTo("증거금률 40%"));
        assertThat(client.getMarginTradingTrend("005930")).get()
                .satisfies(c -> assertThat(c.getDetail()).isEqualTo(TODAY + ": 신용융자잔고비중 1.25%"));
        assertThat(client.getSecuritiesLendingTrend("005930")).get()
                .satisfies(c -> assertThat(c.getDetail()).startsWith(TODAY + ": 대차거래량 500주"));
        // 6개월 기간이면 3년 전 행은 빠지고 합계·최고일로 요약한다.
        assertThat(client.getSecuritiesLendingTrend("005930", 6)).get()
                .satisfies(c -> assertThat(c.getDetail()).contains("누적 대차거래량 1,400주").contains("(900주)"));
        assertThat(client.getRecentShortSellingTrend("005930")).hasSize(1);
        assertThat(client.getNewListings()).hasSize(2);
        assertThat(client.getNewListings(1)).singleElement().satisfies(n -> assertThat(n.getStockCode()).isEqualTo("M00001"));
        assertThat(client.getStockMasterInfo("005930")).get().satisfies(m -> {
            assertThat(m.getStockName()).isEqualTo("삼성전자");
            assertThat(m.getUpperLimitPrice()).isEqualTo(90000L);
            assertThat(m.isSpac()).isFalse();
        });
        assertThat(client.getCollateralLoanEligibility("999999")).isEmpty();
    }

    @Test
    @DisplayName("ETF — 현재가는 ETF 종목만, 구성 종목은 비중과 함께")
    void etf() {
        EtfApiClient client = new EtfApiClient(reader);
        assertThat(client.getCurrentPrice("069500")).isPresent();
        assertThat(client.getCurrentPrice("005930")).isEmpty();
        assertThat(client.getConstituents("069500")).singleElement().satisfies(c -> assertThat(c.getWeight()).isEqualTo(60.0));
        // DART에 없는 ETF 이름·코드로도 코드를 찾는다(공백 무시, 부분일치). 일반 종목은 ETF가 아니라 빈 값.
        assertThat(client.findEtfCode("KODEX 200")).contains("069500");
        assertThat(client.findEtfCode("kodex200 ETF")).contains("069500");
        assertThat(client.findEtfCode("069500")).contains("069500");
        assertThat(client.findEtfCode("삼성전자")).isEmpty();
    }

    @Test
    @DisplayName("업종 지수 — 현재가(종목 평균 등락률 근사), 일/월 추이, 장전·장후 예상지수")
    void industry() {
        IndustryApiClient client = new IndustryApiClient(reader);
        assertThat(client.getCurrentPrice("코스피")).get().satisfies(i -> assertThat(i.getIndustryCode()).isEqualTo("001"));
        assertThat(client.getCurrentPrice("나스닥")).isEmpty();
        // 생성기가 LS 실제 지수를 _market.indexes에 넣었으면 근사치 대신 그 값을 쓴다(실시세 전환 3단계).
        assertThat(client.getCurrentPrice("코스닥")).get().satisfies(i -> {
            assertThat(i.getIndexValue()).isEqualTo(892.27);
            assertThat(i.getChangeRate()).isEqualTo(-0.69);
        });
        assertThat(client.getRecentTrend("코스피")).singleElement().satisfies(t -> assertThat(t.getIndexValue()).isEqualTo(3200.5));
        assertThat(client.getTrend("코스피", 1)).hasSize(1);
        assertThat(client.getTrend("코스피", 12)).hasSize(2);
        assertThat(client.getExpectedIndex("코스피", "장후")).get()
                .satisfies(e -> assertThat(e.getUpperLimitStockCount()).isEqualTo(1L));
        assertThat(client.getExpectedIndex("코스닥", "장전")).isEmpty();
    }

    @Test
    @DisplayName("투자의견 — 최근 1년 안의 의견만, 목표주가가 기준 주가(발표일 종가, 없으면 현재가)의 0.2~5배를 벗어나는 줄은 뺀다")
    void investmentOpinions_recentAndPlausibleOnly(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("market_data.json"), """
                {"035420": {"stockCode":"035420","currentPrice":180000,"opinionsCollectedAt":"2026-10-12","opinions": [
                   {"date":"20261008","securitiesFirm":"메리츠","targetPriceAfter":330000,"closePriceOnDate":null},
                   {"date":"20261002","securitiesFirm":"한화투자","targetPriceAfter":30000,"closePriceOnDate":192200},
                   {"date":"20261001","securitiesFirm":"LS증권","targetPriceAfter":293000,"closePriceOnDate":191900},
                   {"date":"20251013","securitiesFirm":"유안타","targetPriceAfter":250000,"closePriceOnDate":200000},
                   {"date":"20251011","securitiesFirm":"대신","targetPriceAfter":240000,"closePriceOnDate":200000},
                   {"date":"20110714","securitiesFirm":"대  신","targetPriceAfter":27500,"closePriceOnDate":22100}]}}""");
        LocalMarketDataReader fileReader = new LocalMarketDataReader();
        ReflectionTestUtils.setField(fileReader, "localDataPath", dir.toString());
        InvestInfoApiClient client = new InvestInfoApiClient(fileReader);
        ReflectionTestUtils.setField(client, "clock",
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-12T06:00:00Z"), java.time.ZoneId.of("Asia/Seoul")));

        assertThat(client.getInvestmentOpinions("035420"))
                .extracting(o -> o.getSecuritiesFirm())
                .containsExactly("메리츠", "LS증권", "유안타"); // 한화투자(0.16배)·1년 넘은 대신(2025-10-11)·2011년 의견 제외
    }

    @Test
    @DisplayName("투자정보 — 애널리스트 의견, 주총 일정, 재무순위, 해외지수, 증시 주변 자금")
    void investInfo() {
        InvestInfoApiClient client = new InvestInfoApiClient(reader);
        assertThat(client.getInvestmentOpinions("005930")).singleElement()
                .satisfies(o -> assertThat(o.getTargetPriceAfter()).isEqualTo(90000L));
        assertThat(client.getInvestmentOpinions("069500")).isEmpty();
        assertThat(client.getShareholderMeetingSchedule("005930")).hasSize(1);
        assertThat(client.getFinancialRanking("8")).singleElement().satisfies(r -> assertThat(r.getRoe()).isEqualTo(35.9));
        assertThat(client.getFinancialRanking("z")).isEmpty();
        assertThat(client.getOverseasIndex("S", "DJI@DJI")).get().satisfies(i -> assertThat(i.getPrice()).isEqualTo(42000.5));
        assertThat(client.getOverseasIndex("S", "UNKNOWN")).isEmpty();
        assertThat(client.getRecentMarketLiquidityTrend()).hasSize(2);
        assertThat(client.getMarketLiquidityTrend(3)).hasSize(1);
        // opinionsCollectedAt이 있으면 LS증권 실제 투자의견 스냅샷, 없으면 모의 의견(fix/stock-detail-review).
        assertThat(client.getInvestmentOpinionCollectedAt("000660")).contains("2026-10-11");
        assertThat(client.getInvestmentOpinionCollectedAt("005930")).isEmpty();
    }

    @Nested
    @DisplayName("순위 (HighItemApiClient)")
    class Rankings {

        @Test
        @DisplayName("상승률·하락률·시가총액은 전체 종목을 직접 정렬하고 _market 키는 종목으로 세지 않는다")
        void computedRankings() {
            HighItemApiClient client = new HighItemApiClient(reader);
            assertThat(client.getTopPriceChangeRate()).extracting(RankingItemDto::getStockCode).containsExactly("005930", "069500");
            assertThat(client.getTopPriceDeclineRate()).extracting(RankingItemDto::getStockCode).containsExactly("000660");
            assertThat(client.getTopMarketCap(HighItemApiClient.ALL_REGISTERED_STOCKS)).hasSize(3)
                    .first().extracting(RankingItemDto::getStockCode).isEqualTo("005930");
        }

        @Test
        @DisplayName("거래량 급증·시간외 순위는 생성기가 만든 순위를 그대로 쓴다")
        void generatedRankings() {
            HighItemApiClient client = new HighItemApiClient(reader);
            assertThat(client.getSurgingVolumeVsYesterday()).singleElement()
                    .satisfies(r -> assertThat(r.getExtraInfo()).contains("120%"));
            assertThat(client.getTopAfterHoursPriceChangeRate()).hasSize(1);
            assertThat(client.getTopAfterHoursVolume()).hasSize(1);
        }

        @Test
        @DisplayName("시가총액 비교는 보고 있는 종목의 같은 시장 순위와 시장 내 상위 목록을 돌려준다(fix/stock-detail-review)")
        void marketCapPeers() {
            HighItemApiClient client = new HighItemApiClient(reader);

            MarketCapPeersDto peers = client.getMarketCapPeers("000660");

            assertThat(peers.market()).isEqualTo("KOSPI");
            assertThat(peers.stockName()).isEqualTo("SK하이닉스");
            assertThat(peers.rank()).isEqualTo(2);
            // ETF(069500)는 상장주식수가 없어 비교 대상에서 빠진다.
            assertThat(peers.totalCount()).isEqualTo(2);
            assertThat(peers.items()).extracting(RankingItemDto::getStockCode).containsExactly("005930", "000660");
            assertThat(peers.items()).extracting(RankingItemDto::getRank).containsExactly(1, 2);
        }

        @Test
        @DisplayName("ETF를 보면 시가총액 비교 순위 없이 시장만 돌려준다")
        void marketCapPeers_etf() {
            MarketCapPeersDto peers = new HighItemApiClient(reader).getMarketCapPeers("069500");

            assertThat(peers.market()).isEqualTo("KOSPI");
            assertThat(peers.rank()).isNull();
            assertThat(peers.items()).isEmpty();
        }

        @Test
        @DisplayName("시가총액 비교 행에는 시가총액(원)·PER·PBR을 채운다")
        void marketCapPeers_fillsMarketCapAndValuation() {
            RankingItemDto hynix = new HighItemApiClient(reader).getMarketCapPeers("000660").items().get(1);

            assertThat(hynix.getMarketCap()).isEqualTo(200000L * 700 * 1000);
            assertThat(hynix.getPer()).isEqualTo(12.5);
            assertThat(hynix.getPbr()).isEqualTo(1.8);
            // PER·PBR 0(우선주 등 미계산)은 null, 적자 기업의 음수 PER은 그대로.
            assertThat(HighItemApiClient.valuation(0.0)).isNull();
            assertThat(HighItemApiClient.valuation(-159.0)).isEqualTo(-159.0);
        }

        @Test
        @DisplayName("시세 데이터에 없는 종목이면 순위 없이 빈 목록을 돌려준다")
        void marketCapPeers_unknownStock() {
            MarketCapPeersDto peers = new HighItemApiClient(reader).getMarketCapPeers("999999");

            assertThat(peers.rank()).isNull();
            assertThat(peers.items()).isEmpty();
        }
    }
}
