package com.fongmi.android.tv.model;

import com.fongmi.android.tv.bean.Site;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.Test;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ViewModelSearchRunnerTest {
    private static Site crawlerSite() { return new Site() { @Override public Integer getType() { return 3; } }; }

    @Test public void uninterruptibleOldCrawlersCannotBlockFreshHttpSearch() throws Exception {
        var http = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2));
        var crawlerPool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
        var crawlers = MoreExecutors.listeningDecorator(crawlerPool);
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelSearchRunner(http, crawlers, timer, 5000, 2, 2);
        CountDownLatch oldStarted = new CountDownLatch(2), release = new CountDownLatch(1), freshHttp = new CountDownLatch(1);
        try {
            runner.start(java.util.List.of(crawlerSite(), crawlerSite(), crawlerSite()), site -> () -> {
                oldStarted.countDown();
                while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
                return null;
            }, result -> {}, status -> {});
            assertTrue(oldStarted.await(1, TimeUnit.SECONDS));
            runner.stop();
            runner.start(java.util.List.of(crawlerSite(), crawlerSite(), crawlerSite(), crawlerSite(), new Site()), site -> () -> {
                if (site.getType() != 3) freshHttp.countDown();
                return null;
            }, result -> {}, status -> {});
            assertTrue("Fresh HTTP site waited behind stale crawler workers and pending crawler sites", freshHttp.await(1, TimeUnit.SECONDS));
            assertEquals("Old crawler work was interrupted rather than remaining occupied", 1, release.getCount());
            assertTrue(crawlerPool.getQueue().size() <= 2);
        } finally { release.countDown(); runner.stop(); http.shutdownNow(); crawlers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test public void saturatedCrawlerQueueClosesRejectedTasksAndStillSearchesHttp() throws Exception {
        var http = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        var crawlerPool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        var crawlers = MoreExecutors.listeningDecorator(crawlerPool);
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelSearchRunner(http, crawlers, timer, 100, 1, 2);
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var httpStarted = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var tasks = new java.util.concurrent.CopyOnWriteArrayList<BlockingSearch>();
        try {
            crawlers.submit(() -> { occupied.countDown(); release.await(); return null; });
            assertTrue(occupied.await(1, TimeUnit.SECONDS));
            runner.start(java.util.List.of(crawlerSite(), crawlerSite(), crawlerSite(), new Site()), site -> {
                if (site.getType() != 3) return () -> { httpStarted.countDown(); return null; };
                BlockingSearch task = new BlockingSearch(); tasks.add(task); return task;
            }, result -> {}, status -> { if (status.startsWith("搜索完成 4/4")) done.countDown(); });
            assertTrue(httpStarted.await(1, TimeUnit.SECONDS));
            assertTrue("Rejected tasks left aggregate progress stuck", done.await(5, TimeUnit.SECONDS));
            assertEquals(9, tasks.size());
            for (BlockingSearch task : tasks) assertEquals("Rejected/timed-out resource was not closed once", 1, task.closed.get());
            assertTrue(crawlerPool.getQueue().size() <= 1);
        } finally { release.countDown(); runner.stop(); http.shutdownNow(); crawlers.shutdownNow(); timer.shutdownNow(); }
    }

    private static final class BlockingSearch implements Callable<com.fongmi.android.tv.bean.Result>, AutoCloseable {
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger closed = new AtomicInteger();
        @Override public com.fongmi.android.tv.bean.Result call() {
            started.countDown();
            boolean waiting = true;
            while (waiting) try { release.await(); waiting = false; } catch (InterruptedException ignored) { }
            return null;
        }
        @Override public void close() { closed.incrementAndGet(); release.countDown(); }
    }

    @Test public void timeoutClosesResourceAndReleasesLaneForNextSource() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1));
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelSearchRunner(workers, timer, 100, 1);
        BlockingSearch slow = new BlockingSearch();
        CountDownLatch next = new CountDownLatch(1);
        AtomicInteger assigned = new AtomicInteger();
        try {
            runner.start(java.util.List.of(new Site(), new Site()), site -> assigned.getAndIncrement() == 0 ? slow : () -> { next.countDown(); return null; }, result -> {}, status -> {});
            assertTrue(slow.started.await(1, TimeUnit.SECONDS));
            assertTrue("Timed-out resource kept the only worker occupied", next.await(1, TimeUnit.SECONDS));
            runner.stop();
            assertEquals("Cancellation and timeout must close once", 1, slow.closed.get());
        } finally { slow.release.countDown(); runner.stop(); workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test public void stopClosesActiveResourceBeforeReplacementSearchStarts() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1));
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelSearchRunner(workers, timer, 5000, 1);
        BlockingSearch old = new BlockingSearch();
        CountDownLatch replacement = new CountDownLatch(1);
        try {
            runner.start(java.util.List.of(new Site()), site -> old, result -> {}, status -> {});
            assertTrue(old.started.await(1, TimeUnit.SECONDS));
            runner.stop();
            runner.start(java.util.List.of(new Site()), site -> () -> { replacement.countDown(); return null; }, result -> {}, status -> {});
            assertTrue("Old request kept replacement search queued", replacement.await(1, TimeUnit.SECONDS));
            assertEquals(1, old.closed.get());
        } finally { old.release.countDown(); runner.stop(); workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test public void fastSourcesStartWhileFirstEightSourcesAreStillWaiting() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(16));
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelSearchRunner(workers, timer, 5000, 16);
        CountDownLatch release = new CountDownLatch(1), fast = new CountDownLatch(8);
        try {
            var sites = new ArrayList<Site>();
            for (int i = 0; i < 16; i++) sites.add(new Site());
            AtomicInteger assigned = new AtomicInteger();
            runner.start(sites, site -> {
                int index = assigned.getAndIncrement();
                return () -> { if (index < 8) release.await(); else fast.countDown(); return null; };
            }, value -> {}, status -> {});
            assertTrue("Fast sources were blocked behind the first slow wave", fast.await(2, TimeUnit.SECONDS));
            assertEquals(1, release.getCount());
        } finally { release.countDown(); runner.stop(); workers.shutdownNow(); timer.shutdownNow(); }
    }
    @Test public void searchesEverySourceBeyondThirtyWithoutBlockingOnOneFailure() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(16));
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelSearchRunner(workers, timer, 2000, 16);
        try {
            var sites = new ArrayList<Site>();
            for (int i = 0; i < 67; i++) sites.add(new Site());
            AtomicInteger calls = new AtomicInteger(), active = new AtomicInteger(), peak = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(1);
            runner.start(sites, site -> () -> {
                int number = calls.incrementAndGet();
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                try { Thread.sleep(2); if (number == 2) throw new IllegalStateException("failed source"); return null; }
                finally { active.decrementAndGet(); }
            }, result -> {}, status -> { if (status.startsWith("搜索完成 67/67")) done.countDown(); });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(68, calls.get());
            assertTrue(peak.get() <= 16);
        } finally { runner.stop(); workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test public void cancellationDiscardsOldSourceResults() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(4));
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelSearchRunner(workers, timer, 2000);
        try {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger reports = new AtomicInteger();
            runner.start(java.util.List.of(new Site()), site -> () -> { started.countDown(); release.await(); return null; }, result -> fail(), status -> reports.incrementAndGet());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            runner.stop(); int count = reports.get(); release.countDown();
            workers.shutdown(); assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(count, reports.get());
        } finally { runner.stop(); workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test public void transientFailureUsesFreshResourcesAndKeepsHealthyResults() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelSearchRunner(workers, timer, 2000, 1);
        Site flaky = new Site(), healthy = new Site();
        AtomicInteger attempts = new AtomicInteger(), closed = new AtomicInteger(), healthyCalls = new AtomicInteger();
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var statuses = new java.util.concurrent.CopyOnWriteArrayList<String>();
        CountDownLatch done = new CountDownLatch(1);
        class RetryTask implements Callable<com.fongmi.android.tv.bean.Result>, AutoCloseable {
            final int attempt = attempts.incrementAndGet();
            boolean ended;
            @Override public com.fongmi.android.tv.bean.Result call() {
                if (attempt == 1) throw new IllegalStateException("temporary");
                return result("recovered");
            }
            @Override public void close() { assertFalse(ended); ended = true; closed.incrementAndGet(); }
        }
        try {
            runner.start(java.util.List.of(flaky, healthy), site -> site == flaky ? new RetryTask() : () -> {
                healthyCalls.incrementAndGet(); return result("healthy");
            }, value -> received.add(value.getList().get(0).getId()), status -> {
                statuses.add(status); if (status.startsWith("搜索完成 2/2")) done.countDown();
            });
            assertTrue(done.await(4, TimeUnit.SECONDS));
            assertEquals(java.util.List.of("healthy", "recovered"), received);
            assertEquals(2, attempts.get()); assertEquals(2, closed.get()); assertEquals(1, healthyCalls.get());
            assertTrue(statuses.get(statuses.size() - 1).contains("0 个失败"));
        } finally { runner.stop(); workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test public void permanentFailureStopsAfterTwoRetriesAndCountsSourceOnce() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelSearchRunner(workers, timer, 2000, 1);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        try {
            runner.start(java.util.List.of(new Site()), site -> () -> {
                attempts.incrementAndGet(); throw new IllegalStateException("unavailable");
            }, value -> fail("Failed source published a result"), status -> {
                if (status.startsWith("搜索完成 1/1") && status.contains("1 个失败")) done.countDown();
            });
            assertTrue(done.await(4, TimeUnit.SECONDS));
            assertEquals(3, attempts.get());
        } finally { runner.stop(); workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test public void replacementDuringBackoffCancelsOldRetryAndStatus() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelSearchRunner(workers, timer, 2000, 1);
        AtomicInteger attempts = new AtomicInteger(), oldReports = new AtomicInteger();
        CountDownLatch waiting = new CountDownLatch(1), fresh = new CountDownLatch(1), barrier = new CountDownLatch(1);
        try {
            runner.start(java.util.List.of(new Site()), site -> () -> {
                attempts.incrementAndGet(); throw new IllegalStateException("temporary");
            }, value -> fail(), status -> { if (oldReports.incrementAndGet() == 2) waiting.countDown(); });
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            runner.start(java.util.List.of(new Site()), site -> () -> result("fresh"), value -> fresh.countDown(), status -> {});
            int previousReports = oldReports.get();
            assertTrue(fresh.await(1, TimeUnit.SECONDS));
            timer.schedule(barrier::countDown, 900, TimeUnit.MILLISECONDS);
            assertTrue(barrier.await(2, TimeUnit.SECONDS));
            assertEquals(1, attempts.get()); assertEquals(previousReports, oldReports.get());
        } finally { runner.stop(); workers.shutdownNow(); timer.shutdownNow(); }
    }

    @Test public void filteredSearchPreservesRawRowsForPaginationAndRecovery() throws Exception {
        var workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        var timer = Executors.newSingleThreadScheduledExecutor();
        var runner = new ViewModelSearchRunner(workers, timer, 2000, 1);
        long revision = com.fongmi.android.tv.api.config.VodConfig.getDiscoveryRevision();
        com.fongmi.android.tv.resource.ResourceHealth.recordDetailFailure("test-source", "invalid",
                new java.io.IOException("资源已下架"), revision);
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<com.fongmi.android.tv.bean.Result> received = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            runner.start(java.util.List.of(new Site()), site -> () -> result("invalid"), value -> { received.set(value); done.countDown(); }, status -> {});
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertTrue(received.get().getList().isEmpty());
            assertEquals("invalid", received.get().getSearchRawList().get(0).getId());
        } finally {
            runner.stop(); workers.shutdownNow(); timer.shutdownNow();
            com.fongmi.android.tv.resource.ResourceHealth.clear();
        }
    }

    private static com.fongmi.android.tv.bean.Result result(String id) {
        var vod = new com.fongmi.android.tv.bean.Vod() {
            @Override public String getId() { return id; }
            @Override public String getName() { return id; }
            @Override public boolean isFolder() { return false; }
            @Override public boolean isAction() { return false; }
            @Override public String getSiteKey() { return "test-source"; }
        };
        var result = new com.fongmi.android.tv.bean.Result(); result.setList(java.util.List.of(vod)); return result;
    }
}
