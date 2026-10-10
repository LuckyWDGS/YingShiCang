package com.fongmi.android.tv.maintenance;

import com.fongmi.android.tv.BuildConfig;
import java.io.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Update and log traffic must never inherit source plugins' permissive TLS clients. */
public final class MaintenanceHttp {
    public static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private MaintenanceHttp() {}
    public static HttpUrl validate(String url) throws IOException {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty()) throw new IOException("服务地址无效");
        boolean localTest = BuildConfig.DEBUG && (parsed.host().equals("10.0.2.2") || parsed.host().equals("127.0.0.1") || parsed.host().equals("localhost"));
        if (!parsed.isHttps() && !localTest) throw new IOException("服务必须使用 HTTPS");
        return parsed;
    }
    public static String text(Response response, int cap) throws IOException {
        if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = response.body().byteStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > cap) throw new IOException("响应超过限制");
                out.write(buffer, 0, n);
            }
        }
        return out.toString("UTF-8");
    }
}
