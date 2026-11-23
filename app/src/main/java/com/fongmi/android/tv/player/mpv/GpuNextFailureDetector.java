package com.fongmi.android.tv.player.mpv;

/**
 * Notices when MPV's gpu-next output keeps failing to show frames (user 2026-11-23: with gpu-next on, some titles
 * stayed black while the sound went on and nothing said why). On LDPlayer a 4K 10-bit stream logged
 * "Failed creating framebuffer", "Failed uploading frame!" and "Failed updating frames!" for every frame. A few
 * failures can happen while a surface changes, and gpu-next logs one harmless error at start-up (no LittleCMS), so
 * only per-frame failures count, and only many of them in a short window. Pure Java: the engine feeds it MPV's log.
 */
final class GpuNextFailureDetector {

    static final int MPV_LOG_LEVEL_ERROR = 20;
    static final int FAILURES = 100;
    static final long WINDOW_MS = 3000;

    private static final String[] FRAME_FAILURES = {"Failed uploading frame", "Failed updating frames", "Failed mapping frame", "Failed creating framebuffer"};

    private long windowStartMs;
    private int count;
    private boolean triggered;

    /** Forget earlier failures, for a new playback. */
    synchronized void reset() {
        count = 0;
        triggered = false;
    }

    /** Returns true once, on the log message that shows rendering has failed. */
    synchronized boolean onLog(String prefix, int level, String text, long nowMs) {
        if (triggered || !isFrameFailure(prefix, level, text)) return false;
        if (count == 0 || nowMs - windowStartMs > WINDOW_MS) {
            windowStartMs = nowMs;
            count = 0;
        }
        if (++count < FAILURES) return false;
        triggered = true;
        return true;
    }

    static boolean isFrameFailure(String prefix, int level, String text) {
        if (prefix == null || text == null || level > MPV_LOG_LEVEL_ERROR || !prefix.startsWith("vo/gpu-next")) return false;
        for (String failure : FRAME_FAILURES) if (text.contains(failure)) return true;
        return false;
    }
}
