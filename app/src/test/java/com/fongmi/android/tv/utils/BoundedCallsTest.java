package com.fongmi.android.tv.utils;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BoundedCallsTest {
    @Test public void timedOutNativeCallRetainsCapacityAndLateResponseIsClosed() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
        AtomicInteger disposed = new AtomicInteger();
        try (BoundedCalls pool = new BoundedCalls(1, Executors.defaultThreadFactory())) {
            try {
                pool.call(() -> {
                    started.countDown();
                    while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) {}
                    return "late response";
                }, 100, value -> { disposed.incrementAndGet(); closed.countDown(); });
                fail("Expected timeout");
            } catch (TimeoutException expected) {}
            assertTrue(started.await(1, TimeUnit.SECONDS));
            try { pool.call(() -> "extra", 100, value -> {}); fail("No extra worker while native is stuck"); }
            catch (RejectedExecutionException expected) {}
            release.countDown();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertEquals(1, disposed.get());
        } finally { release.countDown(); }
    }

    @Test public void completedResponseBelongsToCaller() throws Exception {
        AtomicInteger disposed = new AtomicInteger();
        try (BoundedCalls pool = new BoundedCalls(1, Executors.defaultThreadFactory())) {
            assertEquals("stream", pool.call(() -> "stream", 1000, value -> disposed.incrementAndGet()));
            assertEquals(0, disposed.get());
        }
    }

    @Test public void cancellingWaitInterruptsTheActualCall() throws Exception {
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try (BoundedCalls pool = new BoundedCalls(1, Executors.defaultThreadFactory())) {
            Future<?> waiting = waiter.submit(() -> {
                try { pool.call(() -> {
                    started.countDown();
                    try { new CountDownLatch(1).await(); } catch (InterruptedException e) { interrupted.countDown(); throw e; }
                    return null;
                }, 10000, value -> {}); } catch (Exception expected) {}
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            waiting.cancel(true);
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        } finally { waiter.shutdownNow(); }
    }
}
