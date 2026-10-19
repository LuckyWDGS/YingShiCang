package com.yingshicang.enginebench;

import android.app.Activity;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector;
import androidx.media3.mpvplayer.MpvAndroidOptions;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.mpvplayer.MpvPlayerConfig;
import is.xyz.mpv.MPVLib;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated benchmark; never reads the production app's files, settings or authentication. */
public final class BenchmarkActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONArray samples = new JSONArray();
    private final JSONArray events = new JSONArray();
    private final JSONArray codecs = new JSONArray();
    private Player player;
    private SurfaceView surface;
    private String mode, run, uri, fixture, decoder = "";
    private Boolean hardwareCodec;
    private long started, cpuStarted, duration, firstFrame = -1, readyAt = -1;
    private int errors, drops, codecErrors, sampleIndex;
    private boolean finished;
    private boolean resumed, windowFocused, surfaceAvailable;
    private int backgroundEvents, surfaceLosses, focusLosses;
    private JSONObject videoFormat = new JSONObject();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mode = getIntent().getStringExtra("mode");
        run = getIntent().getStringExtra("run");
        uri = getIntent().getStringExtra("uri");
        fixture = getIntent().getStringExtra("fixture");
        duration = Math.max(15, Math.min(3600, getIntent().getIntExtra("seconds", 300))) * 1000L;
        if (mode == null || !mode.matches("(exo|mpv)-(hard|soft)") || run == null
                || !run.matches("[a-zA-Z0-9_-]{1,80}") || uri == null) { finish(); return; }
        for (android.media.MediaCodecInfo info : new android.media.MediaCodecList(
                android.media.MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equals("video/avc") || type.equals("video/hevc") || type.equals("video/av01")) {
                    codecs.put(object("name", info.getName(), "mime", type,
                            "hardware", android.os.Build.VERSION.SDK_INT >= 29
                                    ? info.isHardwareAccelerated() : JSONObject.NULL));
                }
            }
        }
        surface = new SurfaceView(this);
        setContentView(surface);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                surfaceAvailable = true;
                event("surfaceCreated", holder.getSurface().isValid());
                if (player == null && !finished) start();
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                event("surfaceChanged", object("width", width, "height", height, "valid", holder.getSurface().isValid()));
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceAvailable = false;
                if (started > 0 && !finished) surfaceLosses++;
                event("surfaceDestroyed", true);
            }
        });
    }

    private JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return result;
    }

    private void event(String kind, Object value) {
        if (events.length() < 200) events.put(object("ms", started > 0 ? SystemClock.elapsedRealtime() - started : 0,
                "kind", kind, "value", value));
    }

    private void recordVideoFormat(Format format) {
        videoFormat = object("sampleMimeType", format.sampleMimeType, "codecs", format.codecs,
                "width", format.width, "height", format.height, "frameRate", format.frameRate);
        event("videoFormat", videoFormat);
    }

    private void start() {
        started = SystemClock.elapsedRealtime();
        cpuStarted = Process.getElapsedCpuTime();
        try {
            int decode = mode.endsWith("soft") ? C.DECODE_SOFTWARE : C.DECODE_HARDWARE;
            if (mode.startsWith("mpv")) {
                MpvPlayerConfig config = new MpvPlayerConfig.Builder().addAndroidDefaults(
                        new MpvAndroidOptions.Builder().setGpuNextEnabled(getIntent().getBooleanExtra("gpuNext", false))
                            .setVulkanEnabled(getIntent().getBooleanExtra("vulkan", false))
                            .setAudioPassthroughEnabled(false).build()).build();
                player = new MpvPlayer.Builder(this).setDecode(decode).setConfig(config).build();
            } else {
                DecodeTrackSelector selector = new DecodeTrackSelector(this);
                selector.setRendererDecodePreferences(C.DECODE_HARDWARE, decode);
                DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                        .setEnableDecoderFallback(true)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
                ExoPlayer exo = new ExoPlayer.Builder(this, renderers).setTrackSelector(selector).build();
                exo.addAnalyticsListener(new AnalyticsListener() {
                    @Override public void onVideoInputFormatChanged(EventTime time, Format format, DecoderReuseEvaluation reuse) {
                        recordVideoFormat(format);
                    }
                    @Override public void onVideoDecoderInitialized(EventTime time, String name, long timestamp, long elapsed) {
                        decoder = name; event("videoDecoder", name);
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            for (android.media.MediaCodecInfo info : new android.media.MediaCodecList(
                                    android.media.MediaCodecList.ALL_CODECS).getCodecInfos()) {
                                if (info.getName().equals(name)) hardwareCodec = info.isHardwareAccelerated();
                            }
                        }
                    }
                    @Override public void onDroppedVideoFrames(EventTime time, int count, long elapsed) { drops += count; }
                    @Override public void onVideoCodecError(EventTime time, Exception e) { codecErrors++; event("codecError", e.getClass().getSimpleName()); }
                });
                player = exo;
            }
            player.addListener(new Player.Listener() {
                @Override public void onTracksChanged(Tracks tracks) {
                    for (Tracks.Group group : tracks.getGroups()) {
                        if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
                        for (int i = 0; i < group.length; i++) {
                            if (group.isTrackSelected(i)) recordVideoFormat(group.getTrackFormat(i));
                        }
                    }
                }
                @Override public void onRenderedFirstFrame() {
                    if (firstFrame < 0) firstFrame = SystemClock.elapsedRealtime() - started;
                    event("firstFrame", firstFrame);
                }
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY && readyAt < 0) readyAt = SystemClock.elapsedRealtime() - started;
                    event("state", state);
                }
                @Override public void onPlayerError(PlaybackException e) { errors++; event("error", e.errorCode); }
            });
            player.setVideoSurfaceView(surface);
            // Long-run cases must observe one continuous fixture. Repeating a short
            // file at the EOS boundary can make MediaCodec counters look frozen or
            // accumulate skipped output, which is a fixture artifact rather than a
            // decoder stability result.
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
            player.setVolume(0.2f);
            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
            player.play();
            handler.post(tick);
        } catch (Throwable e) {
            errors++;
            event("startupError", e.getClass().getName() + ": " + Log.getStackTraceString(e));
            complete("startupFailure");
        }
    }

    private Object mpv(String name, boolean numeric) {
        try {
            Object value = numeric ? MPVLib.getPropertyDouble(name) : MPVLib.getPropertyString(name);
            return value == null ? JSONObject.NULL : value;
        } catch (RuntimeException e) { return JSONObject.NULL; }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (finished || player == null) return;
            long elapsed = SystemClock.elapsedRealtime() - started;
            JSONObject sample = object("ms", elapsed, "positionMs", player.getCurrentPosition(),
                    "bufferMs", player.getTotalBufferedDuration(), "state", player.getPlaybackState(),
                    "playing", player.isPlaying(), "cpuMs", Process.getElapsedCpuTime() - cpuStarted,
                    "drops", drops, "codecErrors", codecErrors, "errors", errors,
                    "resumed", resumed, "windowFocused", windowFocused,
                    "surfaceAvailable", surfaceAvailable,
                    "surfaceValid", surfaceAvailable && surface.getHolder().getSurface().isValid());
            try {
                if (sampleIndex++ % 10 == 0) {
                    Debug.MemoryInfo memory = new Debug.MemoryInfo(); Debug.getMemoryInfo(memory);
                    sample.put("pssKb", memory.getTotalPss());
                }
                if (mode.startsWith("mpv")) {
                    sample.put("hwdec", mpv("hwdec-current", false));
                    sample.put("decoder", mpv("video-codec", false));
                    sample.put("mpvVersion", mpv("mpv-version", false));
                    sample.put("ffmpegVersion", mpv("ffmpeg-version", false));
                    sample.put("decoderDrops", mpv("decoder-frame-drop-count", true));
                    sample.put("outputDrops", mpv("frame-drop-count", true));
                    sample.put("avsync", mpv("avsync", true));
                    sample.put("videoOutput", mpv("current-vo", false));
                    sample.put("videoWidth", mpv("video-params/w", true));
                    sample.put("videoHeight", mpv("video-params/h", true));
                    sample.put("containerFps", mpv("container-fps", true));
                }
                if (player instanceof ExoPlayer) {
                    androidx.media3.exoplayer.DecoderCounters counters = ((ExoPlayer) player).getVideoDecoderCounters();
                    if (counters != null) {
                        counters.ensureUpdated();
                        sample.put("rendered", counters.renderedOutputBufferCount);
                        sample.put("decoderDrops", counters.droppedBufferCount);
                        sample.put("skipped", counters.skippedOutputBufferCount);
                    }
                }
            } catch (Exception e) { event("samplingError", e.getClass().getSimpleName()); }
            samples.put(sample);
            // Avoid rewriting a growing report every second during long playback.
            if (sampleIndex % 5 == 1) persist("running");
            if (elapsed >= duration || (errors > 0 && elapsed > 15000)) complete("finished");
            else handler.postDelayed(this, 1000);
        }
    };

    private void persist(String status) {
        try {
            JSONObject result = object("run", run, "mode", mode, "status", status,
                    "schemaVersion", 2, "requestedFixture", fixture,
                    "videoFormat", videoFormat,
                    "lifecycleEvidence", object("backgroundEvents", backgroundEvents,
                            "surfaceLosses", surfaceLosses, "focusLosses", focusLosses),
                    "elapsedMs", SystemClock.elapsedRealtime() - started, "requestedMs", duration,
                    "firstFrameMs", firstFrame, "readyMs", readyAt, "decoder", decoder,
                    "hardwareCodec", hardwareCodec == null ? JSONObject.NULL : hardwareCodec,
                    "platformVideoCodecs", codecs,
                    "errors", errors, "codecErrors", codecErrors, "events", events, "samples", samples,
                    "gpuNext", getIntent().getBooleanExtra("gpuNext", false),
                    "vulkan", getIntent().getBooleanExtra("vulkan", false),
                    "ffmpegAvailable", FfmpegLibrary.isAvailable(), "ffmpegVersion", FfmpegLibrary.getVersion());
            File out = new File(getExternalFilesDir(null), run + ".json");
            File temp = new File(out.getPath() + ".tmp");
            try (FileOutputStream file = new FileOutputStream(temp)) { file.write(result.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!temp.renameTo(out)) throw new java.io.IOException("Cannot replace report");
        } catch (Exception e) { android.util.Log.e("EngineBenchmark", "Report failed", e); }
    }

    private void complete(String status) {
        finished = true;
        handler.removeCallbacks(tick);
        persist(status);
        if (player != null) { player.release(); player = null; }
    }

    @Override public void onStart() { super.onStart(); event("lifecycle", "started"); }

    @Override public void onResume() {
        super.onResume();
        resumed = true;
        event("lifecycle", "resumed");
    }

    @Override public void onPause() {
        if (started > 0 && !finished) backgroundEvents++;
        resumed = false;
        event("lifecycle", "paused");
        super.onPause();
    }

    @Override public void onStop() { event("lifecycle", "stopped"); super.onStop(); }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (windowFocused && !focused && started > 0 && !finished) focusLosses++;
        windowFocused = focused;
        event("windowFocus", focused);
    }

    @Override public void onDestroy() {
        if (!finished && started > 0) complete("interrupted");
        super.onDestroy();
    }
}
