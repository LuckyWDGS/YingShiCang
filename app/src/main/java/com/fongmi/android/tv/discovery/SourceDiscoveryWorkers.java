package com.fongmi.android.tv.discovery;

import com.fongmi.android.tv.utils.BackgroundThreads;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounded independent lanes: an uninterruptible crawler cannot hold up HTTP sources. */
final class SourceDiscoveryWorkers implements AutoCloseable {
    static final int NETWORK_PARALLEL = 12;
    // Preserve the prior crawler throughput while isolating blocked plugins from HTTP.
    static final int CRAWLER_PARALLEL = 6;
    private final ThreadPoolExecutor network, crawler;
    private final int networkLimit, crawlerLimit;

    SourceDiscoveryWorkers() { this(NETWORK_PARALLEL, CRAWLER_PARALLEL); }

    SourceDiscoveryWorkers(int networkLimit, int crawlerLimit) {
        this.networkLimit = networkLimit; this.crawlerLimit = crawlerLimit;
        network = pool(networkLimit, "source-discovery-http");
        crawler = pool(crawlerLimit, "source-discovery-crawler");
    }

    private static ThreadPoolExecutor pool(int limit, String name) {
        return new ThreadPoolExecutor(limit, limit, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(limit * 2), BackgroundThreads.factory(name), new ThreadPoolExecutor.AbortPolicy());
    }

    int limit(boolean plugin) { return plugin ? crawlerLimit : networkLimit; }

    <T> Future<T> submit(boolean plugin, Callable<T> task, Consumer<Future<T>> completed) {
        FutureTask<T> future = new FutureTask<>(task) {
            @Override protected void done() { completed.accept(this); }
        };
        (plugin ? crawler : network).execute(future);
        return future;
    }

    void purge() { network.purge(); crawler.purge(); }

    @Override public void close() { network.shutdownNow(); crawler.shutdownNow(); }
}
