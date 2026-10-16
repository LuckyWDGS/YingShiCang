package com.fongmi.android.tv.api.config;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class VodConfigFetchTest {
    private static final String URL = "https://config.test/current";
    private static final String JSON = "{\"sites\":[{\"key\":\"chosen\",\"name\":\"源里的真实名称\",\"api\":\"csp_Test\"}]}";
    private String cache(long now) throws Exception { return VodConfigFetch.snapshot(URL, VodConfigFetch.parse(JSON), now); }
    private VodConfigFetch.Transport offline() { return () -> { throw new IOException("offline"); }; }

    @Test public void retryReturnsFreshName() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var loaded = VodConfigFetch.load(URL, null, 100, () -> {
            if (calls.incrementAndGet() == 1) throw new IOException("connection closed");
            return JSON;
        });
        assertFalse(loaded.cached()); assertEquals(2, calls.get());
        assertEquals("源里的真实名称", loaded.object().getAsJsonArray("sites").get(0).getAsJsonObject().get("name").getAsString());
    }
    @Test public void htmlRetriesButNeverBecomesConfiguration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IOException.class, () -> VodConfigFetch.load(URL,null,100,() -> { calls.incrementAndGet(); return "<html>unavailable</html>"; }));
        assertEquals(2,calls.get());
    }
    @Test public void offlineUsesExactRecentSuccessfulConfiguration() throws Exception {
        assertTrue(VodConfigFetch.load(URL,cache(100),200,offline()).cached());
        assertThrows(IOException.class, () -> VodConfigFetch.load(URL+"-other",cache(100),200,offline()));
    }
    @Test public void expiredFutureAndBrokenCachesAreRejected() throws Exception {
        assertThrows(IOException.class, () -> VodConfigFetch.load(URL,cache(100),100+VodConfigFetch.CACHE_TTL_MS,offline()));
        assertThrows(IOException.class, () -> VodConfigFetch.load(URL,cache(100),99,offline()));
        assertThrows(IOException.class, () -> VodConfigFetch.load(URL,"{}",100,offline()));
    }
    @Test public void explicitProviderResponseOrEmptySitesCannotRestoreStaleContent() throws Exception {
        for (String raw : new String[]{"{\"msg\":\"expired\"}","{\"sites\":[]}","{\"sites\":[null]}"}) {
            AtomicInteger calls=new AtomicInteger();
            assertThrows(IllegalArgumentException.class,()->VodConfigFetch.load(URL,cache(100),200,()->{calls.incrementAndGet();return raw;}));
            assertEquals(1,calls.get());
        }
    }
    @Test public void freshSuccessAlwaysWinsOverCache() throws Exception {
        var loaded=VodConfigFetch.load(URL,cache(100),200,()->JSON.replace("源里的真实名称","新的名称"));
        assertFalse(loaded.cached());assertTrue(loaded.object().toString().contains("新的名称"));
    }
    @Test public void cancellationNeverRetriesOrRestoresCache() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        assertThrows(IOException.class,()->VodConfigFetch.load(URL,cache(100),200,()->{calls.incrementAndGet();throw new IOException("Canceled");}));
        assertEquals(1,calls.get());
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class,()->VodConfigFetch.load(URL,null,100,()->JSON));
        } finally { Thread.interrupted(); }
    }
    @Test public void depotAndCommentedTvBoxJsonRemainSupported() throws Exception {
        assertNotNull(VodConfigFetch.parse("{\"urls\":[{\"url\":\"https://test/config\"}]}"));
        assertNotNull(VodConfigFetch.parse(JSON.replace("{\"sites", "{\n// TVBox\n\"sites")));
        assertEquals("",VodConfigFetch.snapshot(URL,VodConfigFetch.parse("{\"urls\":[{}]}"),100));
    }
}
