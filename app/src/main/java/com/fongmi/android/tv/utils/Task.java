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

    private static final ListeningExecutorService executor = MoreExecutors.listeningDecorator(pool(5, "task"));
    private static final ListeningExecutorService largeExecutor = MoreExecutors.listeningDecorator(pool(20, "task-large"));
    public static final int SEARCH_PARALLELISM = 16;
    public static final int SEARCH_CRAWLER_PARALLELISM = 16;
    private static final ThreadPoolExecutor searchWorkers = pool(SEARCH_PARALLELISM, new ArrayBlockingQueue<>(32), "search");
    private static final ThreadPoolExecutor crawlerSearchWorkers = pool(SEARCH_CRAWLER_PARALLELISM, new ArrayBlockingQueue<>(32), "search-crawler");
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
