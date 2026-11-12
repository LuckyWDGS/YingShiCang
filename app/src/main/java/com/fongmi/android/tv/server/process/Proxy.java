package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.utils.BoundedCalls;
import com.fongmi.android.tv.utils.BackgroundThreads;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.IStatus;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class Proxy implements Process {

    private static final String INVALID_RESPONSE = "Invalid proxy response";
    private static final BoundedCalls DANMAKU = new BoundedCalls(1, BackgroundThreads.factory("proxy-danmaku"));
    private static final BoundedCalls MEDIA = new BoundedCalls(6, BackgroundThreads.factory("proxy-media"));

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/proxy");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        String lane = "media";
        long started = System.nanoTime();
        try {
            Map<String, String> params = new java.util.HashMap<>(session.getParms());
            params.putAll(session.getHeaders());
            params.putAll(files);
            boolean danmaku = "danmu".equalsIgnoreCase(params.get("do")) || "danmaku".equalsIgnoreCase(params.get("do"));
            lane = danmaku ? "danmaku" : "media";
            String name = "proxy." + lane;
            return (danmaku ? DANMAKU : MEDIA).call(() -> {
                long trail = com.fongmi.android.tv.diagnostics.CrashTrail.begin(name);
                try {
                    return createResponse(BaseLoader.get().proxy(params));
                } finally {
                    com.fongmi.android.tv.diagnostics.CrashTrail.end(trail);
                }
            }, danmaku ? 55_000 : 60_000, Proxy::discard);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.RejectedExecutionException error) {
            boolean busy = error instanceof java.util.concurrent.RejectedExecutionException;
            DiagnosticLog.write(App.get(), "plugin_proxy", "lane=" + lane + " outcome=" + (busy ? "busy" : "timeout")
                    + " ms=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + " active=" + ("danmaku".equals(lane) ? DANMAKU : MEDIA).active());
            return NanoHTTPD.newFixedLengthResponse(busy ? Status.SERVICE_UNAVAILABLE : toStatus(504),
                    "text/plain", busy ? "Source proxy busy" : "Source proxy timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return NanoHTTPD.newFixedLengthResponse(Status.SERVICE_UNAVAILABLE, "text/plain", "Source proxy cancelled");
        } catch (Throwable e) {
            e.printStackTrace();
            return Nano.error(Objects.toString(e.getMessage(), e.toString()));
        }
    }

    private static void discard(Response response) {
        try { if (response != null) response.close(); } catch (Exception ignored) {}
    }

    private static Response createResponse(Object[] rs) {
        if (rs == null || rs.length == 0) return Nano.error(INVALID_RESPONSE);
        if (rs[0] instanceof Response response) return response;
        if (rs.length < 3) return Nano.error(INVALID_RESPONSE);
        Response response = NanoHTTPD.newChunkedResponse(toStatus((Integer) rs[0]), (String) rs[1], (InputStream) rs[2]);
        if (rs.length > 3 && rs[3] != null) for (Map.Entry<String, String> entry : ((Map<String, String>) rs[3]).entrySet()) response.addHeader(entry.getKey(), entry.getValue());
        return response;
    }

    private static IStatus toStatus(int code) {
        Status status = Status.lookup(code);
        return status != null ? status : code >= 100 && code <= 599 ? new ProxyStatus(code) : Status.INTERNAL_ERROR;
    }

    private record ProxyStatus(int code) implements IStatus {

        @Override
        public String getDescription() {
            return code + " Proxy Status";
        }

        @Override
        public int getRequestStatus() {
            return code;
        }
    }
}
