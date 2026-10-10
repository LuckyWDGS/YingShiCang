package com.fongmi.android.tv.discovery;

import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class SourceDiscoveryWorkersTest {
    @Test public void fastSourcesCompleteWhileEarlierSlowSourceStillRunsAndConcurrencyIsBounded() throws Exception {
        CountDownLatch slowGate = new CountDownLatch(1), fastGate = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(3);
        BlockingQueue<Future<String>> completions = new LinkedBlockingQueue<>();
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        try (SourceDiscoveryWorkers workers = new SourceDiscoveryWorkers(3, 1)) {
            Future<String> slow = workers.submit(false, () -> {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max); started.countDown();
                try { assertTrue(slowGate.await(3, TimeUnit.SECONDS)); return "slow"; }
                finally { active.decrementAndGet(); }
            }, completions::offer);
            for (int i = 0; i < 2; i++) workers.submit(false, () -> {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max); started.countDown();
                try { assertTrue(fastGate.await(3, TimeUnit.SECONDS)); return "fast"; }
                finally { active.decrementAndGet(); }
            }, completions::offer);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Future<String> queued = workers.submit(false, () -> {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                try { return "later-source"; } finally { active.decrementAndGet(); }
            }, completions::offer);
            assertFalse(queued.isDone());
            fastGate.countDown();
            Future<String> first = completions.poll(1, TimeUnit.SECONDS);
            assertNotNull(first); assertEquals("fast", first.get());
            assertFalse("Slow first source must not hold up completed results", slow.isDone());
            assertEquals("later-source", queued.get(1, TimeUnit.SECONDS));
            assertEquals(3, peak.get());
            slowGate.countDown(); assertEquals("slow", slow.get(1, TimeUnit.SECONDS));
        } finally { slowGate.countDown(); fastGate.countDown(); }
    }

    @Test public void cancelledInterruptionIgnoringCrawlerCannotStarveNewHttpSource() throws Exception {
        CountDownLatch crawlerStarted = new CountDownLatch(1), crawlerGate = new CountDownLatch(1);
        try (SourceDiscoveryWorkers workers = new SourceDiscoveryWorkers(1, 1)) {
            Future<String> stuck = workers.submit(true, () -> {
                crawlerStarted.countDown();
                while (crawlerGate.getCount() > 0) {
                    try { crawlerGate.await(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* Simulate a plugin that ignores cancellation. */ }
                }
                return "old crawler";
            }, completed -> {});
            assertTrue(crawlerStarted.await(1, TimeUnit.SECONDS)); stuck.cancel(true);
            Future<String> freshCrawler = workers.submit(true, () -> "new crawler", completed -> {});
            Future<String> http = workers.submit(false, () -> "new HTTP source", completed -> {});
            assertEquals("new HTTP source", http.get(1, TimeUnit.SECONDS));
            assertFalse(freshCrawler.isDone());
            crawlerGate.countDown(); assertEquals("new crawler", freshCrawler.get(1, TimeUnit.SECONDS));
        } finally { crawlerGate.countDown(); }
    }

    @Test public void cancelledQueuedWorkIsPurgedAndCannotRunAfterNextRequest() throws Exception {
        CountDownLatch started = new CountDownLatch(1), gate = new CountDownLatch(1);
        AtomicInteger staleRuns = new AtomicInteger();
        try (SourceDiscoveryWorkers workers = new SourceDiscoveryWorkers(1, 1)) {
            Future<String> first = workers.submit(false, () -> {
                started.countDown(); assertTrue(gate.await(3, TimeUnit.SECONDS)); return "first";
            }, completed -> {});
            assertTrue(started.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 2; i++) workers.submit(false, () -> {
                staleRuns.incrementAndGet(); return "stale";
            }, completed -> {}).cancel(true);
            workers.purge();
            Future<String> replacement = workers.submit(false, () -> "replacement", completed -> {});
            gate.countDown(); assertEquals("first", first.get(1, TimeUnit.SECONDS));
            assertEquals("replacement", replacement.get(1, TimeUnit.SECONDS));
            assertEquals(0, staleRuns.get());
        } finally { gate.countDown(); }
    }
}
