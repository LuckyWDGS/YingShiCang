package com.fongmi.android.tv.discovery;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SourceDiscoveryOrderTest {
    private record Item(String title, String year, String score) {}
    private static final SourceDiscoveryOrder.Fields<Item> FIELDS = new SourceDiscoveryOrder.Fields<>() {
        @Override public String title(Item item) { return item.title(); }
        @Override public String year(Item item) { return item.year(); }
        @Override public String score(Item item) { return item.score(); }
    };
    private static Item item(String title, String year) { return new Item(title, year, ""); }
    private static Item scored(String title, String score) { return new Item(title, "", score); }
    private static List<String> titles(List<Item> items) { return items.stream().map(Item::title).toList(); }

    @Test public void baseTitleDropsOnlyLanguageMarks() {
        String base = SourceDiscoveryOrder.baseTitle("出更二人组");
        for (String title : List.of("出更二人组(粤)", "出更二人组(普通话)", "出更二人组（國語）", "出更二人组 [粤语版]", "出更二人组【国粤】", "出更二人组(粤)(国语)"))
            assertEquals(title, base, SourceDiscoveryOrder.baseTitle(title));
        assertNotEquals(base, SourceDiscoveryOrder.baseTitle("出更二人组(上)"));
        assertNotEquals(SourceDiscoveryOrder.baseTitle("英雄"), SourceDiscoveryOrder.baseTitle("英雄(2024)"));
        assertEquals("phantom", SourceDiscoveryOrder.baseTitle(" Phan tom "));
    }

    @Test public void versionsOfOneTitleShareTheFirstPositionPreferringNonCantonese() {
        List<Item> items = List.of(item("智勇双妹唛(粤)", "1990"), item("妙探对讲机(粤)", "1991"), item("智勇双妹唛(普通话)", "1990"),
                item("妙探对讲机", ""), item("另一部", "2020"));
        assertEquals(List.of("智勇双妹唛(普通话)", "妙探对讲机", "另一部"), titles(SourceDiscoveryOrder.dedupe(items, FIELDS)));
        // Only Cantonese versions: the first one stays.
        assertEquals(List.of("A(粤)"), titles(SourceDiscoveryOrder.dedupe(List.of(item("A(粤)", "2000"), item("A(粵語)", "2000")), FIELDS)));
    }

    @Test public void sameTitleFromDifferentYearsStaysSeparate() {
        List<Item> items = List.of(item("小妇人", "1994"), item("小妇人", "2019"), item("小妇人", "2020"), item("小妇人(国语)", ""));
        assertEquals(List.of("小妇人", "小妇人"), titles(SourceDiscoveryOrder.dedupe(items, FIELDS)));
        assertEquals("1994", SourceDiscoveryOrder.dedupe(items, FIELDS).get(0).year());
        assertEquals("2019", SourceDiscoveryOrder.dedupe(items, FIELDS).get(1).year());
    }

    @Test public void heatOrdersSourcesWithoutTheirOwnSortNewestFirstThenTakesTurns() {
        List<Item> oldFirst = List.of(item("老1", "1990"), item("新1", "2025"), item("无年份", ""), item("中1", "2010"));
        List<Item> sorted = List.of(item("热A", "2001"), item("热B", "2024"));
        List<Item> result = SourceDiscoveryOrder.order(List.of(oldFirst, sorted), new boolean[]{true, false}, "U", FIELDS);
        assertEquals(List.of("新1", "热A", "中1", "热B", "老1", "无年份"), titles(result));
        // The default selection ("") is 近期热度 too.
        assertEquals(titles(result), titles(SourceDiscoveryOrder.order(List.of(oldFirst, sorted), new boolean[]{true, false}, "", FIELDS)));
    }

    @Test public void releaseAndScoreSortTheWholeList() {
        List<Item> a = List.of(item("a1", "2000"), item("a2", "2023"));
        List<Item> b = List.of(item("b1", "2025"), item("b2", ""));
        assertEquals(List.of("b1", "a2", "a1", "b2"), titles(SourceDiscoveryOrder.order(List.of(a, b), new boolean[]{true, true}, "R", FIELDS)));
        List<Item> s1 = List.of(scored("x", "7.1"), scored("y", ""));
        List<Item> s2 = List.of(scored("z", "9.0"), scored("w", "8.2"));
        assertEquals(List.of("z", "w", "x", "y"), titles(SourceDiscoveryOrder.order(List.of(s1, s2), new boolean[]{true, true}, "S", FIELDS)));
    }

    @Test public void duplicatesAcrossSourcesAreMergedAfterOrdering() {
        List<Item> a = List.of(item("同一部", "2024"), item("甲", "2023"));
        List<Item> b = List.of(item("同一部(国语)", "2024"), item("乙", "2022"));
        assertEquals(List.of("同一部", "甲", "乙"), titles(SourceDiscoveryOrder.order(List.of(a, b), new boolean[]{false, false}, "U", FIELDS)));
    }

    @Test public void groupsAdmitEachTitleOnce() {
        SourceDiscoveryOrder.Groups<Item> shown = new SourceDiscoveryOrder.Groups<>(FIELDS);
        assertTrue(shown.admit(item("皆大欢喜古装版(粤)", "2001")));
        assertFalse(shown.admit(item("皆大欢喜古装版(普通话)", "2001")));
        assertTrue(shown.admit(item("皆大欢喜时装版(粤)", "2003")));
        assertTrue(shown.admit(item("皆大欢喜古装版", "2015")));
        shown.clear();
        assertTrue(shown.admit(item("皆大欢喜古装版(普通话)", "2001")));
    }
}
