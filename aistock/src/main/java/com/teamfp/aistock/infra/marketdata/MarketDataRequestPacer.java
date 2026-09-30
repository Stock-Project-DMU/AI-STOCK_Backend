package com.teamfp.aistock.infra.marketdata;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.web.client.RestClientResponseException;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

/** Keeps REST calls for the same LS TR apart across all market-data clients in this JVM. */
final class MarketDataRequestPacer {
    static final MarketDataRequestPacer INSTANCE = new MarketDataRequestPacer();
    private static final long DEFAULT_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(1_100);
    private static final long QUOTE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(120);
    private static final long LIMIT_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(1_500);

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    private static final class Slot {
        long nextAllowedNanos;
        boolean scheduled;
    }

    <T> T call(String trCode, Supplier<T> request) {
        Slot slot = slots.computeIfAbsent(trCode, ignored -> new Slot());
        synchronized (slot) {
            for (int attempt = 0; attempt < 2; attempt++) {
                await(slot);
                slot.nextAllowedNanos = System.nanoTime() + intervalFor(trCode);
                slot.scheduled = true;
                try {
                    return request.get();
                } catch (CustomException e) {
                    if (attempt == 0 && isRateLimited(e)) {
                        slot.nextAllowedNanos = System.nanoTime() + LIMIT_RETRY_NANOS;
                        continue;
                    }
                    throw e;
                }
            }
            throw new IllegalStateException("Unreachable market-data retry state");
        }
    }

    private long intervalFor(String trCode) {
        return "t1102".equals(trCode) ? QUOTE_INTERVAL_NANOS : DEFAULT_INTERVAL_NANOS;
    }

    private void await(Slot slot) {
        if (!slot.scheduled) return;
        long remaining = slot.nextAllowedNanos - System.nanoTime();
        while (remaining > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CustomException(ErrorCode.MARKET_DATA_UNAVAILABLE, e);
            }
            remaining = slot.nextAllowedNanos - System.nanoTime();
        }
    }

    private boolean isRateLimited(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException response
                    && response.getResponseBodyAsString().contains("IGW00201")) {
                return true;
            }
        }
        return false;
    }
}
