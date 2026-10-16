package com.fongmi.android.tv.utils;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PosterLabelTest {
    @Test public void aiCardsOnlyDisplaySourceDeclaredTotals() {
        assertEquals("共24集", PosterLabel.totalEpisodes("全24集"));
        assertEquals("共9集", PosterLabel.totalEpisodes("共09集"));
        assertEquals("共12集", PosterLabel.totalEpisodes("12集已完结"));
        assertEquals("共24集", PosterLabel.totalEpisodes("更新至18集 / 共24集"));
        for (String note : new String[]{null, "", "更新至18集", "第18集", "18", "8.2分", "评分：9.1", "完结", "全0集", "1080P", "2026-10-16"})
            assertEquals(note, "", PosterLabel.totalEpisodes(note));
    }

    @Test public void eachSurfaceUsesItsOwnMetadataMeaning() {
        assertEquals("8.2", PosterLabel.rating("更新至18集 / 共24集", "8.2"));
        assertEquals("共24集", PosterLabel.episodes("更新至18集 / 共24集"));
        assertEquals("", PosterLabel.rating("全24集", ""));
        assertEquals("", PosterLabel.episodes("8.2分"));
        assertEquals("", PosterLabel.episodes("1080P"));
        assertEquals("", PosterLabel.episodes("2026-10-14"));
    }
    @Test public void historyNamesAndSourceIdentitySurviveMissingConfiguration() {
        assertEquals("第1集", PosterLabel.history("1"));
        assertEquals("第3集", PosterLabel.history("第03集"));
        assertEquals("特别篇", PosterLabel.history("特别篇"));
        assertEquals("", PosterLabel.history(null));
        assertEquals("热播", PosterLabel.source("", "热播"));
        assertEquals("真实站源", PosterLabel.source("真实站源", "key"));
        assertEquals("", PosterLabel.source(null, null));
    }
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
