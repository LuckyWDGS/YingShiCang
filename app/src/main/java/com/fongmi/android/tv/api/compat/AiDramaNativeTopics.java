package com.fongmi.android.tv.api.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JSON-only adapter for the audited comic theme endpoint; labels always come from its rows. */
public final class AiDramaNativeTopics {
    private static final int LIMIT = 18, MAX_PAGE = 500, MAX_SCHEMA = 32768, MAX_TAGS = 64;
    // Capability IDs verified by native-comic-fine-topic-matrix-safe-summary.json
    // and native-survival-string-filter-safe-summary.json (2026-10-18).
    // This restricts supported operations, rather than supplying display labels.
    private static final List<String> CAPABILITY_ORDER = List.of("379", "21", "37", "19", "36", "1334",
            "598", "739", "599", "79", "425", "1", "758", "28");
    private static final Set<String> SUPPORTED_IDS = Set.copyOf(CAPABILITY_ORDER);

    private AiDramaNativeTopics() {}

    public static Map<String, String> choices(JsonObject response) throws IOException {
        JsonArray rows = rows(response, 1);
        Map<String, String> declared = new LinkedHashMap<>();
        for (JsonElement value : rows) {
            JsonObject row = row(value);
            merge(declared, tags(row));
        }
        Map<String, String> result = new LinkedHashMap<>();
        // A stable audited capability order keeps the requested survival category
        // reachable first, independent of which recommendation row arrives first.
        for (String id : CAPABILITY_ORDER) if (declared.containsKey(id)) result.put(id, declared.get(id));
        return Collections.unmodifiableMap(result);
    }

    /** Recommendation rows prove coverage only when they declare every audited capability. */
    public static boolean complete(Map<String, String> choices) {
        return choices != null && choices.keySet().containsAll(SUPPORTED_IDS);
    }

    /** Empty theme is solely the first-page declaration request; AI filtering was not effective. */
    public static JsonObject request(String id, int page, String nativeTopic) throws IOException {
        page(page);
        if (!"comic".equals(id) || nativeTopic == null || nativeTopic.isEmpty() && page != 1)
            throw unavailable();
        if (!nativeTopic.isEmpty()) topic(nativeTopic);
        JsonObject select = new JsonObject();
        select.add("category_dim_epoch", new JsonArray());
        select.add("online_time", new JsonArray());
        select.add("gender", new JsonArray());
        select.add("category_dim_role", new JsonArray());
        JsonArray genre = new JsonArray(); genre.add("comic_series"); select.add("genre", genre);
        JsonArray sort = new JsonArray(); sort.add("hot_score"); select.add("sort", sort);
        JsonArray theme = new JsonArray(); if (!nativeTopic.isEmpty()) theme.add(nativeTopic);
        select.add("category_dim_theme", theme);
        JsonObject body = new JsonObject();
        body.addProperty("filter_ids", ""); body.addProperty("req_scene", "comic_series");
        body.addProperty("offset", (page - 1) * LIMIT); body.addProperty("need_selector_panel", false);
        body.addProperty("limit", LIMIT); body.add("select_items", select);
        body.addProperty("session_id", ""); body.addProperty("req_type", "only_content");
        body.addProperty("client_req_type", 3);
        return body;
    }

    /** An ignored filter is an error, never an apparently successful unfiltered page. */
    public static JsonObject pageResult(JsonObject response, int page, String nativeTopic) throws IOException {
        topic(nativeTopic);
        JsonArray source = rows(response, page), result = new JsonArray();
        Map<String, String> declared = new LinkedHashMap<>();
        for (JsonElement value : source) {
            JsonObject row = row(value);
            Map<String, String> labels = tags(row); merge(declared, labels);
            if (!labels.containsKey(nativeTopic)) throw unavailable();
            String id = string(row, "series_id", 128), title = string(row, "title", 1024);
            if (id.isBlank() || title.isBlank()) throw unavailable();
            JsonObject item = new JsonObject();
            item.addProperty("vod_id", id); item.addProperty("vod_name", title);
            item.addProperty("vod_pic", optionalString(row, "cover", 8192));
            item.addProperty("vod_content", optionalString(row, "video_desc", 65536));
            int episodes = integer(row, "episode_cnt");
            item.addProperty("vod_remarks", episodes > 0 ? "全" + episodes + "集" : "");
            item.addProperty("vod_class", String.join("/", labels.values()));
            result.add(item);
        }
        JsonObject data = object(response, "data"), output = new JsonObject();
        output.add("list", result); output.addProperty("page", page);
        output.addProperty("pagecount", data.get("has_more").getAsBoolean() ? 9999 : page);
        output.addProperty("limit", LIMIT);
        return output;
    }

