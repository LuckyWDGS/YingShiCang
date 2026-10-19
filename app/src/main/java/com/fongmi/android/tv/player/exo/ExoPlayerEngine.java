package com.fongmi.android.tv.player.exo;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.diagnostics.PlaybackDisplayDiagnostics;
import com.fongmi.android.tv.diagnostics.PlaybackFrameDiagnostics;
import com.fongmi.android.tv.diagnostics.PlaybackLoadDiagnostics;
import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.engine.HlsFormatPolicy;
import com.fongmi.android.tv.player.engine.SoftwareHevcFallback;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;

import java.io.IOException;

public class ExoPlayerEngine implements PlayerEngine, AnalyticsListener {

    private static final long FRAME_DIAGNOSTIC_INTERVAL_MS = 10_000;

    private final ExoErrorMessageProvider provider;
    private final ExoPlayerSession session;
    private final ExoPlayerEffect effect;
    private final ExoDiskPreload preload;
    private final ExoPlayer player;
    private PlaySpec spec;
    private final FormatRecovery formatRecovery = new FormatRecovery();
    private String diagnosticDecoder = "unknown";
    private Format diagnosticFormat;
    private long lastFrameDiagnosticMs = -1;
    private long pendingDroppedFrames, pendingDroppedElapsedMs;
    private long pendingProcessingOffsetUs, pendingProcessingFrames;
    private final PlaybackFrameDiagnostics frameDiagnostics = new PlaybackFrameDiagnostics();
    private final PlaybackLoadDiagnostics loadDiagnostics = new PlaybackLoadDiagnostics();
    private long lastBufferedMs = -1;
    private boolean videoDiagnosticActive;
    private boolean awaitingVideoFrame;

    public ExoPlayerEngine(int decode, Player.Listener listener) {
        this.effect = new ExoPlayerEffect();
        this.preload = new ExoDiskPreload();
        this.provider = new ExoErrorMessageProvider();
        this.session = new ExoPlayerSession(decode, listener, effect.getAudioProcessor());
        this.player = this.session.player();
        this.player.addAnalyticsListener(this);
        this.effect.setPlayer(player);
    }

    @Override
    public void onAudioTrackInitialized(@NonNull EventTime eventTime, @NonNull AudioSink.AudioTrackConfig audioTrackConfig) {
        effect.applyAudioEffect();
    }

    @Override
    public void onAudioTrackReleased(@NonNull EventTime eventTime, @NonNull AudioSink.AudioTrackConfig audioTrackConfig) {
        effect.applyAudioEffect();
    }

    @Override
    public void onTracksChanged(@NonNull EventTime eventTime, @NonNull Tracks tracks) {
        effect.applyVideoEffect();
    }

    @Override
    public void onLoadStarted(@NonNull EventTime eventTime, @NonNull LoadEventInfo loadEventInfo,
                              @NonNull MediaLoadData mediaLoadData, int retryCount) {
        logLoadDiagnostic(PlaybackLoadDiagnostics.Event.STARTED, eventTime, loadEventInfo,
                mediaLoadData, retryCount, false, null);
    }

    @Override
    public void onLoadCompleted(@NonNull EventTime eventTime, @NonNull LoadEventInfo loadEventInfo,
                                @NonNull MediaLoadData mediaLoadData) {
        logLoadDiagnostic(PlaybackLoadDiagnostics.Event.COMPLETED, eventTime, loadEventInfo,
                mediaLoadData, -1, false, null);
    }

    @Override
    public void onLoadCanceled(@NonNull EventTime eventTime, @NonNull LoadEventInfo loadEventInfo,
                               @NonNull MediaLoadData mediaLoadData) {
        logLoadDiagnostic(PlaybackLoadDiagnostics.Event.CANCELED, eventTime, loadEventInfo,
                mediaLoadData, -1, true, null);
    }

