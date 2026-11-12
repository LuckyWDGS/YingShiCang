package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.cloudlogin.DriveLogins;
import com.fongmi.android.tv.playback.PlaybackResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Opening a title and losing lines, through the controller with a stand-in screen and source. */
public class VodLineFlowTest {

    private static final String KEY = "site";
    private static final String ID = "1";

    private final List<VodPlayRequest> requests = new ArrayList<>();
    private final List<String> switched = new ArrayList<>();
    private final List<String> searches = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final History saved = new History();
    private final VodPlaybackState state = new VodPlaybackState();
    private long now = 1_000;
    private int retried;
    private VodPlaybackController controller;

    @Before
    public void setUp() {
        DriveLogins.setScanned(Set.of("baidu")::contains);
        saved.setKey(KEY + "@@@" + ID + "@@@0");
        saved.setPosition(C.TIME_UNSET);
        VodDataSource source = new VodDataSource() {
            @Override public void detailContent(String key, String id) {}
            @Override public void playerContent(VodPlayRequest request) { requests.add(request); }
            @Override public void preloadContent(VodPlayRequest request) {}
            @Override public void searchContent(List<Site> sites, String keyword, boolean quick) { searches.add(keyword); }
        };
        controller = new VodPlaybackController(new Screen(), source, state, new SavedHistory(), () -> now) {
            @Override MediaMetadata metadata(Episode episode) { return MediaMetadata.EMPTY; }
        };
        state.setDetailRequest(KEY, ID);
    }

    @After
    public void tearDown() {
        DriveLogins.setScanned(null);
    }

    private static Flag line(String name, int episodes) {
        Flag flag = new Flag(name);
        for (int i = 1; i <= episodes; i++) flag.getEpisodes().add(Episode.create("第" + i + "集", name + "/" + i));
        return flag;
    }

    private static Flag named(String line, String... names) {
        Flag flag = new Flag(line);
        for (int i = 0; i < names.length; i++) flag.getEpisodes().add(Episode.create(names[i], line + "/" + i));
        return flag;
    }

    private void open(Flag... lines) {
        Vod vod = new Vod() {
            @Override public String getName() { return "QA"; }
            @Override public String getId() { return ID; }
        };
        vod.getFlags().addAll(List.of(lines));
        controller.onDetailResult(new VodDetailResult(KEY, ID, Result.vod(vod)));
    }

    private void saved(String line, String episode) {
        saved.setVodFlag(line);
        saved.setVodRemarks(episode);
    }

    private VodPlayRequest last() {
        return requests.get(requests.size() - 1);
    }

    private void addressFails() {
        controller.onPlaybackResult(new PlaybackResult<>(last(), null, new java.io.IOException("connection reset")));
    }

    /** As ResourceResponseSchema hands a source's refusal on: its words ride along as the cause. */
    private void sourceSays(String words) {
        controller.onPlaybackResult(new PlaybackResult<>(last(), null, new java.io.IOException("Source response unavailable", new java.io.IOException(words))));
    }

    private String shown() {
        return errors.get(errors.size() - 1);
    }

    private void addressArrives() {
        Result result = new Result();
        result.setUrl("http://127.0.0.1/" + requests.size());
        controller.onPlaybackResult(new PlaybackResult<>(last(), result));
    }

    @Test
    public void aScannedLineOpensInPlaceOfAnUnscannedSavedDrive() {
        saved("UC", "第5集");
        open(line("UC", 24), line("夸父", 24), line("嘟嘟", 24), line("线路一", 24));
        assertEquals("嘟嘟", last().getFlag());
        assertEquals("嘟嘟/5", last().getId());
    }

    @Test
    public void aSavedLineThatNeedsNoScanStays() {
        saved("线路一", "第5集");
        open(line("嘟嘟", 24), line("线路一", 24));
        assertEquals("线路一", last().getFlag());
    }

