package com.fongmi.android.tv.diagnostics;

import android.content.Context;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded, asynchronous diagnostics. Never uses the player network client. */
public final class DiagnosticLog {
    public static final long MAX_BYTES = 10_000_000L;
    private static final String CRASH_EXIT_SEEN = "ysc_crash_exit_seen";
    private static volatile RingLog store;
    private static volatile File crashMarker;
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), r -> new Thread(r, "diagnostics-writer"), new ThreadPoolExecutor.DiscardPolicy());
    private DiagnosticLog() {}
    public static synchronized void install(Context context) {
        if (store != null) return;
        // The upstream crash UI runs in another process. Keep a single file owner.
        if (android.os.Build.VERSION.SDK_INT >= 28
                && !com.fongmi.android.tv.BuildConfig.APPLICATION_ID.equals(android.app.Application.getProcessName())) return;
        try { store = new RingLog(new File(context.getFilesDir(), "diagnostics"), MAX_BYTES); }
        catch (IOException ignored) { return; }
        crashMarker = new File(context.getFilesDir(), "diagnostics-crash-pending");
        CrashTrail.install(new File(context.getFilesDir(), "diagnostics-trail"));
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            // Bounded emergency write; preserve the platform/upstream crash handler.
            append("uncaught", crashSummary(error));
            markCrash();
            if (previous != null) previous.uncaughtException(thread, error);
            else Process.killProcess(Process.myPid());
        });
        if (android.os.Build.VERSION.SDK_INT >= 30) WRITER.execute(() -> {
            try {
                android.app.ActivityManager manager = context.getSystemService(android.app.ActivityManager.class);
                long seen = com.github.catvod.utils.Prefers.getLong(CRASH_EXIT_SEEN), newest = seen;
                java.util.List<android.app.ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(null, 0, 3);
                // The trail is the last main process's: tell it with that process's exit, if it was not a normal one.
                android.app.ApplicationExitInfo last = null;
                for (android.app.ApplicationExitInfo exit : exits)
                    if (context.getPackageName().equals(exit.getProcessName()) && (last == null || exit.getTimestamp() > last.getTimestamp())) last = exit;
                for (android.app.ApplicationExitInfo exit : exits) {
                    // An exit already logged by an earlier launch (each launch used to repeat the last three).
                    if (exit.getTimestamp() <= seen) continue;
                    append("process_exit", "time=" + exit.getTimestamp() + " reason=" + exit.getReason() + " status=" + exit.getStatus());
                    if (exit == last && abnormal(exit.getReason())) {
                        String trail = CrashTrail.left(exit.getTimestamp());
                        if (!trail.isEmpty()) append("crash_context", "reason=" + exit.getReason() + " " + trail);
                    }
                    // Native (MPV/FFmpeg) crashes bypass the Java handler; mark them from the exit record.
                    if (exit.getReason() == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE && exit.getTimestamp() > seen) {
                        markCrash();
                        // Android 12+ keeps the tombstone: record the signal and the crashing thread's top frames.
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            try {
                                String summary = NativeCrashSummary.summarize(exit.getTraceInputStream());
                                if (!summary.isEmpty()) append("native_crash", LogSanitizer.clean(summary));
                            } catch (Exception unreadable) {
                                append("native_crash", "unreadable " + unreadable.getClass().getSimpleName());
                            }
                        }
                    }
                    newest = Math.max(newest, exit.getTimestamp());
                }
                if (newest > seen) com.github.catvod.utils.Prefers.put(CRASH_EXIT_SEEN, newest);
            } catch (Exception ignored) {}
        });
        // Older systems keep no exit records: say what was still running, whatever ended the process.
        else if (CrashTrail.leftCalls()) WRITER.execute(() -> append("last_run", CrashTrail.left(-1)));
    }
    /** Killed, out of memory, crashed (Java or native), not responding, failed to start, or over its resource limits. */
    static boolean abnormal(int reason) {
        return (reason >= 2 && reason <= 7) || reason == 9;
    }
    /** Exception, top frames and a short cause chain, bounded so a crash cannot flood the log. */
    static String crashSummary(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 3; depth++, current = current.getCause()) {
            if (depth > 0) text.append(" | caused by ");
            text.append(current.getClass().getName()).append(": ").append(current.getMessage());
            StackTraceElement[] frames = current.getStackTrace();
            int count = Math.min(frames.length, depth == 0 ? 12 : 4);
            for (int i = 0; i < count; i++) text.append(" at ").append(frames[i]);
            if (frames.length > count) text.append(" ...").append(frames.length - count).append(" more");
            if (current.getCause() == current) break;
        }
        return text.toString();
    }

    public static void write(Context context, String event, String detail) {
        String safeEvent = LogSanitizer.clean(event);
        String safeDetail = LogSanitizer.clean(detail);
        WRITER.execute(() -> append(safeEvent, safeDetail));
    }
    private static void append(String event, String detail) {
        RingLog log = store;
        if (log == null) return;
        try { log.append(Instant.now() + "\t" + LogSanitizer.clean(event) + "\t" + LogSanitizer.clean(detail)); }
        catch (Exception ignored) { /* An unavailable disk must not break playback. */ }
    }
    /** Flags a crash so the next launch uploads the log automatically; a new crash resets the retry count. */
    private static void markCrash() {
        File marker = crashMarker;
        if (marker == null) return;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(marker)) { out.write('0'); }
        catch (Exception ignored) {}
    }
    /** Present while a crash log still waits for automatic upload; null before {@link #install}. */
    public static File crashMarker() {
        return crashMarker;
    }
    public static RingLog store() throws IOException {
        if (store == null) throw new IOException("诊断日志暂不可用");
        return store;
    }
    public static long size(Context context) {
        try { return store().size(); } catch (IOException ignored) { return 0; }
    }
}
