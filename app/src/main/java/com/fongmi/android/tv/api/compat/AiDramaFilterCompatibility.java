package com.fongmi.android.tv.api.compat;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private static final String BRIDGE = "com.github.catvod.spider.merge.oE";
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
                if (AI_DJ.equals(spider.getClass().getName()) && BRIDGE.equals(declaring)
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
            Class<?> bridge = primary.getClass().getSuperclass();
            while (bridge != null && !BRIDGE.equals(bridge.getName())) bridge = bridge.getSuperclass();
            if (bridge == null) throw new ReflectiveOperationException();
            Field field = bridge.getDeclaredField("s"); field.setAccessible(true);
            Object python = field.get(primary);
            if (python == null || !"com.chaquo.python.PyObject".equals(python.getClass().getName())) throw new ReflectiveOperationException();
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
            group.add("value", options); JsonArray groups = new JsonArray(); groups.add(group); filters.add(id, groups);
        }
        if (clean.isEmpty()) throw new IOException("AI source native categories unavailable");
        home.add("class", clean); home.add("filters", filters);
        return home.toString();
    }

    /** null retains the original API for ordinary/default pages and all unrelated plugins. */
    public static String page(Site site, Spider spider, String id, int page, Map<String, String> extend,
                              OkHttpClient client, Consumer<Call> calls) throws IOException {
        String topic = extend.getOrDefault("topic", "");
        if (topic.isEmpty()) return null;
        Spider primary = audited(spider);
        if (primary == null) return null;
        if (page < 1 || !("comic".equals(id) || "ai".equals(id)) || extend.size() != 1)
            throw new IOException("AI source filter declaration unavailable");
        Catalog catalog = catalog(site, primary, client, calls);
        Category category = catalog.categories.get(id);
        if (category == null || !category.choices.containsKey(topic)) throw new IOException("AI source filter option unavailable");
        JsonObject data = route(execute(routeUrl(catalog.base, category.slug, topic, page), client, calls), category.slug, category.rpc, topic, page);
        return pageResult(data, page).toString();
    }

    private static Catalog catalog(Site site, Spider primary, OkHttpClient client, Consumer<Call> calls) throws IOException {
        HttpUrl base = origin(primary);
        long revision = VodConfig.getDiscoveryRevision();
        String signature = signature(site, base, revision);
        synchronized (CACHE) { Catalog cached = CACHE.get(signature); if (cached != null) return cached; }
        Map<String, Category> categories = new LinkedHashMap<>();
        for (String id : new String[]{"comic", "ai"}) {
            String slug = "comic".equals(id) ? "comic-drama" : "ai-drama"; int rpc = "comic".equals(id) ? 3 : 4;
            JsonObject data = route(execute(routeUrl(base, slug, "", 1), client, calls), slug, rpc, "", 1);
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
            if (response.body().contentLength() > MAX_BODY) throw new IOException("AI source page exceeds limit");
            byte[] bytes;
            try (var input = response.body().byteStream(); var output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_BODY) throw new IOException("AI source page exceeds limit");
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            if (Thread.currentThread().isInterrupted() || call.isCanceled()) throw new java.io.InterruptedIOException("AI source request cancelled");
            return new String(bytes, StandardCharsets.UTF_8);
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
