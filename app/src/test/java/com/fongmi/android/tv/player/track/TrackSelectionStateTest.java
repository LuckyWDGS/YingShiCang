package com.fongmi.android.tv.player.track;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;

import com.fongmi.android.tv.bean.Track;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Playback boundaries and late discovery must not override a user's in-session choice. */
public class TrackSelectionStateTest {
    private final TrackSelectionState state = new TrackSelectionState();
    private final FakePlayer fake = new FakePlayer();
    private final TrackGroup audio = new TrackGroup("audio", format("first", MimeTypes.AUDIO_AAC), format("second", MimeTypes.AUDIO_AAC));

    @Test
    public void audioArrivingAfterVideoStillGetsDefault() {
        fake.tracks(group(new TrackGroup("video", format("video", MimeTypes.VIDEO_H264)), C.FORMAT_HANDLED));
        state.apply(fake.player, List::of);
        fake.tracks(group(audio, C.FORMAT_HANDLED, C.FORMAT_HANDLED));
        state.apply(fake.player, List::of);
        assertEquals(List.of(0), fake.parameters.overrides.get(audio).trackIndices);
    }

    @Test
    public void synchronousNotificationsDoNotReenterInitialization() {
        fake.tracks(group(audio, C.FORMAT_HANDLED, C.FORMAT_HANDLED));
        fake.changed = () -> state.apply(fake.player, List::of);
        state.apply(fake.player, List::of);
        assertEquals(List.of(0), fake.parameters.overrides.get(audio).trackIndices);
        assertTrue(fake.writes < 10);
    }

    @Test
    public void manualSelectionSurvivesTrackUpdatesButNotAnUnrelatedPlayback() {
        fake.tracks(group(audio, C.FORMAT_HANDLED, C.FORMAT_HANDLED));
        state.apply(fake.player, List::of);
        fake.select(audio, 1);
        state.apply(fake.player, List::of);
        assertEquals(List.of(1), fake.parameters.overrides.get(audio).trackIndices);
        state.reset();
        state.apply(fake.player, List::of);
        assertEquals(List.of(0), fake.parameters.overrides.get(audio).trackIndices);
    }

    @Test
    public void savedChoiceAndExplicitOffOverrideTheDefault() {
        fake.tracks(group(audio, C.FORMAT_HANDLED, C.FORMAT_HANDLED));
        Track saved = new Track(C.TRACK_TYPE_AUDIO, "second", TrackUtil.describeFormat(audio.getFormat(1)));
        saved.setSelected(true);
        state.apply(fake.player, () -> List.of(saved));
        assertEquals(List.of(1), fake.parameters.overrides.get(audio).trackIndices);
        saved.setSelected(false);
        state.reset();
        state.apply(fake.player, () -> List.of(saved));
        assertTrue(fake.parameters.overrides.get(audio).trackIndices.isEmpty());
    }

    @Test
    public void equalRebuiltGroupStillRepresentsTheUsersChoice() {
        fake.select(audio, 1);
        TrackGroup rebuilt = new TrackGroup("audio", audio.getFormat(0), audio.getFormat(1));
        fake.tracks(group(rebuilt, C.FORMAT_HANDLED, C.FORMAT_HANDLED));
        TrackUtil.selectFirstAudioTrackIfUnset(fake.player);
        assertEquals(List.of(1), fake.parameters.overrides.get(rebuilt).trackIndices);
    }

    @Test
    public void unsupportedFirstAudioFallsBackWithoutChangingSubtitles() {
        TrackGroup text = new TrackGroup("text", format("subtitles", MimeTypes.TEXT_VTT));
        fake.select(text, 0);
        fake.tracks(group(audio, C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_HANDLED));
        state.apply(fake.player, List::of);
        assertEquals(List.of(1), fake.parameters.overrides.get(audio).trackIndices);
        assertEquals(List.of(0), fake.parameters.overrides.get(text).trackIndices);
    }

    private static Format format(String id, String mime) {
        return new Format.Builder().setId(id).setSampleMimeType(mime).build();
    }

    private static Tracks.Group group(TrackGroup group, int... support) {
        return new Tracks.Group(group, false, support, new boolean[support.length]);
    }

    private static final class FakePlayer implements InvocationHandler {
        Tracks tracks = Tracks.EMPTY;
        TrackSelectionParameters parameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT;
        Runnable changed;
        int writes;
        final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class}, this);

        void tracks(Tracks.Group... groups) { tracks = new Tracks(Arrays.asList(groups)); }

        void select(TrackGroup group, Integer... indices) {
            parameters = parameters.buildUpon().setOverrideForType(new TrackSelectionOverride(group, Arrays.asList(indices))).build();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getCurrentTracks" -> tracks;
                case "getTrackSelectionParameters" -> parameters;
                case "setTrackSelectionParameters" -> {
                    if (++writes > 20) throw new AssertionError("Track initialization reentered");
                    parameters = (TrackSelectionParameters) args[0];
                    if (changed != null) changed.run();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
