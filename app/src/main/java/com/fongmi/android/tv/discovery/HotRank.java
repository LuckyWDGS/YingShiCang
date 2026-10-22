package com.fongmi.android.tv.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Search page hot words. Douban's real-time collections follow what is airing now; 360kan's rank
 * (the old source) changes slowly and is only the fallback. Both become Word JSON: {"data":[{"title":…}]}.
 */
public final class HotRank {

    public static final int LIMIT = 10;
    public static final Map<String, String> DOUBAN_HEADERS = Map.of("User-Agent", "Mozilla/5.0", "Referer", "https://movie.douban.com/");

    private HotRank() {}

    public static String doubanUrl(String collection) {
        return "https://m.douban.com/rexxar/api/v2/subject_collection/" + collection + "/items?start=0&count=" + LIMIT;
    }

    /** Titles of a rexxar subject_collection response; "" when it holds none. */
    public static String fromDouban(String body) {
        JsonElement items = JsonParser.parseString(body).getAsJsonObject().get("subject_collection_items");
        Set<String> titles = new LinkedHashSet<>();
        if (items != null && items.isJsonArray()) for (JsonElement item : items.getAsJsonArray()) {
            if (item.isJsonObject() && item.getAsJsonObject().has("title")) add(titles, item.getAsJsonObject().get("title"));
        }
        return words(titles);
    }

    /** Titles of 360kan's rank for one category; "" when it holds none. */
    public static String from360(String body, int category) {
        JsonElement data = JsonParser.parseString(body).getAsJsonObject().get("data");
        Set<String> titles = new LinkedHashSet<>();
        if (data != null && data.isJsonArray()) for (JsonElement element : data.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (item.has("cat") && item.get("cat").getAsInt() == category && item.has("title")) add(titles, item.get("title"));
        }
        return words(titles);
    }

    private static void add(Set<String> titles, JsonElement title) {
        if (titles.size() >= LIMIT || !title.isJsonPrimitive()) return;
        // Douban titles sometimes carry direction marks (神探之痕迹‎) that would break the search.
        String value = title.getAsString().replaceAll("[\\u200b-\\u200f\\u2060\\ufeff]", "").trim();
        if (!value.isEmpty()) titles.add(value);
    }

    private static String words(Set<String> titles) {
        if (titles.isEmpty()) return "";
        JsonArray data = new JsonArray();
        for (String title : titles) {
            JsonObject word = new JsonObject();
            word.addProperty("title", title);
            data.add(word);
        }
        JsonObject result = new JsonObject();
        result.add("data", data);
        return result.toString();
    }
}
