package com.fongmi.android.tv.api;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.compat.WoggAddressCompatibility;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Response;
import okhttp3.OkHttpClient;
import java.util.function.Consumer;

public class SiteApi {

    /*
     * A few bundled crawler jars contain native Go helpers (notably their
     * danmaku search implementation).  Those helpers share one Go runtime per
     * class loader and are not safe when two Spider instances from the same jar
     * enter it concurrently.  Aggregate search deliberately runs crawlers in
     * parallel, so serialize only calls that belong to the same plugin loader;
     * unrelated jars and normal HTTP sources keep their existing parallelism.
     */
    /*
     * Keep the gate weak.  A configuration refresh replaces DexClassLoader
     * instances; a strong key here would retain every old loader (and its
     * complete plugin dex/native graph) for the lifetime of the process.
     */
    private static final Map<ClassLoader, Semaphore> CRAWLER_GATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /*
     * A search can contain dozens of type-3 sources.  Creating/initializing
     * all of their spiders at once is both wasteful and unsafe for third-party
     * native helpers on low-memory TV devices.  Keep the executor lane
     * independent so HTTP sources can still make progress, but bound the
     * crawler initialization and the single-process Python bridge separately.
     */
    private static final Semaphore CRAWLER_INIT_GATE = new Semaphore(4, true);

    private static Semaphore crawlerGate(Spider spider) {
        ClassLoader loader = spider.getClass().getClassLoader();
        ClassLoader key = loader == null ? SiteApi.class.getClassLoader() : loader;
        synchronized (CRAWLER_GATES) {
            return CRAWLER_GATES.computeIfAbsent(key, ignored -> new Semaphore(1, true));
        }
    }

    /** The only bundled crawler with a shared native runtime that was audited as non-reentrant. */
    private static boolean isAppSx(Spider spider) {
        if (spider == null) return false;
        String name = spider.getClass().getName();
        return "com.github.catvod.spider.AppSx".equals(name)
                || "com.github.catvod.spider.AppSxGuard".equals(name);
    }

    private static boolean usesPythonBridge(Spider spider) {
        String name = spider.getClass().getName();
        return name.startsWith("com.fongmi.chaquo.")
                || com.fongmi.android.tv.api.compat.PythonPluginCompatibility.requiresRuntime(spider);
    }

    private static <T> T withPermit(Semaphore gate, Callable<T> operation) throws Exception {
        gate.acquire();
        try { return operation.call(); }
        finally { gate.release(); }
    }

    private static <T> T withPermit(Semaphore gate, SearchRequestScope scope, Callable<T> operation) throws Exception {
        boolean acquired = false;
        try {
            while (!acquired) {
                scope.checkCancellation();
                long wait = Math.min(50, scope.remainingMillis());
                acquired = gate.tryAcquire(wait, TimeUnit.MILLISECONDS);
            }
            scope.checkCancellation();
            return operation.call();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            java.io.InterruptedIOException cancelled = new java.io.InterruptedIOException("Search cancelled");
            cancelled.initCause(interrupted);
            throw cancelled;
        } finally {
            if (acquired) gate.release();
        }
    }

    private static Spider crawler(Site site) throws Exception {
        return withPermit(CRAWLER_INIT_GATE, site::spider);
    }

    private static Spider recentCrawler(Site site) throws Exception {
        return withPermit(CRAWLER_INIT_GATE, () -> site.recent().spider());
    }

    private static Spider searchSpider(Site site, SearchRequestScope scope) throws Exception {
        return withPermit(CRAWLER_INIT_GATE, scope, site::spider);
    }

    private static <T> T crawlerCall(Spider spider, Callable<T> operation) throws Exception {
        return crawlerCall(spider, null, operation);
    }

