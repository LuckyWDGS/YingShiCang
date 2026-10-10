package com.fongmi.android.tv.discovery;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class AiDramaRunnerTest {
    private static AiDramaCatalog catalog(String id) {
        return new AiDramaCatalog(List.of(new AiDramaCatalog.Category(id, "AI漫剧", List.of())));
    }

    @Test public void apiCandidateGateUsesAuditedSymbolsRatherThanDisplayNameOrSubstring() {
        assertTrue(AiDramaRunner.supportedApi("csp_AiDj")); assertTrue(AiDramaRunner.supportedApi("csp_AiDjGuard"));
        for (String api : List.of("csp_OtherAiDj", "csp_AiDjGuardExtra", "AI漫剧", "https://example.invalid/AiDj.js", ""))
            assertFalse(AiDramaRunner.supportedApi(api));
        assertFalse(AiDramaRunner.supportedApi(null));
    }

    @Test public void failedOrEmptyMetadataCannotPreventSuccessfulRetry() {
        var cache = new AiDramaRunner.MetadataCache();
        assertNull(cache.get(1, "original-signature"));
        cache.put(1, "original-signature", null); cache.put(1, "original-signature", new AiDramaCatalog(List.of()));
        assertNull(cache.get(1, "original-signature"));
        var valid = catalog("comic"); cache.put(1, "original-signature", valid);
        assertSame(valid, cache.get(1, "original-signature"));
        assertNull(cache.get(1, "replacement-signature"));
    }

    @Test public void lateOldMetadataCannotEvictNewConfigurationAndClearForcesFreshFetch() {
        var cache = new AiDramaRunner.MetadataCache();
        var old = catalog("old"); var fresh = catalog("new");
        cache.get(1, "signature"); cache.put(1, "signature", old);
        assertNull(cache.get(2, "signature")); cache.put(2, "signature", fresh);
        assertNull(cache.get(1, "signature")); cache.put(1, "signature", old);
        assertSame(fresh, cache.get(2, "signature"));
        cache.clear(); assertNull(cache.get(2, "signature"));
    }

    @Test public void failedPageRetainsCursorAndGoodPagesRetryAddsOnlyNativeNewIdsInSourceOrder() {
        var pages = new AiDramaRunner.Pages<String>();
        var first = new LinkedHashMap<String, String>(); first.put("source\0a", "A"); first.put("source\0b", "B");
        pages.accept(List.of("a", "b"), 3, first);
        assertEquals(List.of("A", "B"), pages.items());
        assertEquals(2, pages.next()); var before = pages.items();
        // A failed request never calls accept: retry must request this same page and retain prior cards.
        assertEquals(2, pages.next()); assertEquals(before, pages.items());
        var accepted = new LinkedHashMap<String, String>(); accepted.put("source\0b", "changed"); accepted.put("source\0c", "C");
        pages.accept(List.of("b", "c"), 3, accepted);
        assertEquals(3, pages.next()); assertEquals(3, pages.items().size());
        assertEquals("C", pages.items().get(2)); assertFalse(pages.items().contains("changed"));
        pages.accept(List.of("c", "d"), 3, Map.of("source\0d", "D")); assertFalse(pages.more());
    }

    @Test public void filteredEmptyPageUsesRawIdsAndScopeChangeResetsPagination() {
        var pages = new AiDramaRunner.Pages<String>();
        pages.accept(List.of("restricted-or-invalid"), 4, Map.of());
        assertTrue(pages.more()); assertEquals(2, pages.next()); assertTrue(pages.items().isEmpty());
        pages.accept(List.of("same-id"), 4, Map.of("source\0same-id", "one"));
        pages.accept(List.of("same-id"), 4, Map.of("source\0same-id", "duplicate")); assertFalse(pages.more());
        pages.reset(); assertEquals(1, pages.next()); assertTrue(pages.more()); assertTrue(pages.items().isEmpty());
    }
}
