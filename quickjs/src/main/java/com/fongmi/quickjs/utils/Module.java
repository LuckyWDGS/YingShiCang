package com.fongmi.quickjs.utils;

import android.text.TextUtils;
import android.util.LruCache;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Asset;

import java.io.IOException;

import okhttp3.Response;

public class Module {

    private static final int MAX_SIZE = 50;
    private final LruCache<String, String> cache;

    public Module() {
        cache = new LruCache<>(MAX_SIZE);
    }

    public static Module get() {
        return Loader.INSTANCE;
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

    private String fetchRemote(String name) {
        try (Response response = OkHttp.newCall(name).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            if (response.body() == null) throw new IOException("JS模块响应为空");
            return response.body().string();
        } catch (IOException e) {
            throw new IllegalStateException("JS模块下载失败", e);
        }
    }

    public void clear() {
        cache.evictAll();
    }

    private static class Loader {
        static volatile Module INSTANCE = new Module();
    }
}
