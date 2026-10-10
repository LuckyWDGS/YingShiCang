package com.fongmi.android.tv.api.compat;

import com.github.catvod.crawler.Spider;
import com.github.catvod.spider.AiDj;
import com.github.catvod.spider.BaseSpiderGuard;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;

public class AiDramaFilterCompatibilityTest {
    private static JsonObject fixture(String slug, int rpc, String topic, int page) {
        var route = new JsonObject();
        route.addProperty("contentType", slug); route.addProperty("rpcContentType", rpc);
        route.addProperty("canonicalPath", "/category/" + slug + (topic.isEmpty() ? "" : "/" + topic));
        var ids = new JsonArray(); if (!topic.isEmpty()) ids.add("7"); route.add("categoryJsonIds", ids);
        if (!topic.isEmpty()) { route.addProperty("dimension", "topic"); route.addProperty("slug", topic); }
        var data = new JsonObject(); data.addProperty("isSuccess", true); data.add("categoryRoute", route);
        var query = new JsonObject(); query.addProperty("topic", ""); query.addProperty("page", page); data.add("query", query);
        var api = new JsonObject(); api.addProperty("content_type", rpc); api.addProperty("page_num", page);
        api.add("category_json_ids", ids.deepCopy()); api.add("route", route.deepCopy()); data.add("apiQuery", api);
        var choices = new JsonArray();
        var option = new JsonObject(); option.addProperty("selector_item_id", "fantasy"); option.addProperty("show_name", "玄幻");
        var declaredIds = new JsonArray(); declaredIds.add("7"); option.add("category_json_ids", declaredIds); choices.add(option);
        var undeclared = new JsonObject(); undeclared.addProperty("selector_item_id", "romance"); undeclared.addProperty("show_name", "过期项");
        undeclared.add("category_json_ids", new JsonArray()); choices.add(undeclared);
        var group = new JsonObject(); group.add("items", choices); var selectors = new JsonArray(); selectors.add(group); data.add("selectorList", selectors);
        var raw = new JsonObject(); raw.addProperty("series_id", "native-id"); raw.addProperty("series_name", "Fixture series");
        raw.addProperty("series_cover", "https://fixture.invalid/cover.jpg"); raw.addProperty("series_intro", "Fixture content"); raw.addProperty("episode_cnt", 23);
        var tags = new JsonArray(); tags.add("玄幻"); tags.add("冒险"); raw.add("tags", tags);
        var rows = new JsonArray(); rows.add(raw); data.add("recommendList", rows);
        var paging = new JsonObject(); paging.addProperty("pageNum", page); paging.addProperty("totalPages", 10);
        paging.addProperty("pageSize", 24); paging.addProperty("total", 239); data.add("pagination", paging);
        return data;
    }
    private static String html(JsonObject data) {
        var loaders = new JsonObject(); loaders.add("category-loader", data);
        var state = new JsonObject(); state.add("loaderData", loaders);
        return "<script>window._ROUTER_DATA = " + state + ";</script>";
    }
    private static void reject(JsonObject data) throws Exception {
        try { AiDramaFilterCompatibility.route(html(data), "ai-drama", 4, "fantasy", 2); fail("Mismatched native route accepted"); }
        catch (IOException expected) { assertNull(expected.getCause()); }
    }

