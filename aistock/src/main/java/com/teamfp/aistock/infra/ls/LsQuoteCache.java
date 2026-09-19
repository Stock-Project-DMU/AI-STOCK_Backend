package com.teamfp.aistock.infra.ls;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small, bounded cache for public market queries only. Expired values are never served. */
final class LsQuoteCache {
    private record Entry(Map<String, Object> value, long expiresAt) {}
    private final Map<Object, Entry> entries = new LinkedHashMap<>();
    private final Clock clock;

    LsQuoteCache(Clock clock) { this.clock = clock; }

    synchronized Map<String, Object> get(Object key) {
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (clock.millis() >= entry.expiresAt()) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    synchronized void put(Object key, Map<String, Object> value, long ttlMillis) {
        if (ttlMillis <= 0 || value == null || !"00000".equals(value.get("rsp_cd"))) return;
        entries.put(key, new Entry(value, clock.millis() + ttlMillis));
        while (entries.size() > 512) entries.remove(entries.keySet().iterator().next());
    }
}
