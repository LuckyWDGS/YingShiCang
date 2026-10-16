package com.fongmi.android.tv.api.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.CancellationException;

/** Transport recovery only. Source selection and plugin initialization remain with VodConfig. */
public final class VodConfigFetch {
    public static final long ATTEMPT_TIMEOUT_MS = 10_000;
    public static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L;

    public interface Transport { String fetch() throws Exception; }
    public record Loaded(JsonObject object, boolean cached) {}

    private VodConfigFetch() {}

    public static Loaded load(String url, String cache, long now, Transport transport) throws Exception {
        IOException failure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            checkCanceled();
            try {
                JsonObject object = parse(transport.fetch());
                checkCanceled();
                return new Loaded(object, false);
            } catch (IOException e) {
                checkCanceled();
                if ("Canceled".equals(e.getMessage())) throw e;
                failure = e;
            }
        }
        checkCanceled();
        JsonObject saved = cached(url, cache, now);
        if (saved != null) return new Loaded(saved, true);
        throw failure;
    }

    private static void checkCanceled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    static JsonObject parse(String json) throws IOException {
        JsonObject object;
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) throw new IllegalArgumentException();
            object = element.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("源地址未返回有效的 TVBox 配置", e);
        }
        // Explicit provider responses and valid empty configurations must not resurrect stale sites.
        if (object.has("msg")) throw new IllegalArgumentException(object.get("msg").toString());
        if (object.has("urls")) {
            if (!object.get("urls").isJsonArray() || object.getAsJsonArray("urls").isEmpty())
                throw new IllegalArgumentException("配置中没有可用线路");
        } else if (!object.has("sites") || !object.get("sites").isJsonArray() || object.getAsJsonArray("sites").isEmpty()
                || object.getAsJsonArray("sites").asList().stream().anyMatch(e -> !e.isJsonObject())) {
            throw new IllegalArgumentException("配置中没有可用站点");
        }
        return object;
    }

    public static String snapshot(String url, JsonObject object, long now) {
        if (object.has("urls")) return "";
        JsonObject record = new JsonObject();
        record.addProperty("version", 1);
        record.addProperty("url", url);
        record.addProperty("savedAt", now);
        record.add("payload", object.deepCopy());
        return record.toString();
    }

    private static JsonObject cached(String url, String cache, long now) {
        try {
            JsonObject record = JsonParser.parseString(cache).getAsJsonObject();
            long age = now - record.get("savedAt").getAsLong();
            if (record.get("version").getAsInt() != 1 || !url.equals(record.get("url").getAsString())
                    || age < 0 || age >= CACHE_TTL_MS) return null;
            JsonObject object = parse(record.get("payload").toString());
            return object.has("urls") ? null : object;
        } catch (Exception ignored) {
            return null;
        }
    }
}
