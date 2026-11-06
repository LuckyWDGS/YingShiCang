package com.fongmi.android.tv.resource;

import com.fongmi.android.tv.bean.Site;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SourceIdentityTest {
    private Site source(String api, String ext) {
        return new Site() {
            @Override public String getKey() { return "same-key"; }
            @Override public String getApi() { return api; }
            @Override public String getExt() { return ext; }
        };
    }
    @Test public void configurationsAndEndpointChangesHaveIndependentRests() {
        SourceBench bench = new SourceBench();
        Site old = source("api-a", "secret-a"), changed = source("api-b", "secret-a");
        String id = SourceIdentity.of("config-a", old);
        for (int i = 0; i < 3; i++) bench.failed(id);
        assertTrue(bench.resting(id));
        assertFalse(bench.resting(SourceIdentity.of("config-b", old)));
        assertFalse(bench.resting(SourceIdentity.of("config-a", changed)));
        assertNotEquals(id, SourceIdentity.of("config-a", source("api-a", "secret-b")));
        assertFalse(id.contains("secret"));
        assertEquals(id, SourceIdentity.of("config-a", source("api-a", "secret-a")));
    }

    @Test public void samePageBecomesEligibleAfterRestExpires() {
        long[] now = {1000};
        SourceBench bench = new SourceBench(() -> now[0], "", value -> {}, message -> {});
        for (int i = 0; i < 3; i++) bench.failed("a");
        assertEquals(List.of("b"), bench.active(List.of("a", "b"), x -> x));
        now[0] += SourceBench.REST_MS;
        assertEquals(List.of("a", "b"), bench.active(List.of("a", "b"), x -> x));
        assertEquals(0, bench.remaining("a"));
    }
}
