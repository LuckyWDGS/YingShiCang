package com.fongmi.android.tv.discovery;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.fongmi.android.tv.discovery.SourceDiscoveryPlanner.*;
import static org.junit.Assert.*;

public class SourceDiscoveryPlannerTest {
    private static Choice choice(String label, String value) { return new Choice(label, value); }
    private static Facet facet(String key, String label, String initial, Choice... choices) {
        return new Facet(key, label, initial, List.of(choices));
    }
    private static Category category(String id, String label, Facet... facets) { return new Category(id, label, List.of(facets)); }
    private static DiscoveryCatalog.State state(int tab, String... selections) {
        DiscoveryCatalog.State state = new DiscoveryCatalog.State(tab);
        for (int i = 0; i < selections.length; i += 2) assertTrue(state.select(selections[i], selections[i + 1]));
        return state;
    }

    @Test public void usesOpaqueIdsFacetKeysAndValuesWithDeclaredDefaults() {
        Category film = category("film::opaque", "电影",
                facet("flavor", "类型", "v-any", choice("全部", "v-any"), choice("喜剧", "v-44")),
                facet("loc", "地区", "a-any", choice("全部", "a-any"), choice("内地", "a-cn")),
                facet("released", "年代", "y-all", choice("全部", "y-all"), choice("2025", "y-5")),
                facet("other", "线路", "opaque-default", choice("默认", "opaque-default")),
                facet("sequence", "排序", "s-base", choice("近期热度", "s-base"), choice("评分", "s-score")));
        DiscoveryCatalog.State state = state(4, "genre", "喜剧", "region", "中国大陆", "year", "2025", "sort", "S");
        Query query = plan(state, List.of(film, category("drama", "国产剧")), false).get(0);
        assertEquals("film::opaque", query.categoryId());
        assertEquals(Map.of("flavor", "v-44", "loc", "a-cn", "released", "y-5", "other", "opaque-default", "sequence", "s-score"), query.extend());
        assertEquals(Set.of("genre", "region", "year"), query.mappedConstraints());
        assertTrue(accepts(state, query, "", "", "", ""));
        assertFalse(accepts(state, query, "国产剧", "", "", ""));
    }

    @Test public void normalModeNeverUsesAdultCategorySiteOrAdultInitialFacet() {
        Facet content = facet("age", "内容", "x18", choice("18+", "x18"), choice("普通", "safe"));
        List<Category> categories = List.of(category("movie", "电影", content), category("adult", "成人"), category("education", "成人教育"));
        List<Query> normal = plan(new DiscoveryCatalog.State(4), categories, false);
        assertEquals(1, normal.size());
        assertEquals("safe", normal.get(0).extend().get("age"));
        assertFalse(normal.get(0).adult());
        assertTrue(plan(new DiscoveryCatalog.State(4), categories, true).isEmpty());
        assertTrue(plan(new DiscoveryCatalog.State(4), List.of(category("only", "电影", facet("age", "内容", "XXX", choice("XXX", "XXX")))), false).isEmpty());
    }

    @Test public void adultModePrioritizesDeclaredScopeAndRequiresItemProofForFallback() {
        DiscoveryCatalog.State movie = state(4, "content", "情色");
        List<Category> categories = List.of(category("regular", "电影"), category("adult", "成人"),
                category("faceted", "电影", facet("content", "内容", "safe", choice("普通", "safe"), choice("伦理", "E"))),
                category("education", "成人教育"), category("drama", "电视剧"));
        List<Query> queries = plan(movie, categories, false);
        assertEquals(List.of("adult", "faceted", "regular"), queries.stream().map(Query::categoryId).toList());
        assertEquals("E", queries.get(1).extend().get("content"));
        assertTrue(queries.get(0).adult());
        assertTrue(queries.get(1).adult());
        assertFalse(queries.get(2).adult());
        assertFalse(accepts(movie, queries.get(2), "电影", "", "", ""));
        assertTrue(accepts(movie, queries.get(2), "电影", "18+", "", ""));
        assertEquals(1, plan(movie, List.of(category("regular", "电影")), true).size());
        DiscoveryCatalog.State tv = state(5, "content", "情色");
        Query generic = plan(tv, List.of(category("adult", "成人")), false).get(0);
        assertTrue(generic.itemKindRequired());
        assertFalse(accepts(tv, generic, "", "", "", ""));
        assertFalse(accepts(tv, generic, "电影", "", "", ""));
        assertTrue(accepts(tv, generic, "电视剧", "", "", ""));
        assertEquals(1, plan(tv, List.of(category("drama", "电视剧")), true).size());
        assertTrue(plan(movie, List.of(category("index", "推荐")), true).isEmpty());
    }

