package com.fongmi.android.tv.cloudlogin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CloudLoginCoordinatorTest {
    @Test
    public void firstAuthenticationWinsAndCancelsTheOtherSessions() {
        FakeProvider provider = new FakeProvider();
        List<Integer> winners = new ArrayList<>();
        CloudLoginCoordinator<String, String> coordinator = new CloudLoginCoordinator<>(provider,
                listener(winners));

        assertTrue(coordinator.start());
        provider.events[1].onAuthenticated("account-1");
        provider.events[0].onAuthenticated("late-account-0");

        assertEquals(List.of(1), winners);
        assertEquals(3, provider.cancelledCount());
        assertFalse(coordinator.retry(2));
    }

    @Test
    public void retryInvalidatesLateCallbacksFromThePreviousGeneration() {
        FakeProvider provider = new FakeProvider();
        List<Integer> errors = new ArrayList<>();
        CloudLoginCoordinator<String, String> coordinator = new CloudLoginCoordinator<>(provider,
                listener(new ArrayList<>(), errors, null));

        coordinator.start();
        CloudLoginCoordinator.Events<String, String> old = provider.events[0];
        assertTrue(coordinator.retry(0));
        old.onError("late");
        provider.events[0].onError("current");

        assertEquals(List.of(0), errors);
        assertEquals(1, provider.cancelledCount());
    }

    @Test
    public void scannedStateKeepsTheCurrentSessionAliveUntilAuthentication() {
        FakeProvider provider = new FakeProvider();
        List<Integer> scanned = new ArrayList<>();
        List<Integer> expired = new ArrayList<>();
        List<Integer> errors = new ArrayList<>();
        List<Integer> winners = new ArrayList<>();
        CloudLoginCoordinator.Listener<String, String> listener = new CloudLoginCoordinator.Listener<>() {
            @Override public void onQrReady(int index, String qr, String status) { }
            @Override public void onScanned(int index, String status) { scanned.add(index); }
            @Override public void onExpired(int index, String status) { expired.add(index); }
            @Override public void onError(int index, String status) { errors.add(index); }
            @Override public void onAuthenticated(int index, String account) { winners.add(index); }
            @Override public void onClosed() { }
        };
        CloudLoginCoordinator<String, String> coordinator = new CloudLoginCoordinator<>(provider, listener);

        assertTrue(coordinator.start());
        provider.events[0].onQrReady("qr", "ready");
        provider.events[0].onScanned("waiting-confirmation");
        provider.events[0].onAuthenticated("account-0");

        assertEquals(List.of(0), scanned);
        assertTrue(expired.isEmpty());
        assertTrue(errors.isEmpty());
        assertEquals(List.of(0), winners);
        assertEquals("scanned must not replace the QR session", 3, provider.cancelledCount());
    }

    @Test
    public void closeCancelsEverySessionAndIgnoresCallbacks() {
        FakeProvider provider = new FakeProvider();
        List<Integer> winners = new ArrayList<>();
        int[] closed = {0};
        CloudLoginCoordinator<String, String> coordinator = new CloudLoginCoordinator<>(provider,
                listener(winners, null, closed));

        coordinator.start();
        coordinator.close();
        coordinator.close();
        provider.events[2].onAuthenticated("late");

        assertEquals(3, provider.cancelledCount());
        assertEquals(1, closed[0]);
        assertTrue(winners.isEmpty());
    }

    @Test
    public void closeAfterAuthenticationIsCleanupAndDoesNotReportCancellation() {
        FakeProvider provider = new FakeProvider();
        int[] closed = {0};
        CloudLoginCoordinator<String, String> coordinator = new CloudLoginCoordinator<>(provider,
                listener(new ArrayList<>(), null, closed));

        coordinator.start();
        provider.events[0].onAuthenticated("account-0");
        coordinator.close();

        assertEquals(0, closed[0]);
        assertTrue(coordinator.isClosed());
    }

    @Test
    public void overlappingSameGenerationOpenCancelsTheReplacedSession() throws Exception {
        OverlapProvider provider = new OverlapProvider();
        CloudLoginCoordinator<String, String> coordinator = new CloudLoginCoordinator<>(provider,
                listener(new ArrayList<>()));
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        AtomicBoolean retryResult = new AtomicBoolean();

        Thread starter = new Thread(() -> {
            try {
                assertTrue(coordinator.start());
            } catch (Throwable failure) {
                startFailure.set(failure);
            }
        }, "cloud-login-start");
        starter.start();

        assertTrue(provider.indexOneStarted.await(2, TimeUnit.SECONDS));
        Thread retry = new Thread(() -> retryResult.set(coordinator.retry(2)), "cloud-login-retry");
        retry.start();
        // retry(2) increments the provider generation before entering its
        // factory.  Keep it there while start() reaches the same provider so
        // both opens intentionally share that generation.
        assertTrue(provider.indexTwoFirstOpen.await(2, TimeUnit.SECONDS));
        provider.releaseIndexOne.countDown();
        assertTrue(provider.indexTwoSecondOpen.await(2, TimeUnit.SECONDS));
        provider.releaseIndexTwo.countDown();

        starter.join(2_000);
        retry.join(2_000);
        assertFalse(starter.isAlive());
        assertFalse(retry.isAlive());
        assertTrue(startFailure.get() == null);
        assertTrue(retryResult.get());
        assertEquals(2, provider.indexTwoSessions.size());
        assertEquals("the stale same-generation session must be cancelled", 1,
                provider.indexTwoCancelled());
        assertEquals(1, provider.indexTwoActive());
        coordinator.close();
    }

    @Test
    public void reentrantReplacementCancelCannotStartTheInvalidatedSession() throws Exception {
        ReentrantReplacementProvider provider = new ReentrantReplacementProvider();
        CloudLoginCoordinator<String, String> coordinator = new CloudLoginCoordinator<>(provider,
                listener(new ArrayList<>()));
        provider.owner.set(coordinator);

        Thread starter = new Thread(coordinator::start, "cloud-login-start-reentrant");
        starter.start();
        assertTrue(provider.indexOneStarted.await(2, TimeUnit.SECONDS));

        // Open provider 2 from retry() while start() is still blocked on
        // provider 1.  Its session is installed first and will be replaced
        // when start() reaches provider 2 with the same generation.
        Thread retry = new Thread(() -> provider.retryResult.set(coordinator.retry(2)),
                "cloud-login-retry-reentrant");
        retry.start();
        assertTrue(provider.firstProviderTwoStarted.await(2, TimeUnit.SECONDS));
        provider.releaseFirstProviderTwo.countDown();
        assertTrue(provider.providerTwoInstalled.await(2, TimeUnit.SECONDS));

        provider.releaseIndexOne.countDown();
        assertTrue(provider.secondProviderTwoStarted.await(2, TimeUnit.SECONDS));
        assertTrue(provider.replacedCancelEntered.await(2, TimeUnit.SECONDS));
        provider.releaseReplacementFactory.countDown();
        assertTrue(provider.replacementRetryFinished.await(2, TimeUnit.SECONDS));

        starter.join(2_000);
        retry.join(2_000);
        assertFalse(starter.isAlive());
        assertFalse(retry.isAlive());
        assertEquals("the superseded session must never be started", 0,
                provider.replacedStartCount.get());
        coordinator.close();
    }

    @Test
    public void staleFactoryResultIsCancelledWithoutHoldingCoordinatorLock() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        AtomicBoolean cancelReentered = new AtomicBoolean();
        AtomicBoolean cancelCompletedBeforeReturn = new AtomicBoolean();
        AtomicReference<CloudLoginCoordinator<String, String>> owner = new AtomicReference<>();
        CloudLoginCoordinator.Factory<String, String> factory = (index, generation) -> {
            if (index == 0) {
                factoryEntered.countDown();
                if (!releaseFactory.await(2, TimeUnit.SECONDS))
                    throw new AssertionError("factory did not release");
                return new CloudLoginCoordinator.Session<>() {
                    @Override public void start(CloudLoginCoordinator.Events<String, String> callbacks) { }

                    @Override public void cancel() {
                        Thread callback = new Thread(() -> {
                            // This blocks if cancel() is still called while
                            // open() owns the coordinator monitor.
                            owner.get().isClosed();
                            cancelReentered.set(true);
                        }, "cloud-login-cancel-callback");
                        callback.start();
                        try { callback.join(250); } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        cancelCompletedBeforeReturn.set(!callback.isAlive());
                    }
                };
            }
            return new CloudLoginCoordinator.Session<>() {
                @Override public void start(CloudLoginCoordinator.Events<String, String> callbacks) { }
                @Override public void cancel() { }
            };
        };
        CloudLoginCoordinator<String, String> coordinator = new CloudLoginCoordinator<>(factory,
                listener(new ArrayList<>()));
        owner.set(coordinator);
        Thread starter = new Thread(coordinator::start, "cloud-login-start");
        starter.start();
        assertTrue(factoryEntered.await(2, TimeUnit.SECONDS));
        coordinator.close();
        releaseFactory.countDown();
        starter.join(2_000);

        assertFalse(starter.isAlive());
        assertTrue("stale cleanup must be able to re-enter the coordinator", cancelReentered.get());
        assertTrue("stale cleanup must run outside the coordinator lock",
                cancelCompletedBeforeReturn.get());
    }

    @Test
    public void providerErrorsAndNullPayloadsStayInsideTheirProviderLane() {
        List<Integer> errors = new ArrayList<>();
        @SuppressWarnings("unchecked")
        CloudLoginCoordinator.Events<String, String>[] callbacks = new CloudLoginCoordinator.Events[3];
        CloudLoginCoordinator.Factory<String, String> factory = (index, generation) -> {
            if (index == 0) throw new AssertionError("optional provider missing");
            return new CloudLoginCoordinator.Session<>() {
                @Override public void start(CloudLoginCoordinator.Events<String, String> events) {
                    callbacks[index] = events;
                }
                @Override public void cancel() { }
            };
        };
        CloudLoginCoordinator<String, String> coordinator = new CloudLoginCoordinator<>(factory,
                listener(new ArrayList<>(), errors, null));

        assertTrue(coordinator.start());
        // The adapter contract is non-null, but the coordinator still guards
        // the runtime boundary so a malformed callback cannot reach the UI.
        callbacks[1].onQrReady(null, "malformed");
        callbacks[2].onAuthenticated(null);
        coordinator.close();
        assertEquals(List.of(0, 1, 2), errors);
    }

    @Test
    public void startFailureCancelsPartiallyOpenedSessionImmediately() {
        AtomicInteger cancelled = new AtomicInteger();
        List<Integer> errors = new ArrayList<>();
        CloudLoginCoordinator.Factory<String, String> factory = (index, generation) -> {
            if (index == 0) {
                return new CloudLoginCoordinator.Session<>() {
                    @Override
                    public void start(CloudLoginCoordinator.Events<String, String> events) {
                        throw new IllegalStateException("provider start failed");
                    }

                    @Override public void cancel() { cancelled.incrementAndGet(); }
                };
            }
            return new CloudLoginCoordinator.Session<>() {
                @Override public void start(CloudLoginCoordinator.Events<String, String> events) { }
                @Override public void cancel() { }
            };
        };
        CloudLoginCoordinator<String, String> coordinator = new CloudLoginCoordinator<>(factory,
                listener(new ArrayList<>(), errors, null));

        assertTrue(coordinator.start());
        assertEquals(1, cancelled.get());
        assertEquals(List.of(0), errors);
        coordinator.close();
        assertEquals("failed session must not be cancelled again on close", 1, cancelled.get());
    }

    @Test
    public void startFailureCleanupCanRetryWithoutReportingTheOldFailure() {
        List<Integer> errors = new ArrayList<>();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicReference<CloudLoginCoordinator<String, String>> owner = new AtomicReference<>();
        AtomicReference<CloudLoginCoordinator.Events<String, String>> failedEvents = new AtomicReference<>();
        AtomicReference<CloudLoginCoordinator.Events<String, String>> retryEvents = new AtomicReference<>();
        CloudLoginCoordinator.Factory<String, String> factory = (index, generation) -> {
            boolean fails = index == 0 && opens.getAndIncrement() == 0;
            return new CloudLoginCoordinator.Session<>() {
                @Override
                public void start(CloudLoginCoordinator.Events<String, String> events) {
                    if (fails) {
                        failedEvents.set(events);
                        throw new IllegalStateException("provider start failed");
                    }
                    if (index == 0) retryEvents.set(events);
                }

                @Override
                public void cancel() {
                    if (!fails) return;
                    cancelled.incrementAndGet();
                    assertTrue(owner.get().retry(0));
                    failedEvents.get().onError("cancelled session");
                }
            };
        };
        CloudLoginCoordinator<String, String> coordinator = new CloudLoginCoordinator<>(factory,
                listener(new ArrayList<>(), errors, null));
        owner.set(coordinator);

        assertTrue(coordinator.start());
        assertEquals(1, cancelled.get());
        assertTrue("cleanup must not report a failure against the replacement", errors.isEmpty());
        failedEvents.get().onError("late failure");
        retryEvents.get().onError("current failure");
        assertEquals(List.of(0), errors);
        coordinator.close();
    }

    @Test
    public void startFailureCleanupCanCloseWithoutReportingAnError() {
        List<Integer> errors = new ArrayList<>();
        int[] closed = {0};
        AtomicInteger opened = new AtomicInteger();
        AtomicReference<CloudLoginCoordinator<String, String>> owner = new AtomicReference<>();
        CloudLoginCoordinator.Factory<String, String> factory = (index, generation) -> {
            opened.incrementAndGet();
            return new CloudLoginCoordinator.Session<>() {
                @Override
                public void start(CloudLoginCoordinator.Events<String, String> events) {
                    throw new IllegalStateException("provider start failed");
                }

                @Override public void cancel() { owner.get().close(); }
            };
        };
        CloudLoginCoordinator<String, String> coordinator = new CloudLoginCoordinator<>(factory,
                listener(new ArrayList<>(), errors, closed));
        owner.set(coordinator);

        assertTrue(coordinator.start());
        assertEquals(1, opened.get());
        assertEquals(1, closed[0]);
        assertTrue(coordinator.isClosed());
        assertTrue(errors.isEmpty());
    }

    private static CloudLoginCoordinator.Listener<String, String> listener(List<Integer> winners) {
        return listener(winners, null, null);
    }

    private static CloudLoginCoordinator.Listener<String, String> listener(List<Integer> winners,
            List<Integer> errors, int[] closed) {
        return new CloudLoginCoordinator.Listener<>() {
            @Override public void onQrReady(int index, String qr, String status) { }
            @Override public void onScanned(int index, String status) { }
            @Override public void onExpired(int index, String status) { }
            @Override public void onError(int index, String status) { if (errors != null) errors.add(index); }
            @Override public void onAuthenticated(int index, String account) { winners.add(index); }
            @Override public void onClosed() { if (closed != null) closed[0]++; }
        };
    }

    private static final class FakeProvider implements CloudLoginCoordinator.Factory<String, String> {
        final CloudLoginCoordinator.Events<String, String>[] events = new CloudLoginCoordinator.Events[3];
        final List<Session> sessions = new ArrayList<>();

        @Override public CloudLoginCoordinator.Session<String, String> open(int index, int generation) {
            Session session = new Session();
            sessions.add(session);
            return new CloudLoginCoordinator.Session<>() {
                @Override public void start(CloudLoginCoordinator.Events<String, String> callbacks) {
                    events[index] = callbacks;
                }
                @Override public void cancel() { session.cancelled = true; }
            };
        }

        int cancelledCount() {
            int count = 0;
            for (Session session : sessions) if (session.cancelled) count++;
            return count;
        }

        private static final class Session { boolean cancelled; }
    }

    private static final class OverlapProvider implements CloudLoginCoordinator.Factory<String, String> {
        final CountDownLatch indexOneStarted = new CountDownLatch(1);
        final CountDownLatch releaseIndexOne = new CountDownLatch(1);
        final CountDownLatch indexTwoFirstOpen = new CountDownLatch(1);
        final CountDownLatch indexTwoSecondOpen = new CountDownLatch(1);
        final CountDownLatch releaseIndexTwo = new CountDownLatch(1);
        final AtomicInteger indexTwoOpens = new AtomicInteger();
        final List<OverlapSession> indexTwoSessions = new ArrayList<>();

        @Override public CloudLoginCoordinator.Session<String, String> open(int index, int generation)
                throws Exception {
            if (index == 1) {
                indexOneStarted.countDown();
                if (!releaseIndexOne.await(2, TimeUnit.SECONDS))
                    throw new AssertionError("index one did not release");
            }
            OverlapSession session = new OverlapSession();
            if (index == 2) {
                int call = indexTwoOpens.incrementAndGet();
                synchronized (indexTwoSessions) {
                    indexTwoSessions.add(session);
                }
                if (call == 1) indexTwoFirstOpen.countDown();
                else if (call == 2) indexTwoSecondOpen.countDown();
                if (!releaseIndexTwo.await(2, TimeUnit.SECONDS))
                    throw new AssertionError("index two did not release");
            }
            return session;
        }

        int indexTwoCancelled() {
            int count = 0;
            synchronized (indexTwoSessions) {
                for (OverlapSession session : indexTwoSessions) if (session.cancelled.get()) count++;
            }
            return count;
        }

        int indexTwoActive() {
            int count = 0;
            synchronized (indexTwoSessions) {
                for (OverlapSession session : indexTwoSessions) if (!session.cancelled.get()) count++;
            }
            return count;
        }

        private static final class OverlapSession implements CloudLoginCoordinator.Session<String, String> {
            final AtomicBoolean cancelled = new AtomicBoolean();

            @Override public void start(CloudLoginCoordinator.Events<String, String> callbacks) { }

            @Override public void cancel() { cancelled.set(true); }
        }
    }

    private static final class ReentrantReplacementProvider implements CloudLoginCoordinator.Factory<String, String> {
        final CountDownLatch indexOneStarted = new CountDownLatch(1);
        final CountDownLatch releaseIndexOne = new CountDownLatch(1);
        final CountDownLatch firstProviderTwoStarted = new CountDownLatch(1);
        final CountDownLatch secondProviderTwoStarted = new CountDownLatch(1);
        final CountDownLatch providerTwoInstalled = new CountDownLatch(1);
        final CountDownLatch releaseFirstProviderTwo = new CountDownLatch(1);
        final CountDownLatch releaseReplacementFactory = new CountDownLatch(1);
        final CountDownLatch replacedCancelEntered = new CountDownLatch(1);
        final CountDownLatch replacementRetryFinished = new CountDownLatch(1);
        final AtomicReference<CloudLoginCoordinator<String, String>> owner = new AtomicReference<>();
        final AtomicBoolean retryResult = new AtomicBoolean();
        final AtomicInteger providerTwoOpens = new AtomicInteger();
        final AtomicInteger replacedStartCount = new AtomicInteger();

        @Override
        public CloudLoginCoordinator.Session<String, String> open(int index, int generation) throws Exception {
            if (index == 1) {
                indexOneStarted.countDown();
                if (!releaseIndexOne.await(2, TimeUnit.SECONDS))
                    throw new AssertionError("provider one did not release");
            }
            if (index != 2) return idleSession();
            int call = providerTwoOpens.incrementAndGet();
            if (call == 1) {
                firstProviderTwoStarted.countDown();
                if (!releaseFirstProviderTwo.await(2, TimeUnit.SECONDS))
                    throw new AssertionError("first provider two open did not release");
                return new ReentrantSession(true);
            }
            if (call == 2) {
                secondProviderTwoStarted.countDown();
                return new ReentrantSession(false);
            }
            if (!releaseReplacementFactory.await(2, TimeUnit.SECONDS))
                throw new AssertionError("replacement factory did not release");
            return idleSession();
        }

        private CloudLoginCoordinator.Session<String, String> idleSession() {
            return new CloudLoginCoordinator.Session<>() {
                @Override public void start(CloudLoginCoordinator.Events<String, String> callbacks) { }
                @Override public void cancel() { }
            };
        }

        private final class ReentrantSession implements CloudLoginCoordinator.Session<String, String> {
            private final boolean replaced;

            private ReentrantSession(boolean replaced) { this.replaced = replaced; }

            @Override
            public void start(CloudLoginCoordinator.Events<String, String> callbacks) {
                if (replaced) providerTwoInstalled.countDown();
                else replacedStartCount.incrementAndGet();
            }

            @Override
            public void cancel() {
                if (!replaced) return;
                replacedCancelEntered.countDown();
                owner.get().retry(2);
                replacementRetryFinished.countDown();
            }
        }
    }
}
