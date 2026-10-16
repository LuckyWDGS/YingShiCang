package com.fongmi.android.tv.api;

import com.fongmi.android.tv.resource.ResourceHealth;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

/** Do not turn an HTML error page, challenge or broken plugin payload into an empty detail. */
public final class ResourceResponseSchema {
    private ResourceResponseSchema() {}

    public static void xml(String body) throws IOException {
        if (body == null || body.trim().isEmpty()) throw new IOException("Empty XML detail response");
        org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(body, "", org.jsoup.parser.Parser.xmlParser());
        if (document.children().size() != 1 || !document.child(0).tagName().equals("rss"))
            throw new IOException("Invalid XML detail response");
        org.jsoup.nodes.Element list = document.selectFirst("rss > list");
        if (list == null || !list.ownText().trim().isEmpty()) throw new IOException("Invalid XML detail list");
        for (org.jsoup.nodes.Element row : list.children()) {
            if (!row.tagName().equals("video") || (row.selectFirst("id") == null && row.selectFirst("name") == null))
                throw new IOException("Invalid XML detail row");
        }
    }

    public static JsonObject json(String body, boolean detail) throws IOException {
        if (body == null || body.trim().isEmpty()) throw new IOException("Empty source response");
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) throw new IOException("Invalid source response schema");
            JsonObject object = parsed.getAsJsonObject();
            String error = text(object.get("error"));
            String message = text(object.get("msg"));
            if (message.isEmpty()) message = text(object.get("message"));
            String status = text(object.get("status"));
            String code = text(object.get("code"));
            boolean httpFailure = code.matches("[45]\\d\\d") || status.matches("[45]\\d\\d");
            boolean failure = httpFailure || "false".equalsIgnoreCase(text(object.get("success")))
                    || "error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)
                    || "-1".equals(code) || isError(object.get("error"));
            boolean hasContent = detail
                    ? object.has("list") && object.get("list").isJsonArray() && !object.getAsJsonArray("list").isEmpty()
                    : object.has("url") && playableUrl(object.get("url"));
            if (failure || (!message.isEmpty() && !hasContent && !successMessage(message))) {
                // Only keep a fixed classification; provider messages can contain private URLs.
                boolean missing = ResourceHealth.failureStatus(new IOException(error + " " + message + " " + code + " " + status)) == ResourceHealth.Status.INVALID;
                throw new IOException(missing ? "Resource not found" : "Source response unavailable");
            }
            if (detail) {
                if (!object.has("list") || !object.get("list").isJsonArray()) throw new IOException("Detail list unavailable");
                for (JsonElement item : object.getAsJsonArray("list")) {
                    if (!item.isJsonObject()) throw new IOException("Invalid detail row");
                    JsonObject row = item.getAsJsonObject();
                    if (text(row.get("vod_id")).isEmpty() && text(row.get("vod_name")).isEmpty())
                        throw new IOException("Unrecognized detail row");
                    for (String field : new String[]{"vod_play_from", "vod_play_url"})
                        if (row.has(field) && !row.get(field).isJsonNull() && !isString(row.get(field)))
                            throw new IOException("Invalid detail playlist");
                }
            } else {
                if (!object.has("url")) throw new IOException("Player response unavailable");
                playableUrl(object.get("url")); // Validate even when a success message is present.
                if (isString(object.get("url"))) object.addProperty("url", text(object.get("url")));
            }
            if (successMessage(message)) object.remove("msg");
            return object;
        } catch (RuntimeException error) {
            throw new IOException("Invalid source response JSON");
        }
    }

    private static boolean successMessage(String message) {
        return message.equalsIgnoreCase("ok") || message.equalsIgnoreCase("success")
                || message.equals("成功") || message.equals("请求成功") || message.equals("获取成功") || message.equals("数据返回成功");
    }

    private static boolean isError(JsonElement value) {
        if (value == null || value.isJsonNull()) return false;
        if (!value.isJsonPrimitive()) return true;
        String error = text(value);
        return !error.isEmpty() && !error.equals("0") && !error.equalsIgnoreCase("false");
    }

    // UrlAdapter accepts a string, alternating quality/URL strings, or {values:[{n,v}]}.
    // Reject other shapes before its permissive fallback can silently erase the URL.
    private static boolean playableUrl(JsonElement url) throws IOException {
        if (url == null || url.isJsonNull()) throw new IOException("Player URL unavailable");
        if (isString(url)) return !text(url).isEmpty();
        if (url.isJsonArray()) {
            JsonArray values = url.getAsJsonArray();
            if (values.size() % 2 != 0) throw new IOException("Invalid player URL pairs");
            boolean playable = false;
            for (int i = 0; i < values.size(); i++) {
                if (!isString(values.get(i))) throw new IOException("Invalid player URL pair");
                if (i % 2 == 1 && !text(values.get(i)).isEmpty()) playable = true;
            }
            for (int i = values.size() - 2; i >= 0; i -= 2) if (text(values.get(i + 1)).isEmpty()) {
                values.remove(i + 1); values.remove(i);
            }
            return playable;
        }
        if (url.isJsonObject()) {
            JsonObject object = url.getAsJsonObject();
            if (!object.has("values") || !object.get("values").isJsonArray()) throw new IOException("Invalid player URL object");
            boolean playable = false;
            for (JsonElement item : object.getAsJsonArray("values")) {
                if (!item.isJsonObject() || !isString(item.getAsJsonObject().get("v"))) throw new IOException("Invalid player URL value");
                if (!text(item.getAsJsonObject().get("v")).isEmpty()) playable = true;
            }
            JsonArray values = object.getAsJsonArray("values");
            for (int i = values.size() - 1; i >= 0; i--) if (text(values.get(i).getAsJsonObject().get("v")).isEmpty()) values.remove(i);
            // The first selection is made by the controller, never trust a provider index.
            object.addProperty("position", 0);
            return playable;
        }
        throw new IOException("Invalid player URL schema");
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static String text(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString().trim() : "";
    }
}
