package com.fongmi.android.tv.utils;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A fixed-size pool that calls which never return cannot use up.
 *
 * Details, play addresses and home pages of every source run on five threads. A plugin call that ignores
 * its cancellation keeps its thread: on a TV (2026-11-25) four of the five were held by play-address calls
 * 86 to 592 seconds old, so one more would have left every later request queued until the app was
 * restarted. A task still running after {@code stuckMillis} (longer than any caller waits) no longer counts
 * towards the size: the pool gets one more thread for each, up to {@code extra}, and goes back down as
 * they return.
 */
public final class StuckAwarePool extends ThreadPoolExecutor {

    /** Told the number of stuck tasks and the pool's size whenever the size changes. */
    public interface Listener { void resized(int stuck, int threads); }

    private final ConcurrentHashMap<Thread, Long> running = new ConcurrentHashMap<>();
    private final int base, extra;
    private final long stuckMillis;
    private final LongSupplier clock;
    private final Listener listener;
    private int granted;

    public StuckAwarePool(int threads, int extra, long stuckMillis, BlockingQueue<Runnable> queue, ThreadFactory factory,
                          LongSupplier clock, Listener listener) {
        super(threads, threads, 30, TimeUnit.SECONDS, queue, factory);
        allowCoreThreadTimeOut(true);
        this.base = threads;
        this.extra = extra;
        this.stuckMillis = stuckMillis;
        this.clock = clock;
        this.listener = listener;
    }

    @Override protected void beforeExecute(Thread thread, Runnable task) {
        running.put(thread, clock.getAsLong());
        super.beforeExecute(thread, task);
    }

    @Override protected void afterExecute(Runnable task, Throwable error) {
        super.afterExecute(task, error);
        running.remove(Thread.currentThread());
        resize();
    }

    @Override public void execute(Runnable command) {
        resize();
        super.execute(command);
    }

    /** Tasks that have run longer than the limit. */
    public int stuck() {
        long now = clock.getAsLong();
        int count = 0;
        for (long since : running.values()) if (now - since >= stuckMillis) count++;
        return count;
    }

    private void resize() {
        int stuck, threads;
        synchronized (this) {
            stuck = stuck();
            int want = Math.min(extra, stuck);
            if (want == granted) return;
            threads = base + want;
            // The maximum may never be below the core size, whichever way the size moves.
            if (want > granted) { setMaximumPoolSize(threads); setCorePoolSize(threads); }
            else { setCorePoolSize(threads); setMaximumPoolSize(threads); }
            granted = want;
        }
        listener.resized(stuck, threads);
    }
}
