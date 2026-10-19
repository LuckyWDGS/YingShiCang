package com.fongmi.android.tv.utils;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded, expire-after-write suppression for failed image requests. */
final class ImageFailureCache {

    private final LinkedHashMap<String, Long> failures = new LinkedHashMap<>(16, 0.75f, true);
    private final int capacity;
    private final long ttlNanos;
    private final LongSupplier clock;

    ImageFailureCache(int capacity, long ttlNanos) {
        this(capacity, ttlNanos, System::nanoTime);
    }

    ImageFailureCache(int capacity, long ttlNanos, LongSupplier clock) {
        if (capacity <= 0 || ttlNanos <= 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.ttlNanos = ttlNanos;
        this.clock = clock;
    }

    synchronized boolean contains(String url) {
        Long writtenAt = failures.get(url);
        if (writtenAt == null) return false;
        if (clock.getAsLong() - writtenAt < ttlNanos) return true;
        failures.remove(url);
        return false;
    }

    synchronized void record(String url) {
        if (url == null || url.isEmpty()) return;
        long now = clock.getAsLong();
        // Keep expiry and capacity eviction in the same map and under one lock.
        // The old segmented cache could assert while draining its expired write
        // queue on the next Glide failure after a long playback session.
        Iterator<Map.Entry<String, Long>> entries = failures.entrySet().iterator();
        while (entries.hasNext()) {
            if (now - entries.next().getValue() >= ttlNanos) entries.remove();
        }
        failures.remove(url);
        failures.put(url, now);
        while (failures.size() > capacity) failures.remove(failures.keySet().iterator().next());
    }

    synchronized void invalidate(String url) {
        failures.remove(url);
    }
}
