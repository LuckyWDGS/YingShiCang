package com.fongmi.android.tv.api;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

import okhttp3.Call;
import okhttp3.Response;
import okhttp3.OkHttpClient;
import java.util.function.Consumer;

public class SiteApi {

    private static boolean directMedia(String url) {
        String scheme = android.net.Uri.parse(url).getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "content".equals(scheme) || "file".equals(scheme))
                && Sniffer.isVideoFormat(url);
    }


    public static final String PUSH = "push_agent";

    public static String call(@NonNull Site site, @NonNull ArrayMap<String, String> params) throws IOException {
        // Discovery can expose non-home type-4 sources whose extension has never
        // passed through homeContent(). Resolve it without changing the origin Site.
        OkHttpClient resolvedClient = null;
        if (site.getType() == 4 && site.getExt().startsWith("http")) {
            resolvedClient = OkHttp.client().newBuilder().callTimeout(
                    Math.min(20000, Math.max(5000, site.getTimeout())), java.util.concurrent.TimeUnit.MILLISECONDS).build();
            site = App.gson().fromJson(App.gson().toJson(site), Site.class);
            discoveryExtension(site, resolvedClient, call -> {});
        }
        if (!site.getExt().isEmpty()) params.put("extend", site.getExt());
        Call call = site.getExt().length() <= 1000 ? OkHttp.newCall(site.getApi(), site.getHeader(), params) : OkHttp.newCall(site.getApi(), site.getHeader(), OkHttp.toBody(params));
        if (resolvedClient != null) call = resolvedClient.newCall(call.request());
        try (Response response = call.execute()) {
            return response.body().string();
        }
    }

    private static boolean isSpider(@NonNull Site site) {
        return site.getType() == 3;
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
            if (body.isEmpty()) throw new IOException("Source extension unavailable");
            privateCopy.setExt(body);
        }
    }

    public static Result discoveryClasses(Site site, OkHttpClient client, Consumer<Call> calls) throws Exception {
        if (isSpider(site)) {
            Spider spider = site.spider();
            String home = com.fongmi.android.tv.api.compat.AiDramaFilterCompatibility.classes(site, spider,
                    spider.homeContent(true), client, calls);
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

    /** Uses the captured Site, rather than looking its key up in a possibly changed configuration. */
    public static Result discoveryPage(Site site, String tid, int page, Map<String, String> extend,
                                       OkHttpClient client, Consumer<Call> calls) throws Exception {
        if (isSpider(site)) {
            Spider spider = site.spider();
            String compatible = com.fongmi.android.tv.api.compat.AiDramaFilterCompatibility.page(site, spider, tid, page, extend, client, calls);
            return discoveryResult(compatible != null ? compatible
                    : spider.categoryContent(tid, String.valueOf(page), true, new HashMap<>(extend)), 3, "list");
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
            Spider spider = site.recent().spider();
            boolean crash = Prefers.getBoolean("crash");
            String home = crash ? "" : spider.homeContent(true);
            String video = crash ? "" : spider.homeVideoContent();
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
            String homeContent = call(site.fetchExt(), params);
            SpiderDebug.log("home", homeContent);
            Result result = Result.fromJson(homeContent);
            setTypes(site, result);
            return result;
        } else {
            try (Response response = OkHttp.newCall(site.getApi(), site.getHeader()).execute()) {
                String homeContent = response.body().string();
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
            String categoryContent = site.recent().spider().categoryContent(tid, page, filter, extend);
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
            String detailContent = site.recent().spider().detailContent(Arrays.asList(id));
            SpiderDebug.log("detail", detailContent);
            Result result = Result.fromJson(detailContent);
            Source.get().parse(result.getVod().setFlags());
            return result;
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("ac", ac(site.getType()));
            params.put("ids", id);
            String detailContent = call(site, params);
            SpiderDebug.log("detail", detailContent);
            Result result = Result.fromType(site.getType(), detailContent);
            Source.get().parse(result.getVod().setFlags());
            return result;
        }
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
            String playerContent = site.recent().spider().playerContent(flag, id, VodConfig.get().getFlags());
            SpiderDebug.log("player", playerContent);
            Result result = Result.fromJson(playerContent);
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
            Result result = Result.fromJson(playerContent);
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
            Spider spider = site.spider();
            // Only the verified base implementation has no optional pagination.
            if (hasPage && SpiderSearchCapability.hasDefaultPagination(spider)) return Result.empty();
            String searchContent = hasPage ? spider.searchContent(keyword, quick, page) : spider.searchContent(keyword, quick);
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

    private static Result fetchSearchPic(Site site, Result result, SearchRequestScope scope) throws Exception {
        if (site.getType() > 2 || result.getList().isEmpty() || !result.getVod().getPic().isEmpty()) return result;
        ArrayList<String> ids = new ArrayList<>();
        boolean empty = site.getCategories().isEmpty();
        for (Vod item : result.getList()) if (empty || site.getCategories().contains(item.getTypeName())) ids.add(item.getId());
        if (ids.isEmpty()) return result.clear();
        if (!empty) result.setList(result.getList().stream().filter(item -> ids.contains(item.getId())).toList());
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
        if (site.getType() == 3) return Result.fromJson(site.recent().spider().action(action));
        if (site.getType() == 4) return Result.fromJson(OkHttp.string(action));
        return Result.empty();
    }

    @NonNull
    public static Result fetchPic(@NonNull Site site, @NonNull Result result) throws Exception {
        if (site.getType() > 2 || result.getList().isEmpty() || !result.getVod().getPic().isEmpty()) return result;
        ArrayList<String> ids = new ArrayList<>();
        boolean empty = site.getCategories().isEmpty();
        for (Vod item : result.getList()) if (empty || site.getCategories().contains(item.getTypeName())) ids.add(item.getId());
        if (ids.isEmpty()) return result.clear();
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("ac", ac(site.getType()));
        params.put("ids", TextUtils.join(",", ids));
        try (Response response = OkHttp.newCall(site.getApi(), site.getHeader(), params).execute()) {
            result.setList(Result.fromType(site.getType(), response.body().string()).getList());
            return result;
        }
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
