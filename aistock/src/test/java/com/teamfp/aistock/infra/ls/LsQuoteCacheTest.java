package com.teamfp.aistock.infra.ls;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LsQuoteCacheTest {
    @Test
    void expiresAndRejectsFailures() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(100L);
        var cache = new LsQuoteCache(clock);
        var value = Map.<String, Object>of("rsp_cd", "00000", "price", 123);
        cache.put("stock-a", value, 2000);
        assertThat(cache.get("stock-a")).isEqualTo(value);
        assertThat(cache.get("stock-b")).isNull();
        cache.put("error", Map.of("rsp_cd", "99999"), 2000);
        assertThat(cache.get("error")).isNull();
        when(clock.millis()).thenReturn(2100L);
        assertThat(cache.get("stock-a")).isNull();
    }

    @Test
    void boundsMemory() {
        var cache = new LsQuoteCache(Clock.systemUTC());
        for (int i = 0; i < 513; i++) cache.put(i, Map.of("rsp_cd", "00000"), 60_000);
        assertThat(cache.get(0)).isNull();
        assertThat(cache.get(512)).isNotNull();
    }
}