    @Test
    public void nothingScannedLeavesTheSourceOrderAndTheSavedLine() {
        DriveLogins.setScanned(family -> false);
        saved("夸父", "第3集");
        open(line("UC", 24), line("夸父", 24));
        assertEquals("夸父", last().getFlag());
        assertEquals("UC", state.getFlags().get(0).getFlag());
    }

    @Test
    public void aMissingEpisodeLandsOnTheClosestOneFromTheStart() {
        saved("UC", "第22集");
        saved.setPosition(600_000);
        saved.setDuration(1_200_000);
        open(line("UC", 24), line("嘟嘟", 20));
        assertEquals("嘟嘟/20", last().getId());
        assertEquals(C.TIME_UNSET, saved.getPosition());
    }

    @Test
    public void theSameEpisodeKeepsItsPosition() {
        saved("UC", "第5集");
        saved.setPosition(600_000);
        saved.setDuration(1_200_000);
        open(line("UC", 24), line("嘟嘟", 24));
        assertEquals("嘟嘟/5", last().getId());
        assertEquals(600_000, saved.getPosition());
    }

    @Test
    public void failingLinesGoRoundOnceThenTheNextSource() {
        saved("线路二", "第5集");
        open(line("线路一", 24), line("线路二", 24), line("线路三", 24));
        assertEquals("线路二", last().getFlag());
        addressFails();
        assertEquals("线路三", last().getFlag());
        addressFails();
        assertEquals("线路一", last().getFlag());
        assertTrue(searches.isEmpty());
        addressFails();
        assertEquals(List.of("线路三", "线路一"), switched);
        assertEquals(List.of("QA"), searches);
    }

    @Test
    public void aLineThatPlayedCountsAgainIfItFailsLater() {
        saved("线路一", "第5集");
        open(line("线路一", 24), line("线路二", 24));
        addressFails();
        assertEquals("线路二", last().getFlag());
        addressArrives();
        controller.playbackReady();
        for (int i = 0; i < 2; i++) {
            controller.playbackError("stalled");
            addressArrives();
        }
        controller.playbackError("stalled");
        assertEquals("线路一", last().getFlag());
        assertTrue(searches.isEmpty());
    }

    @Test
    public void pickingAnEpisodeGivesEveryLineItsTurnAgain() {
        saved("线路一", "第5集");
        open(line("线路一", 24), line("线路二", 24));
        addressFails();
        controller.selectEpisode(state.getFlag().getEpisodes().get(7));
        addressFails();
        assertEquals("线路一", last().getFlag());
        assertEquals("线路一/8", last().getId());
        assertTrue(searches.isEmpty());
    }

    @Test
    public void aStalledPlayerRetriesTheLineTwiceBeforeMoving() {
        saved("线路一", "第5集");
        open(line("线路一", 24), line("线路二", 24));
        addressArrives();
        controller.playbackError("stalled");
        assertEquals(1, retried);
        assertEquals("线路一", last().getFlag());
        addressArrives();
        controller.playbackError("stalled");
        assertEquals(2, retried);
        assertEquals("线路一", last().getFlag());
        addressArrives();
        controller.playbackError("stalled");
        assertEquals(2, retried);
        assertEquals("线路二", last().getFlag());
    }

    @Test
    public void aNewDetailForgetsFailedLines() {
        saved("线路一", "第5集");
        open(line("线路一", 24), line("线路二", 24));
        addressFails();
        assertEquals("线路二", last().getFlag());
        open(line("线路一", 24), line("线路二", 24));
        addressFails();
        assertEquals("线路一", last().getFlag());
        assertTrue(searches.isEmpty());
    }

