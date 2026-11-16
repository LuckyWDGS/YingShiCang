package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.resource.ResourceHealth;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Path;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * The last detail of titles opened recently, so reopening one (from history) can start on its lines at once
 * while the source is asked again. Kept in the cache directory (清除缓存 removes it), per configuration
 * address, source and title; never sent anywhere.
 */
public final class VodDetailCache {

    static final long TTL_MS = 3L * 24 * 60 * 60 * 1000;
    static final int MAX_TITLES = 40;
    private static final int VERSION = 1;

    private VodDetailCache() {
    }

    private static File dir() {
        return Path.cache("detail-cache");
    }

    private static File file(String config, String key, String id) {
        return new File(dir(), Crypto.md5(config + "\u0000" + key + "\u0000" + id) + ".json");
    }

    /**
     * The record to keep, made before the detail is handed to the screen: the screen reorders and selects
     * its lines and episodes, and serialising it meanwhile failed (ConcurrentModificationException on LDPlayer).
     */
    public static String encode(String config, String key, String id, Result result, long now) {
        try {
            Vod vod = result.getVod();
            if (!ResourceHealth.hasPlayableEntry(vod)) return null;
            JsonObject item = App.gson().toJsonTree(vod).getAsJsonObject();
            item.remove("site");
            JsonObject record = new JsonObject();
            record.addProperty("version", VERSION);
            record.addProperty("savedAt", now);
            record.addProperty("config", config);
            record.addProperty("key", key);
            record.addProperty("id", id);
            record.add("vod", item);
            return record.toString();
        } catch (Exception | StackOverflowError e) {
            DiagnosticLog.write(App.get(), "vod_detail_cache_failed", "encode " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Off the main thread. */
    public static void write(String config, String key, String id, String record) {
        if (record == null) return;
        try {
            File dir = dir();
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File target = file(config, key, id);
            File temp = new File(dir, target.getName() + ".tmp");
            Path.write(temp, record.getBytes(StandardCharsets.UTF_8));
            if (!temp.renameTo(target)) temp.delete();
            prune(dir);
        } catch (Exception e) {
            DiagnosticLog.write(App.get(), "vod_detail_cache_failed", "write " + e.getClass().getSimpleName());
        }
    }

    /** Off the main thread; null when there is no usable copy. */
    public static Result get(String config, String key, String id, long now) {
        try {
            File file = file(config, key, id);
            if (!file.isFile()) return null;
            JsonObject record = JsonParser.parseString(Path.read(file)).getAsJsonObject();
            long age = now - record.get("savedAt").getAsLong();
            if (record.get("version").getAsInt() != VERSION || age < 0 || age >= TTL_MS
                    || !config.equals(string(record.get("config"))) || !key.equals(string(record.get("key"))) || !id.equals(string(record.get("id")))) {
                file.delete();
                return null;
            }
            Vod vod = App.gson().fromJson(record.get("vod"), Vod.class);
            if (vod == null || !ResourceHealth.hasPlayableEntry(vod)) return null;
            return Result.vod(vod);
        } catch (Exception e) {
            DiagnosticLog.write(App.get(), "vod_detail_cache_failed", "read " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String string(JsonElement element) {
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles((parent, name) -> name.endsWith(".json"));
        if (files == null || files.length <= MAX_TITLES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = MAX_TITLES; i < files.length; i++) files[i].delete();
    }
}
