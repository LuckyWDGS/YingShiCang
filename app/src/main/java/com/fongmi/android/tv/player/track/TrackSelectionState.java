package com.fongmi.android.tv.player.track;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;

import com.fongmi.android.tv.bean.Track;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Restores each track type once per playback, including tracks discovered after the video. */
public final class TrackSelectionState {
    private final Set<Integer> initialized = new HashSet<>();

    public void reset() {
        initialized.clear();
    }

    public void apply(Player player, Supplier<List<Track>> savedTracks) {
        Set<Integer> added = new HashSet<>();
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            // Mark before changing parameters: some engines notify listeners synchronously.
            if (initialized.add(group.getType())) added.add(group.getType());
        }
        if (added.isEmpty()) return;
        if (added.contains(C.TRACK_TYPE_AUDIO)) {
            // An equal-looking group in the next episode is still a new playback.
            // Only the persisted choice below should carry across that boundary.
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build());
        }
        TrackUtil.setTrackSelection(player, savedTracks.get().stream()
                .filter(track -> added.contains(track.getType())).toList());
        if (added.contains(C.TRACK_TYPE_AUDIO)) TrackUtil.selectFirstAudioTrackIfUnset(player);
    }
}
