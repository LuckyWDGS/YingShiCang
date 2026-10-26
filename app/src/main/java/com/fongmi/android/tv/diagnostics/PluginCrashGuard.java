package com.fongmi.android.tv.diagnostics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the app alive when a source plugin's own code throws on the main thread or on a thread it
 * started (user's Xiaomi TV 2026-10-25, 3.0.1: a plugin Runnable posted to the main looper called
 * getMessage() on a null Throwable about 2s after every launch, so the app closed three times in a
 * row). Only errors with a plugin frame on the stack are skipped; a plugin class is one the app's own
 * class loader cannot find, because plugins come from their own DexClassLoader. Everything else still
 * reaches the crash handlers unchanged.
 */
public final class PluginCrashGuard {

    private static final int MAX_LOGGED = 5;
    private static final Map<String, Boolean> PLUGIN_CLASSES = new ConcurrentHashMap<>();
    private static final AtomicInteger SKIPPED = new AtomicInteger();
    private static boolean installed;

    private PluginCrashGuard() {}

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        Context app = context.getApplicationContext();
        Thread main = Looper.getMainLooper().getThread();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            // A dead main thread cannot be kept; the loop below handles main-thread plugin errors.
            if (thread != main && fromPlugin(error)) skipped(app, "background", error);
            else if (previous != null) previous.uncaughtException(thread, error);
        });
        // Runs inside the main looper: a plugin error ends this Looper.loop() call and the next one
        // carries on with the remaining messages; any other error leaves for ActivityThread as before.
        new Handler(Looper.getMainLooper()).post(() -> {
            while (true) {
                try {
                    Looper.loop();
                    return;
                } catch (Throwable error) {
                    if (!fromPlugin(error)) throw error;
                    skipped(app, "main", error);
                }
            }
        });
    }

    static boolean fromPlugin(Throwable error) {
        if (error instanceof OutOfMemoryError) return false;
        for (Throwable current = error; current != null; current = current.getCause()) {
            for (StackTraceElement frame : current.getStackTrace()) if (isPluginClass(frame.getClassName())) return true;
            if (current.getCause() == current) break;
        }
        return false;
    }

    private static boolean isPluginClass(String name) {
        for (String prefix : SYSTEM_PREFIXES) if (name.startsWith(prefix)) return false;
        // Reflection proxies have no loadable name either.
        if (name.startsWith("$Proxy") || name.contains(".$Proxy")) return false;
        return PLUGIN_CLASSES.computeIfAbsent(name, PluginCrashGuard::notInApp);
    }

    // Framework code, and WebView, which has its own class loader.
    private static final String[] SYSTEM_PREFIXES = {"java.", "javax.", "android.", "com.android.", "dalvik.", "libcore.", "sun.", "org.chromium."};

    private static boolean notInApp(String name) {
        try {
            Class.forName(name, false, PluginCrashGuard.class.getClassLoader());
            return false;
        } catch (Throwable missing) {
            return true;
        }
    }

    private static void skipped(Context context, String thread, Throwable error) {
        int count = SKIPPED.incrementAndGet();
        if (count > MAX_LOGGED) return;
        DiagnosticLog.write(context, "plugin_crash_skipped", "thread=" + thread + " count=" + count + " " + DiagnosticLog.crashSummary(error));
    }
}
