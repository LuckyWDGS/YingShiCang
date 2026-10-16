package com.fongmi.android.tv.discovery;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class AiDramaRunnerTest {
    @Test public void metadataRefreshRetainsCardsOnlyForTheSameEffectiveQuery() {
        var full = new AiDramaCatalog(List.of(new AiDramaCatalog.Category("comic", "AI漫剧", List.of(
                new AiDramaCatalog.Group("topic", "题材", "", List.of(new AiDramaCatalog.Option("全部", ""))),
                new AiDramaCatalog.Group("sort", "排序", "", List.of(new AiDramaCatalog.Option("默认", ""),
                        new AiDramaCatalog.Option("最新", "online_time")))))));
        var provisional = new AiDramaCatalog.State(catalog("comic"), "s");
        var restored = new AiDramaCatalog.State(full, "s");
        assertTrue(AiDramaRunner.sameQuery(provisional, restored));
        restored.select("sort", "online_time");
        assertFalse(AiDramaRunner.sameQuery(provisional, restored));
        assertFalse(AiDramaRunner.sameQuery(new AiDramaCatalog.State(catalog("ai"), "s"), provisional));
    }
    @Test public void nativeLatestDoesNotDependOnWebTopicsButUnknownChoicesStillDo() {
        var nativeCatalog = new AiDramaCatalog(List.of(new AiDramaCatalog.Category("ai", "AI短剧", List.of(
                new AiDramaCatalog.Group("sort", "排序", "", List.of(new AiDramaCatalog.Option("默认", ""),
                        new AiDramaCatalog.Option("最新", "online_time")))))));
        String saved = "{\"scope\":\"s\",\"category\":\"ai\",\"extend\":{\"topic\":\"\",\"sort\":\"online_time\"}}";
        assertTrue(AiDramaRunner.nativeStateEligible(saved, "s", nativeCatalog));
        assertFalse(AiDramaRunner.nativeStateEligible(saved, "s", catalog("ai")));
        assertFalse(AiDramaRunner.nativeStateEligible(saved.replace("\"topic\":\"\"", "\"topic\":\"fantasy\""), "s", nativeCatalog));
        assertFalse(AiDramaRunner.nativeStateEligible(saved.replace("online_time", "unverified"), "s", nativeCatalog));
        assertTrue(AiDramaRunner.nativeStateEligible(saved, "different-config", catalog("ai")));
        assertFalse(AiDramaRunner.nativeStateEligible("broken", "s", nativeCatalog));
    }
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

    @Test public void cachedMetadataRestoresAnIndependentStateBeforeAnySourceWork() {
        var metadata = new AiDramaCatalog(List.of(
                new AiDramaCatalog.Category("comic", "AI漫剧", List.of()),
                new AiDramaCatalog.Category("ai", "AI短剧", List.of())));
        var saved = new AiDramaCatalog.State(metadata, "signature"); saved.select(AiDramaCatalog.CATEGORY, "ai");
        var cache = new AiDramaRunner.MetadataCache(); cache.get(1, "signature"); cache.put(1, "signature", metadata);
        var first = cache.restore(1, "signature", saved.serialize());
        assertSame(metadata, first.catalog()); assertEquals("ai", first.state().categoryId());
        assertEquals(2, first.state().groups().get(0).options.size());
        first.state().select(AiDramaCatalog.CATEGORY, "comic");
        assertEquals("ai", cache.restore(1, "signature", saved.serialize()).state().categoryId());
        assertNull(cache.restore(1, "replacement", saved.serialize()));
        assertNull(cache.restore(2, "signature", saved.serialize()));
    }

    @Test public void metadataCacheIsBoundedAndRecentlyUsedSuccessfulEntriesStayAvailable() {
        var cache = new AiDramaRunner.MetadataCache(); cache.get(1, "0");
        for (int i = 0; i < 32; i++) cache.put(1, Integer.toString(i), catalog(Integer.toString(i)));
        assertNotNull(cache.get(1, "0")); cache.put(1, "32", catalog("32"));
        assertNotNull(cache.get(1, "0")); assertNull(cache.get(1, "1")); assertNotNull(cache.get(1, "32"));
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
        pages.accept(List.of("same-id"), 4, Map.of("source\0same-id", "duplicate")); assertTrue(pages.more());
        pages.accept(List.of("later-id"), 4, Map.of("source\0later-id", "later"));
        assertEquals(List.of("one", "later"), pages.items()); assertFalse(pages.more());
        pages.reset(); assertEquals(1, pages.next()); assertTrue(pages.more()); assertTrue(pages.items().isEmpty());
    }

    @Test public void intermediateEmptyOrRepeatedPagesCanReachLaterNewNativeContent() {
        var pages = new AiDramaRunner.Pages<String>();
        pages.accept(List.of("a"), 9999, Map.of("source\0a", "A"));
        pages.accept(List.of(), 9999, Map.of()); assertTrue(pages.more());
        pages.accept(List.of("a"), 9999, Map.of("source\0a", "duplicate")); assertTrue(pages.more());
        pages.accept(List.of("b"), 9999, Map.of("source\0b", "B"));
        assertEquals(5, pages.next()); assertTrue(pages.more()); assertEquals(List.of("A", "B"), pages.items());
        pages.accept(List.of(), 9999, Map.of()); pages.accept(List.of("b"), 9999, Map.of());
        assertTrue(pages.more()); pages.accept(List.of(), 9999, Map.of()); assertFalse(pages.more());
        assertEquals(List.of("A", "B"), pages.items());
    }

    @Test public void realPageCountAndMaximumTraversalRemainHardBounds() {
        var terminal = new AiDramaRunner.Pages<String>(); terminal.accept(List.of(), 1, Map.of()); assertFalse(terminal.more());
        var bounded = new AiDramaRunner.Pages<String>();
        for (int page = 1; page <= 500; page++) bounded.accept(List.of(Integer.toString(page)), 9999, Map.of());
        assertEquals(501, bounded.next()); assertFalse(bounded.more());
        bounded.accept(List.of("501"), 9999, Map.of("source\0extra", "ignored"));
        assertEquals(501, bounded.next()); assertTrue(bounded.items().isEmpty());
    }

    @Test public void speculativeFirstPageIsAllowedOnlyForEmptyOrDefaultSavedState() {
        assertTrue(AiDramaRunner.defaultStateEligible(null));
        assertTrue(AiDramaRunner.defaultStateEligible(""));
        assertTrue(AiDramaRunner.defaultStateEligible("{\"scope\":\"s\",\"category\":\"comic\",\"extend\":{\"topic\":\"\",\"sort\":\"\"}}"));
        assertTrue(AiDramaRunner.defaultStateEligible("{\"scope\":\"s\",\"category\":\"ai\",\"extend\":{}}"));
        assertFalse(AiDramaRunner.defaultStateEligible("{\"scope\":\"s\",\"category\":\"movie\",\"extend\":{}}"));
        assertFalse(AiDramaRunner.defaultStateEligible("{\"scope\":\"s\",\"category\":\"comic\",\"extend\":{\"topic\":\"fantasy\"}}"));
        assertFalse(AiDramaRunner.defaultStateEligible("not-json"));
    }
}
