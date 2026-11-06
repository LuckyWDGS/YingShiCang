package com.fongmi.android.tv.utils;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** No waiting queue. An uninterruptible plugin retains its slot until it really returns. */
public final class BoundedCalls implements AutoCloseable {
    private final ThreadPoolExecutor workers;

    public BoundedCalls(int limit, ThreadFactory factory) {
        workers = new ThreadPoolExecutor(0, limit, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), factory);
    }

    public <T> T call(Callable<T> action, long timeoutMs, Consumer<T> discard) throws Exception {
        Result<T> result = new Result<>(discard);
        Future<?> future = workers.submit(() -> { result.complete(action.call()); return null; });
        boolean delivered = false;
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            T value = result.take();
            delivered = true;
            return value;
        } finally {
            if (!delivered) {
                result.abandon();
                future.cancel(true);
            }
        }
    }

    public int active() { return workers.getActiveCount(); }

    @Override public void close() { workers.shutdownNow(); }

    private static final class Result<T> {
        private final Consumer<T> discard;
        private T value;
        private boolean abandoned;
        Result(Consumer<T> discard) { this.discard = discard; }
        synchronized void complete(T value) {
            if (abandoned) discard.accept(value);
            else this.value = value;
        }
        synchronized T take() {
            T value = this.value;
            this.value = null;
            return value;
        }
        synchronized void abandon() {
            abandoned = true;
            if (value != null) discard.accept(take());
        }
    }
}
