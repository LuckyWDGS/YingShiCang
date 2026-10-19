package com.fongmi.android.tv.browse;

import androidx.media3.common.MediaItem;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VodBrowseTest {

    @Test
    public void sourceTimeoutIsOneAggregateBudgetInsteadOfPerSource() {
        SettableFuture<List<MediaItem>> slow = SettableFuture.create();
        SettableFuture<List<MediaItem>> ready = SettableFuture.create();
        SettableFuture<List<MediaItem>> anotherSlow = SettableFuture.create();
        ready.set(Collections.singletonList((MediaItem) null));

        long started = System.nanoTime();
        List<MediaItem> result = VodBrowse.collectResults(List.of(slow, ready, anotherSlow), 200);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(1, result.size());
        assertTrue("source timeouts accumulated instead of sharing one budget", elapsedMs < 450);
        assertTrue("unfinished source was not cancelled", slow.isCancelled());
        assertTrue("unfinished source was not cancelled", anotherSlow.isCancelled());
    }

    @Test
    public void interruptionStopsAggregateAndCancelsRemainingSources() {
        SettableFuture<List<MediaItem>> pending = SettableFuture.create();
        Thread.currentThread().interrupt();
        try {
            assertTrue(VodBrowse.collectResults(List.of(pending)).isEmpty());
            assertTrue("interrupted search must cancel its source", pending.isCancelled());
            assertTrue("interruption must be preserved", Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
