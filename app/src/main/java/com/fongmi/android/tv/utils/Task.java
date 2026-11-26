package com.fongmi.android.tv.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class Task {

    // Longer than any caller waits (30 s for a detail or a play address): what still runs then ignored its cancellation.
    private static final long STUCK_MS = 35_000;
    private static final ListeningExecutorService executor = MoreExecutors.listeningDecorator(stuckAware(5, 16, new LinkedBlockingQueue<>(), "task"));
    private static final ListeningExecutorService largeExecutor = MoreExecutors.listeningDecorator(pool(20, "task-large"));
    // 16 + 16, or 4 + 4 on devices of 2 GB and below (SearchCapacity).
    public static final int SEARCH_PARALLELISM = SearchCapacity.lanes();
    public static final int SEARCH_CRAWLER_PARALLELISM = SEARCH_PARALLELISM;
    private static final ThreadPoolExecutor searchWorkers = stuckAware(SEARCH_PARALLELISM, SEARCH_PARALLELISM, new ArrayBlockingQueue<>(32), "search");
    private static final ThreadPoolExecutor crawlerSearchWorkers = stuckAware(SEARCH_CRAWLER_PARALLELISM, SEARCH_CRAWLER_PARALLELISM, new ArrayBlockingQueue<>(32), "search-crawler");
    private static final ListeningExecutorService searchExecutor = MoreExecutors.listeningDecorator(searchWorkers);
    private static final ListeningExecutorService crawlerSearchExecutor = MoreExecutors.listeningDecorator(crawlerSearchWorkers);

    public static ListeningExecutorService searchExecutor() { return searchExecutor; }
    public static ListeningExecutorService searchExecutor(boolean crawler) { return crawler ? crawlerSearchExecutor : searchExecutor; }
    public static void purgeSearchTasks() { searchWorkers.purge(); crawlerSearchWorkers.purge(); }
    private static final ListeningExecutorService serialExecutor = MoreExecutors.listeningDecorator(pool(1, "task-serial"));
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(BackgroundThreads.factory("task-scheduler"));

    private static ThreadPoolExecutor pool(int threads, String name) {
        return pool(threads, new LinkedBlockingQueue<>(), name);
    }

    // Idle pools release their threads; a burst of searches no longer pins dozens of them.
    private static ThreadPoolExecutor pool(int threads, BlockingQueue<Runnable> queue, String name) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, queue, BackgroundThreads.factory(name));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Source calls run here. A plugin call that never returns keeps its thread, so it stops counting towards
     * the size once it has run for {@link #STUCK_MS} (StuckAwarePool); each change is logged as task_pool.
     */
    private static ThreadPoolExecutor stuckAware(int threads, int extra, BlockingQueue<Runnable> queue, String name) {
        return new StuckAwarePool(threads, extra, STUCK_MS, queue, BackgroundThreads.factory(name), () -> System.nanoTime() / 1_000_000,
                (stuck, size) -> com.fongmi.android.tv.diagnostics.DiagnosticLog.write(null, "task_pool", "pool=" + name + " stuck=" + stuck + " threads=" + size));
    }

    public static ListeningExecutorService executor() {
        return executor;
    }

    public static ListeningExecutorService largeExecutor() {
        return largeExecutor;
    }

    public static ScheduledExecutorService scheduler() {
        return scheduler;
    }

    public static Future<?> submit(Runnable task) {
        return executor.submit(task);
    }

    public static Future<?> submitLarge(Runnable task) {
        return largeExecutor.submit(task);
    }

    public static void execute(Runnable task) {
        executor.execute(task);
    }

    public static void executeSerial(Runnable task) {
        serialExecutor.execute(task);
    }

    public static void schedule(Runnable task, long delay, TimeUnit unit) {
        scheduler.schedule(task, delay, unit);
    }

    public static <T> FutureCallback<T> callback(Consumer<T> onSuccess) {
        return callback(onSuccess, null);
    }

    public static <T> FutureCallback<T> callback(Consumer<T> onSuccess, @Nullable Consumer<Throwable> onFailure) {
        return new FutureCallback<>() {
            @Override
            public void onSuccess(T result) {
                onSuccess.accept(result);
            }

            @Override
            public void onFailure(@NonNull Throwable error) {
                if (onFailure != null) onFailure.accept(error);
            }
        };
    }
}
