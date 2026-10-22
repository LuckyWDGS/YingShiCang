package com.fongmi.android.tv.maintenance;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.api.loader.JarLoader;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Startup cleanup for the caches nothing else bounds: plugin jars of sources no longer used and live-TV guides
 * from earlier days. The image cache has its own limit (OkGlideModule) and the playback caches theirs.
 */
public final class CacheJanitor {

    private static final long DELAY_MS = 60_000;
    // A plugin's .verified marker is rewritten each time it loads, so its newest file time is its last use.
    static final long PLUGIN_UNUSED_MS = TimeUnit.DAYS.toMillis(30);
    // plugin-*.jar are download/replace temporaries; a finished load removes them, a crash may not.
    static final long STAGING_MS = TimeUnit.DAYS.toMillis(1);
    // EpgParser refetches any guide not written today.
    static final long EPG_MS = TimeUnit.DAYS.toMillis(2);

    private CacheJanitor() {}

    public static void schedule(Context context) {
        // Only the main process knows which plugins it loaded; the crash screen's process must not clean.
        if (Build.VERSION.SDK_INT >= 28 && !BuildConfig.APPLICATION_ID.equals(Application.getProcessName())) return;
        Context app = context.getApplicationContext();
        App.post(() -> MaintenanceTasks.execute(() -> run(app)), DELAY_MS);
    }

    private static void run(Context context) {
        long now = System.currentTimeMillis();
        long freed = 0;
        int count = 0;
        List<File> stale = stalePlugins(Path.jar(), now, JarLoader.loadedFiles());
        stale.addAll(older(Path.epg(), now - EPG_MS));
        for (File file : stale) {
            long size = file.length();
            if (file.delete()) { freed += size; count++; }
        }
        if (count > 0) DiagnosticLog.write(context, "cache_cleanup", "files=" + count + " freedKb=" + freed / 1024);
    }

    /**
     * Files of plugins unused for 30 days, grouped by name stem (abc.jar, abc.jar.verified, oat/x86/abc.odex),
     * plus day-old staging temporaries. Plugins loaded in this process are never touched.
     */
    static List<File> stalePlugins(File dir, long now, Set<String> loaded) {
        Map<String, List<File>> groups = new HashMap<>();
        collect(dir, groups);
        Map<String, Boolean> inUse = new HashMap<>();
        for (String path : loaded) inUse.put(stem(new File(path)), true);
        List<File> stale = new ArrayList<>();
        for (Map.Entry<String, List<File>> group : groups.entrySet()) {
            if (inUse.containsKey(group.getKey())) continue;
            long newest = 0;
            for (File file : group.getValue()) newest = Math.max(newest, file.lastModified());
            long keep = group.getKey().startsWith("plugin-") ? STAGING_MS : PLUGIN_UNUSED_MS;
            if (newest > 0 && now - newest > keep) stale.addAll(group.getValue());
        }
        return stale;
    }

    static List<File> older(File dir, long cutoff) {
        List<File> stale = new ArrayList<>();
        for (File file : Path.list(dir)) if (file.isFile() && file.lastModified() < cutoff) stale.add(file);
        return stale;
    }

    private static void collect(File dir, Map<String, List<File>> groups) {
        for (File file : Path.list(dir)) {
            if (file.isDirectory()) collect(file, groups);
            else groups.computeIfAbsent(stem(file), key -> new ArrayList<>()).add(file);
        }
    }

    private static String stem(File file) {
        String name = file.getName();
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