    private static <T> T crawlerCall(Spider spider, SearchRequestScope scope, Callable<T> operation) throws Exception {
        // Chaquopy exposes one Python interpreter to the whole process.  A
        // class-loader gate alone cannot protect Python-backed spiders because
        // they may be loaded by different JARs, so serialize all bridge calls.
        if (usesPythonBridge(spider)) {
            try {
                // Compatibility helpers may call the same Python bridge
                // outside this method; share their process-wide gate.
                return scope == null
                        ? com.fongmi.android.tv.api.compat.PythonPluginCompatibility.withRuntimeGate(operation)
                        : com.fongmi.android.tv.api.compat.PythonPluginCompatibility.withRuntimeGate(scope, operation);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                java.io.InterruptedIOException cancelled = new java.io.InterruptedIOException("Search cancelled");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        // A few bundled crawler jars contain native Go helpers (notably their
        // danmaku search implementation).  Those helpers share one Go runtime
        // per class loader and are not safe when two instances from the same
        // jar enter it concurrently.  Serialize only that known unsafe
        // implementation; unrelated jars keep their parallelism.
        if (!isAppSx(spider)) return operation.call();
        Semaphore gate = crawlerGate(spider);
        return scope == null ? withPermit(gate, operation) : withPermit(gate, scope, operation);
    }

    private static boolean directMedia(String url) {
        String scheme = android.net.Uri.parse(url).getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "content".equals(scheme) || "file".equals(scheme))
                && Sniffer.isVideoFormat(url);
    }


    public static final String PUSH = "push_agent";

    public static String call(@NonNull Site site, @NonNull ArrayMap<String, String> params) throws IOException {
        // Discovery can expose non-home type-4 sources whose extension has never
        // passed through homeContent(). Resolve it without changing the origin Site.
        OkHttpClient resolvedClient = sourceClient(site.getTimeout());
        if (site.getType() == 4 && site.getExt().startsWith("http")) {
            resolvedClient = sourceClient(Math.min(20000, Math.max(5000, site.getTimeout())));
            site = App.gson().fromJson(App.gson().toJson(site), Site.class);
            discoveryExtension(site, resolvedClient, call -> {});
        }
        if (!site.getExt().isEmpty()) params.put("extend", site.getExt());
        Call prototype = site.getExt().length() <= 1000 ? OkHttp.newCall(site.getApi(), site.getHeader(), params) : OkHttp.newCall(site.getApi(), site.getHeader(), OkHttp.toBody(params));
        Call call = resolvedClient.newCall(prototype.request());
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            if (response.body() == null) throw new IOException("Empty source response");
            String body = response.body().string();
            if (body.trim().isEmpty()) throw new IOException("Empty source response");
            return body;
        }
    }

    static OkHttpClient sourceClient(long timeoutMillis) {
        return OkHttp.client().newBuilder().callTimeout(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS).build();
    }

    private static boolean isSpider(@NonNull Site site) {
        return site.getType() == 3;
    }

    private static <T> T spiderCall(Spider spider, long budgetMillis, Callable<T> operation) throws Exception {
        return crawlerCall(spider, () -> WoggAddressCompatibility.call(spider, budgetMillis, () -> {}, operation));
    }

    private static long discoveryBudget(Site site) {
        return Math.min(20000, Math.max(5000, site.getTimeout()));
    }

    private static String ac(int type) {
        return type == 0 ? "videolist" : "detail";
    }

    /** Classification-only probing must not consume home recommendations or change recent playback. */
    public static void discoveryExtension(Site privateCopy, OkHttpClient client, Consumer<Call> calls) throws IOException {
        if (privateCopy.getType() != 4 || !privateCopy.getExt().startsWith("http")) return;
        Call call = client.newCall(new okhttp3.Request.Builder().url(privateCopy.getExt()).build());
        calls.accept(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Source extension unavailable");
            String body = response.body().string();
            if (body.trim().isEmpty()) throw new IOException("Source extension unavailable");
            privateCopy.setExt(body);
        }
    }

