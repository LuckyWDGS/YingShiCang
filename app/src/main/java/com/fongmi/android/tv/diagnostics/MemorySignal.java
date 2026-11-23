package com.fongmi.android.tv.diagnostics;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.os.Build;
import android.os.Debug;

/**
 * Memory pressure the system reported. Below Android 11 there is no exit reason, so a process that died
 * (MiTV-ASTP0, Android 9, 2026-11-23: 29 times in a week, mostly while searching) could have been killed for
 * memory or crashed in a plugin's native code. Every trim goes into the crash trail, so the next launch's
 * last_run says the last level and how long before the end it came (trim=none: no warning at all);
 * below Android 11 each change is also logged as memory_trim.
 */
public final class MemorySignal {

    private static final long REPEAT_MS = 30_000;
    private static final long MB = 1_048_576;
    private static String lastLevel = "";
    private static long lastLogged;

    private MemorySignal() {
    }

    /** onTrimMemory: levels that mean the system is short of memory; hiding the UI is not one. */
    public static void trim(Context context, int level) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return;
        record(context, String.valueOf(level));
    }

    /** onLowMemory: the system is about to kill processes in the background. */
    public static void low(Context context) {
        record(context, "low");
    }

    private static void record(Context context, String level) {
        long availMb = -1, thresholdMb = -1;
        boolean lowMemory = false;
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            availMb = info.availMem / MB;
            thresholdMb = info.threshold / MB;
            lowMemory = info.lowMemory;
        } catch (RuntimeException ignored) {
        }
        CrashTrail.memory(level, availMb);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || !shouldLog(level, System.currentTimeMillis())) return;
        Runtime runtime = Runtime.getRuntime();
        long javaMb = (runtime.totalMemory() - runtime.freeMemory()) / MB;
        DiagnosticLog.write(context, "memory_trim", "level=" + level + " avail=" + availMb + "MB threshold=" + thresholdMb
                + "MB low=" + lowMemory + " java=" + javaMb + "MB native=" + Debug.getNativeHeapAllocatedSize() / MB + "MB");
    }

    /** A new level is logged at once; the same level again only after 30 s. */
    static synchronized boolean shouldLog(String level, long now) {
        if (level.equals(lastLevel) && now - lastLogged < REPEAT_MS) return false;
        lastLevel = level;
        lastLogged = now;
        return true;
    }
}