    @Test public void currentTopicRoutesKeepEmptyQueryTopicAndTheirNativeCategoryAndPage() throws Exception {
        for (var kind : List.of("comic-drama", "ai-drama")) for (int page : List.of(1, 2)) {
            int rpc = kind.equals("comic-drama") ? 3 : 4;
            var source = fixture(kind, rpc, "fantasy", page);
            assertEquals(source, AiDramaFilterCompatibility.route(html(source), kind, rpc, "fantasy", page));
        }
    }
    @Test public void sourceDeclaredSelectorsExcludeOldOrUnsupportedOptions() throws Exception {
        assertEquals(Map.of("fantasy", "玄幻"), AiDramaFilterCompatibility.choices(fixture("ai-drama", 4, "fantasy", 1)));
    }
    @Test public void unfilteredNativeRoutesAreAcceptedOnlyWithoutTopicIds() throws Exception {
        var source = fixture("ai-drama", 4, "", 1);
        assertEquals(source, AiDramaFilterCompatibility.route(html(source), "ai-drama", 4, "", 1));
        source.getAsJsonObject("categoryRoute").getAsJsonArray("categoryJsonIds").add("7");
        try { AiDramaFilterCompatibility.route(html(source), "ai-drama", 4, "", 1); fail(); } catch (IOException expected) {}
    }
    @Test public void topicTypePageAndApiDeclarationsCannotBeCrossed() throws Exception {
        var correct = fixture("ai-drama", 4, "fantasy", 2);
        for (String key : List.of("dimension", "slug", "canonicalPath", "contentType")) {
            var altered = correct.deepCopy(); altered.getAsJsonObject("categoryRoute").addProperty(key, "incorrect"); reject(altered);
        }
        var altered = correct.deepCopy(); altered.getAsJsonObject("query").addProperty("page", 1); reject(altered);
        altered = correct.deepCopy(); altered.getAsJsonObject("apiQuery").addProperty("page_num", 1); reject(altered);
        altered = correct.deepCopy(); altered.getAsJsonObject("apiQuery").addProperty("content_type", 3); reject(altered);
        altered = correct.deepCopy(); altered.getAsJsonObject("apiQuery").getAsJsonArray("category_json_ids").add("99"); reject(altered);
        altered = correct.deepCopy(); altered.getAsJsonArray("selectorList").get(0).getAsJsonObject().getAsJsonArray("items")
                .get(0).getAsJsonObject().getAsJsonArray("category_json_ids").add("99"); reject(altered);
    }
    @Test public void unknownChallengesFailurePayloadsAndAmbiguousLoadersRemainFailures() throws Exception {
        for (String input : List.of("", "<html>captcha</html>", "window._ROUTER_DATA={}")) {
            try { AiDramaFilterCompatibility.route(input, "ai-drama", 4, "fantasy", 2); fail(); } catch (IOException expected) { assertNull(expected.getCause()); }
        }
        var source = fixture("ai-drama", 4, "fantasy", 2); source.addProperty("isSuccess", false); reject(source);
        String duplicate = html(fixture("ai-drama", 4, "fantasy", 2)).replace("\"loaderData\":{", "\"loaderData\":{\"duplicate\":" + fixture("ai-drama", 4, "fantasy", 2) + ",");
        try { AiDramaFilterCompatibility.route(duplicate, "ai-drama", 4, "fantasy", 2); fail(); } catch (IOException expected) {}
    }
    @Test public void rowsPreserveOriginalDetailIdPosterEpisodesAndTags() throws Exception {
        var result = AiDramaFilterCompatibility.pageResult(fixture("ai-drama", 4, "fantasy", 2), 2);
        var row = result.getAsJsonArray("list").get(0).getAsJsonObject();
        assertEquals("native-id", row.get("vod_id").getAsString()); assertEquals("Fixture series", row.get("vod_name").getAsString());
        assertEquals("https://fixture.invalid/cover.jpg", row.get("vod_pic").getAsString());
        assertEquals("全23集", row.get("vod_remarks").getAsString()); assertEquals("玄幻/冒险", row.get("vod_class").getAsString());
        assertEquals(2, result.get("page").getAsInt()); assertEquals(10, result.get("pagecount").getAsInt()); assertEquals(239, result.get("total").getAsInt());
    }
    @Test public void genuineShortLastPagePreservesServerEndWhileMalformedPagingFails() throws Exception {
        var source = fixture("ai-drama", 4, "fantasy", 10);
        var result = AiDramaFilterCompatibility.pageResult(source, 10); assertEquals(10, result.get("pagecount").getAsInt());
        source.getAsJsonObject("pagination").addProperty("pageNum", 9);
        try { AiDramaFilterCompatibility.pageResult(source, 10); fail(); } catch (IOException expected) {}
        source.getAsJsonObject("pagination").addProperty("pageNum", 10); source.getAsJsonObject("pagination").addProperty("totalPages", 0);
        try { AiDramaFilterCompatibility.pageResult(source, 10); fail(); } catch (IOException expected) {}
    }
    @Test public void originalDefaultApiAndUnrelatedSourcesKeepOriginalBehavior() throws Exception {
        assertNull(AiDramaFilterCompatibility.page(null, new AiDj(), "ai", 1, Map.of("topic", ""), null, null));
        String original = "{\"class\":[]}";
        assertSame(original, AiDramaFilterCompatibility.classes(null, new Spider() {}, original, null, null));
        assertNull(AiDramaFilterCompatibility.page(null, new Spider() {}, "ai", 1, Map.of("topic", "fantasy"), null, null));
    }
    @Test public void onlyAuditedProtocolAndTransparentGuardsEnableAdapter() {
        var real = new AiDj(); assertSame(real, AiDramaFilterCompatibility.audited(real));
        assertSame(real, AiDramaFilterCompatibility.audited(new BaseSpiderGuard(new BaseSpiderGuard(real))));
        assertNull(AiDramaFilterCompatibility.audited(new AiDj() {}));
        assertNull(AiDramaFilterCompatibility.audited(new BaseSpiderGuard(null)));
        class FakeWrapper extends Spider { private final Spider delegate = real; }
        assertNull(AiDramaFilterCompatibility.audited(new FakeWrapper()));
        class OverriddenGuard extends BaseSpiderGuard {
            OverriddenGuard() { super(real); }
            @Override public String categoryContent(String t, String p, boolean f, java.util.HashMap<String, String> e) { return ""; }
        }
        assertNull(AiDramaFilterCompatibility.audited(new OverriddenGuard()));
    }
    @Test public void pathsEncodeOnlyDeclaredSafeTopicAndCategoryValues() throws Exception {
        assertEquals("https://fixture.invalid/category/ai-drama/fantasy?page=2", AiDramaFilterCompatibility.routeUrl(HttpUrl.get("https://fixture.invalid/"), "ai-drama", "fantasy", 2).toString());
        for (String topic : List.of("../x", "x/y", "x?token=secret")) {
            try { AiDramaFilterCompatibility.routeUrl(HttpUrl.get("https://fixture.invalid/"), "ai-drama", topic, 1); fail(); } catch (IOException expected) { assertNull(expected.getCause()); }
        }
    }
    @Test public void interruptedAndTrackerCancelledRequestsStopBeforeDataIsAccepted() throws Exception {
        var client = new OkHttpClient(); var count = new AtomicInteger();
        Thread.currentThread().interrupt();
        try { AiDramaFilterCompatibility.execute(HttpUrl.get("http://127.0.0.1:1/"), client, call -> count.incrementAndGet()); fail(); }
        catch (java.io.InterruptedIOException expected) { assertEquals(0, count.get()); }
        finally { Thread.interrupted(); }
        try { AiDramaFilterCompatibility.execute(HttpUrl.get("http://127.0.0.1:1/"), client, call -> { count.incrementAndGet(); call.cancel(); }); fail(); }
        catch (IOException expected) { assertEquals(1, count.get()); }
    }
    @Test public void excessiveDeclaredBodyRemainsFailure() throws Exception {
        var client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(new ResponseBody() {
                    @Override public okhttp3.MediaType contentType() { return null; }
                    @Override public long contentLength() { return 4L * 1024 * 1024 + 1; }
                    @Override public okio.BufferedSource source() { return new okio.Buffer().writeUtf8("ignored"); }
                }).build()).build();
        try { AiDramaFilterCompatibility.execute(HttpUrl.get("https://fixture.invalid/"), client, call -> {}); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("limit")); }
    }
}
