package com.fongmi.android.tv.player.engine;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;

import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.media.PlaySpec;

public interface PlayerEngine {

    int SOFT = C.DECODE_SOFTWARE;
    int HARD = C.DECODE_HARDWARE;

    Type getType();

    Player getPlayer();

    int getAudioChannelCount();

    void release();

    void setDecode(int decode);

    /**
     * Returns whether the current video is a 4K HEVC stream using the Exo
     * software decoder that can cause sustained frame starvation.  Other
     * engines return false; this is intentionally read-only and ephemeral.
     */
    default boolean isSoftware4kHevc() {
        return false;
    }

    default PlayerEffect getEffect() {
        return PlayerEffect.NONE;
    }

    void start(PlaySpec spec, long startPositionMs);

    default void preload(PlaySpec spec, long startPositionMs) {
    }

    default void clearPreload() {
    }

    void stop();

    default void setSubtitleStyle() {
    }

    default boolean addSubtitle(Sub sub) {
        return false;
    }

    String getErrorMessage(PlaybackException e);

    ErrorAction handleError(PlaybackException e);

    enum ErrorAction {
        RECOVERED,
        DECODE,
        FATAL
    }

    enum Type {
        EXO,
        MPV
    }
}
