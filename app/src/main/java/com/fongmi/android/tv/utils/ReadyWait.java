package com.fongmi.android.tv.utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** A cancellable observer. Cancelling it never cancels a shared configuration load. */
public final class ReadyWait {
    private ReadyWait() {}

    public static CompletableFuture<Void> observe(ScheduledExecutorService scheduler, long timeoutMs,
            BooleanSupplier ready, Supplier<Future<?>> loading) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        Future<?> tick = scheduler.scheduleWithFixedDelay(() -> {
            if (done.isDone()) return;
            try {
                Future<?> task = loading.get();
                if (ready.getAsBoolean() || System.nanoTime() >= deadline
                        || ((task == null || task.isDone()) && task == loading.get()))
                    done.complete(null);
            } catch (Throwable error) {
                done.completeExceptionally(error);
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
        done.whenComplete((value, error) -> tick.cancel(false));
        return done;
    }
}
