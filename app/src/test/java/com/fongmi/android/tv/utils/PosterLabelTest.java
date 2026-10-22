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
    @Test public void historyFileNamesShowTheirEpisode() {
        assertEquals("第1集", PosterLabel.history("[1.51GB]S01E01.mkv"));
        assertEquals("第1集", PosterLabel.history("[5.18GB]S01E01.4KHQHDR60FPS.mp4"));
        assertEquals("第12集", PosterLabel.history("某剧.S02E12.2160p.mkv"));
        assertEquals("第7集", PosterLabel.history("EP07"));
        assertEquals("第4集", PosterLabel.history("第4集"));
        assertEquals("某电影.2023.HEVC", PosterLabel.history("[2.10GB]某电影.2023.HEVC.mkv"));
        assertEquals("HD中字", PosterLabel.history("HD中字"));
        assertEquals("E.T.", PosterLabel.history("E.T."));
    }
    @Test public void watchedTimeShowsOnlyMeaningfulProgress() {
        assertEquals("20:19", PosterLabel.watched(1_219_730, 2_890_000));
        assertEquals("1:02:03", PosterLabel.watched(3_723_000, 7_200_000));
        assertEquals("", PosterLabel.watched(30_000, 2_890_000));
        assertEquals("", PosterLabel.watched(2_885_000, 2_890_000));
        assertEquals("05:00", PosterLabel.watched(300_000, 0));
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
