package com.fongmi.android.tv.player.track;

import android.text.TextUtils;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;

import com.fongmi.android.tv.bean.Track;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

public class TrackUtil {

    public static String describeFormat(Format format) {
        StringJoiner joiner = new StringJoiner(",");
        if (format.id != null) joiner.add(format.id);
        if (format.label != null) joiner.add(format.label);
        if (format.codecs != null) joiner.add(format.codecs);
        if (format.language != null) joiner.add(format.language);
        if (format.sampleMimeType != null) joiner.add(format.sampleMimeType);
        if (format.containerMimeType != null) joiner.add(format.containerMimeType);
        if (format.width != C.LENGTH_UNSET) joiner.add(String.valueOf(format.width));
        if (format.height != C.LENGTH_UNSET) joiner.add(String.valueOf(format.height));
        if (format.sampleRate != C.RATE_UNSET_INT) joiner.add(String.valueOf(format.sampleRate));
        if (format.channelCount != C.LENGTH_UNSET) joiner.add(String.valueOf(format.channelCount));
        if (format.averageBitrate != C.LENGTH_UNSET) joiner.add(String.valueOf(format.averageBitrate));
        return joiner.toString();
    }

    public static String getSubtitleMimeType(String path) {
        if (TextUtils.isEmpty(path)) return "";
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".vtt")) return MimeTypes.TEXT_VTT;
        if (lower.endsWith(".ssa") || lower.endsWith(".ass")) return MimeTypes.TEXT_SSA;
        if (lower.endsWith(".ttml") || lower.endsWith(".xml") || lower.endsWith(".dfxp")) return MimeTypes.APPLICATION_TTML;
        return MimeTypes.APPLICATION_SUBRIP;
    }

    public static int count(Tracks tracks, int type) {
        return tracks.getGroups().stream().filter(trackGroup -> trackGroup.getType() == type).mapToInt(trackGroup -> trackGroup.length).sum();
    }

    public static void reset(Player player) {
        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon().clearOverrides();
        builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false);
        builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false);
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
        player.setTrackSelectionParameters(builder.build());
    }

    /**
     * Pick the first audio format when the user has not saved an audio override.
     *
     * Media3's default selector may prefer a later language or codec (for example
     * the AAC preference), which makes the initial track differ between sources.
     * A saved TrackDialog choice is represented by an audio override and must win.
     * The player is reused between episodes, so an override for the previous
     * media item must not count as a saved choice for the current one.
     */
    public static boolean selectFirstAudioTrackIfUnset(Player player) {
        Tracks tracks = player.getCurrentTracks();
        TrackSelectionParameters parameters = player.getTrackSelectionParameters();
        boolean hasStaleAudioOverride = false;
        for (TrackSelectionOverride override : parameters.overrides.values()) {
            if (override.getType() != C.TRACK_TYPE_AUDIO) continue;
            boolean current = false;
            for (Tracks.Group trackGroup : tracks.getGroups()) {
                if (trackGroup.getType() == C.TRACK_TYPE_AUDIO
                        && trackGroup.getMediaTrackGroup().equals(override.mediaTrackGroup)) {
                    current = true;
                    break;
                }
            }
            if (current) return false;
            hasStaleAudioOverride = true;
        }
        for (Tracks.Group trackGroup : tracks.getGroups()) {
            if (trackGroup.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < trackGroup.length; i++) {
                if (!trackGroup.isTrackSupported(i)) continue;
                TrackSelectionOverride override = new TrackSelectionOverride(trackGroup.getMediaTrackGroup(), List.of(i));
                TrackSelectionParameters.Builder builder = parameters.buildUpon();
                // This also removes a previous episode's group before selecting
                // the first format in the current media item.
                builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO).setOverrideForType(override);
                player.setTrackSelectionParameters(builder.build());
                return true;
            }
        }
        if (hasStaleAudioOverride) {
            player.setTrackSelectionParameters(parameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build());
        }
        return false;
    }

    private static TrackInfo find(Player player, Track track) {
        if (track.getFormat() == null) return null;
        Tracks currentTracks = player.getCurrentTracks();
        for (Tracks.Group trackGroup : currentTracks.getGroups()) {
            if (trackGroup.getType() != track.getType()) continue;
            for (int i = 0; i < trackGroup.length; i++) {
                Format format = trackGroup.getTrackFormat(i);
                if (track.getFormat().equals(describeFormat(format))) {
                    return new TrackInfo(trackGroup, i);
                }
            }
        }
        return null;
    }

    public static void setTrackSelection(Player player, List<Track> tracks) {
        Map<Integer, TrackGroup> mediaGroupMapByType = new HashMap<>();
        Map<Integer, Integer> selectedIndexMapByType = new HashMap<>();
        for (Track track : tracks) {
            TrackInfo info = find(player, track);
            if (info == null) continue;
            int type = info.trackGroup.getType();
            mediaGroupMapByType.put(type, info.trackGroup.getMediaTrackGroup());
            if (track.isSelected()) selectedIndexMapByType.put(type, info.trackIndex);
        }
        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon();
        mediaGroupMapByType.forEach((type, mediaGroup) -> {
            Integer selectedIndex = selectedIndexMapByType.get(type);
            List<Integer> indices = selectedIndex != null ? List.of(selectedIndex) : List.of();
            builder.setOverrideForType(new TrackSelectionOverride(mediaGroup, indices));
        });
        player.setTrackSelectionParameters(builder.build());
    }

    private record TrackInfo(Tracks.Group trackGroup, int trackIndex) {
    }
}
