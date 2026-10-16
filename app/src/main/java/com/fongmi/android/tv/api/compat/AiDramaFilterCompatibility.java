package com.fongmi.android.tv.api.compat;

import com.chaquo.python.Kwarg;
import com.chaquo.python.PyObject;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.discovery.AiDramaCatalog;
import com.github.catvod.crawler.Spider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Audited AiDj ignores its declared topic facet on AI categories. Read their current native web protocol. */
public final class AiDramaFilterCompatibility {
    private static final String AI_DJ = "com.github.catvod.spider.AiDj";
    // Both releases expose the same bound Python instance in `s`. The provider
    // renamed its Java bridge in October; never infer support from branding alone.
    private static final Set<String> BRIDGES = Set.of("com.github.catvod.spider.merge.oE",
            "com.github.catvod.spider.merge.YU");
    private static final String GUARD = "com.github.catvod.spider.BaseSpiderGuard";
    private static final Pattern ROUTER = Pattern.compile("(?:window\\.)?_ROUTER_DATA\\s*=\\s*");
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final Map<String, Catalog> CACHE = new LinkedHashMap<>();
    private record Category(String id, String slug, int rpc, Map<String, String> choices) {}
    private record Catalog(HttpUrl base, Map<String, Category> categories) {}

    private AiDramaFilterCompatibility() {}

