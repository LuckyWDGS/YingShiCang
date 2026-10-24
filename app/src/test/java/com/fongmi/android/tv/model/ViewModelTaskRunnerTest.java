package com.fongmi.android.tv.model;

import com.google.common.util.concurrent.MoreExecutors;
import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ViewModelTaskRunnerTest {
    private enum Type { SEARCH }
    private static final class BlockingTask implements Callable<String>, AutoCloseable {
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger closed = new AtomicInteger();
        @Override public String call() {
            started.countDown();
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
            return "old";
        }
        @Override public void close() { closed.incrementAndGet(); release.countDown(); }
    }

    @Test public void replacingTaskClosesResourceAndSuppressesItsLateCallback() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelTaskRunner<>(Type.class, workers, timer);
        var old = new BlockingTask();
        var replaced = new CountDownLatch(1);
        try {
            runner.execute(Type.SEARCH, 5000, old, result -> fail("Old result escaped replacement"), error -> fail());
            assertTrue(old.started.await(1, TimeUnit.SECONDS));
            runner.execute(Type.SEARCH, 1000, () -> "new", result -> { assertEquals("new", result); replaced.countDown(); }, error -> fail());
            assertTrue(replaced.await(1, TimeUnit.SECONDS));
            assertEquals(1, old.closed.get());
        } finally { old.release.countDown(); runner.cancelAll(); workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test public void timeoutAndCancelAllCloseResourcesOnce() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelTaskRunner<>(Type.class, workers, timer);
        var task = new BlockingTask();
        var timedOut = new CountDownLatch(1);
        try {
            runner.execute(Type.SEARCH, 100, task, result -> fail(), error -> timedOut.countDown());
            assertTrue(task.started.await(1, TimeUnit.SECONDS));
            assertTrue(timedOut.await(1, TimeUnit.SECONDS));
            runner.cancelAll();
            assertEquals(1, task.closed.get());
        } finally { task.release.countDown(); runner.cancelAll(); workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test public void cancelAllClosesActiveRequestAndSuppressesItsLateValue() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelTaskRunner<>(Type.class, workers, timer);
        var task = new BlockingTask();
        var callbacks = new AtomicInteger();
        try {
            runner.execute(Type.SEARCH, 5000, task, result -> callbacks.incrementAndGet(), error -> callbacks.incrementAndGet());
            assertTrue(task.started.await(1, TimeUnit.SECONDS));
            runner.cancelAll();
            workers.shutdown();
            assertTrue(workers.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(1, task.closed.get());
            assertEquals("Cancelled task delivered a late callback", 0, callbacks.get());
        } finally { task.release.countDown(); runner.cancelAll(); workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test public void pausedTimeDoesNotCountAgainstTheTimeout() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelTaskRunner<>(Type.class, workers, timer);
        var task = new BlockingTask();
        var timedOut = new CountDownLatch(1);
        long start = System.nanoTime();
        var resumedAt = new java.util.concurrent.atomic.AtomicLong(-1);
        // A login dialog shows from the start until resumedAt; that span is paused time.
        java.util.function.LongSupplier paused = () -> {
            long end = resumedAt.get();
            return end >= 0 ? end : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        };
        try {
            runner.execute(Type.SEARCH, 200, paused, task, result -> fail(), error -> {
                assertTrue(error instanceof java.util.concurrent.TimeoutException);
                timedOut.countDown();
            });
            assertTrue(task.started.await(1, TimeUnit.SECONDS));
            assertFalse("Timed out while the dialog was showing", timedOut.await(700, TimeUnit.MILLISECONDS));
            resumedAt.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            assertTrue(timedOut.await(2, TimeUnit.SECONDS));
            assertEquals(1, task.closed.get());
        } finally { task.release.countDown(); runner.cancelAll(); workers.shutdownNow(); timer.shutdownNow(); }
    }
}
