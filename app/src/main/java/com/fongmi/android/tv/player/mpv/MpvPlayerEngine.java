package com.fongmi.android.tv.player.mpv;

import androidx.annotation.NonNull;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.mpvplayer.MpvPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;

import is.xyz.mpv.MPVLib;

public class MpvPlayerEngine implements PlayerEngine, Player.Listener, MPVLib.LogObserver {

    private final MpvErrorMessageProvider provider;
    private final MpvPlayerEffect effect;
    private final MpvPlayer player;
    private final MpvHlsRecovery hlsRecovery = new MpvHlsRecovery();
    private final GpuNextFailureDetector renderFailures = new GpuNextFailureDetector();
    private final boolean gpuNext;
    private volatile Runnable renderFailureHandler;
    private PlaySpec spec;

    public MpvPlayerEngine(int decode, Player.Listener listener, boolean gpuNext) {
        this.gpuNext = gpuNext;
        this.player = MpvUtil.buildPlayer(decode, listener, gpuNext);
        this.provider = new MpvErrorMessageProvider();
        this.effect = new MpvPlayerEffect(player);
        this.player.setAudioOutputListener(effect::applyAudioEffect);
        this.player.addListener(this);
        // MPV's log is global; only an engine drawing with gpu-next listens for its frame failures.
        if (gpuNext) MPVLib.addLogObserver(this);
    }

    @Override
    public boolean usesGpuNext() {
        return gpuNext;
    }

    @Override
    public void setRenderFailureHandler(Runnable handler) {
        this.renderFailureHandler = handler;
    }

    /** MPV's log thread. */
    @Override
    public void logMessage(String prefix, int level, String text) {
        if (!renderFailures.onLog(prefix, level, text, System.currentTimeMillis())) return;
        Runnable handler = renderFailureHandler;
        if (handler != null) App.post(handler);
    }

    public static boolean isAvailable() {
        return MpvUtil.isAvailable();
    }

    @Override
    public Type getType() {
        return Type.MPV;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public int getAudioChannelCount() {
        return player.getAudioChannelCount();
    }

    @Override
    public PlayerEffect getEffect() {
        return effect;
    }

    @Override
    public void release() {
        if (gpuNext) MPVLib.removeLogObserver(this);
        renderFailureHandler = null;
        player.removeListener(this);
        player.setAudioOutputListener(null);
        player.release();
    }

    @Override
    public void setSubtitleStyle() {
        MpvUtil.setSubtitleStyle(player);
    }

    @Override
    public boolean addSubtitle(Sub sub) {
        if (sub == null || sub.isEmpty() || player.getCurrentMediaItem() == null) return false;
        if (player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED) return false;
        player.addSubtitle(MediaItemFactory.buildSubConfig(sub));
        return true;
    }

    @Override
    public void setDecode(int decode) {
        player.setDecode(decode);
    }

    @Override
    public void onTracksChanged(@NonNull Tracks tracks) {
        effect.applyVideoEffect();
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        hlsRecovery.reset();
        renderFailures.reset();
        startInternal(startPositionMs);
    }

    private void startInternal(long startPositionMs) {
        effect.applyVideoEffect();
        effect.clearAudioEffect();
        player.setMediaItem(MediaItemFactory.from(spec), startPositionMs);
        prepareAndPlay();
    }

    private void prepareAndPlay() {
        player.prepare();
        player.play();
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> retryHls();
            default -> ErrorAction.FATAL;
        };
    }

    private ErrorAction retryHls() {
        if (spec == null || !hlsRecovery.allow(spec.getFormat(), spec.getUrl())) return ErrorAction.FATAL;
        spec.setFormat(MimeTypes.APPLICATION_M3U8);
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }
}
