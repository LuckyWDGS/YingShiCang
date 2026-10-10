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
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        Module.get().clear();
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        String identity = key + "\0" + api + "\0" + ext + "\0" + jar;
        return spiders.computeIfAbsent(identity, k -> {
            com.fongmi.quickjs.crawler.Spider spider = null;
            try {
                spider = loader.spider(api, BaseLoader.get().dex(jar));
                spider.siteKey = key;
                spider.init(App.get(), ext);
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
                DiagnosticLog.write(App.get(), "plugin_load_failed", "JS源插件初始化失败 " + cause.getClass().getSimpleName());
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
