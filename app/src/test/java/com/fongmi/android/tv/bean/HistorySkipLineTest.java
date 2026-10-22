package com.fongmi.android.tv.bean;

import androidx.media3.common.C;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HistorySkipLineTest {

    @Test
    public void skipTimesApplyOnlyOnTheLineTheyWereSetOn() {
        History history = new History();
        history.setVodFlag("百度");
        history.setLineSkip(90_000, 120_000);
        assertEquals("百度", history.getSkipFlag());
        assertEquals(90_000, history.getLineOpening());

        history.setVodFlag("夸克");
        assertFalse(history.isSkipLine());
        assertEquals(C.TIME_UNSET, history.getLineOpening());
        assertEquals(C.TIME_UNSET, history.getLineEnding());

        history.setVodFlag("百度");
        assertEquals(120_000, history.getLineEnding());
    }

    @Test
    public void settingOnAnotherLineReplacesTheTimes() {
        History history = new History();
        history.setVodFlag("百度");
        history.setLineSkip(90_000, 120_000);
        history.setVodFlag("夸克");
        history.setLineSkip(75_000, C.TIME_UNSET);
        assertEquals(75_000, history.getLineOpening());
        history.setVodFlag("百度");
        assertEquals(C.TIME_UNSET, history.getLineOpening());
    }

    @Test
    public void recordsWithoutALineKeepTheirTimes() {
        History history = new History();
        history.setVodFlag("线路1");
        history.setOpening(60_000);
        assertTrue(history.isSkipLine());
        assertEquals(60_000, history.getLineOpening());
        assertEquals("线路1", history.copy().getVodFlag());
    }
}