    @Test public void unsupportedConditionsAreExcludedRatherThanQuietlyIgnored() {
        List<Category> categories = List.of(category("movie", "电影"));
        assertTrue(plan(state(4, "genre", "喜剧"), categories, false).isEmpty());
        assertTrue(plan(state(5, "platform", "Netflix"), List.of(category("tv", "电视剧")), false).isEmpty());
        Category regional = category("movie", "电影", facet("loc", "地区", "all", choice("全部", "all"), choice("美国", "US")));
        assertTrue(plan(state(4, "region", "韩国"), List.of(regional), false).isEmpty());
        Category year = category("movie", "电影", facet("y", "年代", "all", choice("全部", "all"), choice("2024", "four")));
        assertTrue(plan(state(4, "year", "2025"), List.of(year), false).isEmpty());
    }

    @Test public void noFacetAreaAndYearRequireItemMetadataAndRecognizeRegionalAliases() {
        DiscoveryCatalog.State state = state(4, "region", "华语", "year", "90年代");
        Query query = plan(state, List.of(category("films", "电影")), false).get(0);
        assertTrue(query.extend().isEmpty());
        assertFalse(accepts(state, query, "电影", "", "", ""));
        assertTrue(accepts(state, query, "电影", "", "1995", "中国香港"));
        assertFalse(accepts(state, query, "电影", "", "2001", "中国香港"));
        assertFalse(accepts(state, query, "电影", "", "1995", "日本"));
        DiscoveryCatalog.State korea = state(5, "region", "韩国");
        Query kdrama = plan(korea, List.of(category("K", "韩剧"), category("C", "国产剧")), false).get(0);
        assertEquals("K", kdrama.categoryId());
        assertTrue(accepts(korea, kdrama, "", "", "", ""));
    }

    @Test public void actualGenreCategoriesEstablishMissingItemKindAndForm() {
        DiscoveryCatalog.State movie = state(4, "genre", "动作");
        Query query = plan(movie, List.of(category("action-id", "动作片"), category("other", "爱情片")), false).get(0);
        assertTrue(accepts(movie, query, "", "", "", ""));
        assertFalse(accepts(movie, query, "综艺", "动作", "", ""));
        DiscoveryCatalog.State variety = state(5, "type", "综艺");
        assertEquals(List.of("shows"), plan(variety, List.of(category("shows", "综艺"), category("tv", "国产剧")), false).stream().map(Query::categoryId).toList());
    }

    @Test public void decadeExpandsOnlyActuallyDeclaredYearValuesAtMostTen() {
        Category movie = category("movie", "电影", facet("opaque-year", "年代", "all",
                choice("全部", "all"), choice("1990", "a"), choice("1994", "b"), choice("2000", "c")));
        DiscoveryCatalog.State state = state(4, "year", "90年代");
        List<Query> queries = plan(state, List.of(movie), false);
        assertEquals(List.of("a", "b"), queries.stream().map(q -> q.extend().get("opaque-year")).toList());
        assertTrue(queries.stream().allMatch(q -> accepts(state, q, "", "", "", "")));
        Category decade = category("movie", "电影", facet("d", "年代", "all", choice("全部", "all"), choice("1990年代", "nineties")));
        assertEquals("nineties", plan(state, List.of(decade), false).get(0).extend().get("d"));
    }

