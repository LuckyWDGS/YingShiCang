package com.fongmi.android.tv.discovery;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class DiscoveryCatalogTest {
    @Test public void retainsAllSevenDistinctOriginalMenus() {
        assertEquals(List.of("排序", "地区"), groupLabels(1));
        assertEquals(List.of("综合", "国产剧", "欧美剧", "日剧", "韩剧", "动画"), optionLabels(2, "category"));
        assertEquals(List.of("综合", "国内", "国外"), optionLabels(3, "category"));
        assertEquals(List.of("内容", "类型", "地区", "排序", "年代"), groupLabels(4));
        assertEquals(List.of("内容", "类型", "电视剧形式", "综艺形式", "地区", "排序", "年代", "平台"), groupLabels(5));
        assertEquals(List.of("实时热门电影", "一周口碑电影榜", "豆瓣电影Top250"), optionLabels(6, "rank"));
        assertEquals(List.of("实时热门电视", "华语口碑剧集榜", "全球口碑剧集榜", "国内口碑综艺榜", "国外口碑综艺榜"), optionLabels(7, "rank"));
        assertTrue(optionLabels(5, "region").contains("国外"));
        assertEquals("tvN", optionLabels(5, "platform").get(11));
    }

    @Test public void movieSortAndRegionAreBothIncludedAndPaginationUsesOffset() {
        DiscoveryCatalog.State state = new DiscoveryCatalog.State(1);
        state.select("sort", "time"); state.select("region", "华语");
        DiscoveryCatalog.Request request = DiscoveryCatalog.requests(state, 2).get(0);
        Map<String, String> query = query(request.url);
        assertEquals("movie", query.get("type"));
        assertEquals("华语", query.get("tag"));
        assertEquals("time", query.get("sort"));
        assertEquals("40", query.get("page_start"));
        assertEquals(40, request.start);
        assertEquals(2, DiscoveryCatalog.requests(new DiscoveryCatalog.State(0), 0).size());
    }

    @Test public void tvAndVarietyUseTheirExactProviderCategoryIdentifiers() {
        DiscoveryCatalog.State series = new DiscoveryCatalog.State(2);
        series.select("category", "tv_animation");
        Map<String, String> query = query(DiscoveryCatalog.requests(series, 1).get(0).url);
        assertEquals("tv", query.get("category"));
        assertEquals("tv_animation", query.get("type"));
        DiscoveryCatalog.State variety = new DiscoveryCatalog.State(3);
        variety.select("category", "show_foreign");
        query = query(DiscoveryCatalog.requests(variety, 0).get(0).url);
        assertEquals("show", query.get("category"));
        assertEquals("show_foreign", query.get("type"));
    }

    @Test public void completeFilterCombinationUsesRealProviderTagsAndSeparateSort() {
        DiscoveryCatalog.State movie = new DiscoveryCatalog.State(4);
        movie.select("genre", "喜剧"); movie.select("region", "日本"); movie.select("year", "2025"); movie.select("sort", "S");
        Map<String, String> query = query(DiscoveryCatalog.requests(movie, 0).get(0).url);
        assertEquals("S", query.get("sort"));
        assertEquals("喜剧,日本,2025", query.get("tags"));
        JsonObject categories = JsonParser.parseString(query.get("selected_categories")).getAsJsonObject();
        assertEquals("喜剧", categories.get("类型").getAsString());
        assertEquals("日本", categories.get("地区").getAsString());
        DiscoveryCatalog.State tv = new DiscoveryCatalog.State(5);
        tv.select("tv_form", "爱情"); tv.select("show_form", "真人秀"); tv.select("region", "国外"); tv.select("platform", "Netflix"); tv.select("year", "90年代");
        assertEquals("综艺", tv.value("type"));
        assertEquals("", tv.value("tv_form"));
        query = query(DiscoveryCatalog.requests(tv, 0).get(0).url);
        assertEquals("真人秀,国外,90年代,Netflix", query.get("tags"));
        assertEquals("综艺", JsonParser.parseString(query.get("selected_categories")).getAsJsonObject().get("形式").getAsString());
    }

    @Test public void everyRankingChoiceUsesItsGenuineCollectionWithoutScoreSorting() {
        for (int tab : new int[]{6, 7}) {
            DiscoveryCatalog.State state = new DiscoveryCatalog.State(tab);
            for (DiscoveryCatalog.Option option : DiscoveryCatalog.groups(tab).get(0).options) {
                state.select("rank", option.value);
                String url = DiscoveryCatalog.requests(state, 0).get(0).url;
                assertTrue(url.contains("/subject_collection/" + option.value + "/items?"));
                assertFalse(url.contains("sort="));
                assertFalse(url.contains("search_subjects"));
            }
        }
    }

    @Test public void adultTagCombinesWithGenreRegionYearSortAndRawOffsetInsteadOfReplacingThem() {
        DiscoveryCatalog.State movie = new DiscoveryCatalog.State(4);
        movie.select("content", "情色"); movie.select("genre", "喜剧"); movie.select("region", "美国");
        movie.select("year", "90年代"); movie.select("sort", "S");
        DiscoveryCatalog.Request request = DiscoveryCatalog.requests(movie, 1).get(0);
        Map<String, String> query = query(request.url);
        assertEquals("喜剧,美国,90年代,情色", query.get("tags"));
        assertEquals("S", query.get("sort"));
        assertEquals("20", query.get("start"));
        JsonObject categories = JsonParser.parseString(query.get("selected_categories")).getAsJsonObject();
        assertEquals("喜剧", categories.get("类型").getAsString());
        assertEquals("美国", categories.get("地区").getAsString());
        assertFalse(categories.has("分级"));
        assertEquals(List.of("全部", "18+（成人向）"), optionLabels(4, "content"));
    }

    @Test public void adultContentIsIndependentOfTvFormsAndNeverRestoresFromPreferences() {
        DiscoveryCatalog.State tv = new DiscoveryCatalog.State(5);
        tv.restore("{\"tv_form\":\"喜剧\",\"type\":\"电视剧\",\"region\":\"美国\"}");
        assertEquals("", tv.value("content"));
        assertFalse(tv.select("content", "18+"));
        assertTrue(tv.select("content", "情色"));
        tv.select("year", "2010年代"); tv.select("platform", "Netflix");
        Map<String, String> query = query(DiscoveryCatalog.requests(tv, 0).get(0).url);
        assertEquals("喜剧,美国,2010年代,Netflix,情色", query.get("tags"));
        assertEquals("电视剧", tv.value("type"));
        DiscoveryCatalog.State restored = new DiscoveryCatalog.State(5);
        restored.restore(tv.serialize());
        assertEquals(tv.serialize(), restored.serialize());
        assertEquals("", restored.value("content"));
        assertFalse(JsonParser.parseString(tv.serialize()).getAsJsonObject().has("content"));
        restored.select("show_form", "真人秀");
        assertEquals("", restored.value("content"));
        assertEquals("", restored.value("tv_form"));
        assertEquals("综艺", restored.value("type"));
        assertTrue(restored.select("content", "情色"));
        assertTrue(restored.select("content", ""));
        assertFalse(query(DiscoveryCatalog.requests(restored, 0).get(0).url).get("tags").contains("情色"));
        assertEquals("", new DiscoveryCatalog.State(4).value("content"));
    }

    @Test public void legacyAdultPreferencesAreExcludedWhileOrdinaryChoicesSurvive() {
        for (int tab : new int[]{4, 5}) {
            DiscoveryCatalog.State state = new DiscoveryCatalog.State(tab);
            state.restore("{\"content\":\"情色\",\"region\":\"美国\",\"year\":\"2025\",\"sort\":\"S\"}");
            assertEquals("", state.value("content"));
            assertEquals("美国", state.value("region"));
            assertEquals("2025", state.value("year"));
            assertEquals("S", state.value("sort"));
            assertTrue(state.select("content", "情色"));
            state.restore("{\"content\":\"情色\"}");
            assertEquals("", state.value("content"));
            state.select("content", "情色");
            state.restore("broken");
            assertEquals("", state.value("content"));
        }
    }

    @Test public void querySnapshotPreservesActiveAdultChoiceAndDoesNotShareMutableState() {
        DiscoveryCatalog.State state = new DiscoveryCatalog.State(4);
        state.select("content", "情色"); state.select("genre", "喜剧");
        DiscoveryCatalog.State snapshot = state.snapshot();
        state.select("content", ""); state.select("genre", "动作");
        assertEquals("情色", snapshot.value("content"));
        assertEquals("喜剧", snapshot.value("genre"));
        snapshot.select("region", "日本");
        assertEquals("", state.value("region"));
    }

    @Test public void persistenceValidatesOptionsAndNeverTransfersFiltersBetweenTabs() {
        DiscoveryCatalog.State state = new DiscoveryCatalog.State(5);
        state.select("type", "电视剧"); state.select("tv_form", "动画"); state.select("platform", "BBC");
        DiscoveryCatalog.State restored = new DiscoveryCatalog.State(5);
        restored.restore(state.serialize());
        assertEquals(state.serialize(), restored.serialize());
        restored.restore("{\"sort\":\"unknown\",\"region\":{},\"platform\":true,\"rank\":\"movie_top250\"}");
        assertEquals("U", restored.value("sort"));
        assertEquals("BBC", restored.value("platform"));
        restored.restore("[broken");
        assertEquals("BBC", restored.value("platform"));
        DiscoveryCatalog.State other = new DiscoveryCatalog.State(2);
        other.restore(state.serialize());
        assertEquals("tv", other.value("category"));
        assertFalse(other.select("sort", "S"));
    }

    @Test public void parsesLiveSearchAndChartShapesWithoutTurningRankIntoRating() {
        DiscoveryCatalog.Page search = DiscoveryCatalog.parse("{\"subjects\":[{\"id\":\"1\",\"title\":\"标题\",\"cover\":\"https://example.com/1.jpg\",\"rate\":\"8.5\",\"episodes_info\":\"12集全\"}]}");
        assertEquals("8.5", search.entries.get(0).score);
        assertEquals("12集全", search.entries.get(0).remarks);
        DiscoveryCatalog.Page chart = DiscoveryCatalog.parse("{\"total\":250,\"subject_collection_items\":[{\"id\":2,\"title\":\"电影\",\"rank\":1,\"rating\":{\"value\":9},\"pic\":{\"normal\":\"https://example.com/2.jpg\"},\"card_subtitle\":\"1994 / 美国 / 剧情\"}]}");
        assertEquals("9.0", chart.entries.get(0).score);
        assertEquals("1994", chart.entries.get(0).year);
        assertEquals("", chart.entries.get(0).remarks);
        assertTrue(chart.hasMore(DiscoveryCatalog.requests(new DiscoveryCatalog.State(6), 0).get(0)));
        DiscoveryCatalog.Page noRating = DiscoveryCatalog.parse("{\"items\":[{\"id\":3,\"title\":\"未评分\",\"rank\":8,\"rating\":null}]}");
        assertEquals("", noRating.entries.get(0).score);
    }

    @Test public void ignoresMalformedRowsAndDuplicateIdsButPreservesRawPagingCount() {
        DiscoveryCatalog.Page page = DiscoveryCatalog.parse("{\"total\":6,\"items\":[null,1,{\"id\":\"1\",\"title\":\"A\",\"rate\":{}},{\"id\":\"1\",\"title\":\"Duplicate\"},{\"id\":\"2\",\"title\":{},\"pic\":null},{\"subject\":{\"id\":\"3\",\"title\":\"C\",\"cover\":\"javascript:bad\",\"rating\":{\"value\":11}}}]}");
        assertEquals(6, page.rawCount);
        assertEquals(2, page.entries.size());
        assertEquals("A", page.entries.get(0).title);
        assertEquals("", page.entries.get(1).cover);
        assertEquals("", page.entries.get(1).score);
        assertFalse(page.hasMore(DiscoveryCatalog.requests(new DiscoveryCatalog.State(2), 0).get(0)));
    }

    @Test public void excludesLiveCollectionPromoDespiteItsMovieItemTypeAndPreservesSubjects() {
        // These discriminator fields match the live 喜剧 recommendation promo and film card.
        DiscoveryCatalog.Page page = DiscoveryCatalog.parse("{\"total\":501,\"items\":["
                + "{\"id\":\"movie_comedy\",\"title\":\"高分经典喜剧片榜\",\"type\":\"chart\",\"card\":\"chart\",\"item_type\":\"movie\",\"uri\":\"douban://douban.com/subject_collection/movie_comedy?type=rank&category=movie&rank_type=film_genre\",\"cover_url\":\"https://img9.doubanio.com/img/files/file-1543903934.png\"},"
                + "{\"id\":\"36452545\",\"title\":\"功夫女足\",\"type\":\"movie\",\"card\":\"subject\",\"item_type\":\"movie\",\"uri\":\"douban://douban.com/movie/36452545\",\"rating\":{\"value\":6.2}},"
                + "{\"id\":\"37817080\",\"title\":\"花儿与少年 第八季\",\"type\":\"tv\",\"card\":\"subject\",\"uri\":\"douban://douban.com/tv/37817080\"}]}" );
        assertEquals(List.of("功夫女足", "花儿与少年 第八季"), page.entries.stream().map(entry -> entry.title).collect(Collectors.toList()));
        assertEquals("6.2", page.entries.get(0).score);
        assertEquals(3, page.rawCount);
        assertTrue(page.hasMore(DiscoveryCatalog.requests(new DiscoveryCatalog.State(4), 0).get(0)));
        assertEquals(1, DiscoveryCatalog.parse("{\"subjects\":[{\"id\":\"36452545\",\"title\":\"功夫女足\",\"url\":\"https://movie.douban.com/subject/36452545/\"}]}").entries.size());
    }

    @Test public void nonSubjectDiscriminatorsCannotBeOverriddenByMovieItemType() {
        for (String discriminator : List.of("\"type\":\"chart\"", "\"card\":\"chart\"", "\"uri\":\"douban://douban.com/subject_collection/movie_comedy\"", "\"type\":\"book\"")) {
            DiscoveryCatalog.Page page = DiscoveryCatalog.parse("{\"items\":[{\"id\":\"123\",\"title\":\"非影视条目\",\"item_type\":\"movie\"," + discriminator + "}]}");
            assertTrue(page.entries.isEmpty());
            assertEquals(1, page.rawCount);
        }
    }

    @Test public void excludesAdultWrapperAndSubjectMetadataBeforeConvertingHomeCards() {
        DiscoveryCatalog.Page page = DiscoveryCatalog.parse("{\"total\":5,\"items\":["
                + "{\"genres\":[\"情色\"],\"subject\":{\"id\":\"1\",\"title\":\"wrapper gate\",\"type\":\"movie\"}},"
                + "{\"subject\":{\"id\":\"2\",\"title\":\"subject gate\",\"type\":\"tv\",\"adult\":true}},"
                + "{\"id\":\"3\",\"title\":\"subtitle gate\",\"card_subtitle\":\"2025 / 美国 / 情色\"},"
                + "{\"id\":\"4\",\"title\":\"normal\",\"genres\":[\"爱情\",\"剧情\"],\"rating\":{\"value\":8.8}},"
                + "{\"id\":\"18\",\"title\":\"adult words in plot are not metadata\",\"intro\":\"成人教育\",\"rating\":{\"value\":9}}]}" );
        assertEquals(List.of("4", "18"), page.entries.stream().map(entry -> entry.id).collect(Collectors.toList()));
        assertEquals(5, page.rawCount);
        assertEquals(5, page.total);
    }

    @Test public void fullyFilteredAdultPageRetainsProviderPagingProgress() {
        DiscoveryCatalog.Page page = DiscoveryCatalog.parse("{\"total\":100,\"items\":[{\"id\":\"1\",\"title\":\"restricted\",\"tags\":[\"R18\"]}]}" );
        assertTrue(page.entries.isEmpty());
        assertEquals(1, page.rawCount);
        assertTrue(page.hasMore(DiscoveryCatalog.requests(new DiscoveryCatalog.State(0), 0).get(0)));
    }

    @Test public void recognizesEmptySuccessButRejectsProviderErrorAndHtml() {
        assertTrue(DiscoveryCatalog.parse("{\"items\":[]}").entries.isEmpty());
        assertFalse(DiscoveryCatalog.parse("{\"items\":[],\"total\":100}").hasMore(DiscoveryCatalog.requests(new DiscoveryCatalog.State(2), 0).get(0)));
        for (String payload : List.of("{\"msg\":\"blocked\"}", "{\"items\":null}", "<html>blocked</html>", "null")) {
            try { DiscoveryCatalog.parse(payload); fail("Must surface invalid provider response"); }
            catch (IllegalArgumentException expected) { /* Network UI should offer retry. */ }
        }
    }

    private static List<String> groupLabels(int tab) {
        return DiscoveryCatalog.groups(tab).stream().map(group -> group.label).collect(Collectors.toList());
    }

    private static List<String> optionLabels(int tab, String key) {
        return DiscoveryCatalog.groups(tab).stream().filter(group -> group.key.equals(key)).findFirst().orElseThrow().options.stream().map(option -> option.label).collect(Collectors.toList());
    }

    private static Map<String, String> query(String url) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] parts = pair.split("=", 2);
            query.put(parts[0], URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return query;
    }
}
