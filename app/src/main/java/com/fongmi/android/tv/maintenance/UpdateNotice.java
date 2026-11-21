package com.fongmi.android.tv.maintenance;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.github.catvod.utils.Prefers;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The newest published version the last update check saw. Home's 设置 shortcut and Settings' 版本更新 row
 * show it as a hint until the app is updated; the launch check never opens the update window by itself
 * (user 2026-11-20), the user opens it from 版本更新.
 */
public final class UpdateNotice {
    private static final String AVAILABLE = "ysc_update_available";
    private static final Set<Runnable> OBSERVERS = new CopyOnWriteArraySet<>();

    private UpdateNotice() {}

    /** The newer version waiting to be installed, or empty when this build is current. */
    public static String available() {
        String version = Prefers.getString(AVAILABLE);
        return newer(version, BuildConfig.VERSION_NAME) ? version : "";
    }

    public static void found(String version) {
        Prefers.put(AVAILABLE, version);
        changed();
    }

    public static void clear() {
        Prefers.remove(AVAILABLE);
        changed();
    }

    /** Runs on the main thread whenever the available version changes, until {@link #unobserve}. */
    public static void observe(Runnable runnable) {
        if (runnable != null) OBSERVERS.add(runnable);
    }

    public static void unobserve(Runnable runnable) {
        if (runnable != null) OBSERVERS.remove(runnable);
    }

    private static void changed() {
        for (Runnable observer : OBSERVERS) App.post(observer);
    }

    /** Numeric x.y.z comparison; anything malformed is never treated as newer. */
    public static boolean newer(String candidate, String current) {
        if (candidate == null || current == null) return false;
        String version = "[0-9]{1,9}(\\.[0-9]{1,9}){0,3}";
        if (!candidate.matches(version) || !current.matches(version)) return false;
        String[] a = candidate.split("\\."), b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? Long.parseLong(a[i]) : 0, y = i < b.length ? Long.parseLong(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    public static boolean isNewer(String candidate) {
        return newer(candidate, BuildConfig.VERSION_NAME);
    }

    /** This build must update: it is below the release's declared minimum, which the release itself satisfies. */
    public static boolean mandatory(String notes, String release) {
        return mandatory(ReleaseNotes.minVersion(notes), release, BuildConfig.VERSION_NAME);
    }

    static boolean mandatory(String minimum, String release, String current) {
        return newer(minimum, current) && !newer(minimum, release);
    }
}
