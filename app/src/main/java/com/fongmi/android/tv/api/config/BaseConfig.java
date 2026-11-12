package com.fongmi.android.tv.api.config;

import android.os.SystemClock;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.ReadyWait;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

abstract class BaseConfig {

    public static final int VOD = 0;
    public static final int LIVE = 1;
    public static final int WALL = 2;

    private final AtomicInteger taskId = new AtomicInteger(0);

    protected boolean sync;
    protected volatile Config config;
    private volatile Future<?> future;

    protected abstract String getTag();

    protected abstract Config defaultConfig();

    protected abstract void load(Config config) throws Throwable;

    protected abstract boolean isLoaded();

    public void ensureLoaded() {
        awaitReady(45_000);
    }

    public synchronized boolean isReady() {
        Future<?> loading = future;
        return isLoaded() && (loading == null || loading.isDone());
    }

    private synchronized void startIfNeeded() {
        if (isLoaded() || (future != null && !future.isDone())) return;
        if (config == null) config = defaultConfig();
        load(new Callback());
    }

    /** Starts at most one shared load; callers only observe it and own their cancellation. */
    public java.util.concurrent.CompletableFuture<Void> whenReady(long timeoutMs) {
        startIfNeeded();
        return ReadyWait.observe(Task.scheduler(), timeoutMs, this::isReady, () -> future);
    }

    /** Off the main thread. The entire wait, including an on-demand load, uses one deadline. */
    public void awaitReady(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        startIfNeeded();
        while (!isReady()) {
            Future<?> loading = future;
            if (loading == null || loading.isDone()) break;
            long left = deadline - System.nanoTime();
            if (left <= 0) return;
            try {
                loading.get(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (TimeoutException e) {
                return;
            } catch (ExecutionException | CancellationException e) {
                // A replaced load is looked at again; the deadline ends the wait.
            }
        }
    }

    protected void postEvent() {
        ConfigEvent.common();
    }

    public boolean needSync(String url) {
        return sync || config == null || TextUtils.isEmpty(config.getUrl()) || url.equals(config.getUrl());
    }

    public Config getConfig() {
        return config == null ? defaultConfig() : config;
    }

    protected void setHeaders(List<Header> headers) {
        OkHttp.responseInterceptor().addAll(headers);
    }

    protected void setProxy(List<Proxy> proxy) {
        OkHttp.selector().addAll(proxy);
    }

    protected void setHosts(List<String> hosts) {
        OkHttp.dns().addAll(hosts);
    }

    public synchronized void load(Callback callback) {
        int id = taskId.incrementAndGet();
        if (config == null) config = defaultConfig();
        Config requested = config;
        Future<?> previous = future;
        FutureTask<Void> next = new FutureTask<>(() -> { loadConfig(id, requested, callback); return null; });
        // Publish the replacement before cancelling the old task, so observers cannot see a gap.
        future = next;
        if (previous != null && !previous.isDone()) previous.cancel(true);
        Task.execute(next);
        callback.start();
    }

    /** For the log: class names are shortened in release builds. */
    private String logType() {
        return this instanceof VodConfig ? "vod" : this instanceof LiveConfig ? "live" : "wall";
    }

    protected void loadConfig(int id, Config config, Callback callback) {
        long started = SystemClock.elapsedRealtime();
        try {
            Server.get().start();
            OkHttp.cancel(getTag());
            load(config);
            if (taskId.get() != id) return;
            DiagnosticLog.write(App.get(), "config_load", "type=" + logType() + " ms=" + (SystemClock.elapsedRealtime() - started) + " outcome=ok saved=" + !TextUtils.isEmpty(config.getNotice()));
            if (config.equals(this.config)) config.update();
            App.post(() -> Notify.show(config.getNotice()));
            App.post(callback::success);
        } catch (Throwable e) {
            e.printStackTrace();
            if (Thread.currentThread().isInterrupted() || "Canceled".equals(e.getMessage())) return;
            if (taskId.get() != id) return;
            DiagnosticLog.write(App.get(), "config_load", "type=" + logType() + " ms=" + (SystemClock.elapsedRealtime() - started) + " outcome=" + e.getClass().getSimpleName());
            if (TextUtils.isEmpty(config.getUrl())) App.post(() -> callback.error(""));
            else App.post(() -> callback.error(Notify.getError(R.string.error_config_get, e)));
        } finally {
            if (taskId.get() == id) postEvent();
        }
    }

    protected boolean isCanceled(Throwable e) {
        if ("Canceled".equals(e.getMessage())) return true;
        if (e instanceof InterruptedException) return true;
        if (e instanceof InterruptedIOException) return true;
        return e.getCause() instanceof InterruptedIOException;
    }

    protected JsonArray fetchArray(JsonObject object, String key) {
        if (!object.has(key)) return new JsonArray();
        JsonElement element = object.get(key);
        if (element.isJsonObject()) return new JsonArray();
        if (element.isJsonPrimitive()) element = fetch(element.getAsString());
        JsonArray result = new JsonArray();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) result.addAll(fetch(item.getAsString()));
            else if (item.isJsonObject()) result.add(item);
        }
        return result;
    }

    private JsonArray fetch(String url) {
        try {
            JsonElement parsed = Json.parse(OkHttp.verifiedString(UrlUtil.convert(url)));
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }
}
