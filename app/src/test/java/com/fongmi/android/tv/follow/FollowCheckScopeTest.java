package com.fongmi.android.tv.follow;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class FollowCheckScopeTest {
    @Test
    public void backgroundCheckDoesNotBlockUserThreadAndClearsAfterFailure() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<?> request = worker.submit(() -> {
                try {
                    FollowCheckScope.call(() -> {
                        assertTrue(FollowCheckScope.isActive());
                        entered.countDown();
                        assertTrue(release.await(3, TimeUnit.SECONDS));
                        throw new IllegalStateException("source failed");
                    });
                    fail("Expected source failure");
                } catch (IllegalStateException expected) {
                    assertFalse(FollowCheckScope.isActive());
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(FollowCheckScope.isActive());
            release.countDown();
            request.get(3, TimeUnit.SECONDS);
            assertFalse(worker.submit(FollowCheckScope::isActive).get(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void nestedCallKeepsOuterBackgroundScope() throws Exception {
        FollowCheckScope.call(() -> {
            assertEquals("result", FollowCheckScope.call(() -> "result"));
            assertTrue(FollowCheckScope.isActive());
            return null;
        });
        assertFalse(FollowCheckScope.isActive());
    }
}