    @Test
    public void aDriveNotLoggedInIsSaidSoAndItsOtherLinesArePassedOver() {
        DriveLogins.setScanned(family -> false);
        saved("夸克1", "第5集");
        open(line("夸克1", 24), line("夸克2", 24), line("百度1", 24), line("百度2", 24), line("线路一", 24));
        sourceSays("[quark](auth) 还没有配置夸克 Cookie，请先去配置中心登录夸克");
        assertEquals("夸克网盘还没有登录\n源提示：还没有配置夸克 Cookie，请先去配置中心登录夸克", shown());
        assertEquals("百度1", last().getFlag());
        sourceSays("[baidu](auth) 还没有配置百度网盘 Cookie，请先去配置中心登录百度网盘");
        assertEquals("夸克网盘、百度网盘还没有登录\n源提示：还没有配置百度网盘 Cookie，请先去配置中心登录百度网盘", shown());
        assertEquals("线路一", last().getFlag());
        assertEquals(3, requests.size());
        addressFails();
        assertEquals("此线路暂时无法连接\n其余线路需要先登录夸克网盘、百度网盘", shown());
        assertEquals(3, requests.size());
        assertEquals(List.of("QA"), searches);
    }

    @Test
    public void aLinePickedByHandIsAskedAndALoginSinceIsNoticed() {
        DriveLogins.setScanned(family -> false);
        saved("夸克1", "第5集");
        open(line("夸克1", 24), line("夸克2", 24), line("夸克3", 24));
        sourceSays("还没有配置夸克 Cookie，请先去配置中心登录夸克");
        assertEquals(1, requests.size());
        assertEquals(List.of("QA"), searches);
        // The user logs in and picks another line of that drive.
        controller.selectFlag(state.getFlags().get(1));
        assertEquals("夸克2", last().getFlag());
        addressArrives();
        controller.playbackReady();
        // Later that line breaks for another reason: the drive's other lines get their turn again.
        for (int i = 0; i < 2; i++) {
            controller.playbackError("stalled");
            addressArrives();
        }
        controller.playbackError("stalled");
        assertEquals("夸克3", last().getFlag());
        assertEquals("stalled", shown());
    }

    @Test
    public void aSourceTriedInsteadStartsOnALineWhoseDriveWasNotRefused() {
        DriveLogins.setScanned(family -> false);
        saved("夸克1", "第5集");
        open(line("夸克1", 24), line("夸克2", 24));
        sourceSays("还没有配置夸克 Cookie，请先去配置中心登录夸克");
        assertEquals(List.of("QA"), searches);
        int asked = requests.size();
        state.setAutoFallback(true);
        open(line("夸克原画", 24), line("线路一", 24));
        assertEquals(asked + 1, requests.size());
        assertEquals("线路一", last().getFlag());
    }

    @Test
    public void theLineRowReportingTheAppsOwnChangeDoesNotCancelTheChangeOfSource() {
        saved("线路一", "第5集");
        open(line("线路一", 24), line("线路二", 24));
        addressFails();
        addressFails();
        assertEquals(List.of("QA"), searches);
        // The TV's row reports the last automatic change of line a moment later.
        controller.selectFlag(state.getFlag());
        assertTrue(state.isSelectFirstSource());
        // A line the user picks does cancel it.
        controller.selectFlag(state.getFlags().get(0));
        org.junit.Assert.assertFalse(state.isSelectFirstSource());
    }

    @Test
    public void anOrdinaryFailureSaysNothingAboutLogins() {
        saved("夸克1", "第5集");
        open(line("夸克1", 24), line("夸克2", 24));
        addressFails();
        assertEquals("此线路暂时无法连接", shown());
        assertEquals("夸克2", last().getFlag());
    }

    @Test
    public void nextSkipsASecondCopyOfTheSameEpisode() {
        saved("线路一", "22.mp4");
        open(named("线路一", "21.mp4", "22.mp4", "22.mp4", "23.mp4"));
        assertEquals("线路一/1", last().getId());
        addressArrives();
        controller.nextEpisode(true);
        assertEquals("线路一/3", last().getId());
    }

    @Test
    public void previousSkipsItToo() {
        saved("线路一", "23.mp4");
        open(named("线路一", "21.mp4", "21.mp4", "22.mp4", "22.mp4", "23.mp4"));
        addressArrives();
        controller.prevEpisode(true);
        assertEquals("线路一/3", last().getId());
        addressArrives();
        controller.prevEpisode(true);
        assertEquals("线路一/1", last().getId());
    }

