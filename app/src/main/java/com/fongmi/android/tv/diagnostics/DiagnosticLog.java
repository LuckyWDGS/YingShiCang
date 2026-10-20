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
    private static volatile RingLog store;
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
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            // Bounded emergency write; preserve the platform/upstream crash handler.
            append("uncaught", crashSummary(error));
            if (previous != null) previous.uncaughtException(thread, error);
            else Process.killProcess(Process.myPid());
        });
        if (android.os.Build.VERSION.SDK_INT >= 30) WRITER.execute(() -> {
            try {
                android.app.ActivityManager manager = context.getSystemService(android.app.ActivityManager.class);
                for (android.app.ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(null, 0, 3))
                    append("process_exit", "time=" + exit.getTimestamp() + " reason=" + exit.getReason() + " status=" + exit.getStatus());
            } catch (Exception ignored) {}
        });
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
    public static RingLog store() throws IOException {
        if (store == null) throw new IOException("诊断日志暂不可用");
        return store;
    }
    public static long size(Context context) {
        try { return store().size(); } catch (IOException ignored) { return 0; }
    }
}