    public static Result discoveryClasses(Site site, OkHttpClient client, Consumer<Call> calls) throws Exception {
        if (isSpider(site)) {
            // Search is the only aggregate path which initializes many third
            // party spiders at once.  Keep initialization bounded so a slow
            // config cannot create dozens of Dex/Python/native runtimes in
            // parallel on a TV device.
            Spider spider = crawler(site);
            String home = com.fongmi.android.tv.api.compat.AiDramaFilterCompatibility.classes(site, spider,
                    spiderCall(spider, discoveryBudget(site), () -> spider.homeContent(true)), client, calls);
            Result result = discoveryResult(home, 3, "class");
            setDiscoveryTypes(site, result);
            return result;
        }
        ArrayMap<String, String> params = new ArrayMap<>();
        if (site.getType() == 4) params.put("filter", "true");
        String body = discoveryCall(site, params, client, calls);
        Result result = discoveryResult(body, site.getType(), "class");
        setDiscoveryTypes(site, result);
        return result;
    }

    /** Audited AI static types only: this does not wait for web topic metadata or consume recommendations. */
    public static Result discoveryNativeAiClasses(Site site) throws Exception {
        if (!isSpider(site)) throw new IOException("AI source native runtime unavailable");
        Spider spider = crawler(site);
        Result result = discoveryResult(com.fongmi.android.tv.api.compat.AiDramaFilterCompatibility.nativeClasses(
                spider, spiderCall(spider, discoveryBudget(site), () -> spider.homeContent(true))), 3, "class");
        setDiscoveryTypes(site, result);
        return result;
    }

    /** Uses the captured Site, rather than looking its key up in a possibly changed configuration. */
    public static Result discoveryPage(Site site, String tid, int page, Map<String, String> extend,
                                       OkHttpClient client, Consumer<Call> calls) throws Exception {
        if (isSpider(site)) {
            Spider spider = crawler(site);
            String compatible = com.fongmi.android.tv.api.compat.AiDramaFilterCompatibility.page(site, spider, tid, page, extend, client, calls);
            return discoveryResult(compatible != null ? compatible
                    : spiderCall(spider, discoveryBudget(site),
                            () -> spider.categoryContent(tid, String.valueOf(page), true, new HashMap<>(extend))), 3, "list");
        }
        ArrayMap<String, String> params = new ArrayMap<>();
        if (site.getType() == 1 && !extend.isEmpty()) params.put("f", App.gson().toJson(extend));
        if (site.getType() == 4) params.put("ext", Util.base64(App.gson().toJson(extend), Util.URL_SAFE));
        params.put("ac", ac(site.getType())); params.put("t", tid); params.put("pg", String.valueOf(page));
        return discoveryResult(discoveryCall(site, params, client, calls), site.getType(), "list");
    }

    private static String discoveryCall(Site site, ArrayMap<String, String> params, OkHttpClient client, Consumer<Call> calls) throws IOException {
        if (!site.getExt().isEmpty()) params.put("extend", site.getExt());
        Call prototype = site.getExt().length() <= 1000 ? OkHttp.newCall(site.getApi(), site.getHeader(), params)
                : OkHttp.newCall(site.getApi(), site.getHeader(), OkHttp.toBody(params));
        Call call = client.newCall(prototype.request());
        calls.accept(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Source unavailable");
            return response.body().string();
        }
    }

