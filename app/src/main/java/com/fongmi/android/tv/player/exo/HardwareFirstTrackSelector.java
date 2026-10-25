package com.fongmi.android.tv.player.exo;

import android.content.Context;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;

import java.util.List;

/**
 * In hardware mode the base selector uses the platform video decoder only when it reports the format
 * as fully handled; anything else went to FFmpeg. TV hardware decoders often under-report the HEVC
 * levels they decode (user's Xiaomi TV 2026-10-25: OMX.MS.HEVC.Decoder, 4K60-capable, was passed over
 * for 720p短剧, so FFmpeg decoded them in software on a 32-bit CPU at ~7 of 30 fps). A hardware
 * decoder that supports the profile and the picture size is preferred here as well; if it still
 * fails, MediaCodec decoder fallback and PlayerManager's software retry stay in place.
 */
final class HardwareFirstTrackSelector extends DecodeTrackSelector {

    private static final String MEDIA_CODEC_VIDEO_RENDERER_NAME = "MediaCodecVideoRenderer";

    private volatile boolean hardwareVideo = true;
    // Track selection reruns for every prepare and parameter change; log each format once.
    private volatile String logged;

    HardwareFirstTrackSelector(Context context) {
        super(context);
    }

    void setHardwareVideo(boolean hardwareVideo) {
        this.hardwareVideo = hardwareVideo;
    }

    @Override
    protected boolean isPreferredRenderer(RendererCapabilities rendererCapability, TrackGroup group, int formatSupportLevel) {
        if (super.isPreferredRenderer(rendererCapability, group, formatSupportLevel)) return true;
        if (!hardwareVideo || group.type != C.TRACK_TYPE_VIDEO || formatSupportLevel != C.FORMAT_EXCEEDS_CAPABILITIES) return false;
        if (!MEDIA_CODEC_VIDEO_RENDERER_NAME.equals(rendererCapability.getName())) return false;
        Format format = group.getFormat(0);
        String decoder = hardwareDecoderFor(format);
        if (decoder == null) return false;
        String detail = "decoder=" + decoder + " mime=" + format.sampleMimeType + " codecs=" + safe(format.codecs)
                + " width=" + format.width + " height=" + format.height + " fps=" + format.frameRate;
        if (!detail.equals(logged)) {
            logged = detail;
            DiagnosticLog.write(App.get(), "playback_video_hardware_preferred", detail);
        }
        return true;
    }

    /** A hardware decoder that supports the format's profile (any level) and its size and frame rate. */
    static String hardwareDecoderFor(Format format) {
        if (format.sampleMimeType == null) return null;
        try {
            List<MediaCodecInfo> infos = MediaCodecUtil.getDecoderInfos(format.sampleMimeType, false, false);
            for (MediaCodecInfo info : infos) {
                if (!info.hardwareAccelerated || info.softwareOnly) continue;
                if (!info.isFormatFunctionallySupported(App.get(), format)) continue;
                if (format.width > 0 && format.height > 0 && !info.isVideoSizeAndRateSupported(format.width, format.height, format.frameRate)) continue;
                return info.name;
            }
        } catch (Exception ignored) {
            // No decoder list: keep the base selection.
        }
        return null;
    }

    static String safe(String codecs) {
        return codecs == null ? "none" : codecs.replaceAll("[^A-Za-z0-9.,_-]", "");
    }
}
