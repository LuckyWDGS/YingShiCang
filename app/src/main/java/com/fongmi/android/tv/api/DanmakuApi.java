package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;

import androidx.collection.ArrayMap;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.impl.ApiCallback;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

public class DanmakuApi {

    private static final String TAG = DanmakuApi.class.getSimpleName();
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_RESULTS = 128;
    private static final long REQUEST_TIMEOUT_MS = 10_000;
    private static final AtomicInteger REQUEST_ID = new AtomicInteger();
    /*
     * Keep one bounded client for the lifetime of the process.  Creating a
     * fresh client for every query gives each request its own dispatcher, so
     * OkHttp.cancel(TAG) on the shared client cannot stop an in-flight query
     * when the user changes episode or closes the dialog.  A shared client
     * preserves the normal connection pool and makes cancellation effective.
     */
    private static volatile okhttp3.OkHttpClient bounded;

    public static boolean canSearch() {
        return DanmakuSetting.isLoad() && DanmakuSetting.isAuto() && !TextUtils.isEmpty(DanmakuSetting.getEffectiveApiUrl());
    }

    public static void cancel() {
        REQUEST_ID.incrementAndGet();
        OkHttp.cancel(TAG);
        okhttp3.OkHttpClient client = bounded;
        if (client != null) OkHttp.cancel(client, TAG);
    }

    public static void search(String name, String episode, Consumer<Danmaku> found) {
        search(name, episode, items -> items.stream().findFirst().ifPresent(found), error -> {});
    }

    public static void search(String name, String episode, Consumer<List<Danmaku>> success, Consumer<Exception> error) {
        int requestId = begin();
        try {
            newCall(name, episode).enqueue(new ApiCallback<>(requestId, REQUEST_ID,
                    response -> parseItems(read(response)), success, error));
        } catch (RuntimeException e) {
            ApiCallback.post(requestId, REQUEST_ID, () -> error.accept(e));
        }
    }

    private static int begin() {
        cancel();
        return REQUEST_ID.get();
    }

    private static Call newCall(String name, String episode) {
        name = Trans.t2s(name);
        episode = Trans.t2s(episode);
        String url = DanmakuSetting.getEffectiveApiUrl();
        return url.contains("{name}") || url.contains("{episode}") ? getCall(url, name, episode) : postCall(url, name, episode);
    }

    private static Call getCall(String url, String name, String episode) {
        String target = url.replace("{name}", Uri.encode(name)).replace("{episode}", Uri.encode(episode));
        return boundedClient().newCall(new Request.Builder().url(target).tag(TAG).get().build());
    }

    private static Call postCall(String url, String name, String episode) {
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("name", name);
        params.put("episode", episode);
        return boundedClient().newCall(new Request.Builder().url(url).post(OkHttp.toBody(params)).tag(TAG).build());
    }

    private static okhttp3.OkHttpClient boundedClient() {
        okhttp3.OkHttpClient client = bounded;
        if (client != null) return client;
        synchronized (DanmakuApi.class) {
            client = bounded;
            if (client == null) {
                client = OkHttp.client().newBuilder()
                        .connectTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .writeTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build();
                bounded = client;
            }
            return client;
        }
    }

    private static String read(Response response) throws IOException {
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " " + response.message());
        okhttp3.ResponseBody body = response.body();
        if (body == null) throw new IOException(ResUtil.getString(R.string.error_empty));
        long length = body.contentLength();
        if (length > MAX_RESPONSE_BYTES) throw new IOException("Danmaku response too large");
        Charset charset = StandardCharsets.UTF_8;
        if (body.contentType() != null && body.contentType().charset(StandardCharsets.UTF_8) != null) {
            charset = body.contentType().charset(StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(0, Math.min(length, MAX_RESPONSE_BYTES)));
        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        try (var input = body.byteStream()) {
            while ((count = input.read(buffer)) != -1) {
                if (count > MAX_RESPONSE_BYTES - total) throw new IOException("Danmaku response too large");
                out.write(buffer, 0, count);
                total += count;
            }
        }
        String text = new String(out.toByteArray(), charset);
        if (TextUtils.isEmpty(text.trim())) throw new IOException(ResUtil.getString(R.string.error_empty));
        return text;
    }

    static List<Danmaku> parseItems(String text) {
        List<Danmaku> parsed = Danmaku.arrayFrom(text);
        if (parsed == null || parsed.isEmpty()) return List.of();
        List<Danmaku> result = new ArrayList<>(Math.min(parsed.size(), MAX_RESULTS));
        Set<String> urls = new LinkedHashSet<>();
        for (Danmaku item : parsed) {
            if (item == null || item.isEmpty() || !urls.add(item.getUrl())) continue;
            result.add(item);
            if (result.size() >= MAX_RESULTS) break;
        }
        return result;
    }
}
