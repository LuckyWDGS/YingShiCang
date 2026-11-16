package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    private final List<String> notices = new ArrayList<>();
    // As the database does once another source's history of the same title is saved.
    private boolean mergedAway;
    private final History saved = new History();
    private final VodPlaybackState state = new VodPlaybackState();
    private final java.util.Map<String, History> stored = new java.util.HashMap<>();
    private String key = KEY;
    private long now = 1_000;
    private int retried;
    private int quietRetries;
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

    /** Another source answers the fallback search with the same title. */
    private void otherSource(String site) {
        Vod vod = new Vod() {
            @Override public String getName() { return "QA"; }
            @Override public String getId() { return ID; }
        };
        vod.setSite(Site.get(site, site.toUpperCase()));
        Result result = new Result();
        result.setList(List.of(vod));
        controller.onSearchResult(result);
    }

    private void open(Flag... lines) {
        Vod vod = new Vod() {
            @Override public String getName() { return "QA"; }
            @Override public String getId() { return ID; }
        };
        vod.getFlags().addAll(List.of(lines));
        controller.onDetailResult(new VodDetailResult(key, ID, Result.vod(vod)));
    }

    /** The copy kept from the last time the title was opened. */
    private void openCached(Flag... lines) {
        Vod vod = new Vod() {
            @Override public String getName() { return "QA"; }
            @Override public String getId() { return ID; }
        };
        vod.getFlags().addAll(List.of(lines));
        controller.onDetailResult(new VodDetailResult(key, ID, Result.vod(vod), true));
    }

    @Test
    public void aCachedAddressThatFailsWaitsForTheSourceAndIsAskedAgain() {
        saved("线路一", "第3集");
        openCached(line("线路一", 10), line("线路二", 10));
        assertEquals("线路一/3", last().getId());
        addressFails();
        addressFails();
        addressFails();
        assertEquals("nothing changes line while the source's own detail is due", 1, requests.size());
        assertTrue(errors.isEmpty());
        open(line("线路一", 10), line("线路二", 10));
        assertEquals("the same episode is asked again", 2, requests.size());
        assertEquals("线路一/3", last().getId());
        addressArrives();
    }

    @Test
    public void aCachedStartWithAWorkingAddressIsNotRestartedByTheFreshDetail() {
        saved("线路一", "第3集");
        openCached(line("线路一", 10));
        addressArrives();
        open(line("线路一", 12));
        assertEquals(1, requests.size());
        assertEquals("the fresh detail's new episodes are merged in", 12, state.getFlags().get(0).getEpisodes().size());
    }

    @Test
    public void aCachedFailureCountsWhenTheSourceHasNothing() {
        saved("线路一", "第3集");
        openCached(line("线路一", 10), line("线路二", 10));
        addressFails();
        Vod empty = new Vod() {
            @Override public String getId() { return ID; }
        };
        controller.onDetailResult(new VodDetailResult(key, ID, Result.vod(empty)));
        assertFalse("the failure is handled as it would have been", errors.isEmpty());
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
    public void theLineThatHasTheEpisodeOpensAndAPickedLineGivesTheClosest() {
        // Supersedes 3.1.13 (嘟嘟 第20集 from the start): the user wants 第22集 itself (2026-11-14).
        saved("UC", "第22集");
        saved.setPosition(600_000);
        saved.setDuration(1_200_000);
        open(line("UC", 24), line("嘟嘟", 20));
        assertEquals("UC/22", last().getId());
        assertTrue(searches.isEmpty());
        controller.selectFlag(state.getFlags().get(0));
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
    public void theNextSourceOpensOnTheEpisodeBeingWatchedNotTheFirst() {
        saved("线路一", "第20集");
        saved.setPosition(600_000);
        saved.setDuration(2_400_000);
        open(line("线路一", 24));
        assertEquals("线路一/20", last().getId());
        addressFails();
        assertEquals(List.of("QA"), searches);
        otherSource("qb");
        assertEquals("qb", key);
        // The other source was seen before at 第1集: what is watched now wins.
        open(line("线路A", 30), line("线路B", 30));
        assertEquals("线路A/20", last().getId());
        History moved = stored.get("qb@@@1@@@0");
        assertEquals("第20集", moved.getVodRemarks());
        assertEquals(600_000, moved.getPosition());
    }

    @Test
    public void aLineWithoutTheEpisodeIsPassedOverForAnotherSource() {
        saved("线路一", "第20集");
        saved.setPosition(600_000);
        saved.setDuration(2_400_000);
        open(line("线路一", 24), line("线路二", 12), line("线路三", 24));
        addressFails();
        assertEquals("线路三/20", last().getId());
        addressFails();
        // 线路二 stops at 第12集: no line left has 第20集, so the search for another source starts.
        assertEquals(2, requests.size());
        assertEquals(List.of("QA"), searches);
    }

    @Test
    public void openedForAnEpisodeThisSourceLacksLooksElsewhere() {
        saved("线路一", "第20集");
        saved.setPosition(600_000);
        saved.setDuration(2_400_000);
        open(line("线路一", 12), line("线路二", 12));
        assertTrue(requests.isEmpty());
        assertEquals("本片源没有第20集，正在查找其他片源", shown());
        assertEquals(List.of("QA"), searches);
        otherSource("qb");
        open(line("线路A", 24));
        assertEquals("线路A/20", last().getId());
    }

    @Test
    public void whenNoOtherSourceAnswersTheClosestEpisodeHerePlays() {
        saved("线路一", "第20集");
        saved.setPosition(600_000);
        saved.setDuration(2_400_000);
        open(line("线路一", 12), line("线路二", 12));
        assertTrue(requests.isEmpty());
        // Every source has answered and none has the title.
        controller.onSearchFinished();
        assertEquals("线路一/12", last().getId());
        assertEquals(List.of("其他片源也没有第20集，改为播放第12集"), notices);
        // Another episode: the time saved for 第20集 does not apply.
        assertEquals("第12集", saved.getVodRemarks());
        assertEquals(C.TIME_UNSET, saved.getPosition());
        // Once: a later end of search does nothing more.
        controller.onSearchFinished();
        assertEquals(1, requests.size());
    }

    @Test
    public void whenOtherSourcesLackItTooPlaybackReturnsToTheFirstSource() {
        saved("线路一", "第20集");
        saved.setPosition(600_000);
        saved.setDuration(2_400_000);
        open(line("线路一", 12), line("线路二", 12));
        otherSource("qb");
        assertEquals("qb", key);
        open(line("线路A", 10));
        // qb stops at 第10集 and the search is still running: wait.
        assertTrue(requests.isEmpty());
        controller.onSearchFinished();
        assertEquals(KEY, key);
        assertTrue(requests.isEmpty());
        open(line("线路一", 12), line("线路二", 12));
        assertEquals("线路一/12", last().getId());
        assertEquals(List.of("其他片源也没有第20集，改为播放第12集"), notices);
        assertEquals("线路一", saved.getVodFlag());
        assertEquals(List.of("QA"), searches);
    }

    @Test
    public void theFirstSourcesHistoryMergedAwayStillReturnsToTheClosestEpisode() {
        saved("线路二", "第20集");
        saved.setPosition(600_000);
        saved.setDuration(2_400_000);
        open(line("线路一", 12), line("线路二", 12));
        otherSource("qb");
        open(line("线路A", 10));
        mergedAway = true;
        controller.onSearchFinished();
        assertEquals(KEY, key);
        open(line("线路一", 12), line("线路二", 12));
        // Not 线路一/1, where a new row would start: the line and the closest episode of what was wanted.
        assertEquals("线路二/12", last().getId());
        History again = stored.get(saved.getKey());
        assertEquals("第12集", again.getVodRemarks());
        assertEquals(C.TIME_UNSET, again.getPosition());
    }

    @Test
    public void theLastSourceLackingItAfterTheSearchEndedReturnsToo() {
        saved("线路一", "第20集");
        saved.setPosition(600_000);
        saved.setDuration(2_400_000);
        open(line("线路一", 12));
        otherSource("qb");
        // The search ends while qb's detail is on its way.
        controller.onSearchFinished();
        assertEquals("qb", key);
        open(line("线路A", 10));
        assertEquals(KEY, key);
        // Nothing is said yet: qb's lines are still the ones on screen.
        assertTrue(notices.isEmpty());
        open(line("线路一", 12));
        assertEquals("线路一/12", last().getId());
        assertEquals(List.of("其他片源也没有第20集，改为播放第12集"), notices);
        // It fails as well: nothing left, and no going round again.
        addressFails();
        addressFails();
        addressFails();
        int asked = requests.size();
        controller.onSearchFinished();
        assertEquals(asked, requests.size());
        assertEquals(List.of("QA"), searches);
    }

    @Test
    public void anotherSourceWithTheEpisodeEndsTheMatter() {
        saved("线路一", "第20集");
        saved.setPosition(600_000);
        saved.setDuration(2_400_000);
        open(line("线路一", 12));
        otherSource("qb");
        open(line("线路A", 24));
        assertEquals("线路A/20", last().getId());
        addressArrives();
        controller.playbackReady();
        controller.onSearchFinished();
        assertEquals("qb", key);
        assertEquals(1, requests.size());
        assertTrue(notices.isEmpty());
    }

    @Test
    public void aPickByHandWhileSearchingIsLeftAlone() {
        saved("线路一", "第20集");
        saved.setPosition(600_000);
        saved.setDuration(2_400_000);
        Flag first = line("线路一", 12);
        open(first);
        controller.selectEpisode(first.getEpisodes().get(2));
        assertEquals("线路一/3", last().getId());
        controller.onSearchFinished();
        assertEquals(1, requests.size());
        assertTrue(notices.isEmpty());
    }

    @Test
    public void linesThatAllFailStillEndWhereTheyDid() {
        saved("线路一", "第5集");
        open(line("线路一", 24));
        addressFails();
        addressFails();
        addressFails();
        assertEquals(List.of("QA"), searches);
        int asked = requests.size();
        controller.onSearchFinished();
        assertEquals(asked, requests.size());
        assertTrue(notices.isEmpty());
    }

    @Test
    public void aSourceThePickedByHandIsPlayedAsItIs() {
        saved("线路一", "第20集");
        saved.setPosition(600_000);
        saved.setDuration(2_400_000);
        open(line("线路一", 24));
        Vod vod = new Vod() {
            @Override public String getName() { return "QA"; }
            @Override public String getId() { return ID; }
        };
        vod.setSite(Site.get("qc", "QC"));
        controller.selectSource(vod);
        open(line("线路C", 12));
        assertEquals("线路C/12", last().getId());
        assertTrue(searches.isEmpty());
    }

    @Test
    public void theCreditsAskForTheNextEpisodeOnlyOnce() {
        saved("线路一", "第1集");
        open(line("线路一", 3));
        addressArrives();
        saved.setLineSkip(C.TIME_UNSET, 60_000);
        // The old episode keeps ticking until the next one's address arrives: it must not skip 第2集.
        for (int second = 0; second < 5; second++) controller.onTimeChanged(now + second * 1000L, 1_150_000 + second * 1000L, 1_200_000);
        assertEquals(2, requests.size());
        assertEquals("线路一/2", last().getId());
        addressArrives();
        // The last episode's credits: once, then quiet however long the TV is left on it.
        for (int second = 0; second < 5; second++) controller.onTimeChanged(now + second * 1000L, 1_150_000 + second * 1000L, 1_200_000);
        assertEquals("线路一/3", last().getId());
        addressArrives();
        int asked = requests.size();
        for (int second = 0; second < 5; second++) controller.onTimeChanged(now + second * 1000L, 1_150_000 + second * 1000L, 1_200_000);
        assertEquals(asked, requests.size());
    }

    @Test
    public void aFailureBeforeTheEpisodeEverPlayedRetriesQuietlyOnce() {
        saved("线路一", "第5集");
        open(line("线路一", 24), line("线路二", 24));
        addressArrives();
        // The first address fails at once; nothing had played yet.
        controller.playbackError("HTTP 500");
        assertEquals(1, retried);
        assertEquals(1, quietRetries);
        addressArrives();
        // It fails again: now the notice shows.
        controller.playbackError("HTTP 500");
        assertEquals(2, retried);
        assertEquals(1, quietRetries);
    }

    @Test
    public void aStallAfterPlayingStillSaysSo() {
        saved("线路一", "第5集");
        open(line("线路一", 24), line("线路二", 24));
        addressArrives();
        controller.playbackReady();
        controller.playbackError("stalled");
        assertEquals(1, retried);
        assertEquals(0, quietRetries);
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
        // The first source's history is the saved one; another source has its own (stale, or none yet).
        @Override public History findOrCreate(String key, String mark, Vod item) {
            if (key.equals(saved.getKey()) && !mergedAway) return saved;
            return stored.computeIfAbsent(key, k -> {
                History history = new History();
                history.setKey(k);
                history.setPosition(C.TIME_UNSET);
                history.setDuration(C.TIME_UNSET);
                history.findEpisode(new ArrayList<>());
                if (mergedAway) {
                    // A new row starts on the first line's first episode.
                    history.setVodFlag(item.getFlags().get(0).getFlag());
                    history.setVodRemarks(item.getFlags().get(0).getEpisodes().get(0).getName());
                }
                return history;
            });
        }
        @Override public void save(History history) {}
        @Override public void save(History history, boolean exit) {}
        @Override public void saveCurrent(History history) {}
        @Override public void saveProgress(History history, boolean exit, long time, long position, long duration) {}
    }

    private final class Screen implements VodPlaybackHost {
        @Override public String getVodKey() { return key; }
        @Override public String getVodId() { return ID; }
        @Override public void setVodId(String id) {}
        @Override public String getVodName() { return "QA"; }
        @Override public String getVodPic() { return ""; }
        @Override public String getVodMark() { return ""; }
        @Override public String getHistoryKey() { return key.equals(KEY) ? saved.getKey() : key + "@@@" + ID + "@@@0"; }
        @Override public boolean isSiteChangeable() { return true; }
        @Override public boolean isFromCollect() { return false; }
        @Override public boolean isHostFinishing() { return false; }
        @Override public boolean isPlayerEmpty() { return false; }
        @Override public boolean hasPlaybackSession() { return false; }
        @Override public boolean isFullscreenForPlayback() { return false; }
        @Override public boolean isLivePlayback() { return false; }
        @Override public boolean canTrackPlaybackProgress() { return false; }
        @Override public boolean canPreloadNext() { return false; }
        @Override public void onPlaybackRetry(boolean quiet) { retried++; if (quiet) quietRetries++; }
        @Override public long getPlayerPosition() { return 0; }
        @Override public long getPlayerDuration() { return 0; }
        @Override public void usePushId(String id) {}
        @Override public void onPlaybackRequested() {}
        @Override public void prepareSource(Vod item) { key = item.getSiteKey(); }
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
        @Override public void showDetailMessage(String msg) { if (msg != null && !msg.isEmpty()) notices.add(msg); }
        @Override public void showSwitchLine(Flag flag) { switched.add(flag.getFlag()); }
        @Override public void showSwitchSource(Vod item) {}
        @Override public void showNoNext(boolean reversed) {}
        @Override public void showNoPrev(boolean reversed) {}
        @Override public void finishVod() {}
    }
}
