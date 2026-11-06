package com.fongmi.android.tv.utils;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ReadyWaitTest {
    @Test public void finishedOldTaskCannotCompleteObserverOfReplacement() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CompletableFuture<Void> first = CompletableFuture.completedFuture(null), next = new CompletableFuture<>();
        AtomicReference<Future<?>> current = new AtomicReference<>(first);
        CountDownLatch examined = new CountDownLatch(1);
        try {
            var waiting = ReadyWait.observe(scheduler, 10000, () -> {
                current.set(next);
                examined.countDown();
                return false;
            }, current::get);
            assertTrue(examined.await(1, TimeUnit.SECONDS));
            scheduler.submit(() -> {}).get(1, TimeUnit.SECONDS);
            assertFalse(waiting.isDone());
            next.complete(null);
            waiting.get(1, TimeUnit.SECONDS);
        } finally { scheduler.shutdownNow(); }
    }

    @Test public void deadlineDoesNotWaitForUninterruptibleLoad() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CompletableFuture<Void> loading = new CompletableFuture<>();
        try {
            ReadyWait.observe(scheduler, 50, () -> false, () -> loading).get(1, TimeUnit.SECONDS);
            assertFalse(loading.isDone());
        } finally { scheduler.shutdownNow(); }
    }

    @Test public void cancellingOneObserverLeavesSharedLoadAndOtherObserverAlive() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CompletableFuture<Void> loading = new CompletableFuture<>();
        try {
            var first = ReadyWait.observe(scheduler, 10000, () -> false, () -> loading);
            var second = ReadyWait.observe(scheduler, 10000, () -> false, () -> loading);
            first.cancel(false);
            assertFalse(loading.isDone());
            assertFalse(second.isDone());
            loading.complete(null);
            second.get(1, TimeUnit.SECONDS);
            assertTrue(first.isCancelled());
        } finally { scheduler.shutdownNow(); }
    }

    @Test public void replacementLoadIsObservedWithinOriginalBudget() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CompletableFuture<Void> first = new CompletableFuture<>(), next = new CompletableFuture<>();
        AtomicReference<Future<?>> current = new AtomicReference<>(first);
        try {
            var waiting = ReadyWait.observe(scheduler, 100, () -> false, current::get);
            current.set(next);
            first.complete(null);
            waiting.get(1, TimeUnit.SECONDS);
            assertFalse(next.isDone());
        } finally { scheduler.shutdownNow(); }
    }
}
