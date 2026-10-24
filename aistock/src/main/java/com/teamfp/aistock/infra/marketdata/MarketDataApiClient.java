package com.teamfp.aistock.infra.marketdata;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

import org.springframework.stereotype.Component;

import com.teamfp.aistock.infra.marketdata.dto.CallAuctionPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.HistoricalPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.MultiStockPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.PivotLevelDto;
import com.teamfp.aistock.infra.marketdata.dto.StockRiskFlagDto;

import lombok.RequiredArgsConstructor;

/**
 * 종목 시세 — 현재가(+PER/PBR/52주 최고·최저 등), 차트·기간별 시세, 여러 종목 현재가, 관리·투자경고 여부, 피봇 지지·저항선,
 * 동시호가 예상체결가. 모두 {@link LocalMarketDataReader}(local-market-data-generator의 market_data.json·price_history.json)로
 * 응답한다. 데이터가 없으면 빈 값이다.
 */
@Component
@RequiredArgsConstructor
public class MarketDataApiClient {

    private static final int MAX_HISTORICAL_ITEMS = 5;
    private static final int MAX_CALL_AUCTION_ITEMS = 5;
    // 특정 기간(6개월/1년 등)을 묻는 AI 상담 질문은 월봉으로 바꿔 최대 24개(2년)까지 준다.
    private static final int MAX_PERIOD_MONTHS = 24;
    private static final int DWMCODE_DAY = 1;
    private static final int DWMCODE_WEEK = 2;
    private static final int DWMCODE_MONTH = 3;
    // 종목 상세 차트(getChartPrices) 한 번에 받는 최대 봉 수 — 일봉 60/주봉 52/월봉 60을 모두 담는 상한.
    private static final int MAX_CHART_ITEMS = 60;
    private static final DateTimeFormatter HISTORY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LocalMarketDataReader localMarketDataReader;

    /** 종목 현재가(+PER/PBR/52주 최고·최저·상장주식수·소진율). 없으면 빈 값. */
    public Optional<CurrentPriceDetailDto> getCurrentPrice(String stockCode) {
        return localMarketDataReader.getCurrentPrice(stockCode);
    }

    /** 관리종목·투자경고/매매정지 여부. 해당 없으면 빈 목록. */
    public List<StockRiskFlagDto> getRiskFlags(String stockCode) {
        return localMarketDataReader.getStockList(stockCode, "riskFlags", StockRiskFlagDto.class);
    }

    /** 피봇/디마크 — 전일 고가·저가·종가로 계산한 지지·저항선. */
    public Optional<PivotLevelDto> getPivotLevels(String stockCode) {
        return localMarketDataReader.getStockObject(stockCode, "pivot", PivotLevelDto.class);
    }

    /** 최근 {@value #MAX_HISTORICAL_ITEMS}거래일 일봉. */
    public List<HistoricalPriceDto> getRecentHistoricalPrices(String stockCode) {
        return getHistoricalPrices(stockCode, null);
    }

    /**
     * periodMonths가 없으면 최근 {@value #MAX_HISTORICAL_ITEMS}거래일 일봉, 있으면 월봉으로 바꿔 최대
     * {@value #MAX_PERIOD_MONTHS}개월(2년)을 돌려준다(AI 상담 기간별 시세).
     */
    public List<HistoricalPriceDto> getHistoricalPrices(String stockCode, Integer periodMonths) {
        boolean longPeriod = periodMonths != null && periodMonths > 0;
        int dwmcode = longPeriod ? DWMCODE_MONTH : DWMCODE_DAY;
        int cnt = longPeriod ? Math.min(periodMonths, MAX_PERIOD_MONTHS) : MAX_HISTORICAL_ITEMS;
        return bars(stockCode, dwmcode, cnt);
    }

    /**
     * 종목 상세 차트·목표 도달 시뮬레이션용 봉(최신순). dwmcode는 1=일봉/2=주봉/3=월봉, count는 최대 {@value #MAX_CHART_ITEMS}건.
     */
    public List<HistoricalPriceDto> getChartPrices(String stockCode, int dwmcode, int count) {
        if (dwmcode != DWMCODE_DAY && dwmcode != DWMCODE_WEEK && dwmcode != DWMCODE_MONTH) {
            throw new IllegalArgumentException("지원하지 않는 dwmcode: " + dwmcode);
        }
        return bars(stockCode, dwmcode, Math.max(1, Math.min(count, MAX_CHART_ITEMS)));
    }

