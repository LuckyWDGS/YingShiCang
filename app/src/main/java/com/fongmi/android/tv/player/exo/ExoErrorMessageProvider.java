package com.fongmi.android.tv.player.exo;

import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.HttpDataSource;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;

// User-facing text only; diagnostics keep logging PlaybackException.getErrorCodeName().
public class ExoErrorMessageProvider {

    public String get(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_TIMEOUT, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> ResUtil.getString(R.string.error_play_timeout);
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> ResUtil.getString(R.string.error_play_network);
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> httpStatus(e);
            case PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> ResUtil.getString(R.string.error_play_content_type);
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> ResUtil.getString(R.string.error_play_io);
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> ResUtil.getString(R.string.error_play_malformed);
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> ResUtil.getString(R.string.error_play_unsupported);
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ResUtil.getString(R.string.error_play_decode);
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> ResUtil.getString(R.string.error_play_exceeds);
            case PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> ResUtil.getString(R.string.error_play_reclaimed);
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> ResUtil.getString(R.string.error_play_audio);
            case PlaybackException.ERROR_CODE_DRM_UNSPECIFIED, PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR, PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR, PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED, PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED, PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED, PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION, PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> ResUtil.getString(R.string.error_play_drm_failed);
            default -> ResUtil.getString(R.string.error_play_unknown, e.getErrorCodeName());
        };
    }

    private String httpStatus(PlaybackException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException response) return ResUtil.getString(R.string.error_play_http, response.responseCode);
        }
        return ResUtil.getString(R.string.error_play_http_unknown);
    }
}
