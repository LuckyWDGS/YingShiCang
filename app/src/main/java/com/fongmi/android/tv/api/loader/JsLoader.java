package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.crawler.Spider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JsLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = new Loader();
        // A script taken from its saved copy: logged a few times a minute at most (a search starts hundreds).
        Module.get().setObserver(stale -> {
            if (COPY_LINES.take(System.currentTimeMillis())) DiagnosticLog.write(App.get(), "js_copy", stale ? "stale" : "fresh");
        });
    }

    private static final com.fongmi.android.tv.diagnostics.LineBudget COPY_LINES = new com.fongmi.android.tv.diagnostics.LineBudget(5, 60_000);

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        Module.get().clear();
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    /** An already initialized spider, without loading or waiting; null otherwise. */
    public Spider cachedSpider(String key, String api, String ext, String jar) {
        return spiders.get(key + "\0" + api + "\0" + ext + "\0" + jar);
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        String identity = key + "\0" + api + "\0" + ext + "\0" + jar;
        return spiders.computeIfAbsent(identity, k -> {
            com.fongmi.quickjs.crawler.Spider spider = null;
            try {
                spider = loader.spider(api, BaseLoader.get().dex(jar));
                spider.siteKey = key;
                long trail = com.fongmi.android.tv.diagnostics.CrashTrail.begin("js.init");
                try {
                    spider.init(App.get(), ext);
                } finally {
                    com.fongmi.android.tv.diagnostics.CrashTrail.end(trail);
                }
                return spider;
            } catch (Throwable e) {
                if (spider != null) spider.abortInitialization();
                Throwable cause = e;
                boolean interrupted = e instanceof InterruptedException;
                for (int i = 0; i < 8 && cause.getCause() != null; i++) {
                    cause = cause.getCause();
                    interrupted |= cause instanceof InterruptedException;
                }
                if (interrupted) Thread.currentThread().interrupt();
                // QuickJSException carries the script's own error: its type says whether the script wants a newer host.
                String kind = cause.getClass().getSimpleName().contains("QuickJS") ? com.fongmi.android.tv.diagnostics.JsFailure.describe(cause.getMessage()) : "";
                DiagnosticLog.write(App.get(), "plugin_load_failed", "JS源插件初始化失败 " + cause.getClass().getSimpleName() + kind);
                // computeIfAbsent retains successful instances only; a later request can recover.
                throw new IllegalStateException("JS源插件初始化失败", e);
            }
        });
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        return BaseLoader.get().getSpider(recent).proxy(params);
    }
}
