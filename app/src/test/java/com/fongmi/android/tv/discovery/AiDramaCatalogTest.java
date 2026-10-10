package com.fongmi.android.tv.discovery;

import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class AiDramaCatalogTest {
    private static AiDramaCatalog.Group topic(String initial, AiDramaCatalog.Option... options) {
        return new AiDramaCatalog.Group("topic", "题材", initial, List.of(options));
    }
    private static AiDramaCatalog.Option option(String label, String value) { return new AiDramaCatalog.Option(label, value); }
    private static AiDramaCatalog catalog() {
        var topic = topic("", option("全部", ""), option("爱情", "romance"), option("玄幻", "fantasy"));
        return new AiDramaCatalog(List.of(new AiDramaCatalog.Category("comic", "AI漫剧", List.of(topic)),
                new AiDramaCatalog.Category("ai", "AI短剧", List.of(topic))));
    }

    @Test public void nativeTypeAndTopicAreIndependentFromMovieTvGroupsAndPreserveRequestValues() {
        var catalog = catalog(); var state = new AiDramaCatalog.State(catalog, "source-scope");
        assertEquals(List.of("类型", "题材"), state.groups().stream().map(group -> group.label).toList());
        assertEquals(List.of("AI漫剧", "AI短剧"), state.groups().get(0).options.stream().map(option -> option.label).toList());
        assertEquals("comic", state.categoryId()); assertEquals(Map.of("topic", ""), state.extend());
        assertTrue(state.select("topic", "romance")); assertEquals(Map.of("topic", "romance"), state.extend());
        assertFalse(state.extend().containsKey(AiDramaCatalog.CATEGORY));
        assertFalse(state.select("sort", "popular")); assertFalse(state.select("topic", "爱情"));
        assertFalse(state.select("genre", "喜剧"));
    }

    @Test public void validNativeDefaultsAndExactWhitespaceValuesAreRetained() {
        var topic = topic(" native ", option("默认", " native "), option("其他", "other"));
        var state = new AiDramaCatalog.State(new AiDramaCatalog(List.of(new AiDramaCatalog.Category(" comic ", "AI漫剧", List.of(topic)))), "scope");
        assertEquals(" comic ", state.categoryId()); assertEquals(" native ", state.extend().get("topic"));
        assertFalse(state.select("topic", "native"));
    }

    @Test public void changingTypeKeepsOnlySemanticallyMatchingDeclaredFacetChoices() {
        var first = new AiDramaCatalog.Category("comic", "AI漫剧", List.of(topic("", option("全部", ""), option("爱情", "romance")),
                new AiDramaCatalog.Group("quality", "品质", "all", List.of(option("全部", "all"), option("高清", "hd")))));
        var second = new AiDramaCatalog.Category("ai", "AI短剧", List.of(topic("", option("全部", ""), option("爱情", "romance"))));
        var third = new AiDramaCatalog.Category("changed", "其他", List.of(topic("", option("全部", ""), option("名称已变", "romance"))));
        var state = new AiDramaCatalog.State(new AiDramaCatalog(List.of(first, second, third)), "scope");
        state.select("topic", "romance"); state.select("quality", "hd");
        assertTrue(state.select(AiDramaCatalog.CATEGORY, "ai"));
        assertEquals(Map.of("topic", "romance"), state.extend());
        assertTrue(state.select(AiDramaCatalog.CATEGORY, "changed")); assertEquals(Map.of("topic", ""), state.extend());
        assertFalse(state.select(AiDramaCatalog.CATEGORY, "made-up"));
    }

    @Test public void restorationRequiresExactSourceScopeAndCurrentMetadataChoices() {
        var source = new AiDramaCatalog.State(catalog(), "scope1");
        source.select(AiDramaCatalog.CATEGORY, "ai"); source.select("topic", "romance");
        String saved = source.serialize();
        var restored = new AiDramaCatalog.State(catalog(), "scope1"); restored.restore(saved);
        assertEquals("ai", restored.categoryId()); assertEquals("romance", restored.value("topic"));
        var replaced = new AiDramaCatalog.State(catalog(), "scope2"); replaced.restore(saved);
        assertEquals("comic", replaced.categoryId()); assertEquals("", replaced.value("topic"));
        var damaged = new AiDramaCatalog.State(catalog(), "scope1");
        damaged.restore("{\"scope\":\"scope1\",\"category\":\"ai\",\"extend\":{\"topic\":\"removed\",\"sort\":\"hot\"}}");
        assertEquals("ai", damaged.categoryId()); assertEquals(Map.of("topic", ""), damaged.extend());
        assertFalse(JsonParser.parseString(damaged.serialize()).getAsJsonObject().toString().contains("sort"));
    }

    @Test public void invalidOrNonStringSavedChoicesCannotForgeNativeFilters() {
        for (String json : List.of("not json", "[]", "{\"scope\":\"scope1\",\"category\":12}",
                "{\"scope\":\"scope1\",\"category\":\"ai\",\"extend\":{\"topic\":123}}")) {
            var state = new AiDramaCatalog.State(catalog(), "scope1"); state.restore(json);
            assertEquals("", state.value("topic")); assertEquals(1, state.extend().size());
        }
    }

    @Test public void snapshotsAndCatalogListsCannotBeMutatedByPendingRequests() {
        var options = new ArrayList<>(List.of(option("全部", ""), option("爱情", "romance")));
        var group = new AiDramaCatalog.Group("topic", "题材", "", options); options.clear();
        assertEquals(2, group.options.size());
        var state = new AiDramaCatalog.State(catalog(), "scope"); var pending = state.snapshot();
        state.select("topic", "romance"); assertEquals("", pending.value("topic"));
        try { pending.extend().put("topic", "forged"); fail(); } catch (UnsupportedOperationException expected) { }
        try { group.options.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void duplicateAndUnsupportedMetadataDoesNotCreatePhantomOptions() {
        var empty = new AiDramaCatalog.Group("empty", "无选项", "", List.of());
        var topic = topic("missing", option("全部", ""), option("重复", ""), option("爱情", "romance"));
        var catalog = new AiDramaCatalog(List.of(new AiDramaCatalog.Category("", "无ID", List.of(topic)),
                new AiDramaCatalog.Category("comic", "AI漫剧", List.of(empty, topic)),
                new AiDramaCatalog.Category("comic", "重复类别", List.of())));
        var state = new AiDramaCatalog.State(catalog, "scope");
        assertEquals(1, catalog.categories().size()); assertEquals(2, state.groups().size());
        assertEquals("", state.value("topic")); assertEquals(2, state.groups().get(1).options.size());
        assertTrue(new AiDramaCatalog(List.of()).groups(state).isEmpty());
    }
}
