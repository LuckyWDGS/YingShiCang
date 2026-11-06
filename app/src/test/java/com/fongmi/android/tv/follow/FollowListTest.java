package com.fongmi.android.tv.follow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class FollowListTest {

    private static final long NOW = 100_000_000_000L;

    @Test
    public void theFirstLookOnlySetsTheCount() {
        FollowList list = new FollowList("");
        assertFalse(list.checked("a", 12, NOW));
        assertFalse(list.updated("a"));
    }

    @Test
    public void moreEpisodesMarkTheTitleAndAreAnnouncedOnce() {
        FollowList list = new FollowList("");
        list.checked("a", 12, NOW);
        assertTrue(list.checked("a", 13, NOW));
        assertTrue(list.updated("a"));
        assertEquals(List.of("a"), list.untold());
        list.announced(list.pending(List.of("a")));
        assertEquals(List.of(), list.untold());
        assertTrue(list.updated("a"));
    }

    @Test
    public void aLineComingBackIsNotAnUpdate() {
        FollowList list = new FollowList("");
        list.checked("a", 12, NOW);
        assertFalse(list.checked("a", 8, NOW));
        assertFalse(list.checked("a", 12, NOW));
    }

    @Test
    public void aSourceNotAnsweringChangesNothingButTheTime() {
        FollowList list = new FollowList("");
        list.checked("a", 12, NOW);
        assertFalse(list.checked("a", 0, NOW + FollowList.CHECK_EVERY_MS));
        assertTrue(list.checked("a", 13, NOW + FollowList.CHECK_EVERY_MS * 2));
    }

    @Test
    public void openingTheTitleClearsTheMarkAndCountsWhatIsThere() {
        FollowList list = new FollowList("");
        list.checked("a", 12, NOW);
        list.checked("a", 13, NOW);
        assertTrue(list.seen("a", 14));
        assertFalse(list.updated("a"));
        assertFalse(list.checked("a", 14, NOW));
    }

    @Test
    public void onlyTitlesNotLookedAtLatelyAreDueAndTheLongestWaitingFirst() {
        FollowList list = new FollowList("");
        list.checked("recent", 12, NOW);
        list.checked("older", 12, NOW - FollowList.CHECK_EVERY_MS - 2);
        list.checked("oldest", 12, NOW - FollowList.CHECK_EVERY_MS - 5);
        assertEquals(List.of("new", "oldest", "older"), list.due(List.of("recent", "older", "oldest", "new"), NOW, 5));
        assertEquals(List.of("new", "oldest"), list.due(List.of("recent", "older", "oldest", "new"), NOW, 2));
    }

    @Test
    public void aSingleEpisodeIsLookedAtLessOften() {
        FollowList list = new FollowList("");
        list.checked("film", 1, NOW);
        assertEquals(List.of(), list.due(List.of("film"), NOW + FollowList.CHECK_EVERY_MS, 5));
        assertEquals(List.of("film"), list.due(List.of("film"), NOW + FollowList.CHECK_SINGLE_EVERY_MS, 5));
    }

    @Test
    public void aClockMovedBackDoesNotStopTheChecks() {
        FollowList list = new FollowList("");
        list.checked("a", 12, NOW);
        assertEquals(List.of("a"), list.due(List.of("a"), NOW - 1000, 5));
    }

    @Test
    public void marksSurviveARestart() {
        FollowList list = new FollowList("");
        list.checked("a", 12, NOW);
        list.checked("a", 13, NOW);
        list.announced(list.pending(List.of("a")));
        FollowList next = new FollowList(list.save());
        assertTrue(next.updated("a"));
        assertEquals(List.of(), next.untold());
        assertFalse(next.checked("a", 13, NOW));
    }

    @Test
    public void theListStaysBoundedAndKeepsMarkedTitles() {
        FollowList list = new FollowList("");
        list.checked("marked", 1, 0);
        list.checked("marked", 2, 0);
        for (int i = 0; i < FollowList.MAX_ENTRIES + 20; i++) list.checked("t" + i, 5, NOW + i);
        FollowList next = new FollowList(list.save());
        assertTrue(next.updated("marked"));
        assertEquals(List.of("t0"), next.due(List.of("t0"), NOW, 5));
        assertTrue(next.checked("t" + (FollowList.MAX_ENTRIES + 19), 6, NOW));
    }

    @Test public void firstSuccessAfterFailuresEstablishesBaseline() {
        FollowList list = new FollowList("");
        list.checked("a", 0, NOW);
        list = new FollowList(list.save());
        assertFalse(list.checked("a", 12, NOW + FollowList.CHECK_EVERY_MS));
        assertFalse(list.updated("a"));
        assertTrue(list.checked("a", 13, NOW + FollowList.CHECK_EVERY_MS * 2));
    }

    @Test public void skippedUiDeliveryRemainsPendingAfterRestart() {
        FollowList list = new FollowList("");
        list.checked("a", 12, NOW);
        list.checked("a", 13, NOW);
        list.pending(List.of("a"));
        FollowList next = new FollowList(list.save());
        assertEquals(List.of("a"), next.untold());
        next.announced(next.pending(List.of("a")));
        assertEquals(List.of(), new FollowList(next.save()).untold());
    }

    @Test public void oldDeliveryCannotConsumeNewerUpdateOrAnotherConfigurationsTitle() {
        FollowList list = new FollowList("");
        list.checked("a", 12, NOW);
        list.checked("a", 13, NOW);
        var old = list.pending(List.of("a"));
        list.checked("a", 14, NOW);
        list.announced(old);
        assertEquals(List.of("a"), list.untold());
        assertTrue(list.pending(List.of("another config")).isEmpty());
    }

    @Test
    public void aDamagedSaveStartsClean() {
        assertFalse(new FollowList("[1,2").updated("a"));
    }
}
