package com.fongmi.android.tv.utils;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImageFailureCacheTest {

    private static final long TTL = TimeUnit.MINUTES.toNanos(2);

    @Test public void expiresAtTwoMinutesWithoutRenewingOnReads() {
        AtomicLong now = new AtomicLong();
        ImageFailureCache cache = new ImageFailureCache(256, TTL, now::get);
        cache.record("poster");
        now.set(TTL - 1);
        assertTrue(cache.contains("poster"));
        now.set(TTL);
        assertFalse(cache.contains("poster"));
    }

    @Test public void boundsFailuresAndRenewsOnlyNewFailures() {
        AtomicLong now = new AtomicLong();
        ImageFailureCache cache = new ImageFailureCache(256, TTL, now::get);
        for (int i = 0; i < 256; i++) cache.record("poster" + i);
        assertTrue(cache.contains("poster0"));
        now.set(1);
        cache.record("poster1");
        cache.record("newPoster");
        assertFalse(cache.contains("poster2"));
        assertTrue(cache.contains("poster0"));
        assertTrue(cache.contains("poster1"));
        now.set(TTL);
        assertFalse(cache.contains("poster0"));
        assertTrue(cache.contains("poster1"));
        assertTrue(cache.contains("newPoster"));
    }

    @Test public void acceptsNewFailureAfterPlaybackExpiresChurnedEntries() {
        AtomicLong now = new AtomicLong();
        ImageFailureCache cache = new ImageFailureCache(256, TTL, now::get);
        // Colliding String hashes plus eviction, invalidation and replacement
        // exercise the cleanup that failed in the released Glide callback.
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < 512; i++) {
                String url = collisionKey(i);
                cache.record(url);
                if ((i & 3) == 0) cache.invalidate(url);
            }
            now.addAndGet(TTL);
            cache.record("remoteSearch" + round);
            assertTrue(cache.contains("remoteSearch" + round));
            for (int i = 0; i < 512; i++) assertFalse(cache.contains(collisionKey(i)));
        }
    }

    @Test public void invalidationAllowsImmediateRetry() {
        ImageFailureCache cache = new ImageFailureCache(256, TTL, () -> 0);
        cache.record("localImage");
        cache.invalidate("localImage");
        assertFalse(cache.contains("localImage"));
        cache.record("localImage");
        assertTrue(cache.contains("localImage"));
        assertFalse(cache.contains("anotherImage"));
    }

    @Test public void concurrentCallbacksAndInvalidationStayBounded() throws Exception {
        AtomicLong now = new AtomicLong();
        ImageFailureCache cache = new ImageFailureCache(256, TTL, now::get);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                final int worker = thread;
                tasks.add(workers.submit(() -> {
                    for (int i = 0; i < 1024; i++) {
                        String url = "poster" + worker + "/" + i;
                        cache.record(url);
                        cache.contains(url);
                        if ((i & 3) == 0) cache.invalidate(url);
                    }
                }));
            }
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            int retained = 0;
            for (int thread = 0; thread < 4; thread++) {
                for (int i = 0; i < 1024; i++) if (cache.contains("poster" + thread + "/" + i)) retained++;
            }
            assertTrue(retained <= 256);
            now.set(TTL);
            cache.record("remoteSearch");
            assertTrue(cache.contains("remoteSearch"));
        } finally {
            workers.shutdownNow();
        }
    }

    private static String collisionKey(int value) {
        StringBuilder key = new StringBuilder();
        for (int bit = 0; bit < 9; bit++) key.append((value & (1 << bit)) == 0 ? "Aa" : "BB");
        return key.toString();
    }
}
