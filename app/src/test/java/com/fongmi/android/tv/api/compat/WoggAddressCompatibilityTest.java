package com.fongmi.android.tv.api.compat;

import com.github.catvod.crawler.Spider;
import com.github.catvod.spider.BaseSpiderGuard;
import com.github.catvod.spider.WoGG;

import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class WoggAddressCompatibilityTest {
    private static final String OLD = "https://original.fixture.invalid/";
    private static final String NEW = "https://updated.fixture.invalid/";
    private static final WoggAddressCompatibility.CancellationCheck ACTIVE = () -> { };

    private <T> T call(Spider spider, Callable<T> callback) throws Exception {
        return WoggAddressCompatibility.call(spider, 3000, ACTIVE, callback);
    }

    @Test public void gateRequiresExactDelegateAndVerifiedUnambiguousGuard() {
        WoGG actual = new WoGG(OLD);
        assertTrue(WoggAddressCompatibility.supports(actual));
        assertTrue(WoggAddressCompatibility.supports(new BaseSpiderGuard(actual)));
        assertTrue(WoggAddressCompatibility.supports(new BaseSpiderGuard(new BaseSpiderGuard(actual))));
        assertFalse(WoggAddressCompatibility.supports(null));
        assertFalse(WoggAddressCompatibility.supports(new WoGG(OLD) {}));
        assertFalse(WoggAddressCompatibility.supports(new BaseSpiderGuard(null)));
        class Unknown extends Spider { private final Spider child = actual; }
        assertFalse(WoggAddressCompatibility.supports(new Unknown()));
        class Ambiguous extends BaseSpiderGuard {
            private final Spider other = new WoGG(NEW);
            Ambiguous() { super(actual); }
        }
        assertFalse(WoggAddressCompatibility.supports(new Ambiguous()));
        class OverrideInit extends BaseSpiderGuard {
            OverrideInit() { super(actual); }
            @Override public void init(android.content.Context context, String extension) { }
        }
        assertFalse(WoggAddressCompatibility.supports(new OverrideInit()));
        class OverrideHome extends BaseSpiderGuard {
            OverrideHome() { super(actual); }
            @Override public String homeContent(boolean filter) { return ""; }
        }
        assertFalse(WoggAddressCompatibility.supports(new OverrideHome()));
        BaseSpiderGuard cycle = new BaseSpiderGuard(actual);
        cycle.setDelegate(cycle);
        assertFalse(WoggAddressCompatibility.supports(cycle));
        Spider deep = actual;
        for (int i = 0; i < 8; i++) deep = new BaseSpiderGuard(deep);
        assertFalse(WoggAddressCompatibility.supports(deep));
    }

    @Test public void detailWaitingBehindSearchInterruptsOnlyThatSearch() throws Exception {
        WoGG source = new WoGG(OLD);
        CountDownLatch searching = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> search = pool.submit(() -> WoggAddressCompatibility.search(source, () -> 10_000, ACTIVE, () -> {
                searching.countDown();
                Thread.sleep(10_000);
                return "never";
            }));
            assertTrue(searching.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            assertEquals("detail", call(source, () -> "detail"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
            try { search.get(2, TimeUnit.SECONDS); fail(); }
            catch (java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof InterruptedException); }
            // The pooled search thread must not carry the preemption interrupt into its next task.
            assertFalse(pool.submit(() -> Thread.currentThread().isInterrupted()).get(2, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void searchesNeverPreemptEachOther() throws Exception {
        WoGG source = new WoGG(OLD);
        CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> first = pool.submit(() -> WoggAddressCompatibility.search(source, () -> 10_000, ACTIVE, () -> {
                holding.countDown();
                release.await();
                return "first";
            }));
            assertTrue(holding.await(2, TimeUnit.SECONDS));
            try {
                WoggAddressCompatibility.search(source, () -> 200, ACTIVE, () -> "second");
                fail();
            } catch (InterruptedIOException expected) {
                // The second search runs out of budget waiting; the first keeps running.
            }
            release.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void unmatchedPluginsKeepEntireOriginalCallbackContract() throws Exception {
        Spider other = new Spider() {};
        Object value = new Object();
        assertSame(value, WoggAddressCompatibility.call(other, 0,
                () -> { throw new AssertionError("Unrelated source cancellation path changed"); }, () -> value));
        IOException original = new IOException("Unrelated fixture failure");
        try { WoggAddressCompatibility.call(other, 0, ACTIVE, () -> { throw original; }); fail(); }
        catch (IOException actual) { assertSame(original, actual); }
    }

    @Test public void overriddenGuardCallbacksAreAllRejected() {
        WoGG actual = new WoGG(OLD);
        Spider[] unknown = {
                new BaseSpiderGuard(actual) { @Override public String homeContent(boolean filter) { return ""; } },
                new BaseSpiderGuard(actual) { @Override public String homeVideoContent() { return ""; } },
                new BaseSpiderGuard(actual) {
                    @Override public String categoryContent(String type, String page, boolean filter,
                                                              java.util.HashMap<String, String> extension) { return ""; }
                },
                new BaseSpiderGuard(actual) { @Override public String detailContent(java.util.List<String> ids) { return ""; } },
                new BaseSpiderGuard(actual) {
                    @Override public String playerContent(String flag, String id, java.util.List<String> flags) { return ""; }
                },
                new BaseSpiderGuard(actual) { @Override public String action(String action) { return ""; } },
                new BaseSpiderGuard(actual) { @Override public String searchContent(String key, boolean quick) { return ""; } },
                new BaseSpiderGuard(actual) { @Override public String searchContent(String key, boolean quick, String page) { return ""; } }
        };
        for (Spider spider : unknown) assertFalse(WoggAddressCompatibility.supports(spider));
    }

    @Test public void failedRefreshRetainsAddressAndOriginalResponseWithoutAnotherCall() throws Exception {
        for (String invalid : new String[] {null, "", "relative/path", "://bad", "ftp://host.invalid/", "https:///path"}) {
            WoGG source = new WoGG(OLD);
            AtomicInteger requests = new AtomicInteger();
            String failurePayload = "<html>genuine upstream failure</html>";
            assertSame(failurePayload, call(source, () -> {
                requests.incrementAndGet();
                source.replace(invalid);
                return failurePayload;
            }));
            assertEquals(OLD, source.address());
            assertEquals(1, requests.get());
        }
    }

    @Test public void newValidAddressAndRetryFlagSurviveUnchanged() throws Exception {
        WoGG source = new WoGG(OLD);
        WoGG.retryFlag(true);
        Object rows = new Object();
        assertSame(rows, call(new BaseSpiderGuard(source), () -> {
            source.replace(NEW);
            return rows;
        }));
        assertEquals(NEW, source.address());
        assertTrue(WoGG.retryFlag());
        call(source, () -> { source.replace(""); return ""; });
        assertEquals(NEW, source.address());
        assertTrue(WoGG.retryFlag());
    }

    @Test public void exceptionAndCancellationRestoreWithoutRewritingOriginalException() throws Exception {
        for (Exception expected : new Exception[] {new IOException("Fixture failure"), new CancellationException("Fixture cancelled")}) {
            WoGG source = new WoGG(OLD);
            try { call(source, () -> { source.replace(""); throw expected; }); fail(); }
            catch (Exception actual) { assertSame(expected, actual); }
            assertEquals(OLD, source.address());
        }
        WoGG source = new WoGG(OLD);
        AtomicBoolean cancelled = new AtomicBoolean();
        try {
            WoggAddressCompatibility.call(source, 1000, () -> {
                if (cancelled.get()) throw new CancellationException("Fixture cancelled after callback");
            }, () -> { source.replace(""); cancelled.set(true); return "stale result"; });
            fail();
        } catch (CancellationException expected) { assertEquals(OLD, source.address()); }
    }

    @Test public void identityStateDoesNotCrossEqualInstancesOrInventUnknownAddress() throws Exception {
        WoGG known = new WoGG(OLD);
        WoGG unknown = new WoGG(null);
        known.siteKey = unknown.siteKey = "same fixture source key";
        assertEquals(known, unknown); // Their plugin equality is intentionally misleading.
        call(known, () -> { known.replace(""); return ""; });
        call(unknown, () -> { unknown.replace("relative/path"); return ""; });
        assertEquals(OLD, known.address());
        assertEquals("relative/path", unknown.address());
    }

    @Test public void interruptedCallbackRestoresAndRetainsItsOriginalException() throws Exception {
        WoGG source = new WoGG(OLD);
        InterruptedException expected = new InterruptedException("Fixture interrupted in callback");
        try {
            try { call(source, () -> { source.replace(""); throw expected; }); fail(); }
            catch (InterruptedException actual) {
                assertSame(expected, actual);
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(OLD, source.address());
            }
        } finally { Thread.interrupted(); }
    }

    @Test public void expiredBudgetDoesNotStartOrDeliverAnOutdatedCallback() throws Exception {
        WoGG source = new WoGG(OLD);
        AtomicInteger entered = new AtomicInteger();
        try { WoggAddressCompatibility.call(source, () -> 0, ACTIVE, () -> { entered.incrementAndGet(); return ""; }); fail(); }
        catch (InterruptedIOException expected) { assertEquals(0, entered.get()); }
        AtomicLong remaining = new AtomicLong(1000);
        try {
            WoggAddressCompatibility.call(source, remaining::get, ACTIVE, () -> {
                entered.incrementAndGet(); source.replace(""); remaining.set(0); return "outdated rows";
            });
            fail();
        } catch (InterruptedIOException expected) {
            assertEquals(1, entered.get());
            assertEquals(OLD, source.address());
        }
    }

    @Test public void registryKeysAreWeakAndStatesCannotRetainDelegateOrLoader() throws Exception {
        Class<?> identity = null, state = null;
        for (Class<?> type : WoggAddressCompatibility.class.getDeclaredClasses()) {
            if (type.getSimpleName().equals("IdentityReference")) identity = type;
            if (type.getSimpleName().equals("State")) state = type;
        }
        assertNotNull(identity);
        assertTrue(WeakReference.class.isAssignableFrom(identity));
        assertNotNull(state);
        for (Field field : state.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            assertFalse(Spider.class.isAssignableFrom(field.getType()));
            assertFalse(ClassLoader.class.isAssignableFrom(field.getType()));
            assertNotEquals(Field.class, field.getType());
        }
    }

    @Test public void waitingUsesCallerRemainingDeadlineAndDoesNotEnterCallback() throws Exception {
        WoGG source = new WoGG(OLD);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Future<?> first = worker.submit(() -> call(source, () -> {
            calls.incrementAndGet(); entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return "";
        }));
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            // A constant supplier must still yield a single bounded acquisition deadline.
            try { WoggAddressCompatibility.call(source, () -> 30, ACTIVE, () -> { calls.incrementAndGet(); return ""; }); fail(); }
            catch (InterruptedIOException expected) { assertTrue(expected.getMessage().contains("deadline")); }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
            assertEquals(1, calls.get());
        } finally {
            release.countDown(); first.get(2, TimeUnit.SECONDS); worker.shutdownNow();
        }
    }

    @Test public void callerCancellationWhileWaitingNeverStartsCallback() throws Exception {
        WoGG source = new WoGG(OLD);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), waiting = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean(), ran = new AtomicBoolean();
        Future<?> first = workers.submit(() -> call(source, () -> {
            entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); return "";
        }));
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            Future<Boolean> second = workers.submit(() -> {
                try {
                    WoggAddressCompatibility.call(source, 2000, () -> {
                        waiting.countDown();
                        if (cancelled.get()) throw new CancellationException("Fixture scope closed");
                    }, () -> { ran.set(true); return ""; });
                    return false;
                } catch (CancellationException expected) { return true; }
            });
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            cancelled.set(true);
            assertTrue(second.get(1, TimeUnit.SECONDS));
            assertFalse(ran.get());
        } finally { release.countDown(); first.get(2, TimeUnit.SECONDS); workers.shutdownNow(); }
    }

    @Test public void interruptedWaitPreservesInterruptFlagAndDoesNotEnterCallback() throws Exception {
        WoGG source = new WoGG(OLD);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), waiting = new CountDownLatch(1);
        AtomicBoolean retained = new AtomicBoolean(), ran = new AtomicBoolean();
        Future<?> first = worker.submit(() -> call(source, () -> {
            entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); return "";
        }));
        Thread second = new Thread(() -> {
            try {
                WoggAddressCompatibility.call(source, 2000, waiting::countDown, () -> { ran.set(true); return ""; });
            } catch (InterruptedIOException expected) { retained.set(Thread.currentThread().isInterrupted()); }
            catch (Exception unexpected) { throw new AssertionError(unexpected); }
        });
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            second.start(); assertTrue(waiting.await(1, TimeUnit.SECONDS)); second.interrupt(); second.join(1000);
            assertFalse(second.isAlive()); assertTrue(retained.get()); assertFalse(ran.get());
        } finally { release.countDown(); first.get(2, TimeUnit.SECONDS); second.interrupt(); worker.shutdownNow(); }
    }

    @Test public void serializedValidRefreshSurvivesAndDifferentInstancesProceed() throws Exception {
        WoGG source = new WoGG(OLD), separate = new WoGG(null);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), waiting = new CountDownLatch(1);
        Future<?> first = workers.submit(() -> call(source, () -> {
            source.replace(""); entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); return "failed";
        }));
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals("healthy", call(separate, () -> { separate.replace(NEW); return "healthy"; }));
            Future<String> next = workers.submit(() -> WoggAddressCompatibility.call(new BaseSpiderGuard(source),
                    2000, waiting::countDown, () -> { assertEquals(OLD, source.address()); source.replace(NEW); return "new rows"; }));
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            release.countDown(); first.get(2, TimeUnit.SECONDS);
            assertEquals("new rows", next.get(2, TimeUnit.SECONDS));
            assertEquals(NEW, source.address()); assertEquals(NEW, separate.address());
        } finally { release.countDown(); first.get(2, TimeUnit.SECONDS); workers.shutdownNow(); }
    }
}
