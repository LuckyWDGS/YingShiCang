package com.fongmi.android.tv.discovery;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

    @Test public void partialMetadataCannotClearSavedNativeTopicAcrossRetries() {
        var full = new AiDramaCatalog(List.of(new AiDramaCatalog.Category("comic", "AI漫剧", List.of(
                new AiDramaCatalog.Group("topic", "题材", "", List.of(
                        new AiDramaCatalog.Option("全部", ""), new AiDramaCatalog.Option("求生", "native:379")))))));
        var selected = new AiDramaCatalog.State(full, "source"); selected.select("topic", "native:379");
        String saved = selected.serialize();
        var partial = new AiDramaCatalog(catalog("comic").categories(), false);
        assertFalse(AiDramaRunner.selectionRestorable(partial, "source", saved));
        assertFalse(AiDramaRunner.selectionRestorable(partial, "source", saved));
        assertEquals("native:379", selected.value("topic"));
        assertEquals(saved, selected.serialize());
        assertTrue(AiDramaRunner.selectionRestorable(full, "source", saved));
        var recovered = new AiDramaCatalog.State(full, "source"); recovered.restore(saved);
        assertEquals("native:379", recovered.value("topic"));
        var removed = new AiDramaCatalog(partial.categories());
        assertTrue(AiDramaRunner.selectionRestorable(removed, "source", saved));
        var removedState = new AiDramaCatalog.State(removed, "source"); removedState.restore(saved);
        assertEquals("", removedState.value("topic"));
    }

    @Test public void partialMetadataDoesNotBlockForeignScopeOrDefaultSelection() {
        var partial = new AiDramaCatalog(catalog("comic").categories(), false);
        String saved = "{\"scope\":\"old-source\",\"category\":\"comic\",\"extend\":{\"topic\":\"native:379\"}}";
        assertTrue(AiDramaRunner.selectionRestorable(partial, "new-source", saved));
        assertTrue(AiDramaRunner.selectionRestorable(partial, "old-source", saved.replace("native:379", "")));
        assertTrue(AiDramaRunner.selectionRestorable(partial, "old-source", "broken"));
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

    @Test public void metadataExpiresAtThirtyMinutesAndHitsDoNotRenewIt() {
        var clock = new AtomicLong(-100);
        var cache = new AiDramaRunner.MetadataCache(clock::get);
        var metadata = catalog("comic");
        cache.get(1, "signature"); cache.put(1, "signature", metadata);
        long fetchedAt = clock.get();
        clock.set(fetchedAt + TimeUnit.MINUTES.toNanos(29));
        assertSame(metadata, cache.get(1, "signature"));
        clock.set(fetchedAt + TimeUnit.MINUTES.toNanos(30) - 1);
        assertSame(metadata, cache.get(1, "signature"));
        clock.incrementAndGet();
        assertNull(cache.get(1, "signature"));
    }

    @Test public void failedOrEmptyRefreshCannotRenewSuccessfulMetadata() {
        var clock = new AtomicLong();
        var cache = new AiDramaRunner.MetadataCache(clock::get);
        var metadata = catalog("comic");
        cache.get(1, "signature"); cache.put(1, "signature", metadata);
        clock.set(TimeUnit.MINUTES.toNanos(29));
        cache.put(1, "signature", null);
        cache.put(1, "signature", new AiDramaCatalog(List.of()));
        assertSame(metadata, cache.get(1, "signature"));
        clock.set(TimeUnit.MINUTES.toNanos(30));
        assertNull(cache.get(1, "signature"));
        cache.put(1, "signature", null);
        assertNull(cache.get(1, "signature"));
    }

    @Test public void expiredMetadataCannotRestoreStateUntilNewSuccess() {
        var clock = new AtomicLong();
        var cache = new AiDramaRunner.MetadataCache(clock::get);
        var original = catalog("comic");
        cache.get(1, "signature"); cache.put(1, "signature", original);
        String saved = new AiDramaCatalog.State(original, "signature").serialize();
        clock.set(TimeUnit.MINUTES.toNanos(30));
        assertNull(cache.restore(1, "signature", saved));
        var fresh = new AiDramaCatalog(List.of(
                new AiDramaCatalog.Category("comic", "AI漫剧", List.of()),
                new AiDramaCatalog.Category("ai", "AI短剧", List.of())));
        cache.put(1, "signature", fresh);
        var restored = cache.restore(1, "signature", saved);
        assertSame(fresh, restored.catalog());
        assertEquals("comic", restored.state().categoryId());
        assertEquals(2, restored.state().groups().get(0).options.size());
        clock.set(TimeUnit.MINUTES.toNanos(60) - 1);
        assertSame(fresh, cache.get(1, "signature"));
        clock.incrementAndGet();
        assertNull(cache.restore(1, "signature", saved));
    }

    @Test public void activeSessionKeepsItsChoicesWhileNewSessionRequiresFreshMetadata() {
        var clock = new AtomicLong();
        var cache = new AiDramaRunner.MetadataCache(clock::get);
        var original = catalog("comic");
        cache.get(1, "signature"); cache.put(1, "signature", original);
        assertSame(original, AiDramaRunner.metadataForSession(null, cache, 1, "signature"));
        clock.set(TimeUnit.MINUTES.toNanos(30));
        assertSame(original, AiDramaRunner.metadataForSession(original, cache, 1, "signature"));
        assertNull(AiDramaRunner.metadataForSession(null, cache, 1, "signature"));
        var fresh = catalog("ai"); cache.put(1, "signature", fresh);
        assertSame(original, AiDramaRunner.metadataForSession(original, cache, 1, "signature"));
        assertSame(fresh, AiDramaRunner.metadataForSession(null, cache, 1, "signature"));
    }

    @Test public void partialTopicsStayUsableButCannotBecomeOrRenewSuccessfulMetadata() {
        var clock = new AtomicLong();
        var cache = new AiDramaRunner.MetadataCache(clock::get);
        var full = catalog("comic");
        var partial = new AiDramaCatalog(full.categories(), false);
        assertTrue(full.complete()); assertFalse(partial.complete());
        assertEquals(full.categories(), partial.categories());
        assertEquals("comic", new AiDramaCatalog.State(partial, "signature").categoryId());
        assertNull(AiDramaRunner.retainedMetadata(partial));
        assertSame(full, AiDramaRunner.retainedMetadata(full));
        cache.get(1, "signature"); cache.put(1, "signature", partial);
        assertNull(cache.get(1, "signature"));
        assertNull(AiDramaRunner.metadataForSession(partial, cache, 1, "signature"));
        cache.put(1, "signature", full);
        clock.set(TimeUnit.MINUTES.toNanos(29));
        cache.put(1, "signature", partial);
        assertSame(full, cache.get(1, "signature"));
        assertSame(full, AiDramaRunner.metadataForSession(partial, cache, 1, "signature"));
        clock.set(TimeUnit.MINUTES.toNanos(30));
        assertNull(cache.get(1, "signature"));
        assertNull(AiDramaRunner.metadataForSession(partial, cache, 1, "signature"));
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
