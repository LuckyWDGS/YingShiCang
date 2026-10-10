package com.fongmi.android.tv.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class Task {

    private static final ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(5));
    private static final ListeningExecutorService largeExecutor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(20));
    public static final int SEARCH_PARALLELISM = 16;
    public static final int SEARCH_CRAWLER_PARALLELISM = 16;
    private static final ThreadPoolExecutor searchWorkers = new ThreadPoolExecutor(SEARCH_PARALLELISM, SEARCH_PARALLELISM,
            0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32));
    private static final ThreadPoolExecutor crawlerSearchWorkers = new ThreadPoolExecutor(SEARCH_CRAWLER_PARALLELISM, SEARCH_CRAWLER_PARALLELISM,
            0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32));
    private static final ListeningExecutorService searchExecutor = MoreExecutors.listeningDecorator(searchWorkers);
    private static final ListeningExecutorService crawlerSearchExecutor = MoreExecutors.listeningDecorator(crawlerSearchWorkers);

    public static ListeningExecutorService searchExecutor() { return searchExecutor; }
    public static ListeningExecutorService searchExecutor(boolean crawler) { return crawler ? crawlerSearchExecutor : searchExecutor; }
    public static void purgeSearchTasks() { searchWorkers.purge(); crawlerSearchWorkers.purge(); }
    private static final ListeningExecutorService serialExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

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