    private static JsonArray rows(JsonObject response, int page) throws IOException {
        page(page);
        if (response == null || integer(response, "code") != 0
                || integer(object(response, "BaseResp"), "StatusCode") != 0) throw unavailable();
        JsonObject data = object(response, "data");
        JsonElement more = data.get("has_more"), raw = data.get("video_data");
        if (more == null || !more.isJsonPrimitive() || !more.getAsJsonPrimitive().isBoolean()
                || raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() > LIMIT) throw unavailable();
        int next = integer(data, "next_offset");
        // The successful nonterminal pages were verified at offsets 18 and 36.
        // A terminal page does not declare a further cursor, so do not invent its offset rule.
        if (more.getAsBoolean() && next != page * LIMIT) throw unavailable();
        return raw.getAsJsonArray();
    }

    private static JsonObject row(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) throw unavailable();
        JsonObject row = value.getAsJsonObject();
        // Both comic and AI use 1004. Only request() determines the supported genre;
        // this field checks the row shape, not whether a category filter took effect.
        if (integer(row, "content_type") != 1004) throw unavailable();
        return row;
    }

    private static Map<String, String> tags(JsonObject row) throws IOException {
        String schema = string(row, "category_schema", MAX_SCHEMA);
        JsonElement parsed;
        try { parsed = JsonParser.parseString(schema); }
        catch (RuntimeException ignored) { throw unavailable(); }
        if (!parsed.isJsonArray() || parsed.getAsJsonArray().size() > MAX_TAGS) throw unavailable();
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonElement value : parsed.getAsJsonArray()) {
            if (!value.isJsonObject()) throw unavailable();
            JsonObject tag = value.getAsJsonObject();
            int number = integer(tag, "category_id");
            String name = string(tag, "name", 80).trim();
            if (number < 1 || name.isEmpty() || name.codePoints().anyMatch(Character::isISOControl)) throw unavailable();
            String previous = result.putIfAbsent(Integer.toString(number), name);
            if (previous != null && !previous.equals(name)) throw unavailable();
        }
        return result;
    }

    private static void merge(Map<String, String> target, Map<String, String> source) throws IOException {
        for (var entry : source.entrySet()) {
            String previous = target.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) throw unavailable();
        }
    }

    private static void page(int page) throws IOException { if (page < 1 || page > MAX_PAGE) throw unavailable(); }
    private static void topic(String value) throws IOException {
        if (value == null || !SUPPORTED_IDS.contains(value)) throw unavailable();
    }
    private static JsonObject object(JsonObject parent, String key) throws IOException {
        JsonElement value = parent == null ? null : parent.get(key);
        if (value == null || !value.isJsonObject()) throw unavailable();
        return value.getAsJsonObject();
    }
    private static int integer(JsonObject parent, String key) throws IOException {
        JsonElement value = parent == null ? null : parent.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.toString().matches("0|[1-9][0-9]{0,9}")) throw unavailable();
        try { return Integer.parseInt(value.toString()); }
        catch (RuntimeException ignored) { throw unavailable(); }
    }
    private static String optionalString(JsonObject parent, String key, int max) throws IOException {
        JsonElement value = parent.get(key);
        return value == null || value.isJsonNull() ? "" : string(parent, key, max);
    }
    private static String string(JsonObject parent, String key, int max) throws IOException {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw unavailable();
        String result = value.getAsString();
        if (result.length() > max) throw unavailable();
        return result;
    }
    private static IOException unavailable() { return new IOException("AI source native topic response unavailable"); }
}