    @Test
    public void aLineOfOneNameStillSteps() {
        saved("线路一", "正片");
        open(named("线路一", "正片", "正片", "正片"));
        addressArrives();
        controller.nextEpisode(true);
        assertEquals("线路一/1", last().getId());
    }

    private final class SavedHistory extends VodHistoryPolicy {
        @Override public History findOrCreate(String key, String mark, Vod item) { return saved; }
        @Override public void save(History history) {}
        @Override public void save(History history, boolean exit) {}
        @Override public void saveCurrent(History history) {}
        @Override public void saveProgress(History history, boolean exit, long time, long position, long duration) {}
    }

    private final class Screen implements VodPlaybackHost {
        @Override public String getVodKey() { return KEY; }
        @Override public String getVodId() { return ID; }
        @Override public void setVodId(String id) {}
        @Override public String getVodName() { return "QA"; }
        @Override public String getVodPic() { return ""; }
        @Override public String getVodMark() { return ""; }
        @Override public String getHistoryKey() { return saved.getKey(); }
        @Override public boolean isSiteChangeable() { return true; }
        @Override public boolean isFromCollect() { return false; }
        @Override public boolean isHostFinishing() { return false; }
        @Override public boolean isPlayerEmpty() { return false; }
        @Override public boolean hasPlaybackSession() { return false; }
        @Override public boolean isFullscreenForPlayback() { return false; }
        @Override public boolean isLivePlayback() { return false; }
        @Override public boolean canTrackPlaybackProgress() { return false; }
        @Override public boolean canPreloadNext() { return false; }
        @Override public void onPlaybackRetry() { retried++; }
        @Override public long getPlayerPosition() { return 0; }
        @Override public long getPlayerDuration() { return 0; }
        @Override public void usePushId(String id) {}
        @Override public void onPlaybackRequested() {}
        @Override public void prepareSource(Vod item) {}
        @Override public void stopPlaybackForRefresh() {}
        @Override public void resetPlaybackForError(String msg) { errors.add(msg); }
        @Override public void replay(long position) {}
        @Override public void startPlayback(Result result, boolean useParse, long startPositionMs, MediaMetadata metadata) {}
        @Override public boolean preloadPlayback(Result result, long startPositionMs, MediaMetadata metadata) { return false; }
        @Override public void clearPreload() {}
        @Override public void loadDanmaku(Result result, History history, Episode episode) {}
        @Override public void renderDetail(Vod item, History history) {}
        @Override public void renderVodUpdate(Vod item) {}
        @Override public void renderEmptyDetail() {}
        @Override public void renderFallbackName(String name) {}
        @Override public void renderFlags(List<Flag> items) {}
        @Override public void renderEpisodes(List<Episode> items) {}
        // As on the TV: the line row reports the app's own change of line as a pick too.
        @Override public void renderFlagSelection(Flag item) { controller.selectFlag(item); }
        @Override public void renderEpisodeSelection(Episode item) {}
        @Override public void renderReverseEpisodes(List<Episode> items, boolean scroll) {}
        @Override public void renderQuality(Result result, boolean visible) {}
        @Override public void renderQualityVisible(boolean visible) {}
        @Override public void renderSources(List<Vod> items) {}
        @Override public void renderHistory(History history) {}
        @Override public void renderUseParse(boolean useParse) {}
        @Override public void renderArtwork(String url) {}
        @Override public void renderDescription(String desc) {}
        @Override public void renderPlaybackMetadata(MediaMetadata metadata) {}
        @Override public void onDetailFallbackScheduled() {}
        @Override public void onDetailFallbackCancelled() {}
        @Override public void onSearchStarted(String keyword) {}
        @Override public void onSearchResult() {}
        @Override public void showDetailMessage(String msg) {}
        @Override public void showSwitchLine(Flag flag) { switched.add(flag.getFlag()); }
        @Override public void showSwitchSource(Vod item) {}
        @Override public void showNoNext(boolean reversed) {}
        @Override public void showNoPrev(boolean reversed) {}
        @Override public void finishVod() {}
    }
}