    @Test public void modeSwitchCannotLeakOldQueryOrMetadataAndOutputIsImmutable() {
        List<Category> categories = List.of(category("movie", "电影", facet("age", "内容", "safe", choice("普通", "safe"), choice("成人", "XXX"))));
        Query adultQuery = plan(state(4, "content", "情色"), categories, false).get(0);
        DiscoveryCatalog.State normal = new DiscoveryCatalog.State(4);
        Query normalQuery = plan(normal, categories, false).get(0);
        assertFalse(accepts(normal, adultQuery, "电影", "", "", ""));
        assertFalse(accepts(normal, normalQuery, "电影", "情色", "", ""));
        assertTrue(accepts(normal, normalQuery, "电影", "喜剧", "", ""));
        assertEquals("safe", normalQuery.extend().get("age"));
        try { normalQuery.extend().put("bad", "value"); fail(); } catch (UnsupportedOperationException expected) { }
        try { normalQuery.mappedConstraints().add("year"); fail(); } catch (UnsupportedOperationException expected) { }
        try { plan(normal, categories, false).add(adultQuery); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void validUnknownInitialIsPreservedAndInvalidInitialUsesFirstNormalChoice() {
        Category movie = category("movie", "电影", facet("unknown", "源特有", "bad", choice("伦理", "adult"), choice("正常", "ok")),
                facet("mode", "源模式", "two", choice("一", "one"), choice("二", "two")));
        Query query = plan(new DiscoveryCatalog.State(4), List.of(movie), false).get(0);
        assertEquals(Map.of("unknown", "ok", "mode", "two"), query.extend());
        DiscoveryCatalog.State sorted = state(4, "sort", "S");
        assertTrue(plan(sorted, List.of(category("movie", "电影")), false).get(0).extend().isEmpty());
    }

    @Test public void adultGenreOverrideCannotDowngradeRequestScope() {
        Category movie = category("movie", "电影", facet("g", "类型", "all",
                choice("全部", "all"), choice("情色", "adult"), choice("喜剧", "comedy")));
        assertTrue(plan(state(4, "content", "情色", "genre", "喜剧"), List.of(movie), false).isEmpty());
        assertEquals("adult", plan(state(4, "content", "情色"), List.of(movie), false).get(0).extend().get("g"));
    }

    @Test public void sensibleAliasesResolveLabelsButNeverInventParameterValues() {
        Category movie = category("film", "Movie", facet("opaque-g", "Genre", "A", choice("All", "A"), choice("Comedy", "C")),
                facet("opaque-a", "Region", "A", choice("All", "A"), choice("South Korea", "K")));
        Query query = plan(state(4, "genre", "喜剧", "region", "韩国"), List.of(movie), false).get(0);
        assertEquals(Map.of("opaque-g", "C", "opaque-a", "K"), query.extend());
        assertTrue(accepts(state(4, "genre", "喜剧", "region", "韩国"), query, "Movie", "", "", ""));
    }

    @Test public void remoteMappingDoesNotPermitContradictoryExplicitItemMetadata() {
        Category movie = category("films", "电影", facet("g", "类型", "all", choice("全部", "all"), choice("喜剧", "g1")),
                facet("y", "年代", "all", choice("全部", "all"), choice("2025", "y5")),
                facet("r", "地区", "all", choice("全部", "all"), choice("中国大陆", "rC")));
        DiscoveryCatalog.State filtered = state(4, "genre", "喜剧", "year", "2025", "region", "中国大陆");
        Query query = plan(filtered, List.of(movie), false).get(0);
        assertTrue(accepts(filtered, query, "电影", "", "", ""));
        assertTrue(accepts(filtered, query, "喜剧片", "爱情,喜剧", "2025", "内地"));
        assertFalse(accepts(filtered, query, "爱情片", "", "2025", "内地"));
        assertFalse(accepts(filtered, query, "电影", "动作", "2025", "内地"));
        assertFalse(accepts(filtered, query, "电影", "喜剧", "2024", "内地"));
        assertFalse(accepts(filtered, query, "电影", "喜剧", "2025", "美国"));
        assertFalse(accepts(filtered, query, "电视剧", "喜剧", "2025", "内地"));
    }

    @Test public void regionalCategoryScopeRejectsExplicitContradictoryArea() {
        DiscoveryCatalog.State tv = new DiscoveryCatalog.State(5);
        Query query = plan(tv, List.of(category("cn-drama", "国产剧")), false).get(0);
        assertTrue(accepts(tv, query, "电视剧", "", "", ""));
        assertTrue(accepts(tv, query, "电视剧", "", "", "中国大陆"));
        assertFalse(accepts(tv, query, "电视剧", "", "", "韩国"));
    }

    @Test public void broadCatalogUsesDeclaredOpaqueKindChoicesAndKeepsOtherDefaults() {
        Category all = category("catalog::opaque", "全部",
                facet("media", "形式", "any", choice("全部", "any"), choice("电影", "m!"),
                        choice("电视剧", "t!"), choice("综艺", "s!")),
                facet("route", "线路", "line-2", choice("一", "line-1"), choice("二", "line-2")));
        DiscoveryCatalog.State movie = new DiscoveryCatalog.State(4);
        Query film = plan(movie, List.of(all), false).get(0);
        assertEquals("catalog::opaque", film.categoryId());
        assertEquals(Map.of("media", "m!", "route", "line-2"), film.extend());
        assertTrue(accepts(movie, film, "", "", "", ""));
        assertFalse(accepts(movie, film, "电视剧", "", "", ""));
        DiscoveryCatalog.State drama = state(5, "type", "电视剧");
        Query tv = plan(drama, List.of(all), false).get(0);
        assertEquals("t!", tv.extend().get("media"));
        assertTrue(tv.mappedConstraints().contains("type"));
        assertTrue(accepts(drama, tv, "", "", "", ""));
        assertFalse(accepts(drama, tv, "综艺", "", "", ""));
        DiscoveryCatalog.State shows = state(5, "type", "综艺");
        Query variety = plan(shows, List.of(all), false).get(0);
        assertEquals("s!", variety.extend().get("media"));
        assertTrue(accepts(shows, variety, "", "", "", ""));
        assertEquals(List.of("t!", "s!"), plan(new DiscoveryCatalog.State(5), List.of(all), false)
                .stream().map(q -> q.extend().get("media")).toList());
        assertTrue(plan(movie, List.of(category("unproven", "影视")), false).isEmpty());
    }

    @Test public void adultBroadCatalogRequiresBothActualKindAndAdultScope() {
        Facet kind = facet("kind", "类型", "all", choice("全部", "all"), choice("电影", "film"),
                choice("電視劇", "series"), choice("綜藝", "shows"));
        Category restricted = category("adult::opaque", "成人", kind);
        DiscoveryCatalog.State tv = state(5, "content", "情色", "type", "电视剧");
        Query query = plan(tv, List.of(restricted), false).get(0);
        assertEquals("adult::opaque", query.categoryId());
        assertEquals("series", query.extend().get("kind"));
        assertTrue(query.adult());
        assertTrue(DiscoveryContentGate.isAdult(query.categoryName()));
        assertTrue(accepts(tv, query, "", "", "", ""));
        assertTrue(plan(new DiscoveryCatalog.State(5), List.of(restricted), false).isEmpty());
        Query ordinary = plan(tv, List.of(category("ordinary", "全部", kind)), false).get(0);
        assertFalse(ordinary.adult());
        assertFalse(accepts(tv, ordinary, "电视剧", "", "", ""));
        assertTrue(accepts(tv, ordinary, "电视剧", "18+", "", ""));
        Query unknown = plan(tv, List.of(category("unknown", "成人")), false).get(0);
        assertTrue(unknown.itemKindRequired());
        assertFalse(accepts(tv, unknown, "其他", "18+", "", ""));
        assertEquals("series", plan(tv, List.of(category("adult-site", "全部", kind)), true)
                .get(0).extend().get("kind"));
    }

    @Test public void broadKindAndGenreUseSeparateRealFacetsAndTraditionalRegionalScope() {
        Category all = category("all", "影视", facet("media", "形式", "a", choice("全部", "a"), choice("电影", "m")),
                facet("flavor", "类型", "a", choice("全部", "a"), choice("喜劇片", "comedy")));
        Query query = plan(state(4, "genre", "喜剧"), List.of(all), false).get(0);
        assertEquals(Map.of("media", "m", "flavor", "comedy"), query.extend());
        DiscoveryCatalog.State korea = state(5, "type", "电视剧", "region", "韩国");
        Query regional = plan(korea, List.of(category("K", "韓劇"), category("C", "國產劇")), false).get(0);
        assertEquals("K", regional.categoryId());
        assertTrue(accepts(korea, regional, "", "", "", ""));
        assertFalse(accepts(korea, regional, "電視劇", "", "", "中国大陆"));
    }

    @Test public void genericItemTypeInheritsVerifiedMovieCategoryOrKindFacet() {
        DiscoveryCatalog.State movie = new DiscoveryCatalog.State(4);
        Query declared = plan(movie, List.of(category("films", "电影")), false).get(0);
        Query faceted = plan(movie, List.of(category("catalog", "全部",
                facet("media", "形式", "all", choice("全部", "all"), choice("电影", "opaque-m")))), false).get(0);
        for (Query query : List.of(declared, faceted)) {
            assertTrue(accepts(movie, query, "剧情", "", "", ""));
            assertTrue(accepts(movie, query, "其他", "", "", ""));
            assertTrue(accepts(movie, query, "喜剧片", "", "", ""));
            assertFalse(accepts(movie, query, "电视剧", "", "", ""));
            assertFalse(accepts(movie, query, "综艺", "", "", ""));
            assertFalse(accepts(movie, query, "其他", "成人向", "", ""));
        }
        DiscoveryCatalog.State comedy = state(4, "genre", "喜剧");
        Query filtered = plan(comedy, List.of(category("films", "电影",
                facet("g", "类型", "all", choice("全部", "all"), choice("喜剧", "comedy")))), false).get(0);
        assertTrue(accepts(comedy, filtered, "其他", "", "", ""));
        assertFalse(accepts(comedy, filtered, "剧情", "", "", ""));
    }

    @Test public void genericTvItemTypeInheritsQueryButExplicitMovieOrShowContradictionsReject() {
        DiscoveryCatalog.State tv = new DiscoveryCatalog.State(5);
        Query drama = plan(tv, List.of(category("series", "电视剧")), false).get(0);
        Query show = plan(tv, List.of(category("shows", "综艺")), false).get(0);
        assertTrue(accepts(tv, drama, "剧情", "", "", ""));
        assertTrue(accepts(tv, drama, "其他", "", "", ""));
        assertTrue(accepts(tv, show, "其他", "", "", ""));
        assertFalse(accepts(tv, drama, "电影", "", "", ""));
        assertFalse(accepts(tv, drama, "综艺", "", "", ""));
        assertFalse(accepts(tv, show, "电视剧", "", "", ""));
    }

    @Test public void itemOnlyAdultEvidenceIsFilteredWithinActualMovieAndTvCategories() {
        for (int tab : List.of(4, 5)) {
            DiscoveryCatalog.State selected = state(tab, "content", "情色");
            String kind = tab == 4 ? "电影" : "电视剧";
            Query query = plan(selected, List.of(category("opaque-items", kind)), false).get(0);
            assertFalse(query.adult());
            assertEquals("opaque-items", query.categoryId());
            assertTrue(query.extend().isEmpty());
            assertTrue(accepts(selected, query, kind, "限制级", "", ""));
            assertTrue(accepts(selected, query, "其他", "18+", "", ""));
            assertFalse(accepts(selected, query, kind, "普通", "", ""));
            assertFalse(accepts(selected, query, tab == 4 ? "电视剧" : "电影", "18+", "", ""));
            assertFalse(accepts(new DiscoveryCatalog.State(tab), query, kind, "18+", "", ""));
        }
    }

    @Test public void genericAdultCatalogRequiresItemKindAndNeverPollutesOtherMedia() {
        Category generic = category("restricted-mixed", "成人");
        DiscoveryCatalog.State movie = state(4, "content", "情色");
        DiscoveryCatalog.State tv = state(5, "content", "情色");
        Query film = plan(movie, List.of(generic), false).get(0);
        Query drama = plan(tv, List.of(generic), false).get(0);
        assertTrue(film.itemKindRequired());
        assertTrue(drama.itemKindRequired());
        assertTrue(accepts(movie, film, "电影", "", "", ""));
        assertFalse(accepts(movie, film, "电视剧", "", "", ""));
        assertFalse(accepts(movie, film, "其他", "", "", ""));
        assertTrue(accepts(tv, drama, "电视剧", "", "", ""));
        assertTrue(accepts(tv, drama, "综艺", "", "", ""));
        assertFalse(accepts(tv, drama, "电影", "", "", ""));
        assertFalse(accepts(tv, drama, "其他", "", "", ""));
        assertTrue(plan(new DiscoveryCatalog.State(4), List.of(generic), false).isEmpty());
        assertTrue(plan(new DiscoveryCatalog.State(5), List.of(generic), false).isEmpty());
        assertTrue(plan(tv, List.of(category("unproven", "影视")), false).isEmpty());
        DiscoveryCatalog.State onlyDrama = state(5, "content", "情色", "type", "电视剧");
        Query specific = plan(onlyDrama, List.of(generic), false).get(0);
        assertFalse(specific.mappedConstraints().contains("type"));
        assertTrue(accepts(onlyDrama, specific, "电视剧", "", "", ""));
        assertFalse(accepts(onlyDrama, specific, "综艺", "", "", ""));
    }

    @Test public void adultCandidateRetainsDeclaredFiltersAndNeverDropsSelectedConstraints() {
        Category source = category("candidate", "电影",
                facet("genre", "类型", "all", choice("全部", "all"), choice("喜剧", "comedy")),
                facet("area", "地区", "all", choice("全部", "all"), choice("日本", "jp")));
        DiscoveryCatalog.State selected = state(4, "content", "情色", "genre", "喜剧", "region", "日本", "year", "2025");
        Query query = plan(selected, List.of(source), false).get(0);
        assertFalse(query.adult());
        assertEquals(Map.of("genre", "comedy", "area", "jp"), query.extend());
        assertTrue(accepts(selected, query, "电影", "喜剧,18+", "2025", "日本"));
        assertFalse(accepts(selected, query, "电影", "喜剧,18+", "2024", "日本"));
        assertFalse(accepts(selected, query, "电影", "喜剧", "2025", "日本"));
        assertTrue(plan(state(5, "content", "情色", "platform", "Netflix"),
                List.of(category("tv-candidate", "电视剧")), false).isEmpty());
    }

    @Test public void numericalAdultAgeMapsOnlyDeclaredAgeFacetsNeverOpaqueIdsOrScores() {
        Facet age = facet("content_rating", "内容分级", "age-18", choice("18", "age-18"), choice("12", "age-12"));
        Category rated = category("rated", "电影", age);
        Query adult = plan(state(4, "content", "情色"), List.of(rated), false).get(0);
        assertTrue(adult.adult());
        assertEquals("age-18", adult.extend().get("content_rating"));
        Query ordinary = plan(new DiscoveryCatalog.State(4), List.of(rated), false).get(0);
        assertFalse(ordinary.adult());
        assertEquals("age-12", ordinary.extend().get("content_rating"));
        assertTrue(plan(new DiscoveryCatalog.State(4), List.of(category("only-age", "电影",
                facet("age_rating", "年龄", "18", choice("18", "18")))), false).isEmpty());
        Category unrelated = category("18", "电影", facet("rating", "评分", "18", choice("18", "18")),
                facet("route", "线路", "18", choice("18", "18")));
        Query candidate = plan(state(4, "content", "情色"), List.of(unrelated), false).get(0);
        assertFalse(candidate.adult());
        assertEquals(Map.of("rating", "18", "route", "18"), candidate.extend());
        assertFalse(accepts(state(4, "content", "情色"), candidate, "电影", "", "", ""));
    }

    @Test public void rawCategoryAdultDeclarationSurvivesKindScopingAndNeverLeaksToNormal() {
        Category movie = new Category("opaque-movie", "电影", List.of(), true);
        Category tv = new Category("opaque-tv", "电视剧", List.of(), true);
        Category broad = new Category("opaque-broad", "全部", List.of(facet("kind", "形式", "any",
                choice("全部", "any"), choice("电影", "m"), choice("电视剧", "t"))), true);
        for (int tab : List.of(4, 5)) {
            assertTrue(plan(new DiscoveryCatalog.State(tab), List.of(movie, tv, broad), false).isEmpty());
            DiscoveryCatalog.State restricted = state(tab, "content", "情色");
            List<Query> queries = plan(restricted, List.of(movie, tv, broad), false);
            assertEquals(2, queries.size());
            assertTrue(queries.stream().allMatch(Query::adult));
            assertTrue(queries.stream().noneMatch(Query::itemKindRequired));
            assertTrue(accepts(restricted, queries.get(1), "", "", "", ""));
        }
        Category unknown = new Category("opaque-unknown", "全部", List.of(), true);
        Query generic = plan(state(5, "content", "情色"), List.of(unknown), false).get(0);
        assertTrue(generic.itemKindRequired());
        assertFalse(accepts(state(5, "content", "情色"), generic, "其他", "", "", ""));
        assertTrue(accepts(state(5, "content", "情色"), generic, "电视剧", "", "", ""));
    }

    @Test public void rawFacetAdultDeclarationMapsOpaqueChoiceAndRejectsItsNormalDefault() {
        Choice flagged = new Choice("限制选项", "opaque-restricted", true);
        Category source = category("opaque-movie", "电影", facet("source-gate", "内容", "opaque-restricted",
                flagged, choice("普通", "opaque-ordinary")));
        Query adult = plan(state(4, "content", "情色"), List.of(source), false).get(0);
        assertTrue(adult.adult());
        assertEquals("opaque-restricted", adult.extend().get("source-gate"));
        Query normal = plan(new DiscoveryCatalog.State(4), List.of(source), false).get(0);
        assertFalse(normal.adult());
        assertEquals("opaque-ordinary", normal.extend().get("source-gate"));
        assertTrue(plan(new DiscoveryCatalog.State(4), List.of(category("only-flag", "电影",
                facet("source-gate", "内容", "opaque-restricted", flagged))), false).isEmpty());
        assertTrue(accepts(state(4, "content", "情色"), adult, "电影", "", "", ""));
        assertFalse(accepts(new DiscoveryCatalog.State(4), adult, "电影", "", "", ""));
    }
}