    /** 여러 종목 현재가(최대 5종목, AI 상담 도구). 로컬 데이터에 없는 종목은 결과에서 빠진다. */
    public List<MultiStockPriceDto> getMultiStockPrices(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return List.of();
        }
        List<String> limited = stockCodes.size() > 5 ? stockCodes.subList(0, 5) : stockCodes;
        Map<String, CurrentPriceDetailDto> all = localMarketDataReader.getAllCurrentPrices();
        return limited.stream()
                .map(all::get)
                .filter(Objects::nonNull)
                .map(price -> MultiStockPriceDto.builder()
                        .stockCode(price.getStockCode())
                        .stockName(price.getStockName())
                        .price(price.getCurrentPrice())
                        .changeAmount(price.getChangeAmount())
                        .changeRate(price.getChangeRate())
                        .volume(price.getVolume())
                        // 로컬 데이터에 거래대금이 없어 현재가 × 거래량(백만원)으로 근사한다.
                        .tradingValue(price.getCurrentPrice() * price.getVolume() / 1_000_000)
                        .build())
                .toList();
    }

    /** 동시호가 예상체결가(최근 {@value #MAX_CALL_AUCTION_ITEMS}건). 시간대 게이트는 AiPlanningService가 건다. */
    public List<CallAuctionPriceDto> getRecentCallAuctionPrices(String stockCode) {
        List<CallAuctionPriceDto> items = localMarketDataReader.getStockList(stockCode, "callAuction", CallAuctionPriceDto.class);
        return items.size() > MAX_CALL_AUCTION_ITEMS ? items.subList(0, MAX_CALL_AUCTION_ITEMS) : items;
    }

    /**
     * 과거 봉(최신순). history_collector.py가 받아 둔 과거 시세 스냅샷(price_history.json)이 있으면 그 봉에 "현재가 ÷ 스냅샷
     * 최신 종가" 비율을 곱해 최신 봉 종가를 현재가에 맞추고(기간 수익률은 그대로), 없으면 종목코드 시드로 종목별 추세·변동성을
     * 가진 합성 봉을 만든다. 어느 쪽이든 최신 봉 종가 = 현재가라 종목 상세와 차트가 서로 모순되지 않는다.
     */
    private List<HistoricalPriceDto> bars(String stockCode, int dwmcode, int cnt) {
        Optional<CurrentPriceDetailDto> currentOpt = localMarketDataReader.getCurrentPrice(stockCode);
        if (currentOpt.isEmpty()) {
            return List.of();
        }
        CurrentPriceDetailDto current = currentOpt.get();
        List<HistoricalPriceDto> snapshot = localMarketDataReader.getPriceHistory(stockCode, dwmcode);
        if (!snapshot.isEmpty() && snapshot.get(0).getClose() != null && snapshot.get(0).getClose() > 0) {
            // 생성기가 실시세 거래일(tradingDate)을 주면 실제 과거 봉 그대로 + 오늘 봉(실시세 전환 5단계). 없으면(이전 시뮬레이션
            // 데이터) 예전처럼 현재가에 맞춰 비율 조정한다.
            return current.getTradingDate() != null
                    ? realBars(snapshot, current, dwmcode, cnt)
                    : rescaledBars(snapshot, current, cnt);
        }
        return syntheticBars(current, dwmcode, cnt);
    }

    /**
     * 실제 과거 봉(price_history.json, 장 마감 후 매일 수집) 위에 오늘 봉을 얹는다 — 과거 봉은 손대지 않는다.
     * 일봉: 오늘 날짜 봉이 이미 있으면(장 마감 후 수집) 실시세로 덮고, 없으면 맨 앞에 붙인다. 주봉·월봉: 최신 봉이 오늘과 같은
     * 주·월이면 종가·고가·저가만 오늘 값으로 갱신하고, 다른 주·월이면 오늘 봉을 새로 붙인다.
     */
    private List<HistoricalPriceDto> realBars(List<HistoricalPriceDto> snapshot, CurrentPriceDetailDto current, int dwmcode,
                                              int cnt) {
        List<HistoricalPriceDto> result = new ArrayList<>(snapshot);
        String tradingDate = current.getTradingDate();
        long price = current.getCurrentPrice();
        if (price > 0) {
            HistoricalPriceDto latest = result.get(0);
            boolean samePeriod = samePeriod(latest.getDate(), tradingDate, dwmcode);
            if (samePeriod && dwmcode == DWMCODE_DAY) {
                result.set(0, todayBar(current, tradingDate));
            } else if (samePeriod) {
                long high = Math.max(latest.getHigh() != null ? latest.getHigh() : price, todayHigh(current));
                long low = Math.min(latest.getLow() != null ? latest.getLow() : price, todayLow(current));
                // 주·월봉 날짜는 그 봉에 담긴 마지막 거래일이다 — 오늘보다 앞이면 오늘 거래량이 아직 빠져 있으니 더한다
                // (장 마감 후 수집으로 오늘까지 담긴 봉이면 그대로 둔다).
                Long volume = latest.getVolume();
                if (latest.getDate().compareTo(tradingDate) < 0) {
                    volume = (volume != null ? volume : 0L) + current.getVolume();
                }
                result.set(0, latest.toBuilder().close(price).high(high).low(low).volume(volume)
                        .marketCap(marketCapMillion(price, current)).build());
            } else if (latest.getDate() != null && tradingDate.compareTo(latest.getDate()) > 0) {
                // 날짜 없는 봉(불완전한 스냅샷)이면 오늘 봉을 붙이지 않고 그대로 둔다 — NullPointerException으로 차트가 500이 나지 않게.
                result.add(0, todayBar(current, tradingDate));
            }
        }
        return result.size() > cnt ? result.subList(0, cnt) : result;
    }

    private HistoricalPriceDto todayBar(CurrentPriceDetailDto current, String tradingDate) {
        long price = current.getCurrentPrice();
        return HistoricalPriceDto.builder()
                .date(tradingDate)
                .open(current.getOpenPrice() != null && current.getOpenPrice() > 0 ? current.getOpenPrice() : price)
                .high(todayHigh(current))
                .low(todayLow(current))
                .close(price)
                .changeRate(current.getChangeRate())
                .volume(current.getVolume())
                .marketCap(marketCapMillion(price, current))
                .build();
    }

    private static long todayHigh(CurrentPriceDetailDto current) {
        Long high = current.getHighPrice();
        return high != null && high > 0 ? Math.max(high, current.getCurrentPrice()) : current.getCurrentPrice();
    }

    private static long todayLow(CurrentPriceDetailDto current) {
        Long low = current.getLowPrice();
        return low != null && low > 0 ? Math.min(low, current.getCurrentPrice()) : current.getCurrentPrice();
    }

    // 같은 일·주(ISO 주)·월인지 — 날짜는 YYYYMMDD.
    private static boolean samePeriod(String barDate, String tradingDate, int dwmcode) {
        if (barDate == null || tradingDate == null) {
            return false;
        }
        LocalDate bar = LocalDate.parse(barDate, HISTORY_DATE_FORMAT);
        LocalDate today = LocalDate.parse(tradingDate, HISTORY_DATE_FORMAT);
        return switch (dwmcode) {
            case DWMCODE_DAY -> bar.equals(today);
            case DWMCODE_WEEK -> bar.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) == today.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR)
                    && bar.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) == today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            default -> bar.getYear() == today.getYear() && bar.getMonthValue() == today.getMonthValue();
        };
    }

    private List<HistoricalPriceDto> rescaledBars(List<HistoricalPriceDto> snapshot, CurrentPriceDetailDto current, int cnt) {
        long currentPrice = current.getCurrentPrice();
        double ratio = (double) currentPrice / snapshot.get(0).getClose();
        int size = Math.min(cnt, snapshot.size());
        List<HistoricalPriceDto> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            HistoricalPriceDto bar = snapshot.get(i);
            long close = i == 0 ? currentPrice : scale(bar.getClose(), ratio);
            long open = scale(bar.getOpen() != null ? bar.getOpen() : bar.getClose(), ratio);
            long high = Math.max(scale(bar.getHigh() != null ? bar.getHigh() : bar.getClose(), ratio), Math.max(open, close));
            long low = Math.min(scale(bar.getLow() != null ? bar.getLow() : bar.getClose(), ratio), Math.min(open, close));
            Long previousClose = i + 1 < snapshot.size() && snapshot.get(i + 1).getClose() != null
                    ? scale(snapshot.get(i + 1).getClose(), ratio) : null;
            double changeRate = previousClose == null || previousClose == 0 ? 0.0
                    : Math.round((close - previousClose) * 10000.0 / previousClose) / 100.0;
            result.add(HistoricalPriceDto.builder()
                    .date(bar.getDate())
                    .open(open)
                    .high(high)
                    .low(Math.max(1, low))
                    .close(close)
                    .changeRate(changeRate)
                    .volume(bar.getVolume())
                    .marketCap(marketCapMillion(close, current))
                    .foreignNetBuy(bar.getForeignNetBuy())
                    .individualNetBuy(bar.getIndividualNetBuy())
                    .build());
        }
        return result;
    }

    private static long scale(Long value, double ratio) {
        return value == null ? 0L : Math.max(1L, Math.round(value * ratio));
    }

    /** 시가총액(백만원, 이전 t1305 marketcap과 같은 단위) = 종가 × 상장주식수(천주) × 1000 ÷ 1,000,000. */
    private static Long marketCapMillion(long close, CurrentPriceDetailDto current) {
        return current.getListingShares() != null ? close * current.getListingShares() / 1_000 : null;
    }

    /**
     * 과거 시세 스냅샷이 없을 때의 합성 봉(최신순). 종목코드 시드로 연 기대수익률(-4%~+18%)과 연 변동성(18%~40%)을 정해
     * 기하 브라운 운동으로 과거 방향으로 거슬러 만든다 — 같은 종목은 항상 같은 그래프이고 종목마다 추세가 다르다.
     * 실제 과거 시세가 아니다. 일봉은 평일만(주말이면 직전 금요일부터), 주봉은 1주, 월봉은 1개월 간격이다.
     */
    private List<HistoricalPriceDto> syntheticBars(CurrentPriceDetailDto current, int dwmcode, int cnt) {
        String stockCode = current.getStockCode() != null ? current.getStockCode() : "";
        Random trendRandom = new Random(stockCode.hashCode());
        double annualDrift = -0.04 + trendRandom.nextDouble() * 0.22;
        double annualVolatility = 0.18 + trendRandom.nextDouble() * 0.22;
        int periodsPerYear = switch (dwmcode) {
            case DWMCODE_DAY -> 250;
            case DWMCODE_WEEK -> 52;
            default -> 12;
        };
        double drift = annualDrift / periodsPerYear;
        double volatility = annualVolatility / Math.sqrt(periodsPerYear);
        Random random = new Random(stockCode.hashCode() * 31L + dwmcode);

        LocalDate date = LocalDate.now();
        if (dwmcode == DWMCODE_DAY) {
            date = previousWeekdayOrSame(date);
        }
        List<HistoricalPriceDto> result = new ArrayList<>();
        long close = current.getCurrentPrice();
        for (int i = 0; i < cnt; i++) {
            // 이번 봉 수익률 r로 직전(과거) 봉 종가를 구한다: 직전 종가 = 이번 종가 / e^r
            double periodReturn = drift - volatility * volatility / 2 + volatility * random.nextGaussian();
            long previousClose = Math.max(1, Math.round(close / Math.exp(periodReturn)));
            long open = Math.max(1, Math.round(previousClose * (1 + (random.nextDouble() - 0.5) * volatility * 0.3)));
            long high = Math.max(open, close) + Math.round(Math.max(open, close) * random.nextDouble() * volatility * 0.5);
            long low = Math.max(1, Math.min(open, close) - Math.round(Math.min(open, close) * random.nextDouble() * volatility * 0.5));
            long volume = Math.max(1, Math.round(current.getVolume() * (0.5 + random.nextDouble())));
            double changeRate = Math.round((close - previousClose) * 10000.0 / previousClose) / 100.0;
            long foreignNetBuy = Math.round(volume * (random.nextDouble() - 0.5) * 0.4);
            long individualNetBuy = Math.round(volume * (random.nextDouble() - 0.5) * 0.4);

            result.add(HistoricalPriceDto.builder()
                    .date(date.format(HISTORY_DATE_FORMAT))
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .changeRate(changeRate)
                    .volume(volume)
                    .marketCap(marketCapMillion(close, current))
                    .foreignNetBuy(foreignNetBuy)
                    .individualNetBuy(individualNetBuy)
                    .build());

            close = previousClose;
            date = switch (dwmcode) {
                case DWMCODE_DAY -> previousWeekdayOrSame(date.minusDays(1));
                case DWMCODE_WEEK -> date.minusWeeks(1);
                default -> date.minusMonths(1);
            };
        }
        return result;
    }

    /** 주말이면 직전 금요일로, 평일이면 그대로 돌려준다(일봉이 장이 열리는 날만 갖도록). */
    private static LocalDate previousWeekdayOrSame(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return date.minusDays(1);
        }
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return date.minusDays(2);
        }
        return date;
    }
}
