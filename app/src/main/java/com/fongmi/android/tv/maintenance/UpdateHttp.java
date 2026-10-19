package com.fongmi.android.tv.maintenance;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Update-only transport. Source plugins and log uploads retain their own clients. */
public final class UpdateHttp {
    private static final int MAX_REDIRECTS = 5;
    private static final String USER_AGENT = "YingShiCang-Updater/1";
    private static final Set<String> GITHUB_HOSTS = Set.of("api.github.com", "github.com",
            "release-assets.githubusercontent.com", "objects.githubusercontent.com");

    private static final class Clients {
        private static final OkHttpClient METADATA = client(120, 30);
        private static final OkHttpClient DOWNLOAD = client(600, 60);
    }

    private UpdateHttp() {}

    private static OkHttpClient client(int callSeconds, int readSeconds) {
        return new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(readSeconds, TimeUnit.SECONDS).callTimeout(callSeconds, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }

    /** The caller owns the final response. Every redirect response is closed here. */
    public static Response execute(String url, boolean github, Consumer<Call> onCall) throws IOException {
        return fetch(Clients.METADATA, url, github, onCall);
    }

    /** APK downloads have a longer deadline without changing metadata or log-upload deadlines. */
    public static Response download(String url, boolean github, Consumer<Call> onCall) throws IOException {
        return fetch(Clients.DOWNLOAD, url, github, onCall);
    }

    private static Response fetch(OkHttpClient client, String url, boolean github,
                                  Consumer<Call> onCall) throws IOException {
        HttpUrl current = validate(url, github);
        for (int redirects = 0; ; redirects++) {
            Request.Builder request = new Request.Builder().url(current).header("User-Agent", USER_AGENT);
            if (current.host().equals("api.github.com")) request.header("Accept", "application/vnd.github+json");
            Call call = client.newCall(request.build());
            // Publish each call before executing it so cancellation also covers redirect transitions.
            if (onCall != null) onCall.accept(call);
            Response response = call.execute();
            if (!isRedirect(response.code())) {
                String error = apiError(current, response.code(), response.header("X-RateLimit-Remaining"),
                        response.header("Retry-After"));
                if (error == null) return response;
                response.close();
                throw new IOException(error);
            }
            try {
                if (redirects >= MAX_REDIRECTS) throw new IOException("更新服务跳转次数过多");
                current = redirect(current, response.header("Location"), github);
            } finally {
                response.close();
            }
        }
    }

    static HttpUrl validate(String url, boolean github) throws IOException {
        HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
        if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty())
            throw new IOException("更新服务地址无效");
        if (!parsed.isHttps()) throw new IOException("更新服务必须使用 HTTPS");
        if (github && (parsed.port() != 443 || !GITHUB_HOSTS.contains(parsed.host())))
            throw new IOException("更新地址不属于 GitHub 官方发布服务");
        return parsed;
    }

    static HttpUrl redirect(HttpUrl current, String location, boolean github) throws IOException {
        if (location == null || location.trim().isEmpty()) throw new IOException("更新服务返回了无效跳转地址");
        HttpUrl next = current.resolve(location);
        if (next == null) throw new IOException("更新服务返回了无效跳转地址");
        return validate(next.toString(), github);
    }

    static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    static String apiError(HttpUrl url, int code, String remaining, String retryAfter) {
        if (!url.host().equals("api.github.com")) return null;
        if (code == 429 || code == 403 && ("0".equals(remaining) || retryAfter != null))
            return "GitHub 检查次数暂时受限，请稍后重试";
        if (code == 403) return "GitHub 暂时拒绝访问，请稍后重试";
        if (code == 404) return "未找到 GitHub 发布版本或更新资源";
        return null;
    }
}