    private static Result discoveryResult(String body, int type, String field) throws IOException {
        if (body == null || body.trim().isEmpty()) {
            if (type == 3) return Result.empty(); // Optional crawler method unsupported.
            throw new IOException("Empty source response");
        }
        if (type != 0) {
            try {
                var value = com.google.gson.JsonParser.parseString(body);
                // The crawler base implementation can return an empty object when
                // it has no category-browsing capability. Malformed class fields or
                // error payloads still go through schema validation and fail.
                if (type == 3 && field.equals("class") && value.isJsonObject()
                        && value.getAsJsonObject().size() == 0) return Result.empty();
                if (!value.isJsonObject() || !value.getAsJsonObject().has(field)
                        || !value.getAsJsonObject().get(field).isJsonArray()) throw new IOException("Source schema unavailable");
                Result result = App.gson().fromJson(body, Result.class).trans();
                if (field.equals("list")) {
                    var raw = value.getAsJsonObject().getAsJsonArray(field);
                    for (int i = 0; i < raw.size() && i < result.getList().size(); i++)
                        if (raw.get(i).isJsonObject() && result.getList().get(i) != null)
                            result.getList().get(i).setDiscoveryAdult(com.fongmi.android.tv.discovery.DiscoveryContentGate.isAdultMetadata(raw.get(i).getAsJsonObject()));
                } else if (field.equals("class")) {
                    var raw = value.getAsJsonObject().getAsJsonArray(field);
                    for (int i = 0; i < raw.size() && i < result.getTypes().size(); i++) {
                        if (raw.get(i).isJsonObject() && result.getTypes().get(i) != null) {
                            result.getTypes().get(i).setDiscoveryAdult(com.fongmi.android.tv.discovery.DiscoveryContentGate.isAdultMetadata(raw.get(i).getAsJsonObject()));
                            setDiscoveryFilterMetadata(raw.get(i).getAsJsonObject().get("filters"), result.getTypes().get(i).getFilters());
                        }
                    }
                    var filters = value.getAsJsonObject().get("filters");
                    if (filters != null && filters.isJsonObject()) result.getFilters().forEach((id, parsed) ->
                            setDiscoveryFilterMetadata(filters.getAsJsonObject().get(id), parsed));
                }
                return result;
            } catch (RuntimeException error) { throw new IOException("Invalid source response", error); }
        } else if (!body.contains("<rss") || !body.contains("<" + field)) throw new IOException("Invalid XML source response");
        try { return new org.simpleframework.xml.core.Persister().read(Result.class, body, false).trans(); }
        catch (Exception error) { throw new IOException("Invalid XML source response", error); }
    }

    private static void setDiscoveryFilterMetadata(com.google.gson.JsonElement raw, List<com.fongmi.android.tv.bean.Filter> parsed) {
        if (raw == null || raw.isJsonNull()) return;
        List<com.google.gson.JsonElement> rawFilters = new ArrayList<>();
        if (raw.isJsonObject()) rawFilters.add(raw);
        else if (raw.isJsonArray()) raw.getAsJsonArray().forEach(rawFilters::add);
        for (var rawFilter : rawFilters) {
            if (!rawFilter.isJsonObject()) continue;
            var object = rawFilter.getAsJsonObject();
            String key = discoveryText(object.get("key"));
            var choices = object.get("value");
            if (key.isEmpty() || choices == null || !choices.isJsonArray()) continue;
            for (var filter : parsed) if (key.equals(filter.getKey())) {
                for (var choice : choices.getAsJsonArray()) {
                    if (!choice.isJsonObject()) continue;
                    String declaredValue = discoveryText(choice.getAsJsonObject().get("v")).trim();
                    if (!com.fongmi.android.tv.discovery.DiscoveryContentGate.isAdultMetadata(choice.getAsJsonObject())) continue;
                    for (var value : filter.getValue()) if (value != null && declaredValue.equals(value.getV())) value.setDiscoveryAdult(true);
                }
            }
        }
    }

