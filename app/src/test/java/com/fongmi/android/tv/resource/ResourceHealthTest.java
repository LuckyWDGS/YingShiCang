package com.fongmi.android.tv.resource;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Url;
import com.fongmi.android.tv.playback.vod.VodPlayRequest;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import javax.net.ssl.SSLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResourceHealthTest {

    @Before
    public void clearHealth() {
        ResourceHealth.clear();
    }

    @Test
    public void emptyDetailIsFilteredOnlyForTheSameSourceAndRevision() {
        assertEquals(ResourceHealth.Status.INVALID,
                ResourceHealth.recordDetail("source-a", "id-1", detail(), 4));
        assertTrue(ResourceHealth.isFiltered(4, "source-a", "id-1"));
        assertFalse(ResourceHealth.isFiltered(4, "source-b", "id-1"));
        assertFalse(ResourceHealth.isFiltered(5, "source-a", "id-1"));
    }

    @Test
    public void playableDetailClearsAnOldInvalidMark() {
        ResourceHealth.recordDetail("source-a", "id-1", detail(), 4);
        Vod vod = new Vod();
        vod.setFlags(List.of(flag("line", episode("episode"))));
        assertEquals(ResourceHealth.Status.AVAILABLE,
                ResourceHealth.recordDetail("source-a", "id-1", detail(vod), 4));
        assertFalse(ResourceHealth.isFiltered(4, "source-a", "id-1"));
    }

    @Test
    public void networkFailureDoesNotFilterTheCard() {
        assertEquals(ResourceHealth.Status.TRANSIENT,
                ResourceHealth.recordDetailFailure("source-a", "id-1",
                        new SocketTimeoutException("timed out"), 4));
        assertFalse(ResourceHealth.isFiltered(4, "source-a", "id-1"));
        assertEquals(ResourceHealth.Status.TRANSIENT,
                ResourceHealth.recordDetailFailure("source-a", "id-1",
                        new IOException("HTTP 404"), 4));
        assertFalse(ResourceHealth.isFiltered(4, "source-a", "id-1"));
        assertEquals(ResourceHealth.Status.INVALID,
                ResourceHealth.recordDetailFailure("source-a", "id-1",
                        new IOException("资源已下架"), 4));
        assertTrue(ResourceHealth.isFiltered(4, "source-a", "id-1"));
    }

    @Test
    public void expiredInvalidMarkIsAutomaticallyAvailableAgain() {
        ResourceHealth.recordInvalidForTest(4, "source-a", "id-1",
                0);
        assertFalse(ResourceHealth.isFiltered(4, "source-a", "id-1"));
    }

    @Test public void oneBadEpisodeOrLineDoesNotHideAnEntireSeries() {
        Episode first = episode("one"), second = episode("two");
        Flag line = flag("line", first, second), backup = flag("backup", first);
        List<Flag> flags = List.of(line, backup);
        ResourceHealth.recordPlayback("a", "1", VodPlayRequest.create("a", line, first), flags, ResourceHealth.Status.INVALID, 1);
        assertFalse(ResourceHealth.isFiltered(1, "a", "1"));
        ResourceHealth.recordPlayback("a", "1", VodPlayRequest.create("a", line, second), flags, ResourceHealth.Status.INVALID, 1);
        assertFalse(ResourceHealth.isFiltered(1, "a", "1"));
        ResourceHealth.recordPlayback("a", "1", VodPlayRequest.create("a", backup, first), flags, ResourceHealth.Status.INVALID, 1);
        assertTrue(ResourceHealth.isFiltered(1, "a", "1"));
        assertFalse(ResourceHealth.isFiltered(1, "b", "1"));
        assertFalse(ResourceHealth.isFiltered(2, "a", "1"));
        ResourceHealth.recordPlayback("a", "1", VodPlayRequest.create("a", backup, first), flags, ResourceHealth.Status.AVAILABLE, 1);
        assertFalse(ResourceHealth.isFiltered(1, "a", "1"));
    }

    @Test public void transientPlaybackAndDifferentSourceNeverPoisonResource() {
        Episode episode = episode("one"); Flag line = flag("line", episode);
        ResourceHealth.recordPlayback("a", "1", VodPlayRequest.create("a", line, episode), List.of(line), ResourceHealth.Status.TRANSIENT, 1);
        ResourceHealth.recordPlayback("a", "1", VodPlayRequest.create("b", line, episode), List.of(line), ResourceHealth.Status.INVALID, 1);
        assertFalse(ResourceHealth.isFiltered(1, "a", "1"));
    }

    @Test public void malformedOrEnvironmentErrorsStayTemporaryEvenIfWrappingMissing() {
        for (Throwable error : List.of(new IOException("HTTP 410"), new UnknownHostException("resource not found"),
                new SSLException("resource expired"), new IOException("HTTP 403 resource not found"),
                new IOException("请登录后查看，资源不存在"), new IOException("invalid JSON"),
                new IOException("outer resource not found", new SocketTimeoutException()))) {
            assertEquals(error.toString(), ResourceHealth.Status.TRANSIENT, ResourceHealth.failureStatus(error));
        }
    }

    @Test public void emptyPlaylistsAreNotPlayableButMissingResponseIsTemporary() {
        Vod vod = new Vod(); vod.setFlags(List.of(flag("line", episode("  "))));
        assertFalse(ResourceHealth.hasPlayableEntry(vod));
        assertEquals(ResourceHealth.Status.INVALID, ResourceHealth.detailStatus(detail(vod)));
        assertEquals(ResourceHealth.Status.TRANSIENT, ResourceHealth.detailStatus(null));
        Result emptyUrl = new Result() {
            @Override public boolean hasMsg() { return false; }
            @Override public Url getUrl() { return new Url() { @Override public boolean isEmpty() { return true; } }; }
        };
        assertEquals(ResourceHealth.Status.INVALID, ResourceHealth.playbackStatus(emptyUrl, null));
        assertEquals(ResourceHealth.Status.TRANSIENT, ResourceHealth.playbackStatus(emptyUrl, new IOException("HTTP 503")));
    }

    @Test public void searchNavigationRowsAreNeverPlaybackCards() {
        assertFalse(ResourceHealth.isSearchCard(card("", false, false)));
        assertFalse(ResourceHealth.isSearchCard(card("id", true, false)));
        assertFalse(ResourceHealth.isSearchCard(card("id", false, true)));
        assertTrue(ResourceHealth.isSearchCard(card("id", false, false)));
    }

    @Test public void appRestartEquivalentClearsLocalObservations() {
        ResourceHealth.recordDetail("a", "1", detail(), 1);
        assertTrue(ResourceHealth.isFiltered(1, "a", "1"));
        ResourceHealth.clear();
        assertFalse(ResourceHealth.isFiltered(1, "a", "1"));
    }

    @Test public void temporaryFailureDemotesOnlyExactResourceAndExpires() {
        ResourceHealth.recordDetailFailure("a", "1", new SocketTimeoutException(), 4);
        assertEquals(1, ResourceHealth.searchPenalty(4, "a", "1"));
        assertFalse(ResourceHealth.isFiltered(4, "a", "1"));
        assertEquals(0, ResourceHealth.searchPenalty(5, "a", "1"));
        assertEquals(0, ResourceHealth.searchPenalty(4, "b", "1"));
        ResourceHealth.clear();
        ResourceHealth.recordTemporaryForTest(4, "a", "1", 0);
        assertEquals(0, ResourceHealth.searchPenalty(4, "a", "1"));
    }

    @Test public void cancellationDoesNotDemoteButTimeoutDoes() {
        for (Throwable error : List.of(new java.util.concurrent.CancellationException(), new InterruptedException(),
                new IOException("Canceled"), new IOException("outer", new InterruptedException()))) {
            ResourceHealth.recordDetailFailure("a", "1", error, 4);
            assertEquals(0, ResourceHealth.searchPenalty(4, "a", "1"));
        }
        ResourceHealth.recordDetailFailure("a", "1", new SocketTimeoutException("timeout"), 4);
        assertEquals(1, ResourceHealth.searchPenalty(4, "a", "1"));
    }

    @Test public void successfulDetailCannotEraseAnObservedPlaybackFailure() {
        Episode episode = episode("one"); Flag line = flag("line", episode);
        VodPlayRequest request = VodPlayRequest.create("a", line, episode);
        ResourceHealth.recordPlayback("a", "1", request, List.of(line), ResourceHealth.Status.TRANSIENT, 4);
        Vod vod = new Vod(); vod.setFlags(List.of(line));
        ResourceHealth.recordDetail("a", "1", detail(vod), 4);
        assertEquals(1, ResourceHealth.searchPenalty(4, "a", "1"));
        ResourceHealth.recordPlayback("a", "1", request, List.of(line), ResourceHealth.Status.AVAILABLE, 4);
        assertEquals(0, ResourceHealth.searchPenalty(4, "a", "1"));
    }

    @Test public void detailRecoveryClearsOnlyItsTemporaryObservation() {
        ResourceHealth.recordDetailFailure("a", "1", new IOException("HTTP 503"), 4);
        Vod vod = new Vod(); vod.setFlags(List.of(flag("line", episode("one"))));
        ResourceHealth.recordDetail("a", "1", detail(vod), 4);
        assertEquals(0, ResourceHealth.searchPenalty(4, "a", "1"));
        ResourceHealth.recordDetailFailure("a", "1", new IOException("HTTP 503"), 4);
        ResourceHealth.clear();
        assertEquals(0, ResourceHealth.searchPenalty(4, "a", "1"));
    }

    private static Result detail(Vod... items) {
        Result result = new Result() { @Override public boolean hasMsg() { return false; } };
        result.setList(List.of(items)); return result;
    }
    private static Episode episode(String url) {
        return new Episode() { @Override public String getUrl() { return url; } };
    }
    private static Flag flag(String name, Episode... episodes) {
        return new Flag() {
            @Override public String getFlag() { return name; }
            @Override public List<Episode> getEpisodes() { return List.of(episodes); }
        };
    }
    private static Vod card(String id, boolean folder, boolean action) {
        return new Vod() {
            @Override public String getId() { return id; }
            @Override public String getName() { return "Title"; }
            @Override public boolean isFolder() { return folder; }
            @Override public boolean isAction() { return action; }
        };
    }
}
