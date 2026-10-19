package com.fongmi.android.tv.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Bounded real-byte probe. Throughput evidence only, never claims decoded playback. */
public final class MediaProbe {
    public record Result(long bytes, long elapsedMs, long firstResponseMs, String kind) {
        public double mbps() { return elapsedMs <= 0 ? 0 : bytes * 8.0 / elapsedMs / 1000; }
    }
    private volatile Call call;
    private volatile boolean canceled;
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(false).build();
    public void cancel() {
        canceled = true;
        Call current = call;
        if (current != null) current.cancel();
    }
    public Result run(String source, Map<String, String> headers) throws IOException {
        if (source == null || source.trim().isEmpty()) throw new IOException("媒体地址为空");
        HttpUrl original = HttpUrl.parse(source.trim());
        if (original == null) throw new IOException("仅支持 HTTP/HTTPS 媒体地址");
        if (!"http".equalsIgnoreCase(original.scheme()) && !"https".equalsIgnoreCase(original.scheme()))
            throw new IOException("仅支持 HTTP/HTTPS 媒体地址");
        Map<String, String> safeHeaders = headers == null ? Map.of() : headers;
        HttpUrl url = original;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String kind = "MP4／媒体直链";
        long spent = 0;
        for (int depth = 0; depth < 4; depth++) {
            if (canceled || PlaybackHealth.active()) throw new IOException("请先退出播放器再测速");
            if (System.nanoTime() > deadline) throw new IOException("测试超时");
            Request.Builder request = new Request.Builder().url(url).header("Accept-Encoding", "identity");
            // Do not forward account cookies to a different origin while resolving manifests.
            if (url.host().equals(original.host()) && url.port() == original.port() && url.scheme().equals(original.scheme())) {
                for (Map.Entry<String, String> h : safeHeaders.entrySet()) {
                    if (h.getKey() == null || h.getValue() == null || h.getKey().isBlank()) continue;
                    request.header(h.getKey(), h.getValue());
                }
            }
            request.header("Range", "bytes=0-7999999");
            call = client.newCall(request.build());
            long begin = System.nanoTime();
            try (Response response = call.execute()) {
                long first = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
                if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
                InputStream in = response.body().byteStream();
                ByteArrayOutputStream firstBytes = new ByteArrayOutputStream();
                byte[] firstBuffer = new byte[1024];
                while (firstBytes.size() < 1024) {
                    int n = in.read(firstBuffer, 0, 1024-firstBytes.size());
                    if (n < 0) break;
                    firstBytes.write(firstBuffer,0,n);
                }
                byte[] initial = firstBytes.toByteArray();
                String prefix = new String(initial, StandardCharsets.UTF_8).trim();
                spent += initial.length;
                if (prefix.startsWith("#EXTM3U")) {
                    ByteArrayOutputStream manifest = new ByteArrayOutputStream(); manifest.write(initial);
                    byte[] buffer = new byte[4096]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (canceled) throw new InterruptedIOException("已取消");
                        if (System.nanoTime() > deadline) throw new IOException("测试超时");
                        spent += n;
                        if (manifest.size() + n > 262144 || spent > 1_000_000) throw new IOException("播放列表超过测试上限");
                        manifest.write(buffer, 0, n);
                    }
                    String next = null;
                    for (String line : manifest.toString("UTF-8").split("\\r?\\n")) {
                        String value = line.trim();
                        if (!value.isEmpty() && !value.startsWith("#")) { next = value; break; }
                    }
                    if (next == null) throw new IOException("HLS 暂无可采样分片");
                    url = response.request().url().resolve(next);
                    if (url == null) throw new IOException("HLS 分片地址无效");
                    kind = "HLS 分片";
                    continue;
                }
                String contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
                if (prefix.startsWith("<") || prefix.startsWith("{") || contentType.contains("text/html") || contentType.contains("json"))
                    throw new IOException("收到网页／配置或 DASH 清单，不能当作媒体速率");
                long total = initial.length;
                byte[] buffer = new byte[16384];
                try {
                    while (total < 8_000_000 && System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(7)) {
                        if (canceled || PlaybackHealth.active()) throw new InterruptedIOException("测试已取消");
                        int n = in.read(buffer, 0, (int)Math.min(buffer.length, 8_000_000 - total));
                        if (n < 0) break;
                        total += n;
                    }
                } catch (java.net.SocketTimeoutException timeout) {
                    throw new IOException("媒体读取超时；不能标记为稳定", timeout);
                }
                if (total < 1024) throw new IOException("媒体样本过少");
                return new Result(total, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin), first, kind);
            } finally { if (call != null) call.cancel(); }
        }
        throw new IOException("播放列表嵌套过深");
    }
}
