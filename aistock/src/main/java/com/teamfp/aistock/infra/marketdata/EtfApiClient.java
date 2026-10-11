package com.teamfp.aistock.infra.marketdata;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.EtfConstituentDto;

import lombok.RequiredArgsConstructor;

/**
 * ETF 현재가와 구성 종목(AI 재무설계사 상담 도구). local-market-data-generator가 market_data.json에 담는 값을 읽는다
 * (fix/local-market-data-stable — 이전 real 모드에서는 외부 시세 데이터 t1901/t1904를 호출했다).
 */
@Component
@RequiredArgsConstructor
public class EtfApiClient {

    private static final int MAX_CONSTITUENT_ITEMS = 10;

    private final LocalMarketDataReader localMarketDataReader;

    /**
     * ETF 현재가. stocks.json에 {@code isEtf: true}로 등록된 종목만 있다 — 등록되지 않았거나 ETF가 아닌 코드는 빈 값.
     * 생성기가 ETF 종목에만 채우는 exchgubun("K"=KRX)도 CurrentPriceDetailDto 그대로 함께 돌려준다.
     */
    public Optional<CurrentPriceDetailDto> getCurrentPrice(String stockCode) {
        return localMarketDataReader.getCurrentPrice(stockCode).filter(CurrentPriceDetailDto::isEtf);
    }

    /**
     * 이름이나 6자리 코드로 등록 ETF의 종목코드를 찾는다(AI 상담 get_etf_info 도구용). DART 회사 목록에는 ETF가 없어
     * "KODEX 200" 같은 이름이 종목코드로 바뀌지 않던 문제를 막는다 — 코드가 정확히 같거나, 이름이 같거나(공백 무시),
     * 한쪽이 다른 쪽을 포함하면(이름이 가장 짧은 ETF) 그 코드를 돌려준다. ETF가 아니면 빈 값.
     */
    public Optional<String> findEtfCode(String nameOrCode) {
        if (nameOrCode == null || nameOrCode.isBlank()) {
            return Optional.empty();
        }
        String wanted = normalize(nameOrCode);
        List<CurrentPriceDetailDto> etfs = localMarketDataReader.getAllCurrentPrices().values().stream()
                .filter(CurrentPriceDetailDto::isEtf)
                .toList();
        for (CurrentPriceDetailDto etf : etfs) {
            if (wanted.equals(etf.getStockCode()) || wanted.contains(etf.getStockCode())
                    || (etf.getStockName() != null && wanted.equals(normalize(etf.getStockName())))) {
                return Optional.of(etf.getStockCode());
            }
        }
        return etfs.stream()
                .filter(etf -> etf.getStockName() != null)
                .filter(etf -> normalize(etf.getStockName()).contains(wanted) || wanted.contains(normalize(etf.getStockName())))
                .min(java.util.Comparator.comparingInt(etf -> etf.getStockName().length()))
                .map(CurrentPriceDetailDto::getStockCode);
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * ETF 구성 종목(비중 큰 순, 최대 {@value #MAX_CONSTITUENT_ITEMS}개). 생성기가 테마 ETF는 그 테마 종목, 지수 ETF는 같은
     * 시장 시가총액 상위 종목으로 만든 모의 구성이다(가격은 30초마다 갱신). ETF가 아니면 빈 목록.
     */
    public List<EtfConstituentDto> getConstituents(String stockCode) {
        List<EtfConstituentDto> items = localMarketDataReader.getStockList(stockCode, "etfConstituents", EtfConstituentDto.class);
        return items.size() > MAX_CONSTITUENT_ITEMS ? items.subList(0, MAX_CONSTITUENT_ITEMS) : items;
    }
}
