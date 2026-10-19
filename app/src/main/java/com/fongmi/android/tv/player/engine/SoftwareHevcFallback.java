package com.fongmi.android.tv.player.engine;

import java.util.Locale;

/**
 * Stateless guards for the protected Exo software-HEVC to MPV fallback.
 *
 * <p>Keeping these checks free of Android player objects makes the fallback
 * decision deterministic and unit-testable.  The decoder name is deliberately
 * matched against the software decoder families exposed by Media3/Android. A
 * hardware decoder name is never enough to justify rebuilding the player.</p>
 */
public final class SoftwareHevcFallback {

    private static final String VIDEO_HEVC_MIME = "video/hevc";

    private SoftwareHevcFallback() {
    }

    /** Keeps an explicit engine choice authoritative for one playback request. */
    public static final class ManualSelectionPolicy<T> {
        private T currentSpec;
        private boolean manuallySelected;

        public void onPlaybackStarted(T spec) {
            if (currentSpec == spec) return;
            currentSpec = spec;
            manuallySelected = false;
        }

        public void onManualSelection(T spec) {
            onPlaybackStarted(spec);
            manuallySelected = spec != null;
        }

        public boolean allows(T spec) {
            return spec != null && (currentSpec != spec || !manuallySelected);
        }

        public void clear() {
            currentSpec = null;
            manuallySelected = false;
        }
    }

    public static boolean isTarget(String decoderName, String sampleMimeType, int width, int height) {
        if (!isSoftwareDecoder(decoderName)) return false;
        if (!VIDEO_HEVC_MIME.equals(sampleMimeType)) return false;
        if (width <= 0 || height <= 0) return false;
        int shortEdge = Math.min(width, height);
        int longEdge = Math.max(width, height);
        return shortEdge >= 2160 && longEdge >= 3840;
    }

    /**
     * Android reports software HEVC through more than the bundled FFmpeg
     * extension.  The Google/Codec2 software prefixes are stable across API
     * levels and still require a 4K HEVC format in {@link #isTarget}.
     */
    static boolean isSoftwareDecoder(String decoderName) {
        if (decoderName == null || decoderName.isBlank()) return false;
        String name = decoderName.toLowerCase(Locale.ROOT);
        return name.startsWith("ffmpeg")
                || name.startsWith("omx.google.")
                || name.startsWith("c2.android.")
                || name.startsWith("c2.google.")
                || name.contains("software")
                || name.startsWith("sw.");
    }

    public static boolean isAllowed(boolean mpvAvailable, boolean drm, String scheme) {
        return mpvAvailable && !drm && !"smb".equalsIgnoreCase(scheme);
    }

    /**
     * The bundled MPV binary is an ARM native library.  Some x86 Android
     * emulators expose it through Houdini, which can load the library but is
     * not stable for the shader compiler used by 4K playback (it can abort the
     * whole app with SIGSEGV).  Only ARM runtimes are eligible for the
     * automatic software-HEVC fallback; x86 stays on Exo's software decoder.
     *
     * <p>This is kept as a small pure helper so the architecture gate is
     * deterministic and unit-testable without an Android device.</p>
     */
    public static boolean isArmRuntime(String[] supportedAbis) {
        if (supportedAbis == null || supportedAbis.length == 0) return true;
        // SUPPORTED_ABIS is ordered by the ABI that the current runtime uses
        // first.  Houdini based x86 devices commonly advertise ARM ABIs as
        // secondary entries, but loading the ARM MPV library through that
        // translation layer is exactly the unsafe case this guard protects.
        String primary = supportedAbis[0];
        return "arm64-v8a".equalsIgnoreCase(primary)
                || "armeabi-v7a".equalsIgnoreCase(primary);
    }
}
