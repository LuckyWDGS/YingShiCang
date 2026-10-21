package com.fongmi.android.tv.maintenance;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.github.catvod.utils.Prefers;

/**
 * The newest published version the last update check saw. Settings shows it as a red dot until the
 * app is updated, and the launch check announces each new version only once.
 */
public final class UpdateNotice {
    private static final String AVAILABLE = "ysc_update_available";
    private static final String PROMPTED = "ysc_update_prompted";
    private static volatile Runnable observer;

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

    /** True only the first time the launch check meets this version; mandatory releases bypass this. */
    public static boolean firstPrompt(String version) {
        if (version.equals(Prefers.getString(PROMPTED))) return false;
        Prefers.put(PROMPTED, version);
        return true;
    }

    /** Runs on the main thread whenever the available version changes; null stops observing. */
    public static void observe(Runnable runnable) {
        observer = runnable;
    }

    private static void changed() {
        Runnable current = observer;
        if (current != null) App.post(current);
    }

    /** Numeric x.y.z comparison; anything malformed is never treated as newer. */
    static boolean newer(String candidate, String current) {
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
