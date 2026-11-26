package com.fongmi.android.tv.utils;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StuckAwarePoolTest {

    private final AtomicLong now = new AtomicLong();
    private final List<String> sizes = new ArrayList<>();

    private StuckAwarePool pool(int threads, int extra) {
        return new StuckAwarePool(threads, extra, 1000, new LinkedBlockingQueue<>(), Thread::new, now::get,
                (stuck, size) -> { synchronized (sizes) { sizes.add(stuck + "/" + size); } });
    }

    private static Future<?> hold(StuckAwarePool pool, CountDownLatch entered, CountDownLatch release) {
        return pool.submit(() -> {
            entered.countDown();
            // A plugin call that ignores its interrupt.
            while (true) {
                try { if (release.await(10, TimeUnit.SECONDS)) return; }
                catch (InterruptedException ignored) { }
            }
        });
    }

    @Test public void callsThatNeverReturnDoNotUseUpThePool() throws Exception {
        StuckAwarePool pool = pool(2, 4);
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1), ran = new CountDownLatch(1);
        try {
            Future<?> first = hold(pool, entered, release), second = hold(pool, entered, release);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            first.cancel(true);
            second.cancel(true);
            // Within the limit both threads are simply busy: the next task waits.
            now.set(500);
            pool.execute(ran::countDown);
            assertFalse(ran.await(200, TimeUnit.MILLISECONDS));
            assertEquals(2, pool.getCorePoolSize());
            // Past it they no longer count; the next submission lets the waiting task run too.
            now.set(1000);
            assertEquals(2, pool.stuck());
            CountDownLatch next = new CountDownLatch(1);
            pool.execute(next::countDown);
            assertTrue(ran.await(2, TimeUnit.SECONDS));
            assertTrue(next.await(2, TimeUnit.SECONDS));
            assertEquals(4, pool.getCorePoolSize());
            // They return at last: back to the configured size.
            release.countDown();
            for (int i = 0; i < 100 && pool.getCorePoolSize() != 2; i++) Thread.sleep(20);
            assertEquals(2, pool.getCorePoolSize());
            assertEquals(2, pool.getMaximumPoolSize());
            synchronized (sizes) {
                assertEquals("2/4", sizes.get(0));
                assertEquals("0/2", sizes.get(sizes.size() - 1));
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test public void growthStopsAtTheExtraThreads() throws Exception {
        StuckAwarePool pool = pool(1, 1);
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1), ran = new CountDownLatch(1);
        try {
            CountDownLatch first = new CountDownLatch(1);
            hold(pool, first, release);
            assertTrue(first.await(2, TimeUnit.SECONDS));
            now.set(1000);
            hold(pool, entered, release);
            for (int i = 0; i < 100 && entered.getCount() > 1; i++) Thread.sleep(20);
            assertEquals(1, entered.getCount());
            now.set(5000);
            pool.execute(ran::countDown);
            assertEquals(2, pool.stuck());
            assertEquals(2, pool.getCorePoolSize());
            assertFalse(ran.await(200, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