    private static String discoveryText(com.google.gson.JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static void setDiscoveryTypes(Site site, Result result) {
        result.getTypes().forEach(type -> {
            if (result.getFilters().containsKey(type.getTypeId())) type.setFilters(result.getFilters().get(type.getTypeId()));
        });
        if (!site.getCategories().isEmpty()) result.setTypes(result.getTypes().stream()
                .filter(type -> site.getCategories().contains(type.getTypeName())).toList());
    }

    @NonNull
    public static Result homeContent(@NonNull Site site) throws Exception {
        if (isSpider(site)) {
            Spider spider = recentCrawler(site);
            boolean crash = Prefers.getBoolean("crash");
            String home = crash ? "" : spiderCall(spider, Constant.TIMEOUT_VOD, () -> spider.homeContent(true));
            String video = crash ? "" : spiderCall(spider, Constant.TIMEOUT_VOD, spider::homeVideoContent);
            Prefers.put("crash", false);
            SpiderDebug.log("home", home);
            SpiderDebug.log("homeVideo", video);
            Result result = Result.fromJson(home);
            List<Vod> list = Result.fromJson(video).getList();
            if (!list.isEmpty()) result.setList(list);
            setTypes(site, result);
            return result;
        } else if (site.getType() == 4) {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("filter", "true");
            String homeContent = call(site, params);
            SpiderDebug.log("home", homeContent);
            Result result = Result.fromJson(homeContent);
            setTypes(site, result);
            return result;
        } else {
            Call prototype = OkHttp.newCall(site.getApi(), site.getHeader());
            try (Response response = sourceClient(site.getTimeout()).newCall(prototype.request()).execute()) {
                String homeContent = readRequiredBody(response, "Source unavailable", "Empty source response");
                SpiderDebug.log("home", homeContent);
                Result result = Result.fromType(site.getType(), homeContent);
                fetchPic(site, result);
                setTypes(site, result);
                return result;
            }
        }
    }

    @NonNull
    public static Result categoryContent(@NonNull String key, @NonNull String tid, @NonNull String page, boolean filter, @NonNull HashMap<String, String> extend) throws Exception {
        SpiderDebug.log("category", "key=%s,tid=%s,page=%s,filter=%s,extend=%s", key, tid, page, filter, extend);
        Site site = VodConfig.get().getSite(key);
        if (isSpider(site)) {
            Spider spider = recentCrawler(site);
            String categoryContent = spiderCall(spider, Constant.TIMEOUT_VOD,
                    () -> spider.categoryContent(tid, page, filter, extend));
            SpiderDebug.log("category", categoryContent);
            return Result.fromJson(categoryContent);
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            if (site.getType() == 1 && !extend.isEmpty()) params.put("f", App.gson().toJson(extend));
            if (site.getType() == 4) params.put("ext", Util.base64(App.gson().toJson(extend), Util.URL_SAFE));
            params.put("ac", ac(site.getType()));
            params.put("t", tid);
            params.put("pg", page);
            String categoryContent = call(site, params);
            SpiderDebug.log("category", categoryContent);
            return Result.fromType(site.getType(), categoryContent);
        }
    }

    @NonNull
    public static Result detailContent(@NonNull String key, @NonNull String id) throws Exception {
        SpiderDebug.log("detail", "key=%s,id=%s", key, id);
        Site site = VodConfig.get().getSite(key);
        if (PUSH.equals(key) && (site.isEmpty() || directMedia(id))) {
            Vod vod = new Vod();
            vod.setId(id);
            vod.setName(id);
            vod.setPlayUrl(id);
            vod.setPlayFrom(ResUtil.getString(R.string.push));
            vod.setPic(ResUtil.getString(R.string.push_image));
            Source.get().parse(vod.setFlags());
            return Result.vod(vod);
        } else if (isSpider(site)) {
            Spider spider = recentCrawler(site);
            String detailContent = spiderCall(spider, Constant.TIMEOUT_VOD,
                    () -> spider.detailContent(Arrays.asList(id)));
            SpiderDebug.log("detail", detailContent);
            Result result = detailResult(site.getType(), detailContent);
            prepareDetail(result);
            return result;
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("ac", ac(site.getType()));
            params.put("ids", id);
            String detailContent = call(site, params);
            SpiderDebug.log("detail", detailContent);
            Result result = detailResult(site.getType(), detailContent);
            prepareDetail(result);
            return result;
        }
    }

    private static void prepareDetail(Result result) throws Exception {
        Vod vod = result.getVod().setFlags();
        boolean hadEpisodes = com.fongmi.android.tv.resource.ResourceHealth.hasPlayableEntry(vod);
        Source.get().parse(vod);
        if (hadEpisodes && !com.fongmi.android.tv.resource.ResourceHealth.hasPlayableEntry(vod))
            throw new IOException("Source detail extraction unavailable");
    }

    private static Result detailResult(int type, String body) throws IOException {
        if (type != 0) return App.gson().fromJson(ResourceResponseSchema.json(body, true), Result.class).trans();
        ResourceResponseSchema.xml(body);
        try { return new org.simpleframework.xml.core.Persister().read(Result.class, body, false).trans(); }
        catch (Exception error) { throw new IOException("Invalid XML detail response"); }
    }

    @NonNull
    public static Result playerContent(@NonNull String key, @NonNull String flag, @NonNull String id) throws Exception {
        SpiderDebug.log("player", "key=%s,flag=%s,id=%s", key, flag, id);
        Site site = VodConfig.get().getSite(key);
        Source.get().stop();
        if (PUSH.equals(key) && directMedia(id)) {
            Result result = new Result();
            result.setUrl(id);
            result.setKey(key);
            result.setParse(0);
            result.setFlag(flag);
            return result;
        } else if (site.getType() == 3) {
            Spider spider = recentCrawler(site);
            String playerContent = spiderCall(spider, Constant.TIMEOUT_VOD,
                    () -> spider.playerContent(flag, id, VodConfig.get().getFlags()));
            SpiderDebug.log("player", playerContent);
            Result result = App.gson().fromJson(ResourceResponseSchema.json(playerContent, false), Result.class).trans();
            if (result.getFlag().isEmpty()) result.setFlag(flag);
            result.setUrl(Source.get().fetch(result));
            result.setHeader(site.getHeader());
            result.setKey(key);
            return result;
        } else if (site.getType() == 4) {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("play", id);
            params.put("flag", flag);
            String playerContent = call(site, params);
            SpiderDebug.log("player", playerContent);
            Result result = App.gson().fromJson(ResourceResponseSchema.json(playerContent, false), Result.class).trans();
            if (result.getFlag().isEmpty()) result.setFlag(flag);
            result.setUrl(Source.get().fetch(result));
            result.setHeader(site.getHeader());
            result.setKey(key);
            return result;
        } else if (site.isEmpty() && "push_agent".equals(key)) {
            Result result = new Result();
            result.setUrl(id);
            result.setKey(key);
            result.setParse(0);
            result.setFlag(flag);
            result.setUrl(Source.get().fetch(result));
            SpiderDebug.log("player", result.toString());
            return result;
        } else {
            Result result = new Result();
            result.setUrl(id);
            result.setKey(key);
            result.setFlag(flag);
            result.setHeader(site.getHeader());
            result.setPlayUrl(site.getPlayUrl());
            result.setParse(Sniffer.isVideoFormat(id) && result.getPlayUrl().isEmpty() ? 0 : 1);
            result.setUrl(Source.get().fetch(result));
            SpiderDebug.log("player", result.toString());
            return result;
        }
    }

    @NonNull
    public static Result searchContent(@NonNull Site site, @NonNull String keyword, boolean quick, @NonNull String page) throws Exception {
        try (SearchTask task = searchTask(site, keyword, quick, page)) { return task.call(); }
    }

    public static SearchTask searchTask(Site site, String keyword, boolean quick, String page) {
        return new SearchTask(site, keyword, quick, page);
    }

    /** Used by both aggregate searches and single-source pagination so cancellation reaches HTTP. */
    public static final class SearchTask implements Callable<Result>, AutoCloseable {
        private final Site site;
        private final String keyword, page;
        private final boolean quick;
        private final SearchRequestScope scope = new SearchRequestScope(com.fongmi.android.tv.Constant.TIMEOUT_SEARCH);

        private SearchTask(Site site, String keyword, boolean quick, String page) {
            this.site = site; this.keyword = keyword; this.quick = quick; this.page = page;
        }

        @Override public Result call() throws Exception {
            try {
                scope.checkCancellation();
                if (quick && !site.isQuickSearch()) return Result.empty();
                return searchContent(site, keyword, quick, page, scope);
            } catch (Exception error) {
                com.fongmi.android.tv.diagnostics.DiagnosticLog.write(App.get(), "source_search_failed",
                        "source=" + com.github.catvod.utils.Crypto.md5(site.getKey())
                                + " type=" + site.getType() + " error=" + error.getClass().getSimpleName());
                throw error;
            } finally { close(); }
        }

        @Override public void close() { scope.close(); }
        public boolean isCrawler() { return site.getType() == 3; }
    }

    private static Result searchContent(Site site, String keyword, boolean quick, String page, SearchRequestScope scope) throws Exception {
        SpiderDebug.log("search", "site=%s,keyword=%s,quick=%s,page=%s", site.getName(), keyword, quick, page);
        boolean hasPage = !page.equals("1");
        if (isSpider(site)) {
            Spider spider = searchSpider(site, scope);
            // Only the verified base implementation has no optional pagination.
            if (hasPage && SpiderSearchCapability.hasDefaultPagination(spider)) return Result.empty();
            String searchContent = crawlerCall(spider, scope, () -> WoggAddressCompatibility.call(spider,
                    scope::remainingMillis, scope::checkCancellation,
                    () -> hasPage ? spider.searchContent(keyword, quick, page) : spider.searchContent(keyword, quick)));
            scope.checkCancellation();
            if (!quick && com.fongmi.android.tv.api.compat.SixVSearchCompatibility.emptyFirstPage(searchContent, page)
                    && com.fongmi.android.tv.api.compat.SixVSearchCompatibility.supports(spider, site.getExt())) {
                searchContent = com.fongmi.android.tv.api.compat.SixVSearchCompatibility.search(site.getExt(), keyword, scope);
            }
            SpiderDebug.log("search", searchContent);
            // An implemented method returning blank or an error payload is a failed query.
            Result result = searchResult(3, searchContent);
            for (Vod vod : result.getList()) vod.setSite(site);
            return result;
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("wd", keyword);
            params.put("quick", String.valueOf(quick));
            params.put("extend", "");
            if (hasPage) params.put("pg", page);
            Site requestSite = site;
            if (site.getType() == 4 && site.getExt().startsWith("http")) {
                requestSite = App.gson().fromJson(App.gson().toJson(site), Site.class);
                String extension = scope.execute(new okhttp3.Request.Builder().url(site.getExt()).build());
                if (extension.isEmpty()) throw new IOException("Source extension unavailable");
                requestSite.setExt(extension);
            }
            scope.checkCancellation();
            String searchContent = scope.execute(searchRequest(requestSite, params));
            SpiderDebug.log("search", searchContent);
            Result result = fetchSearchPic(requestSite, searchResult(site.getType(), searchContent), scope);
            scope.checkCancellation();
            for (Vod vod : result.getList()) vod.setSite(site);
            return result;
        }
    }

    private static Result searchResult(int type, String body) throws IOException {
        if (type == 0) {
            if (body == null || !body.contains("<rss") || !body.contains("<list"))
                throw new IOException("Invalid XML source search response");
            try { return new org.simpleframework.xml.core.Persister().read(Result.class, body, false).trans(); }
            catch (Exception error) { throw new IOException("Invalid XML source search response"); }
        }
        var object = SearchResponseSchema.json(body);
        try { return App.gson().fromJson(object, Result.class).trans(); }
        catch (RuntimeException error) { throw new IOException("Invalid source search fields"); }
    }

    private static okhttp3.Request searchRequest(Site site, ArrayMap<String, String> params) {
        if (!site.getExt().isEmpty()) params.put("extend", site.getExt());
        return (site.getExt().length() <= 1000 ? OkHttp.newCall(site.getApi(), site.getHeader(), params)
                : OkHttp.newCall(site.getApi(), site.getHeader(), OkHttp.toBody(params))).request();
    }

    static Result fetchSearchPic(Site site, Result result, SearchRequestScope scope) throws Exception {
        Vod first = result.getList().isEmpty() ? null : result.getList().get(0);
        if (site.getType() > 2 || first == null || !first.getPic().isEmpty()) return result;
        ArrayList<String> ids = new ArrayList<>();
        boolean empty = site.getCategories().isEmpty();
        for (Vod item : result.getList()) {
            if (item == null || (!empty && !site.getCategories().contains(item.getTypeName()))) continue;
            String id = item.getId();
            if (!id.isEmpty()) ids.add(id);
        }
        // Poster lookup is optional. A stale category declaration or id-less hit
        // must never erase valid search results when there is nothing to query.
        if (ids.isEmpty()) return result;
        if (!empty) result.setList(result.getList().stream()
                .filter(item -> item != null && ids.contains(item.getId())).toList());
        // Leave time for delivering the already valid hits before the overall source deadline.
        long optionalBudget;
        try { optionalBudget = Math.min(2000, scope.remainingMillis() - 250); }
        catch (java.io.InterruptedIOException timeout) { scope.checkCancellation(); return result; }
        if (optionalBudget <= 0) return result;
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("ac", ac(site.getType()));
        params.put("ids", TextUtils.join(",", ids));
        return SearchPosterEnrichment.apply(result, () -> Result.fromType(site.getType(),
                scope.execute(OkHttp.newCall(site.getApi(), site.getHeader(), params).request(), optionalBudget)));
    }

    @NonNull
    public static Result action(@NonNull String key, @NonNull String action) throws Exception {
        Site site = VodConfig.get().getSite(key);
        SpiderDebug.log("action", "key=%s,action=%s", key, action);
        if (site.getType() == 3) {
            Spider spider = recentCrawler(site);
            return Result.fromJson(spiderCall(spider, Constant.TIMEOUT_VOD, () -> spider.action(action)));
        }
        if (site.getType() == 4) {
            if (!action.startsWith("http")) return Result.empty();
            okhttp3.Request request = new okhttp3.Request.Builder().url(action).build();
            try (Response response = sourceClient(site.getTimeout()).newCall(request).execute()) {
                return Result.fromJson(readRequiredBody(response, "Source unavailable", "Empty source response"));
            }
        }
        return Result.empty();
    }

    @NonNull
    public static Result fetchPic(@NonNull Site site, @NonNull Result result) throws Exception {
        Vod first = result.getList().isEmpty() ? null : result.getList().get(0);
        if (site.getType() > 2 || first == null || !first.getPic().isEmpty()) return result;
        ArrayList<String> ids = new ArrayList<>();
        boolean empty = site.getCategories().isEmpty();
        for (Vod item : result.getList()) {
            if (item == null || (!empty && !site.getCategories().contains(item.getTypeName()))) continue;
            String id = item.getId();
            if (!id.isEmpty()) ids.add(id);
        }
        // A missing/stale category declaration or an id-less home row must not
        // erase a perfectly usable home feed just because poster enrichment has
        // nothing it can query.
        if (ids.isEmpty()) return result;
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("ac", ac(site.getType()));
        params.put("ids", String.join(",", ids));
        // Poster lookup is an optional enrichment of an otherwise usable home
        // response.  A source frequently serves its home feed and detail
        // endpoint through different backends, so a 404, timeout or empty
        // response here must not make the whole home page fail.  Merge only
        // valid poster rows and retain the original identities/order when the
        // enrichment is unavailable or malformed.
        return SearchPosterEnrichment.apply(result, () -> {
            Call prototype = OkHttp.newCall(site.getApi(), site.getHeader(), params);
            // Detail lookup only fills missing posters; cap it so a slow or
            // unreachable endpoint cannot hold the entire home page hostage.
            long enrichmentTimeout = Math.max(1, Math.min(2000, site.getTimeout()));
            try (Response response = sourceClient(enrichmentTimeout).newCall(prototype.request()).execute()) {
                return Result.fromType(site.getType(),
                        readRequiredBody(response, "Source unavailable", "Empty source response"));
            }
        });
    }

    /**
     * Reads an HTTP source response only after checking its transport and body.
     * Empty responses are unavailable data, rather than an empty successful feed.
     */
    static String readRequiredBody(Response response, String unavailableMessage, String emptyMessage) throws IOException {
        if (!response.isSuccessful()) throw new IOException(unavailableMessage);
        if (response.body() == null) throw new IOException(emptyMessage);
        String body = response.body().string();
        if (body.trim().isEmpty()) throw new IOException(emptyMessage);
        return body;
    }

    private static void setTypes(@NonNull Site site, @NonNull Result result) {
        result.getTypes().stream().filter(type -> result.getFilters().containsKey(type.getTypeId())).forEach(type -> type.setFilters(result.getFilters().get(type.getTypeId())));
        if (site.getCategories().isEmpty()) return;
        Map<String, Class> typeByName = new HashMap<>();
        result.getTypes().forEach(type -> typeByName.put(type.getTypeName(), type));
        List<Class> types = site.getCategories().stream().map(typeByName::get).filter(Objects::nonNull).toList();
        if (!types.isEmpty()) result.setTypes(types);
    }
}