    @Override
    public void onLoadError(@NonNull EventTime eventTime, @NonNull LoadEventInfo loadEventInfo,
                            @NonNull MediaLoadData mediaLoadData, @NonNull IOException error,
                            boolean wasCanceled) {
        logLoadDiagnostic(PlaybackLoadDiagnostics.Event.ERROR, eventTime, loadEventInfo,
                mediaLoadData, -1, wasCanceled, error);
    }

    @Override
    public void onBandwidthEstimate(@NonNull EventTime eventTime, int totalLoadTimeMs,
                                    long totalBytesLoaded, long bitrateEstimate) {
        String detail = loadDiagnostics.bandwidth(SystemClock.elapsedRealtime(), totalLoadTimeMs,
                totalBytesLoaded, bitrateEstimate);
        if (detail != null) DiagnosticLog.write(App.get(), PlaybackLoadDiagnostics.Event.BANDWIDTH.logName(),
                diagnosticEventContext(eventTime) + " " + detail);
    }

    private void logLoadDiagnostic(PlaybackLoadDiagnostics.Event event, EventTime eventTime,
                                   LoadEventInfo info, MediaLoadData data, int retryCount,
                                   boolean wasCanceled, @Nullable IOException error) {
        int httpStatus = -1;
        Throwable cause = error;
        // A cause can wrap the HTTP exception; keep traversal bounded and never read messages/headers.
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException response) {
                httpStatus = response.responseCode;
                break;
            }
        }
        String detail = loadDiagnostics.load(event, SystemClock.elapsedRealtime(), info.loadTaskId,
                info.bytesLoaded, info.loadDurationMs, data.dataType, data.trackType, retryCount,
                wasCanceled, httpStatus, error);
        if (detail != null) DiagnosticLog.write(App.get(), event.logName(),
                diagnosticEventContext(eventTime) + " " + detail);
    }

    private String diagnosticEventContext(EventTime eventTime) {
        return "eventMs=" + eventTime.realtimeMs
                + " periodSeq=" + (eventTime.mediaPeriodId == null ? "unknown" : eventTime.mediaPeriodId.windowSequenceNumber)
                + " positionMs=" + player.getCurrentPosition() + " bufferedMs=" + player.getTotalBufferedDuration()
                + " state=" + player.getPlaybackState() + " isPlaying=" + player.isPlaying()
                + " isLoading=" + player.isLoading();
    }

    @Override
    public void onVideoEnabled(@NonNull EventTime eventTime, @NonNull DecoderCounters counters) {
        diagnosticDecoder = "unknown";
        diagnosticFormat = null;
        lastFrameDiagnosticMs = -1;
        clearPendingFrameDiagnostics();
        frameDiagnostics.reset();
        lastBufferedMs = -1;
        videoDiagnosticActive = true;
        awaitingVideoFrame = true;
    }

    @Override
    public void onVideoDecoderInitialized(@NonNull EventTime eventTime, @NonNull String decoderName, long initializedTimestampMs, long initializationDurationMs) {
        diagnosticDecoder = decoderName;
        logVideoDiagnostic("playback_video_decoder", eventTime, "initMs=" + initializationDurationMs, player.getVideoDecoderCounters());
    }

    @Override
    public void onVideoInputFormatChanged(@NonNull EventTime eventTime, @NonNull Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
        diagnosticFormat = format;
        logVideoDiagnostic("playback_video_format", eventTime, "", player.getVideoDecoderCounters());
    }

    @Override
    public void onPlaybackStateChanged(@NonNull EventTime eventTime, int state) {
        if (!videoDiagnosticActive) return;
        frameDiagnostics.setPlaybackActive(state == Player.STATE_READY && player.isPlaying() && !awaitingVideoFrame);
        // State transitions are sparse and capture the buffer/decoder/frame
        // relationship even when no drop callback is emitted (for example, a
        // renderer or Surface stall).
        maybeLogFrameDiagnostic(eventTime, true, "trigger=state state=" + state);
    }

    @Override
    public void onIsPlayingChanged(@NonNull EventTime eventTime, boolean isPlaying) {
        frameDiagnostics.setPlaybackActive(isPlaying && !awaitingVideoFrame);
        if (videoDiagnosticActive) {
            maybeLogFrameDiagnostic(eventTime, true, "trigger=is_playing playing=" + isPlaying);
        }
    }

    @Override
    public void onPositionDiscontinuity(@NonNull EventTime eventTime,
                                       @NonNull Player.PositionInfo oldPosition,
                                       @NonNull Player.PositionInfo newPosition, int reason) {
        // A buffered seek may remain READY/isPlaying throughout, so its decoder
        // flush/wait cannot be excluded by playback-state callbacks alone.
        if (reason == Player.DISCONTINUITY_REASON_SEEK
                || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) awaitingVideoFrame = true;
        frameDiagnostics.invalidateRateWindow();
        if (videoDiagnosticActive) {
            maybeLogFrameDiagnostic(eventTime, true, "trigger=discontinuity reason=" + reason);
        }
    }

    @Override
    public void onPlaybackParametersChanged(@NonNull EventTime eventTime,
                                            @NonNull PlaybackParameters playbackParameters) {
        frameDiagnostics.invalidateRateWindow();
    }

    @Override
    public void onIsLoadingChanged(@NonNull EventTime eventTime, boolean isLoading) {
        if (!videoDiagnosticActive) return;
        // Buffer top-ups can toggle several times per second; keep their snapshots
        // on the normal interval while playback state changes remain immediate.
        maybeLogFrameDiagnostic(eventTime, false, "trigger=loading loading=" + isLoading);
    }

    @Override
    public void onDroppedVideoFrames(@NonNull EventTime eventTime, int droppedFrames, long elapsedMs) {
        pendingDroppedFrames += droppedFrames;
        pendingDroppedElapsedMs += elapsedMs;
        maybeLogFrameDiagnostic(eventTime, false, "trigger=dropped");
    }

    @Override
    public void onVideoFrameProcessingOffset(@NonNull EventTime eventTime, long totalProcessingOffsetUs, int frameCount) {
        pendingProcessingOffsetUs += totalProcessingOffsetUs;
        pendingProcessingFrames += frameCount;
        maybeLogFrameDiagnostic(eventTime, false, "trigger=processing");
    }

    @Override
    public void onRenderedFirstFrame(@NonNull EventTime eventTime, @NonNull Object output, long renderTimeMs) {
        if (!videoDiagnosticActive) return;
        // Includes post-seek/output replacement waits even when isPlaying never
        // changed; begin the next rate window at the first usable video frame.
        awaitingVideoFrame = false;
        frameDiagnostics.invalidateRateWindow();
        // Keep the output opaque. A Surface or renderer implementation can carry
        // device-specific details that are not useful for diagnosing a stall.
        logVideoDiagnostic("playback_video_first_frame", eventTime,
                "firstFrameRenderMs=" + renderTimeMs, player.getVideoDecoderCounters());
    }

    @Override
    public void onVideoCodecError(@NonNull EventTime eventTime, @NonNull Exception videoCodecError) {
        if (!videoDiagnosticActive) return;
        // Record only the exception type. Codec messages may contain implementation
        // details or media metadata and are not required for the supply/decode split.
        logVideoDiagnostic("playback_video_codec_error", eventTime,
                "errorType=" + videoCodecError.getClass().getSimpleName(),
                player.getVideoDecoderCounters());
    }

    @Override
    public void onVideoDecoderReleased(@NonNull EventTime eventTime, @NonNull String decoderName) {
        if (!videoDiagnosticActive) return;
        // A release/recreate cycle can explain a counter reset without implying a
        // fatal playback error, so keep the event separate from codec failures.
        logVideoDiagnostic("playback_video_decoder_released", eventTime,
                "releasedDecoder=" + decoderName, player.getVideoDecoderCounters());
    }

    @Override
    public void onVideoDisabled(@NonNull EventTime eventTime, @NonNull DecoderCounters counters) {
        logVideoDiagnostic("playback_video_disabled", eventTime, pendingFrameDiagnostics("trigger=disabled"), counters);
        clearPendingFrameDiagnostics();
        frameDiagnostics.reset();
        lastBufferedMs = -1;
        videoDiagnosticActive = false;
        awaitingVideoFrame = false;
    }

    private void maybeLogFrameDiagnostic(EventTime eventTime, boolean force, String trigger) {
        // Analytics callbacks drive snapshots; no polling or per-frame disk writes.
        long now = SystemClock.elapsedRealtime();
        if (!force && lastFrameDiagnosticMs >= 0 && now - lastFrameDiagnosticMs < FRAME_DIAGNOSTIC_INTERVAL_MS) return;
        lastFrameDiagnosticMs = now;
        logVideoDiagnostic("playback_video_frames", eventTime, pendingFrameDiagnostics(trigger), player.getVideoDecoderCounters());
        clearPendingFrameDiagnostics();
    }

    private String pendingFrameDiagnostics(String trigger) {
        return "reportedDropped=" + pendingDroppedFrames + " droppedElapsedMs=" + pendingDroppedElapsedMs
                + " offsetFrames=" + pendingProcessingFrames + " averageOffsetUs="
                + (pendingProcessingFrames == 0 ? "unknown" : pendingProcessingOffsetUs / pendingProcessingFrames)
                + " " + trigger;
    }

    private void clearPendingFrameDiagnostics() {
        pendingDroppedFrames = pendingDroppedElapsedMs = pendingProcessingOffsetUs = pendingProcessingFrames = 0;
    }

    private void logVideoDiagnostic(String event, EventTime eventTime, String detail, @Nullable DecoderCounters counters) {
        Format format = diagnosticFormat;
        float displayRefreshHz = PlaybackDisplayDiagnostics.getRefreshRate(App.get());
        float sourceFrameRate = format == null ? Format.NO_VALUE : format.frameRate;
        String video = format == null ? "" : " mime=" + format.sampleMimeType + " fps=" + format.frameRate
                + " width=" + format.width + " height=" + format.height;
        String counts = "";
        if (counters != null) {
            counters.ensureUpdated();
            counts = " rendered=" + counters.renderedOutputBufferCount + " dropped=" + counters.droppedBufferCount
                    + " skipped=" + counters.skippedOutputBufferCount + " maxConsecutiveDropped=" + counters.maxConsecutiveDroppedBufferCount;
        }
        frameDiagnostics.setPlaybackActive(player.isPlaying() && !awaitingVideoFrame);
        PlaybackFrameDiagnostics.Delta frameDelta = counters == null ? null : frameDiagnostics.sample(
                SystemClock.elapsedRealtime(), counters.renderedOutputBufferCount,
                counters.droppedBufferCount, counters.skippedOutputBufferCount,
                counters.maxConsecutiveDroppedBufferCount,
                counters.totalVideoFrameProcessingOffsetUs,
                counters.videoFrameProcessingOffsetCount);
        String frame = frameDelta == null ? "" : " " + frameDelta.toLogString();
        long decoderRate = frameDelta == null ? -1 : frameDelta.renderedPerSecond();
        float expectedFrameRate = sourceFrameRate > 0
                ? sourceFrameRate * player.getPlaybackParameters().speed : sourceFrameRate;
        String frameRate = " displayHz=" + PlaybackDisplayDiagnostics.formatRefreshRate(displayRefreshHz)
                + " fpsStatus=" + PlaybackFrameDiagnostics.rateStatus(expectedFrameRate,
                displayRefreshHz, decoderRate);
        long bufferedMs = player.getTotalBufferedDuration();
        String bufferedDelta = bufferedMs < 0 ? "bufferedMs=unknown bufferedBaseline=true"
                : "bufferedMs=" + bufferedMs + " bufferedDeltaMs="
                + (lastBufferedMs < 0 ? "unknown" : bufferedMs - lastBufferedMs)
                + " bufferedBaseline=" + (lastBufferedMs < 0);
        lastBufferedMs = bufferedMs;
        // Never log Format.toString(), MediaItem, media URLs, titles or request headers.
        DiagnosticLog.write(App.get(), event, "eventMs=" + eventTime.realtimeMs
                + " periodSeq=" + (eventTime.mediaPeriodId == null ? "unknown" : eventTime.mediaPeriodId.windowSequenceNumber)
                + " decoder=" + diagnosticDecoder + video + counts + " state=" + player.getPlaybackState()
                + " positionMs=" + player.getCurrentPosition() + " isPlaying=" + player.isPlaying()
                + " awaitingVideoFrame=" + awaitingVideoFrame
                + " isLoading=" + player.isLoading()
                + " playWhenReady=" + player.getPlayWhenReady() + " " + bufferedDelta
                + " speed=" + player.getPlaybackParameters().speed + frameRate + " " + detail + frame);
    }

    @Override
    public Type getType() {
        return Type.EXO;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public int getAudioChannelCount() {
        Format format = player.getAudioFormat();
        return format == null ? Format.NO_VALUE : format.channelCount;
    }

    @Override
    public PlayerEffect getEffect() {
        return effect;
    }

    @Override
    public void release() {
        loadDiagnostics.stop();
        player.removeAnalyticsListener(this);
        preload.release();
        effect.release();
        session.release();
    }

    @Override
    public void setDecode(int decode) {
        session.setDecode(decode);
    }

    @Override
    public boolean isSoftware4kHevc() {
        Format format = diagnosticFormat;
        return format != null && SoftwareHevcFallback.isTarget(diagnosticDecoder,
                format.sampleMimeType, format.width, format.height);
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        loadDiagnostics.reset();
        formatRecovery.reset();
        // A reused engine may carry the previous stream's decoder/format until
        // Media3 emits the next video-enabled callback.  Clear that observation
        // before preparing a new item so a stale software-4K signal cannot
        // trigger an engine fallback for the next source.
        diagnosticDecoder = "unknown";
        diagnosticFormat = null;
        lastFrameDiagnosticMs = -1;
        clearPendingFrameDiagnostics();
        frameDiagnostics.reset();
        lastBufferedMs = -1;
        videoDiagnosticActive = false;
        awaitingVideoFrame = false;
        startInternal(startPositionMs);
    }

    @Override
    public void preload(PlaySpec spec, long startPositionMs) {
        session.preload(MediaItemFactory.from(spec), startPositionMs);
    }

    @Override
    public void clearPreload() {
        session.clearPreload();
    }

    @Override
    public void stop() {
        loadDiagnostics.stop();
        preload.stop();
        player.stop();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> seekToDefaultPosition();
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> retryFormat(e.errorCode);
            default -> ErrorAction.FATAL;
        };
    }

    private void startInternal(long position) {
        MediaItem item = MediaItemFactory.from(spec);
        MediaSource source = session.usePreloadedMediaSource(item);
        effect.clearAudioEffect();
        if (source == null) player.setMediaItem(item, position);
        else player.setMediaSource(source, position);
        preload.start(player, item);
        prepareAndPlay();
    }

    private void prepareAndPlay() {
        player.prepare();
        player.play();
    }

    private ErrorAction seekToDefaultPosition() {
        player.seekToDefaultPosition();
        player.prepare();
        return ErrorAction.RECOVERED;
    }

    private ErrorAction retryFormat(int errorCode) {
        String candidate = ExoUtil.getMimeType(errorCode);
        if (errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                && MimeTypes.APPLICATION_M3U8.equals(candidate)
                && !HlsFormatPolicy.hasHlsEvidence(spec.getFormat(), spec.getUrl())) {
            return ErrorAction.FATAL;
        }
        if (!formatRecovery.allow(spec.getFormat(), candidate)) return ErrorAction.FATAL;
        spec.setFormat(candidate);
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }
}
