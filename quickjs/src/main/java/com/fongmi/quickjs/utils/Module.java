package com.fongmi.quickjs.utils;

import android.text.TextUtils;
import android.util.LruCache;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import java.io.IOException;

import okhttp3.Response;

public class Module {

    /** Told when a script came from its saved copy: stale means the address did not answer. */
    public interface Observer {
        void copyUsed(boolean stale);
    }

    private static final int MAX_SIZE = 50;
    private static final String VALID_AFTER = "js_copies_valid_after";
    private final LruCache<String, String> cache;
    private volatile ScriptCopies copies;
    private volatile Observer observer;

    public Module() {
        cache = new LruCache<>(MAX_SIZE);
    }

    public static Module get() {
        return Loader.INSTANCE;
    }

    public void setObserver(Observer observer) {
        this.observer = observer;
    }

    public String fetch(String name) {
        String content = cache.get(name);
        if (!TextUtils.isEmpty(content)) return content;
        if (name.startsWith("http")) content = fetchRemote(name);
        else if (name.startsWith("assets")) content = Asset.read(name);
        else if (name.startsWith("lib/")) content = Asset.read("js/" + name);
        else throw new IllegalStateException("不支持的JS模块地址");
        if (content == null || content.trim().isEmpty()) throw new IllegalStateException("JS模块内容为空");
        cache.put(name, content);
        return content;
    }

    /**
     * A copy under a day old (and newer than the user's last reload) is used as it is; otherwise the
     * address is asked, and when it does not answer the last copy is used whatever its age.
     */
    private String fetchRemote(String name) {
        ScriptCopies saved = copies();
        long now = System.currentTimeMillis();
        String copy = saved == null ? null : saved.fresh(name, now, Prefers.getLong(VALID_AFTER));
        if (copy != null) {
            used(false);
            return copy;
        }
        try {
            String content = download(name);
            if (saved != null) saved.save(name, content, now);
            return content;
        } catch (IOException e) {
            // A search that was cancelled must stay cancelled, and an address that says the script is gone
            // (4xx other than 408/429) is not answered from an old copy.
            if (Thread.currentThread().isInterrupted() || "Canceled".equals(e.getMessage()) || e instanceof Refused) throw new IllegalStateException("JS模块下载失败", e);
            copy = saved == null ? null : saved.any(name);
            if (copy == null) throw new IllegalStateException("JS模块下载失败", e);
            used(true);
            return copy;
        }
    }

    private String download(String name) throws IOException {
        try (Response response = OkHttp.newCall(name).execute()) {
            int code = response.code();
            if (code >= 400 && code < 500 && code != 408 && code != 429) throw new Refused("HTTP " + code);
            if (!response.isSuccessful()) throw new IOException("HTTP " + code);
            if (response.body() == null) throw new IOException("JS模块响应为空");
            String content = response.body().string();
            if (content.trim().isEmpty()) throw new IOException("JS模块内容为空");
            return content;
        }
    }

    private static final class Refused extends IOException {
        Refused(String message) { super(message); }
    }

    private ScriptCopies copies() {
        ScriptCopies current = copies;
        if (current != null) return current;
        try {
            return copies = new ScriptCopies(Path.js());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private void used(boolean stale) {
        Observer current = observer;
        if (current != null) current.copyUsed(stale);
    }

    /** The user reloaded the configuration: scripts are downloaded again rather than taken from a day-old copy. */
    public void expire() {
        Prefers.put(VALID_AFTER, System.currentTimeMillis());
        clear();
    }

    public void clear() {
        cache.evictAll();
    }

    private static class Loader {
        static volatile Module INSTANCE = new Module();
    }
}
