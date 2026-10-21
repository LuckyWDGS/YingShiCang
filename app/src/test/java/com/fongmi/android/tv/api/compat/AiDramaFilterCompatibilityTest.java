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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;

public class AiDramaFilterCompatibilityTest {

    @Test public void nativeTopicTransportRequiresTheAuditedRequestFields() {
        var fields = List.of("/reading/distribution/category/landpage/v", "select_items", "category_dim_theme",
                "need_selector_panel", "filter_ids", "req_scene", "only_content", "client_req_type");
        assertTrue(AiDramaFilterCompatibility.nativeTopicShape(fields));
        for (String field : fields) {
            var changed = new java.util.ArrayList<>(fields); changed.remove(field);
            assertFalse(AiDramaFilterCompatibility.nativeTopicShape(changed));
        }
        var dependencies = List.of("_device_config", "API_HOST", "_core_sixgod", "json", "dumps", "requests", "post", "_json_response");
        assertTrue(AiDramaFilterCompatibility.nativeApiShape(List.of("path", "body", "config"), dependencies));
        assertFalse(AiDramaFilterCompatibility.nativeApiShape(List.of("body", "path", "config"), dependencies));
        for (String dependency : dependencies) {
            var changed = new java.util.ArrayList<>(dependencies); changed.remove(dependency);
            assertFalse(AiDramaFilterCompatibility.nativeApiShape(List.of("path", "body", "config"), changed));
        }
    }

