package com.teamfp.aistock.infra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.teamfp.aistock.infra.marketdata.dto.RegisteredStockDto;

class RegisteredStockReaderTest {

    @Test
    @DisplayName("classpath stocks.json의 등록 종목 105개를 종목코드·상장주식수와 함께 읽는다")
    void loadsRegisteredStocksFromClasspath() {
        List<RegisteredStockDto> stocks = new RegisteredStockReader().getRegisteredStocks();

        assertThat(stocks).hasSize(105);
        assertThat(stocks).extracting(RegisteredStockDto::stockCode).doesNotHaveDuplicates()
                .allMatch(code -> code.matches("[0-9]{6}"));
        assertThat(stocks).allSatisfy(stock -> assertThat(stock.listingShares()).isPositive());
        assertThat(stocks.get(0)).isEqualTo(new RegisteredStockDto("005930", "삼성전자", "KOSPI", stocks.get(0).listingShares()));
    }

    @Test
    @DisplayName("파일이 없으면 예외 없이 빈 목록을 반환한다(서버 기동은 막지 않음)")
    void missingResource_returnsEmptyList() {
        assertThat(new RegisteredStockReader("not-exist-stocks.json").getRegisteredStocks()).isEmpty();
    }
}
