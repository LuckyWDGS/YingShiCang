package com.fongmi.android.tv.utils;

import android.app.ActivityManager;
import android.content.Context;

import com.fongmi.android.tv.App;

/**
 * How many sources one search lane asks at once. A 2 GB Xiaomi TV (MiTV-ASTP0, Android 9, 2026-11-23 log)
 * searching about 700 plugin sources 16 + 16 at a time kept losing its process, most often while a title
 * played on top of the search, 29 times in a week. Devices of 2 GB and below ask 4 + 4.
 */
public final class SearchCapacity {

    /** Devices sold as 2 GB report about 1.8-2.0 GB; 3 GB ones about 2.7-2.9 GB. */
    static final long FULL_MEMORY_BYTES = 2_560L * 1024 * 1024;
    static final int FULL = 16;
    static final int LOW = 4;

    private SearchCapacity() {
    }

    static int lanes(long totalBytes, boolean lowRam) {
        return lowRam || (totalBytes > 0 && totalBytes < FULL_MEMORY_BYTES) ? LOW : FULL;
    }

    /** For this device; the full count when the memory cannot be read (JVM tests, a failing system service). */
    static int lanes() {
        try {
            ActivityManager manager = (ActivityManager) App.get().getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return lanes(info.totalMem, manager.isLowRamDevice());
        } catch (RuntimeException | LinkageError unavailable) {
            return FULL;
        }
    }
}
