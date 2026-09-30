package com.teamfp.aistock.infra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

class MarketDataRequestPacerTest {

    @Test
    void onePerSecondTrCallsAreSpaced() {
        MarketDataRequestPacer pacer = new MarketDataRequestPacer();
        long first = pacer.call("t3401", System::nanoTime);
        long second = pacer.call("t3401", System::nanoTime);

        assertThat(second - first).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    void onlyQuotaFailureGetsOneDelayedRetry() {
        MarketDataRequestPacer pacer = new MarketDataRequestPacer();
        RestClientResponseException response = mock(RestClientResponseException.class);
        when(response.getResponseBodyAsString()).thenReturn("{\"rsp_cd\":\"IGW00201\"}");
        AtomicInteger attempts = new AtomicInteger();

        String value = pacer.call("t1305", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new CustomException(ErrorCode.MARKET_DATA_UNAVAILABLE, response);
            }
            return "recovered";
        });

        assertThat(value).isEqualTo("recovered");
        assertThat(attempts).hasValue(2);
    }
}
