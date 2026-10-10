package com.fongmi.android.tv.utils;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PosterLabelTest {
    @Test public void explicitTotalTakesPriorityOverProgressAndRating() {
        assertEquals("共24集", PosterLabel.text("更新至18集 / 共24集", "8.2"));
        assertEquals("共9集", PosterLabel.text("全9集", "7.5"));
        assertEquals("共12集", PosterLabel.text("12集已完结", "8"));
    }
    @Test public void updateCountIsNotReportedAsTotal() {
        assertEquals("更新至18集", PosterLabel.text("更新至第18集", "7.4"));
        assertEquals("更新至4集", PosterLabel.text("更至04集", ""));
        assertEquals("更新至8集", PosterLabel.text("更新8集", ""));
    }
    @Test public void missingCountsFallBackToActualRating() {
        assertEquals("7.4", PosterLabel.text("完结", "7.4"));
        assertEquals("6.7", PosterLabel.text("6.7 分", null));
        assertEquals("8.1", PosterLabel.text("评分：8.1", null));
        assertEquals("7.0", PosterLabel.text("HD", "7.0"));
    }
    @Test public void absentOrInvalidDataDoesNotInventBadges() {
        for (String note : new String[]{"HD1080P", "2026-07-21", "第5集", "8", "暂无评分", "", "更新至1080P"})
            assertEquals(note, "", PosterLabel.text(note, ""));
        assertEquals("", PosterLabel.text(null, null));
        assertEquals("", PosterLabel.text("", "0.0"));
        assertEquals("", PosterLabel.text("", "11.2"));
    }
}
