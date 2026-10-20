package com.github.catvod.net;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.ProtocolException;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class ProxyRedirectInterceptor implements Interceptor {

    static final String HIDDEN_LOCATION = "X-Catvod-Redirect-Location";

    private final OkProxySelector selector;

    ProxyRedirectInterceptor(OkProxySelector selector) {
        this.selector = selector;
    }

    /** Marks requests whose redirects this interceptor follows hop by hop. */
    private enum ManualRedirect {INSTANCE}

    /**
     * Network interceptor that hides Location from OkHttp's own follow-up logic on requests this
     * interceptor follows manually, so each hop still gets its own proxy policy. Requests from
     * clients that turned redirects off are untouched and keep their raw 3xx Location.
     */
    static final class HideLocation implements Interceptor {
        @NonNull
        @Override
        public Response intercept(@NonNull Chain chain) throws IOException {
            Response response = chain.proceed(chain.request());
            if (chain.request().tag(ManualRedirect.class) == null || !isRedirect(response.code())) return response;
            String location = response.header("Location");
            if (location == null) return response;
            return response.newBuilder().removeHeader("Location").header(HIDDEN_LOCATION, location).build();
        }
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        // Plugins build clients with followRedirects(false) to read a 302's Location (for example
        // cloud-drive download links). Hand them the raw response instead of following it.
        if (!chain.getFollowRedirects()) return chain.proceed(chain.request());
        Request request = chain.request().newBuilder().tag(ManualRedirect.class, ManualRedirect.INSTANCE).build();
        OkProxySelector.Policy previousPolicy = null;
        Response previousResponse = null;
        int followUpCount = 0;
        while (true) {
            OkProxySelector.Policy policy = null;
            Chain routed = chain;
            if (chain.getProxy() == null && chain.getProxySelector() == selector) {
                policy = selector.policy(request.url().uri(), previousPolicy);
                if (previousPolicy != null && !policy.sharesProxyCredentials(previousPolicy)) request = request.newBuilder().removeHeader("Proxy-Authorization").build();
                request = request.newBuilder().tag(OkProxySelector.Policy.class, policy).build();
                routed = chain.withProxySelector(policy);
            }
            Response response = restoreLocation(routed.proceed(request));
            for (Response prior = response.priorResponse(); prior != null; prior = prior.priorResponse()) followUpCount++;
            response = withPriorResponse(response, previousResponse);
            if (followUpCount > 20) {
                response.close();
                throw new ProtocolException("Too many follow-up requests: " + followUpCount);
            }
            Request followUp = redirect(response, chain.getFollowSslRedirects());
            if (followUp == null) return response;
            RequestBody body = followUp.body();
            if (body != null && body.isOneShot()) return response;
            response.close();
            if (++followUpCount > 20) throw new ProtocolException("Too many follow-up requests: " + followUpCount);
            previousResponse = response.newBuilder().body(ResponseBody.EMPTY).build();
            previousPolicy = policy;
            request = followUp;
        }
    }

    private static Response restoreLocation(Response response) {
        String location = response.header(HIDDEN_LOCATION);
        if (location == null) return response;
        return response.newBuilder().removeHeader(HIDDEN_LOCATION).header("Location", location).build();
    }

    private static boolean isRedirect(int code) {
        return code == 300 || code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private Response withPriorResponse(Response response, Response previous) {
        if (previous == null) return response;
        Response prior = response.priorResponse();
        return response.newBuilder().priorResponse(prior == null ? previous : withPriorResponse(prior, previous)).build();
    }

    private Request redirect(Response response, boolean followSslRedirects) {
        int code = response.code();
        if (!isRedirect(code)) return null;
        String location = response.header("Location");
        if (location == null) return null;
        HttpUrl source = response.request().url();
        HttpUrl target = source.resolve(location);
        if (target == null || !source.scheme().equals(target.scheme()) && !followSslRedirects) return null;
        Request.Builder builder = response.request().newBuilder();
        String method = response.request().method();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            if (redirectsToGet(method, code)) {
                builder.method("GET", null).removeHeader("Transfer-Encoding").removeHeader("Content-Length").removeHeader("Content-Type");
            } else {
                builder.method(method, response.request().body());
            }
        }
        if (!source.scheme().equals(target.scheme()) || !source.host().equals(target.host()) || source.port() != target.port()) builder.removeHeader("Authorization");
        return builder.url(target).build();
    }

    private boolean redirectsToGet(String method, int code) {
        if (code == 303) return !"PROPFIND".equals(method);
        if (code == 307 || code == 308) return false;
        return !"PROPFIND".equals(method) && !"QUERY".equals(method);
    }
}