    @Test public void renamedBridgesRetainExactAiDjIdentityAndDefaultRoute() throws Exception {
        // The provider re-obfuscates its bridge on every release (YU in October, AD after 1.1.0).
        // Equal-length class names preserve the constant-pool layout and avoid a second AiDj source.
        for (String renamed : new String[]{"YU", "AD"}) {
            byte[] bytes;
            try (var in = AiDj.class.getResourceAsStream("AiDj.class")) { bytes = in.readAllBytes(); }
            var encoding = java.nio.charset.StandardCharsets.ISO_8859_1;
            String oldName = "com/github/catvod/spider/merge/oE", newName = "com/github/catvod/spider/merge/" + renamed;
            String fixture = new String(bytes, encoding);
            assertTrue(fixture.contains(oldName));
            byte[] current = fixture.replace(oldName, newName).getBytes(encoding);
            byte[] bridge;
            try (var in = com.github.catvod.spider.merge.YU.class.getResourceAsStream("YU.class")) { bridge = in.readAllBytes(); }
            byte[] renamedBridge = new String(bridge, encoding).replace("com/github/catvod/spider/merge/YU", newName).getBytes(encoding);
            class ProviderLoader extends ClassLoader {
                ProviderLoader() { super(AiDj.class.getClassLoader()); }
                @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (name.equals(newName.replace('/', '.'))) return defineClass(name, renamedBridge, 0, renamedBridge.length);
                    return super.findClass(name);
                }
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals(newName.replace('/', '.'))) {
                        Class<?> loaded = findLoadedClass(name);
                        return loaded != null ? loaded : findClass(name);
                    }
                    return super.loadClass(name, resolve);
                }
                Class<?> provider() { return defineClass(AiDj.class.getName(), current, 0, current.length); }
            }
            Spider spider = (Spider) new ProviderLoader().provider().getConstructor().newInstance();
            assertEquals("com.github.catvod.spider.merge." + renamed, spider.getClass()
                    .getMethod("categoryContent", String.class, String.class, boolean.class, java.util.HashMap.class)
                    .getDeclaringClass().getName());
            assertSame(spider, AiDramaFilterCompatibility.audited(new BaseSpiderGuard(spider)));
            assertNull(AiDramaFilterCompatibility.page(null, spider, "comic", 1, Map.of("topic", ""), null, null));
        }
        assertNull(AiDramaFilterCompatibility.audited(new com.github.catvod.spider.merge.YU()));
    }

    @Test public void bridgeMustBeAiDjsDirectSuperclassInTheMergePackage() {
        assertTrue(AiDramaFilterCompatibility.isBridge(AiDj.class, "com.github.catvod.spider.merge.oE"));
        assertFalse(AiDramaFilterCompatibility.isBridge(AiDj.class, "com.github.catvod.spider.merge.YU"));
        assertFalse(AiDramaFilterCompatibility.isBridge(com.github.catvod.spider.merge.oE.class, "com.github.catvod.crawler.Spider"));
        assertFalse(AiDramaFilterCompatibility.isBridge(AiDj.class, "com.github.catvod.spider.merge.oE.Inner"));
    }

    @Test public void auditedHelperShapeFindsNestedProtocolConstantsWithinBoundedDepth() throws Exception {
        var nested = List.of(List.of("genre: short_play / comic_series / ai_series; sort: ['hot_score']"),
                List.of("sort", List.of("online_time")), "hot_score",
                "/reading/distribution/category/landpage/v");
        assertEquals(List.of("genre: short_play / comic_series / ai_series; sort: ['hot_score']", "sort", "online_time",
                "hot_score", "/reading/distribution/category/landpage/v"),
                AiDramaFilterCompatibility.flattenConstants(nested));
        assertTrue(AiDramaFilterCompatibility.nativeHelperShape(
                List.of("genre", "page", "config", "gender", "sort"),
                AiDramaFilterCompatibility.flattenConstants(nested), List.of("_api_post")));
        var tooDeep = List.of(List.of(List.of(List.of(List.of(List.of("online_time"))))));
        try { AiDramaFilterCompatibility.flattenConstants(tooDeep); fail("unbounded constants accepted"); }
        catch (ReflectiveOperationException expected) { }
    }
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

    @Test public void nativeLatestResultRetainsNativeRowsAndOnlyUsesHasMoreForPagination() throws Exception {
        var rows = new JsonArray();
        var first = new JsonObject(); first.addProperty("vod_id", "native-1"); first.addProperty("vod_name", "最新一");
        first.addProperty("vod_pic", "cover-1"); first.addProperty("vod_remarks", "全12集"); rows.add(first);
        var second = new JsonObject(); second.addProperty("vod_id", "native-2"); second.addProperty("vod_name", "最新二");
        second.addProperty("vod_class", "玄幻/冒险"); rows.add(second);
        var page = AiDramaFilterCompatibility.nativeLatestResult(rows, true, 2);
        assertEquals(2, page.get("page").getAsInt()); assertEquals(9999, page.get("pagecount").getAsInt());
        assertEquals("native-1", page.getAsJsonArray("list").get(0).getAsJsonObject().get("vod_id").getAsString());
        page = AiDramaFilterCompatibility.nativeLatestResult(rows, false, 2);
        assertEquals(2, page.get("pagecount").getAsInt());
        var malformed = rows.deepCopy(); malformed.get(0).getAsJsonObject().remove("vod_id");
        try { AiDramaFilterCompatibility.nativeLatestResult(malformed, false, 1); fail(); }
        catch (IOException expected) { }
    }
    @Test public void nativeLatestResultMapsOriginalAppRowsToVodSchema() throws Exception {
        var rows = new JsonArray(); var raw = new JsonObject();
        raw.addProperty("series_id", "app-1"); raw.addProperty("title", "应用最新");
        raw.addProperty("cover", "cover-app"); raw.addProperty("intro", "简介"); raw.addProperty("episode_cnt", 18);
        var tags = new JsonArray(); tags.add("玄幻"); raw.add("tags", tags); rows.add(raw);
        var page = AiDramaFilterCompatibility.nativeLatestResult(rows, true, 1);
        var item = page.getAsJsonArray("list").get(0).getAsJsonObject();
        assertEquals("app-1", item.get("vod_id").getAsString()); assertEquals("应用最新", item.get("vod_name").getAsString());
        assertEquals("cover-app", item.get("vod_pic").getAsString()); assertEquals("全18集", item.get("vod_remarks").getAsString());
        assertEquals("玄幻", item.get("vod_class").getAsString());
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
        assertNull(AiDramaFilterCompatibility.page(null, new Spider() {}, "other", 1,
                Map.of("sort", "online_time"), null, null));
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

    @Test public void coldNativeCategoryDocumentsRunTogetherAndPreserveTypeOrder() throws Exception {
        var entered = new CountDownLatch(2); var tracked = new AtomicInteger();
        var client = new OkHttpClient.Builder().addInterceptor(chain -> {
            entered.countDown();
            try {
                if (!entered.await(2, TimeUnit.SECONDS)) throw new IOException("Category requests did not overlap");
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException(); }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(chain.request().url().encodedPath(), null)).build();
        }).build();
        var urls = List.of(HttpUrl.get("https://fixture.invalid/comic"), HttpUrl.get("https://fixture.invalid/ai"));
        assertEquals(List.of("/comic", "/ai"), AiDramaFilterCompatibility.fetchConcurrent(urls, client, call -> tracked.incrementAndGet()));
        assertEquals(2, tracked.get());
    }

    @Test public void interruptedNativeMetadataCancelsBothTrackedRequestsAndCannotPublishPartialSuccess() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var tracked = new CopyOnWriteArrayList<okhttp3.Call>(); var failure = new AtomicReference<Throwable>();
        var client = new OkHttpClient.Builder().addInterceptor(chain -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException(); }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("late-body", null)).build();
        }).build();
        var worker = new Thread(() -> {
            try {
                AiDramaFilterCompatibility.fetchConcurrent(List.of(HttpUrl.get("https://fixture.invalid/comic"),
                        HttpUrl.get("https://fixture.invalid/ai")), client, tracked::add);
                failure.set(new AssertionError("Cancelled metadata accepted"));
            } catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); worker.interrupt(); worker.join(2000);
            assertFalse(worker.isAlive()); assertTrue(failure.get() instanceof java.io.InterruptedIOException);
            assertEquals(2, tracked.size()); assertTrue(tracked.stream().allMatch(okhttp3.Call::isCanceled));
        } finally { worker.interrupt(); release.countDown(); worker.join(2000); }
    }

    @Test public void nativeMetadataRequestCountAndTrackerCancellationRemainBounded() throws Exception {
        var count = new AtomicInteger(); var client = new OkHttpClient();
        try {
            AiDramaFilterCompatibility.fetchConcurrent(List.of(HttpUrl.get("https://fixture.invalid/1"),
                    HttpUrl.get("https://fixture.invalid/2"), HttpUrl.get("https://fixture.invalid/3")), client, call -> count.incrementAndGet());
            fail();
        } catch (IOException expected) { assertEquals(0, count.get()); }
        try {
            AiDramaFilterCompatibility.fetchConcurrent(List.of(HttpUrl.get("http://127.0.0.1:1/comic"),
                    HttpUrl.get("http://127.0.0.1:1/ai")), client, call -> { count.incrementAndGet(); call.cancel(); });
            fail();
        } catch (IOException expected) { assertEquals(2, count.get()); }
    }

    @Test public void pythonRuntimeGateSerializesBridgeOperationsAcrossThreads() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1); var active = new AtomicInteger(); var maximum = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        Thread first = new Thread(() -> {
            try {
                PythonPluginCompatibility.withRuntimeGate(() -> {
                    entered.countDown();
                    int now = active.incrementAndGet(); maximum.accumulateAndGet(now, Math::max);
                    try { release.await(2, TimeUnit.SECONDS); }
                    finally { active.decrementAndGet(); }
                    return null;
                });
            } catch (Throwable error) { failure.compareAndSet(null, error); }
        });
        Thread second = new Thread(() -> {
            try {
                PythonPluginCompatibility.withRuntimeGate(() -> {
                    secondEntered.countDown();
                    int now = active.incrementAndGet(); maximum.accumulateAndGet(now, Math::max);
                    active.decrementAndGet(); return null;
                });
            } catch (Throwable error) { failure.compareAndSet(null, error); }
        });
        first.start(); assertTrue(entered.await(1, TimeUnit.SECONDS)); second.start();
        assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
        release.countDown(); first.join(1000); second.join(1000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive()); assertNull(failure.get());
        assertEquals(1, maximum.get());
    }

    @Test public void cancelledSearchDoesNotWaitForPythonRuntimeGate() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var holderFailure = new AtomicReference<Throwable>();
        Thread holder = new Thread(() -> {
            try {
                PythonPluginCompatibility.withRuntimeGate(() -> {
                    entered.countDown(); release.await(2, TimeUnit.SECONDS); return null;
                });
            } catch (Throwable error) { holderFailure.set(error); }
        });
        holder.start(); assertTrue(entered.await(1, TimeUnit.SECONDS));
        var scope = new com.fongmi.android.tv.api.SearchRequestScope(new OkHttpClient(), 5000);
        var waiterFailure = new AtomicReference<Throwable>();
        Thread waiter = new Thread(() -> {
            try { PythonPluginCompatibility.withRuntimeGate(scope, () -> null); }
            catch (Throwable error) { waiterFailure.set(error); }
        });
        waiter.start(); scope.close(); waiter.join(1000); release.countDown(); holder.join(1000);
        assertFalse(waiter.isAlive()); assertFalse(holder.isAlive()); assertNull(holderFailure.get());
        assertTrue(waiterFailure.get() instanceof java.io.InterruptedIOException);
    }
}
