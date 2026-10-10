package com.fongmi.android.tv.source;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import org.greenrobot.eventbus.EventBus;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SourceManager {
    private static final AtomicBoolean switching = new AtomicBoolean();
    public record Saved(String name) {}
    private static final okhttp3.OkHttpClient namesClient = com.github.catvod.net.OkHttp.client().newBuilder()
            .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build();
    private static String autoName(String url) {
        Config existing = com.fongmi.android.tv.db.AppDatabase.get().getConfigDao().find(url,0);
        if (existing != null && existing.getName() != null && !existing.getName().trim().isEmpty()) return existing.getName();
        try (okhttp3.Response response = namesClient.newCall(new okhttp3.Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return SourceNames.fallback(url);
            int limit = 2 * 1024 * 1024;
            // Bound metadata downloads without executing any source plugin.
            java.io.InputStream stream = response.body().byteStream();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while (buffer.size() <= limit && (count = stream.read(chunk,0,Math.min(chunk.length,limit+1-buffer.size()))) != -1) buffer.write(chunk,0,count);
            byte[] bytes = buffer.toByteArray();
            if (bytes.length > limit) return SourceNames.fallback(url);
            String json = com.fongmi.android.tv.api.Decoder.decodeConfig(response.request().url().toString(), new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            String label = SourceNames.metadata(com.github.catvod.utils.Json.parse(json).getAsJsonObject());
            if (!label.isEmpty()) return label;
        } catch (Exception ignored) { }
        return SourceNames.fallback(url);
    }
    public static Config save(String name, String address) {
        String url = SourceInput.url(address);
        boolean automatic = name == null || name.trim().isEmpty();
        String label = automatic ? autoName(url) : SourceInput.name(name, url);
        Config item;
        synchronized (SourceManager.class) {
            Config existing = com.fongmi.android.tv.db.AppDatabase.get().getConfigDao().find(url,0);
            if (automatic && existing != null && existing.getName() != null && !existing.getName().trim().isEmpty()) label = existing.getName();
            item = Config.find(url, label, 0).save();
        } // Never marks an added line as active.
        EventBus.getDefault().post(new Saved(label));
        return item;
    }
    public static boolean busy() { return switching.get(); }
    public static void activate(Config item, Callback callback) {
        if (!switching.compareAndSet(false, true)) { callback.error("正在切换线路，请稍候"); return; }
        VodConfig.switchWithRollback(item, new Callback() {
            @Override public void start() { callback.start(); }
            @Override public void success() { switching.set(false); callback.success(); }
            @Override public void error(String message) { switching.set(false); callback.error(message); }
        });
    }
}
