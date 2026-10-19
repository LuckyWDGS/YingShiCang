package com.fongmi.android.tv.diagnostics;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.util.Locale;

/** Small, read-only display facts used to interpret playback frame counters. */
public final class PlaybackDisplayDiagnostics {

    private PlaybackDisplayDiagnostics() {
    }

    /** Returns the current default display refresh rate, or {@code -1} if unknown. */
    public static float getRefreshRate(Context context) {
        if (context == null) return -1.0f;
        try {
            DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return -1.0f;
            float refreshRate = display.getRefreshRate();
            return Float.isFinite(refreshRate) && refreshRate > 0 ? refreshRate : -1.0f;
        } catch (RuntimeException ignored) {
            // A display can disappear while an activity is moving to the background.
            return -1.0f;
        }
    }

    public static String formatRefreshRate(float refreshRate) {
        return Float.isFinite(refreshRate) && refreshRate > 0
                ? String.format(Locale.US, "%.2f", refreshRate) : "unknown";
    }
}
