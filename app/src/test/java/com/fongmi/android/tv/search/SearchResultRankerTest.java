package com.fongmi.android.tv.search;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchResultRankerTest {
    @Test public void normalizesPunctuationAndTraditionalTitles() {
        assertEquals(0, SearchResultRanker.titleRank("  流浪地球（2） ", "流浪地球(2)"));
        assertEquals(0, SearchResultRanker.titleRank("繁體片名", "繁体片名"));
    }

    @Test public void exactTitleWinsOverPrefixAndUnrelatedRows() {
        assertTrue(SearchResultRanker.score("目标剧", "2025", "目标剧", "2025")
                < SearchResultRanker.score("目标剧：特别篇", "2025", "目标剧", "2025"));
        assertTrue(SearchResultRanker.score("目标剧：特别篇", "2025", "目标剧", "2025")
                < SearchResultRanker.score("完全不同", "2025", "目标剧", "2025"));
    }

    @Test public void matchingYearBreaksSameTitleTies() {
        assertTrue(SearchResultRanker.score("沙丘", "2024", "沙丘", "2024")
                < SearchResultRanker.score("沙丘", "2021", "沙丘", "2024"));
        assertTrue(SearchResultRanker.score("沙丘", "", "沙丘", "2024")
                < SearchResultRanker.score("沙丘", "2021", "沙丘", "2024"));
    }

    @Test public void shorterTitleCannotStandInForASequel() {
        assertEquals(3, SearchResultRanker.titleRank("庆余年", "庆余年第二季"));
        assertEquals(3, SearchResultRanker.titleRank("庆余年第一季", "庆余年第二季"));
        assertEquals(3, SearchResultRanker.titleRank("庆余年20", "庆余年2"));
        assertEquals(3, SearchResultRanker.titleRank("高清 庆余年20", "庆余年第二季"));
        assertEquals(3, SearchResultRanker.titleRank("更新 庆余年12", "庆余年第一季"));
        assertEquals(3, SearchResultRanker.titleRank("11899", "1899"));
    }

    @Test public void seasonAliasesMatchWithoutRewritingOrdinaryChineseNumbers() {
        for (String title : new String[]{"庆余年2", "庆余年 第２季", "慶餘年S02", "庆余年 Season 2"})
            assertEquals(title, 0, SearchResultRanker.titleRank(title, "庆余年第二季"));
        assertEquals(0, SearchResultRanker.titleRank("目标 第二十一季", "目标S21"));
        assertEquals(3, SearchResultRanker.titleRank("1代宗师", "一代宗师"));
    }

    @Test public void clickedCardRejectsKnownDifferentWorkButAcceptsMissingYear() {
        SearchResultRanker.Target target = SearchResultRanker.target("沙丘", "2024");
        assertTrue(target.accepts(target.score("沙丘", "2024-03-01")));
        assertTrue(target.accepts(target.score("沙丘 高清版", "")));
        assertTrue(target.accepts(target.score("沙丘", "0")));
        assertTrue(target.accepts(target.score("沙丘", "未知")));
        assertFalse(target.accepts(target.score("沙丘", "2021")));
        assertFalse(target.accepts(target.score("完全不同", "2024")));
    }

    @Test public void invalidExpectedYearDoesNotExcludeOtherwiseMatchingSources() {
        for (String year : new String[]{"0", "未知", "", "123456"}) {
            SearchResultRanker.Target target = SearchResultRanker.target("三体", year);
            assertTrue(target.accepts(target.score("三体", "2023")));
        }
        assertEquals(0, SearchResultRanker.score("三体", "2023", "三体", "第1年 2023年"));
    }

    @Test public void unnumberedOriginalRejectsExplicitSequelWithMissingYear() {
        SearchResultRanker.Target movie = SearchResultRanker.target("沙丘", "2021");
        for (String title : new String[]{"沙丘2", "沙丘第2部", "国语 沙丘第二部", "沙丘2 (2024) 4K"})
            assertFalse(title, movie.accepts(movie.score(title, "0")));
        assertEquals(3, SearchResultRanker.titleRank("高清庆余年第2季", "庆余年"));
    }

    @Test public void editionQualityDoesNotBecomeAnAddedInstallment() {
        for (String title : new String[]{"沙丘国语", "沙丘 高清版", "沙丘4K", "沙丘 1080p", "沙丘 4K国语"})
            assertTrue(title, SearchResultRanker.titleRank(title, "沙丘") < 3);
        for (String title : new String[]{"沙丘2 4K", "沙丘2 1080p", "沙丘第二部 (2024) 4K", "高清沙丘2-4K"})
            assertTrue(title, SearchResultRanker.titleRank(title, "沙丘2") < 3);
        assertEquals(3, SearchResultRanker.titleRank("沙丘20 4K", "沙丘2"));
        assertEquals(3, SearchResultRanker.titleRank("沙丘2 4K", "沙丘"));
    }

    @Test public void isolatedYearDecorationRetainsItsIdentityEvidence() {
        SearchResultRanker.Target target = SearchResultRanker.target("沙丘", "2021");
        for (String title : new String[]{"沙丘 (2021)", "沙丘 2021", "沙丘【2021】国语", "沙丘 (2021) 4K"})
            assertTrue(title, target.accepts(target.score(title, "")));
        assertFalse(target.accepts(target.score("沙丘 (2024)", "")));
        assertEquals(3, SearchResultRanker.titleRank("沙丘2021", "沙丘"));
        assertTrue(SearchResultRanker.titleRank("2022 1899", "1899") < 3);
        assertTrue(SearchResultRanker.titleRank("1899 (2022) 1080p", "1899") < 3);
        SearchResultRanker.Target numeric = SearchResultRanker.target("1899", "2022");
        assertTrue(numeric.accepts(numeric.score("1899", "")));
        SearchResultRanker.Target narrative = SearchResultRanker.target("2001太空漫游", "1968");
        assertTrue(narrative.accepts(narrative.score("2001 太空漫游 4K", "")));
    }

    @Test public void manualSearchOrdersTheRequestedShortDramaBeforeFastUnrelatedSources() {
        SearchResultRanker.Query query = SearchResultRanker.query("美颜系统开挂大佬全跪下");
        assertEquals(Arrays.asList("美颜系统开挂，大佬全跪下", "美颜系统开挂大佬全跪下第2部",
                        "美颜系统开挂大佬全跪下第3部", "美颜系统开挂大佬全跪下第10部",
                        "美颜系统开挂", "白鹿原", "白鹿原2017", "二次元"),
                sortedTitles(query, "白鹿原", "白鹿原2017", "二次元",
                        "美颜系统开挂大佬全跪下第3部", "美颜系统开挂",
                        "美颜系统开挂大佬全跪下第10部", "美颜系统开挂，大佬全跪下",
                        "美颜系统开挂大佬全跪下第2部"));
    }

    @Test public void manualSearchNaturallyOrdersSeasonAliasesAndDecoratedSequels() {
        SearchResultRanker.Query query = SearchResultRanker.query("庆余年");
        assertEquals(query.score("庆余年2"), query.score("慶餘年 第２季"));
        assertEquals(query.score("庆余年2"), query.score("庆余年 Season 2"));
        assertEquals(query.score("庆余年2"), query.score("庆余年S02"));
        assertEquals(query.score("庆余年2"), query.score("庆余年第二季 (2024) 4K"));
        assertTrue(query.score("庆余年2") < query.score("庆余年第三季"));
        assertTrue(query.score("庆余年第三季") < query.score("庆余年第十季"));
    }

    @Test public void manualSearchPreservesQualityTokensExplicitlyNamedByTheQuery() {
        SearchResultRanker.Query quality = SearchResultRanker.query("1080p");
        assertTrue(quality.score("1080p高清合集") < quality.score("完全不同"));
        SearchResultRanker.Query titleAndQuality = SearchResultRanker.query("沙丘4K");
        assertTrue(titleAndQuality.score("沙丘4K国语") < titleAndQuality.score("沙丘国语"));
        assertEquals(Arrays.asList("沙丘4K国语", "沙丘国语"),
                sortedTitles(titleAndQuality, "沙丘国语", "沙丘4K国语"));
    }

    @Test public void manualSearchNaturallyOrdersSequelsAfterLanguagePrefixes() {
        String title = "美颜系统开挂大佬全跪下";
        SearchResultRanker.Query query = SearchResultRanker.query(title);
        assertEquals(Arrays.asList("国语" + title + "2", "国语" + title + "3", "国语" + title + "10"),
                sortedTitles(query, "国语" + title + "10", "国语" + title + "3", "国语" + title + "2"));
        assertEquals(query.score(title + "2"), query.score("国语" + title + "第二部 4K"));
    }

    @Test public void manualSearchNaturallyOrdersExplicitSeasonsOfNumericWorkNames() {
        for (String title : new String[]{"1899", "3%"}) {
            SearchResultRanker.Query query = SearchResultRanker.query(title);
            assertEquals(Arrays.asList(title + "第2季", title + "第3季"),
                    sortedTitles(query, title + "第3季", title + "第2季"));
            assertEquals(query.score(title + "第2季"), query.score("国语" + title + "第二季 (2024) 4K"));
            assertTrue(query.score(title + "第2季") < query.score(title + "20"));
        }
    }

    @Test public void manualSearchDoesNotInterpretPictureQualityOrIsolatedYearAsASequel() {
        SearchResultRanker.Query query = SearchResultRanker.query("沙丘");
        for (String title : new String[]{"沙丘 4K", "沙丘 1080p", "沙丘 (2024)", "沙丘【2024】 4K"})
            assertEquals(title, query.score("沙丘"), query.score(title));
        assertTrue(query.score("沙丘 4K") < query.score("沙丘第二部"));
        assertTrue(query.score("沙丘第二部") < query.score("沙丘第三部"));
        assertEquals(0, SearchResultRanker.query("1899").score("1899 (2022) 1080p"));
        assertEquals(0, SearchResultRanker.query("2001太空漫游").score("2001 太空漫游 4K"));
    }

    @Test public void manualSearchForNumberedWorkDoesNotTreatTwentyAsPartTwo() {
        SearchResultRanker.Query query = SearchResultRanker.query("庆余年2");
        assertEquals(0, query.score("慶餘年第二季"));
        assertTrue(query.score("庆余年2 国语") < query.score("庆余年20"));
        assertTrue(query.score("庆余年20") >= 50_000);
        assertTrue(query.score("庆余年20 4K") >= 50_000);
        assertTrue(SearchResultRanker.query("1899").score("11899") >= 50_000);
    }

    @Test public void manualSearchUsesLongestContinuousPartialTitleBeforeLengthTies() {
        SearchResultRanker.Query query = SearchResultRanker.query("美颜系统开挂大佬全跪下");
        String longerMatch = "美颜系统开挂番外特别加长版附加剧情";
        String shorterMatch = "美颜系统";
        String scatteredCharacters = "美开颜挂系佬统全";
        assertTrue(query.score(longerMatch) < query.score(shorterMatch));
        assertTrue(query.score(shorterMatch) < query.score(scatteredCharacters));
    }

    @Test public void manualSearchKeepsArrivalOrderForEqualTitlesWithYearDecorations() {
        SearchResultRanker.Query query = SearchResultRanker.query("沙丘");
        List<String> arrivalOrder = Arrays.asList("沙丘 (2024) 4K", "沙丘", "沙丘【2021】", "沙丘 2023");
        for (String title : arrivalOrder) assertEquals(title, 0, query.score(title));
        assertEquals(arrivalOrder, sortedTitles(query, arrivalOrder.toArray(new String[0])));
    }

    @Test public void emptyAndSingleLetterManualQueriesRemainBroad() {
        SearchResultRanker.Query empty = SearchResultRanker.query("");
        assertEquals(empty.score("沙丘"), empty.score("白鹿原"));
        assertEquals(empty.score("沙丘"), empty.score(null));
        SearchResultRanker.Query singleLetter = SearchResultRanker.query("Ａ");
        assertEquals(0, singleLetter.score("a"));
        assertTrue(singleLetter.score("a") < singleLetter.score("About"));
        assertTrue(singleLetter.score("About") < singleLetter.score("Beta"));
        assertTrue(singleLetter.score("Beta") < singleLetter.score("xyz"));
        SearchResultRanker.Query singleCharacter = SearchResultRanker.query("美");
        assertTrue(singleCharacter.score("美颜系统") < singleCharacter.score("完全不同"));
    }

    @Test(timeout = 5_000) public void manualPartialComparisonBoundsPathologicallyLongTitles() {
        String queryTitle = repeated('a', 12_000) + "共同结尾";
        String candidateTitle = repeated('b', 12_000) + "共同结尾";
        SearchResultRanker.Query query = SearchResultRanker.query(queryTitle);
        assertEquals(query.score("完全不同"), query.score(candidateTitle));
        assertEquals(0, query.score(queryTitle));
    }

    private static List<String> sortedTitles(SearchResultRanker.Query query, String... titles) {
        List<ScoredTitle> scored = new ArrayList<>();
        for (String title : titles) scored.add(new ScoredTitle(title, query.score(title)));
        scored.sort(Comparator.comparingInt(value -> value.score));
        List<String> sorted = new ArrayList<>();
        for (ScoredTitle value : scored) sorted.add(value.title);
        return sorted;
    }

    private static String repeated(char value, int length) {
        char[] values = new char[length];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static final class ScoredTitle {
        private final String title;
        private final int score;

        private ScoredTitle(String title, int score) {
            this.title = title;
            this.score = score;
        }
    }
}
