package com.fongmi.android.tv.api.loader;

import android.content.Context;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.compat.PluginNoticeCompatibility;
import com.fongmi.android.tv.api.compat.PythonPluginCompatibility;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.chaquo.PythonRuntime;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Path;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dalvik.system.DexClassLoader;

public class JarLoader {

    // Third-party destroy methods must never hold a cancelled search lane open.
    private static final ThreadPoolExecutor FAILED_CLEANUP = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "plugin-failed-cleanup");
                thread.setDaemon(true);
                return thread;
            });

    static { FAILED_CLEANUP.allowCoreThreadTimeOut(true); }

    private final ConcurrentHashMap<String, DexClassLoader> loaders;
    private final ConcurrentHashMap<String, Method> methods;
    private final ConcurrentHashMap<String, Spider> spiders;
    /**
     * A missing class is a property of the current source artifact, not a
     * transient network failure. Keep that result for this loader lifetime so
     * aggregate searches do not repeatedly initialize the same broken source.
     */
    private final ConcurrentHashMap<String, String> deterministicFailures;
    private final ConcurrentHashMap<String, Object> locks;
    private volatile String recent;

    public JarLoader() {
        loaders = new ConcurrentHashMap<>();
        methods = new ConcurrentHashMap<>();
        spiders = new ConcurrentHashMap<>();
        deterministicFailures = new ConcurrentHashMap<>();
        locks = new ConcurrentHashMap<>();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        loaders.clear();
        methods.clear();
        spiders.clear();
        deterministicFailures.clear();
        locks.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    private void load(String key, File file) {
        checkCancellation();
        if (!Path.exists(file) || !file.setReadOnly()) throw new IllegalStateException("源插件文件不可用");
        String cachePath = Path.jar().getAbsolutePath();
        DexClassLoader loader = new DexClassLoader(file.getAbsolutePath(), cachePath, cachePath, App.get().getClassLoader());
        invokeInit(loader);
        checkCancellation();
        Log.d("PluginNotice", PluginNoticeCompatibility.install(loader, file).name());
        invokeProxy(key, loader);
        checkCancellation();
        loaders.put(key, loader);
    }

    private void invokeInit(DexClassLoader loader) {
        final Method method;
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Init");
            method = clz.getMethod("init", Context.class);
        } catch (ClassNotFoundException | NoSuchMethodException optional) {
            return;
        }
        try {
            method.invoke(null, App.get());
        } catch (Throwable e) {
            preserveCancellation(e);
            throw new IllegalStateException("源插件入口初始化失败", e);
        }
    }

    private void invokeProxy(String key, DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Proxy");
            Method method = clz.getMethod("proxy", Map.class);
            methods.put(key, method);
        } catch (Throwable e) {
            preserveCancellation(e);
            e.printStackTrace();
        }
    }

    public void parseJar(String key, String jar) {
        checkCancellation();
        if (loaders.containsKey(key)) return;
        if (jar.startsWith("assets")) jar = UrlUtil.convert(jar);
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            checkCancellation();
            if (loaders.containsKey(key)) return;
            String[] texts = jar.split(";md5;");
            String md5 = texts.length > 1 ? texts[1].trim() : "";
            if (md5.startsWith("http")) md5 = OkHttp.string(md5).trim();
            jar = texts[0];
            if (jar.startsWith("http")) {
                String address = jar;
                try {
                    JarArtifact.load(Path.jar(jar), key, md5, System.currentTimeMillis(), destination -> {
                        try { Download.create(address, destination).timeout(15_000).maxBytes(32 * 1024 * 1024).get(); }
                        catch (RuntimeException e) {
                            preserveCancellation(e);
                            checkCancellation();
                            if (e.getCause() instanceof IOException io) throw io;
                            throw e;
                        }
                    }, file -> load(key, file), BundledJar.matches(address, md5)
                            ? new JarArtifact.Bundle(BundledJar.SHA256, destination -> {
                                try (var input = App.get().getAssets().open(BundledJar.ASSET)) {
                                    Files.copy(input, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                }
                            }) : null);
                } catch (IOException e) { throw new IllegalStateException("源插件下载失败", e); }
            } else if (jar.startsWith("file")) {
                load(key, Path.local(jar));
            }
        }
    }

    public DexClassLoader dex(String jar) {
        try {
            String jaKey = Crypto.md5(jar);
            parseJar(jaKey, jar);
            return loaders.get(jaKey);
        } catch (Throwable e) {
            preserveCancellation(e);
            e.printStackTrace();
            return null;
        }
    }

    /** An already initialized spider, without loading or waiting; null otherwise. */
    public Spider cachedSpider(String key, String api, String ext, String jar) {
        String jaKey = Crypto.md5(jar);
        return spiders.get(jaKey + key + Crypto.md5(api + "\0" + ext));
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        checkCancellation();
        String jaKey = Crypto.md5(jar);
        String spKey = jaKey + key + Crypto.md5(api + "\0" + ext);
        String cachedFailure = deterministicFailures.get(spKey);
        if (cachedFailure != null) throw new IllegalStateException(cachedFailure);
        return spiders.computeIfAbsent(spKey, k -> {
            Spider spider = null;
            try {
                parseJar(jaKey, jar);
                DexClassLoader loader = loaders.get(jaKey);
                if (loader == null) throw new IllegalStateException("源插件文件未加载");
                spider = (Spider) loader.loadClass("com.github.catvod.spider." + api.split("csp_")[1]).newInstance();
                spider.siteKey = key;
                Context context = App.get();
                if (PythonPluginCompatibility.requiresRuntime(spider)) {
                    PythonRuntime.prepare();
                    context = PythonPluginCompatibility.hostContext(context);
                }
                checkCancellation();
                spider.init(context, ext);
                checkCancellation();
                return spider;
            } catch (Throwable e) {
                if (spider != null) releaseFailed(spider);
                String detail = String.valueOf(e);
                Throwable cause = e;
                for (int i = 0; i < 8 && cause.getCause() != null; i++) {
                    cause = cause.getCause(); detail += " " + cause;
                }
                preserveCancellation(e);
                String message = detail.contains("EM_AARCH64") || detail.contains("EM_ARM") || detail.contains("UnsatisfiedLinkError")
                        ? "源插件原生库与当前设备架构不兼容" : "源插件初始化失败";
                RuntimeException failure = new IllegalStateException(message, e);
                // Missing classes are deterministic for this exact artifact/API.
                // Cache only that case; transient download/init/network failures
                // remain retryable on a later search.
                if (hasMissingClass(e)) deterministicFailures.putIfAbsent(spKey, message);
                com.fongmi.android.tv.diagnostics.DiagnosticLog.write(App.get(), "plugin_load_failed", message + " " + cause.getClass().getSimpleName());
                throw failure;
            }
        });
    }

    private static boolean hasMissingClass(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof ClassNotFoundException || cause instanceof NoClassDefFoundError) return true;
        }
        return false;
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Plugin load cancelled");
    }

    private static void preserveCancellation(Throwable error) {
        Throwable cause = error;
        for (int i = 0; cause != null && i < 9; i++, cause = cause.getCause()) {
            // Download.checkCanceled consumes the flag and throws an empty InterruptedIOException.
            // A timed-out socket/call also uses InterruptedIOException and must not become cancellation.
            boolean downloadCancelled = cause instanceof java.io.InterruptedIOException
                    && !(cause instanceof java.net.SocketTimeoutException)
                    && (cause.getMessage() == null || cause.getMessage().isEmpty());
            if (cause instanceof InterruptedException || cause instanceof CancellationException || downloadCancelled) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void releaseFailed(Spider spider) {
        try {
            FAILED_CLEANUP.execute(() -> { try { spider.destroy(); } catch (Throwable ignored) { } });
        } catch (RejectedExecutionException ignored) {
            // Cleanup is best effort and bounded even if multiple plugins ignore destruction.
            com.fongmi.android.tv.diagnostics.DiagnosticLog.write(App.get(), "plugin_cleanup_busy", "失败插件清理线程繁忙");
        }
    }

    private DexClassLoader requireRecentLoader() {
        DexClassLoader loader = loaders.get(recent);
        if (loader == null) throw new IllegalStateException("No jar loaded for recent key: " + recent);
        return loader;
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Json" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class);
        return (JSONObject) method.invoke(null, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Mix" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
        return (JSONObject) method.invoke(null, jxs, name, flag, url);
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        Method method = recent != null ? methods.get(recent) : null;
        Object[] result = proxyInvoke(method, params);
        if (result != null) return result;
        return tryOthers(params);
    }

    private Object[] tryOthers(Map<String, String> p) {
        return methods.entrySet().stream().filter(e -> !e.getKey().equals(recent)).map(e -> proxyInvoke(e.getValue(), p)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Object[] proxyInvoke(Method method, Map<String, String> params) {
        try {
            return method == null ? null : (Object[]) method.invoke(null, params);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }
}