    static Spider audited(Spider spider) {
        IdentityHashMap<Spider, Boolean> seen = new IdentityHashMap<>();
        for (int depth = 0; spider != null && depth < 8; depth++) {
            if (seen.put(spider, Boolean.TRUE) != null) return null;
            try {
                String declaring = spider.getClass().getMethod("categoryContent", String.class,
                        String.class, boolean.class, java.util.HashMap.class).getDeclaringClass().getName();
                if (AI_DJ.equals(spider.getClass().getName()) && BRIDGES.contains(declaring)
                        && PythonPluginCompatibility.requiresRuntime(spider)) return spider;
                if (!GUARD.equals(declaring)) return null;
                Field delegate = null;
                for (Class<?> type = spider.getClass(); type != null && type != Spider.class; type = type.getSuperclass())
                    for (Field field : type.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || !Spider.class.isAssignableFrom(field.getType())) continue;
                        if (delegate != null) return null;
                        delegate = field;
                    }
                if (delegate == null) return null;
                delegate.setAccessible(true); spider = (Spider) delegate.get(spider);
            } catch (ReflectiveOperationException | RuntimeException error) { return null; }
        }
        return null;
    }

    /** Only the proven instance's Python function globals supply the origin; no configured domain is invented. */
    static HttpUrl origin(Spider primary) throws IOException {
        try {
            Object python = python(primary);
            Object method = attribute(python, "categoryContent");
            Object function = attribute(method, "__func__");
            Object globals = attribute(function, "__globals__");
            Object site = globals.getClass().getMethod("callAttr", String.class, Object[].class)
                    .invoke(globals, "get", new Object[]{"SITE"});
            HttpUrl url = site == null ? null : HttpUrl.parse(site.toString());
            if (url == null || !url.username().isEmpty() || !url.password().isEmpty() || url.query() != null
                    || url.fragment() != null || !"/".equals(url.encodedPath())) throw new ReflectiveOperationException();
            return url;
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Native reflection exceptions can include private script text or endpoints.
            throw new IOException("AI source web origin unavailable");
        }
    }

    private static PyObject python(Spider primary) throws ReflectiveOperationException {
        Class<?> bridge = primary.getClass().getSuperclass();
        while (bridge != null && !BRIDGES.contains(bridge.getName())) bridge = bridge.getSuperclass();
        if (bridge == null) throw new ReflectiveOperationException();
        Field field = bridge.getDeclaredField("s"); field.setAccessible(true);
        Object value = field.get(primary);
        if (!(value instanceof PyObject)) throw new ReflectiveOperationException();
        return (PyObject) value;
    }

    private record NativeHelper(PyObject instance, PyObject globals, PyObject helper, PyObject json) {}

    /** Same bound Python instance, globals and signed helper used by the original category method. */
    private static NativeHelper latestHelper(Spider spider) throws ReflectiveOperationException {
        Spider primary = audited(spider);
        if (primary == null) throw new ReflectiveOperationException();
        PyObject method = python(primary).get("categoryContent");
        PyObject function = method == null ? null : method.get("__func__");
        PyObject globals = function == null ? null : function.get("__globals__");
        PyObject instance = method == null ? null : method.get("__self__");
        PyObject helper = globals == null ? null : globals.callAttr("get", "_app_category_list");
        if (instance == null || helper == null || helper.get("__globals__") == null
                || helper.get("__globals__").id() != globals.id()) throw new ReflectiveOperationException();
        PyObject code = helper.get("__code__");
        if (code == null || code.get("co_argcount").toInt() != 5) throw new ReflectiveOperationException();
        List<String> arguments = code.get("co_varnames").asList().stream().limit(5).map(Object::toString).toList();
        List<String> constants = flattenConstants(code.get("co_consts"));
        List<String> names = code.get("co_names").asList().stream().map(Object::toString).toList();
        if (!nativeHelperShape(arguments, constants, names)) throw new ReflectiveOperationException();
        PyObject json = globals.callAttr("get", "json");
        if (json == null || instance.get("device_id") == null || instance.get("install_id") == null)
            throw new ReflectiveOperationException();
        return new NativeHelper(instance, globals, helper, json);
    }

    /**
     * Python bytecode keeps protocol strings in nested tuple constants.  Flatten only
     * a small, bounded shape: this is a capability check, so accepting arbitrary
     * recursive objects would make an unrelated helper look compatible.
     */
    static List<String> flattenConstants(Object values) throws ReflectiveOperationException {
        List<String> out = new ArrayList<>();
        flattenConstants(values, 0, new int[]{256}, out);
        return out;
    }

    private static void flattenConstants(Object value, int depth, int[] remaining, List<String> out)
            throws ReflectiveOperationException {
        if (depth > 4 || remaining[0]-- <= 0) throw new ReflectiveOperationException();
        if (value instanceof String string) out.add(string);
        else if (value instanceof List<?> values) {
            for (Object item : values) flattenConstants(item, depth + 1, remaining, out);
        } else if (value instanceof PyObject python) {
            String type = python.type().get("__name__").toString();
            if ("str".equals(type)) out.add(python.toString());
            else if ("tuple".equals(type))
                for (PyObject item : python.asList()) flattenConstants(item, depth + 1, remaining, out);
        }
    }

    static boolean nativeHelperShape(List<String> arguments, List<String> constants, List<String> names) {
        return arguments.equals(List.of("genre", "page", "config", "gender", "sort"))
                && constants.stream().anyMatch(value -> value.contains("genre: short_play / comic_series / ai_series")
                && value.contains("sort: ['hot_score']"))
                && constants.contains("online_time")
                && constants.contains("hot_score")
                && constants.contains("/reading/distribution/category/landpage/v") && names.contains("_api_post");
    }

    public static boolean supportsLatest(Spider spider) {
        try { latestHelper(spider); return true; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
    }

    /** Only genuine comic/ai types are provisional; the old generic topic declaration is unverified. */
    public static String nativeClasses(Spider spider, String raw) throws IOException {
        if (audited(spider) == null) throw new IOException("AI source native runtime unavailable");
        JsonObject home = object(raw); JsonArray clean = new JsonArray();
        JsonObject filters = new JsonObject();
        boolean latest = supportsLatest(spider);
        for (JsonElement value : array(home, "class")) if (value.isJsonObject()) {
            JsonObject type = value.getAsJsonObject().deepCopy(); String id = text(type, "type_id");
            if (!("comic".equals(id) || "ai".equals(id))) continue;
            type.remove("filters"); clean.add(type);
            // Sorting is a verified native capability, independent of the optional web topic catalog.
            if (latest) { JsonArray groups = new JsonArray(); groups.add(sortGroup()); filters.add(id, groups); }
        }
        if (clean.isEmpty()) throw new IOException("AI source native categories unavailable");
        home.add("class", clean); home.add("filters", filters);
        return home.toString();
    }

    private static JsonObject sortGroup() {
        JsonObject group = new JsonObject(); group.addProperty("key", AiDramaCatalog.SORT);
        group.addProperty("name", "排序"); group.addProperty("init", "");
        JsonArray options = new JsonArray(); options.add(option("默认", "")); options.add(option("最新", AiDramaCatalog.LATEST));
        group.add("value", options); return group;
    }
    private static Object attribute(Object value, String name) throws ReflectiveOperationException {
        if (value == null) throw new ReflectiveOperationException();
        Object result = value.getClass().getMethod("get", Object.class).invoke(value, name);
        if (result == null) throw new ReflectiveOperationException();
        return result;
    }

    public static String classes(Site site, Spider spider, String raw, OkHttpClient client, Consumer<Call> calls) throws IOException {
        Spider primary = audited(spider);
        if (primary == null) return raw;
        JsonObject home = object(raw);
        Catalog catalog = catalog(site, primary, client, calls);
        JsonArray types = array(home, "class"), clean = new JsonArray();
        JsonObject filters = new JsonObject();
        boolean latest = supportsLatest(primary);
        for (JsonElement value : types) {
            if (!value.isJsonObject()) continue;
            JsonObject type = value.getAsJsonObject().deepCopy();
            String id = text(type, "type_id"); Category category = catalog.categories.get(id);
            if (category == null) continue;
            type.remove("filters"); clean.add(type);
            JsonObject group = new JsonObject(); group.addProperty("key", "topic");
            group.addProperty("name", "题材"); group.addProperty("init", "");
            JsonArray options = new JsonArray();
            options.add(option("全部", ""));
            category.choices.forEach((key, label) -> options.add(option(label, key)));
            group.add("value", options); JsonArray groups = new JsonArray(); groups.add(group);
            if (latest) groups.add(sortGroup());
            filters.add(id, groups);
        }
        if (clean.isEmpty()) throw new IOException("AI source native categories unavailable");
        home.add("class", clean); home.add("filters", filters);
        return home.toString();
    }

    /** null retains the original API for ordinary/default pages and all unrelated plugins. */
    public static String page(Site site, Spider spider, String id, int page, Map<String, String> extend,
                              OkHttpClient client, Consumer<Call> calls) throws IOException {
        Spider primary = audited(spider);
        if (primary == null) return null;
        String topic = extend.getOrDefault("topic", "");
        String sort = extend.getOrDefault(AiDramaCatalog.SORT, "");
        if (!sort.isEmpty()) {
            if (!AiDramaCatalog.LATEST.equals(sort) || !topic.isEmpty() || page < 1
                    || !("comic".equals(id) || "ai".equals(id))
                    || !extend.keySet().stream().allMatch(key -> "topic".equals(key) || AiDramaCatalog.SORT.equals(key)))
                throw new IOException("AI source latest request unavailable");
            return nativeLatest(spider, id, page);
        }
        if (topic.isEmpty()) return null;
        if (page < 1 || !("comic".equals(id) || "ai".equals(id))
                || !extend.keySet().stream().allMatch(key -> "topic".equals(key) || AiDramaCatalog.SORT.equals(key)))
            throw new IOException("AI source filter declaration unavailable");
        Catalog catalog = catalog(site, primary, client, calls);
        Category category = catalog.categories.get(id);
        if (category == null || !category.choices.containsKey(topic)) throw new IOException("AI source filter option unavailable");
        JsonObject data = route(execute(routeUrl(catalog.base, category.slug, topic, page), client, calls), category.slug, category.rpc, topic, page);
        return pageResult(data, page).toString();
    }

    public static String nativeLatest(Spider spider, String id, int page) throws IOException {
        if (page < 1 || !("comic".equals(id) || "ai".equals(id))) throw new IOException("AI source latest category unavailable");
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("AI source request cancelled");
        try {
            NativeHelper nativeHelper = latestHelper(spider);
            JsonObject config = new JsonObject();
            config.addProperty("device_id", nativeHelper.instance.get("device_id").toString());
            config.addProperty("install_id", nativeHelper.instance.get("install_id").toString());
            if (text(config, "device_id").isEmpty() || text(config, "install_id").isEmpty()) throw new ReflectiveOperationException();
            PyObject pythonConfig = nativeHelper.json.callAttr("loads", config.toString());
            PyObject sort = nativeHelper.json.callAttr("loads", "[\"online_time\"]");
            PyObject response = nativeHelper.helper.call("comic".equals(id) ? "comic_series" : "ai_series", page,
                    pythonConfig, new Kwarg("sort", sort));
            List<PyObject> tuple = response.asList();
            if (tuple.size() != 2 || tuple.get(0) == null || tuple.get(1) == null) throw new ReflectiveOperationException();
            JsonElement rows = JsonParser.parseString(nativeHelper.json.callAttr("dumps", tuple.get(0)).toString());
            if (!rows.isJsonArray()) throw new ReflectiveOperationException();
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("AI source request cancelled");
            return nativeLatestResult(rows.getAsJsonArray(), tuple.get(1).toBoolean(), page).toString();
        } catch (java.io.InterruptedIOException cancelled) { throw cancelled; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            throw new IOException("AI source latest helper unavailable");
        }
    }

    static JsonObject nativeLatestResult(JsonArray rows, boolean more, int page) throws IOException {
        JsonArray list = new JsonArray();
        for (JsonElement value : rows) {
            if (!value.isJsonObject()) throw new IOException("AI source latest row unavailable");
            JsonObject raw = value.getAsJsonObject();
            // Keep already-normalized rows usable for fixtures and future helper
            // revisions which may perform the mapping internally.
            if (!text(raw, "vod_id").isEmpty() && !text(raw, "vod_name").isEmpty()) {
                list.add(raw.deepCopy());
                continue;
            }
            JsonObject row = raw.has("video_data") && raw.get("video_data").isJsonObject()
                    ? raw.getAsJsonObject("video_data") : raw;
            String id = text(row, "series_id");
            String name = text(row, "title");
            if (name.isEmpty()) name = text(row, "series_title");
            if (name.isEmpty()) name = text(row, "series_name");
            if (id.isEmpty()) id = text(raw, "keyword");
            if (id.isEmpty() || name.isEmpty()) throw new IOException("AI source latest identity unavailable");
            JsonObject item = new JsonObject(); item.addProperty("vod_id", id); item.addProperty("vod_name", name);
            String cover = text(row, "cover"); if (cover.isEmpty()) cover = text(row, "series_cover");
            String intro = text(row, "intro"); if (intro.isEmpty()) intro = text(row, "series_intro");
            item.addProperty("vod_pic", cover); item.addProperty("vod_content", intro);
            int episodes = integer(row, "episode_cnt");
            item.addProperty("vod_remarks", episodes > 0 ? "全" + episodes + "集" : "");
            JsonElement tags = row.get("tags");
            if (tags != null && tags.isJsonArray()) {
                List<String> labels = new ArrayList<>();
                for (JsonElement tag : tags.getAsJsonArray())
                    if (tag.isJsonPrimitive() && tag.getAsJsonPrimitive().isString()) labels.add(tag.getAsString());
                item.addProperty("vod_class", String.join("/", labels));
            }
            list.add(item);
        }
        JsonObject result = new JsonObject(); result.add("list", list); result.addProperty("page", page);
        result.addProperty("pagecount", more ? 9999 : page); result.addProperty("limit", list.size());
        // The native helper declares only has_more, not a catalog total.
        return result;
    }

    private static Catalog catalog(Site site, Spider primary, OkHttpClient client, Consumer<Call> calls) throws IOException {
        HttpUrl base = origin(primary);
        long revision = VodConfig.getDiscoveryRevision();
        String signature = signature(site, base, revision);
        synchronized (CACHE) { Catalog cached = CACHE.get(signature); if (cached != null) return cached; }
        List<HttpUrl> routes = List.of(routeUrl(base, "comic-drama", "", 1), routeUrl(base, "ai-drama", "", 1));
        List<String> pages = fetchConcurrent(routes, client, calls);
        Map<String, Category> categories = new LinkedHashMap<>();
        for (int index = 0; index < 2; index++) {
            String id = index == 0 ? "comic" : "ai";
            String slug = index == 0 ? "comic-drama" : "ai-drama"; int rpc = index == 0 ? 3 : 4;
            JsonObject data = route(pages.get(index), slug, rpc, "", 1);
            Map<String, String> choices = choices(data);
            categories.put(id, new Category(id, slug, rpc, java.util.Collections.unmodifiableMap(choices)));
        }
        Catalog catalog = new Catalog(base, java.util.Collections.unmodifiableMap(categories));
        synchronized (CACHE) {
            if (revision == VodConfig.getDiscoveryRevision()) {
                CACHE.put(signature, catalog);
                while (CACHE.size() > 32) CACHE.remove(CACHE.keySet().iterator().next());
            }
        }
        return catalog;
    }

    /**
     * Fetch the two native category documents together. Each group is limited to two calls,
     * handed to the caller's tracker before enqueue so the existing timeout and cancellation
     * budget applies to both requests.
     */
    static List<String> fetchConcurrent(List<HttpUrl> urls, OkHttpClient client,
                                       Consumer<Call> calls) throws IOException {
        if (urls.isEmpty()) return List.of();
        if (urls.size() > 2) throw new IOException("AI source category request limit exceeded");
        CountDownLatch done = new CountDownLatch(urls.size());
        AtomicReferenceArray<String> bodies = new AtomicReferenceArray<>(urls.size());
        AtomicReference<IOException> failure = new AtomicReference<>();
        List<Call> pending = new ArrayList<>(urls.size());
        try {
            for (HttpUrl url : urls) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("AI source request cancelled");
                Call call = client.newCall(new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build());
                pending.add(call); calls.accept(call);
            }
        } catch (IOException | RuntimeException error) { pending.forEach(Call::cancel); throw error; }
        for (int index = 0; index < pending.size(); index++) {
            Call call = pending.get(index);
            final int slot = index;
            call.enqueue(new okhttp3.Callback() {
                @Override public void onFailure(Call ignored, IOException error) {
                    if (failure.compareAndSet(null, error)) pending.forEach(Call::cancel);
                    done.countDown();
                }
                @Override public void onResponse(Call ignored, Response response) {
                    try {
                        if (!response.isSuccessful() || response.body() == null) throw new IOException("AI source page unavailable");
                        String body = readBody(response);
                        if (call.isCanceled()) throw new java.io.InterruptedIOException("AI source request cancelled");
                        bodies.set(slot, body);
                    } catch (IOException error) {
                        if (failure.compareAndSet(null, error)) pending.forEach(Call::cancel);
                    }
                    finally { response.close(); done.countDown(); }
                }
            });
        }
        try { done.await(); }
        catch (InterruptedException error) {
            pending.forEach(Call::cancel); Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("AI source request cancelled");
        }
        IOException error = failure.get();
        if (error != null) { pending.forEach(Call::cancel); throw error; }
        List<String> result = new ArrayList<>(urls.size());
        for (int index = 0; index < urls.size(); index++) result.add(bodies.get(index));
        return result;
    }

    private static String signature(Site site, HttpUrl base, long revision) {
        String raw = revision + "\0" + site.getKey() + "\0" + site.getApi() + "\0" + site.getExt() + "\0"
                + site.getJar() + "\0" + site.getHeader() + "\0" + site.getCategories() + "\0" + base;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte part : hash) value.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
            return value.toString();
        } catch (Exception impossible) { throw new IllegalStateException(); }
    }
    static HttpUrl routeUrl(HttpUrl base, String slug, String topic, int page) throws IOException {
        if (!("comic-drama".equals(slug) || "ai-drama".equals(slug))
                || !topic.matches("[a-z0-9-]{0,64}") || page < 1) throw new IOException("AI source route unavailable");
        HttpUrl.Builder url = base.newBuilder().addPathSegment("category").addPathSegment(slug);
        if (!topic.isEmpty()) url.addPathSegment(topic);
        return url.addQueryParameter("page", Integer.toString(page)).build();
    }
    static String execute(HttpUrl url, OkHttpClient client, Consumer<Call> calls) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("AI source request cancelled");
        Call call = client.newCall(new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build());
        calls.accept(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("AI source page unavailable");
            String body = readBody(response);
            if (Thread.currentThread().isInterrupted() || call.isCanceled()) throw new java.io.InterruptedIOException("AI source request cancelled");
            return body;
        }
    }

    private static String readBody(Response response) throws IOException {
        if (response.body().contentLength() > MAX_BODY) throw new IOException("AI source page exceeds limit");
        try (var input = response.body().byteStream(); var output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BODY) throw new IOException("AI source page exceeds limit");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Extract only the native category loader and require its own route/query to match the request. */
    static JsonObject route(String html, String slug, int rpc, String topic, int page) throws IOException {
        Matcher marker = ROUTER.matcher(html == null ? "" : html);
        if (!marker.find()) throw new IOException("AI source category schema unavailable");
        try {
            JsonReader reader = new JsonReader(new StringReader(html.substring(marker.end())));
            JsonElement value = JsonParser.parseReader(reader);
            JsonObject loaders = value.getAsJsonObject().getAsJsonObject("loaderData");
            if (loaders == null) throw new IOException("AI source loader unavailable");
            JsonObject data = null;
            for (var entry : loaders.entrySet()) if (entry.getValue().isJsonObject()) {
                JsonObject candidate = entry.getValue().getAsJsonObject();
                if (candidate.has("recommendList") && candidate.has("categoryRoute")) {
                    if (data != null) throw new IOException("AI source category schema ambiguous");
                    data = candidate;
                }
            }
            if (data == null) throw new IOException("AI source category unavailable");
            JsonObject category = nested(data, "categoryRoute"), query = nested(data, "query"), api = nested(data, "apiQuery");
            String expectedPath = "/category/" + slug + (topic.isEmpty() ? "" : "/" + topic);
            if (!slug.equals(text(category, "contentType")) || integer(category, "rpcContentType") != rpc
                    || !expectedPath.equals(text(category, "canonicalPath")) || !text(query, "topic").isEmpty()
                    || integer(query, "page") != page || integer(api, "page_num") != page || integer(api, "content_type") != rpc
                    || !category.equals(nested(api, "route"))
                    || !array(category, "categoryJsonIds").equals(array(api, "category_json_ids"))
                    || !data.has("isSuccess") || !data.get("isSuccess").getAsBoolean())
                throw new IOException("AI source route response mismatched");
            // Current native routing carries the selected topic in the route, leaving query.topic empty.
            if (!topic.isEmpty()) {
                if (!"topic".equals(text(category, "dimension")) || !topic.equals(text(category, "slug"))
                        || array(category, "categoryJsonIds").isEmpty()
                        || !declaredTopicIds(data, topic).equals(array(category, "categoryJsonIds")))
                    throw new IOException("AI source topic response mismatched");
            } else if (!array(category, "categoryJsonIds").isEmpty())
                throw new IOException("AI source aggregate response mismatched");
            array(data, "recommendList"); nested(data, "pagination"); return data;
        } catch (RuntimeException error) { throw new IOException("AI source category schema unavailable"); }
    }
    private static JsonArray declaredTopicIds(JsonObject data, String topic) throws IOException {
        for (JsonElement row : array(data, "selectorList")) {
            if (!row.isJsonObject()) throw new IOException("AI source selectors unavailable");
            for (JsonElement value : array(row.getAsJsonObject(), "items")) if (value.isJsonObject()) {
                JsonObject item = value.getAsJsonObject();
                if (topic.equals(text(item, "selector_item_id"))) return array(item, "category_json_ids");
            }
        }
        throw new IOException("AI source topic declaration unavailable");
    }
    static Map<String, String> choices(JsonObject data) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonElement row : array(data, "selectorList")) {
            if (!row.isJsonObject()) throw new IOException("AI source selectors unavailable");
            for (JsonElement value : array(row.getAsJsonObject(), "items")) {
                if (!value.isJsonObject()) continue;
                JsonObject item = value.getAsJsonObject();
                String id = text(item, "selector_item_id"), label = text(item, "show_name");
                if (!id.matches("[a-z0-9-]{1,64}") || label.isEmpty() || label.length() > 80) continue;
                // Current native selector declarations include their real server category ids.
                if (array(item, "category_json_ids").isEmpty()) continue;
                result.putIfAbsent(id, label);
            }
        }
        if (result.isEmpty()) throw new IOException("AI source filter declarations unavailable");
        return result;
    }
    static JsonObject pageResult(JsonObject data, int page) throws IOException {
        JsonObject pagination = nested(data, "pagination");
        int returned = integer(pagination, "pageNum"), pages = integer(pagination, "totalPages");
        if (returned != page || pages < 1) throw new IOException("AI source pagination unavailable");
        JsonArray result = new JsonArray();
        for (JsonElement value : array(data, "recommendList")) {
            if (!value.isJsonObject()) throw new IOException("AI source row unavailable");
            JsonObject raw = value.getAsJsonObject();
            JsonObject row = raw.has("video_data") && raw.get("video_data").isJsonObject() ? raw.getAsJsonObject("video_data") : raw;
            String id = text(row, "series_id"), name = text(row, "series_title");
            if (id.isEmpty()) id = text(raw, "keyword");
            if (name.isEmpty()) name = text(row, "series_name");
            if (name.isEmpty()) name = text(raw, "name");
            if (id.isEmpty() || name.isEmpty()) throw new IOException("AI source row identity unavailable");
            JsonObject item = new JsonObject(); item.addProperty("vod_id", id); item.addProperty("vod_name", name);
            item.addProperty("vod_pic", text(row, "series_cover")); item.addProperty("vod_content", text(row, "series_intro"));
            int episodes = integer(row, "episode_cnt"); item.addProperty("vod_remarks", episodes > 0 ? "全" + episodes + "集" : "");
            JsonElement tags = row.get("tags");
            if (tags != null && tags.isJsonArray()) {
                java.util.List<String> labels = new java.util.ArrayList<>();
                for (JsonElement tag : tags.getAsJsonArray()) if (tag.isJsonPrimitive() && tag.getAsJsonPrimitive().isString()) labels.add(tag.getAsString());
                item.addProperty("vod_class", String.join("/", labels));
            }
            result.add(item);
        }
        JsonObject response = new JsonObject(); response.add("list", result); response.addProperty("page", returned);
        response.addProperty("pagecount", pages); response.addProperty("limit", integer(pagination, "pageSize"));
        response.addProperty("total", integer(pagination, "total")); return response;
    }
    private static JsonObject option(String name, String value) {
        JsonObject result = new JsonObject(); result.addProperty("n", name); result.addProperty("v", value); return result;
    }
    private static JsonObject object(String raw) throws IOException {
        try { return JsonParser.parseString(raw).getAsJsonObject(); }
        catch (RuntimeException error) { throw new IOException("AI source category metadata unavailable"); }
    }
    private static JsonObject nested(JsonObject parent, String key) throws IOException {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonObject()) throw new IOException("AI source category metadata unavailable");
        return value.getAsJsonObject();
    }
    private static JsonArray array(JsonObject parent, String key) throws IOException {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonArray()) throw new IOException("AI source category metadata unavailable");
        return value.getAsJsonArray();
    }
    private static String text(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonPrimitive() && !value.isJsonNull() ? value.getAsString() : "";
    }
    private static int integer(JsonObject parent, String key) {
        try { return parent.has(key) ? parent.get(key).getAsInt() : 0; }
        catch (RuntimeException error) { return 0; }
    }
}
