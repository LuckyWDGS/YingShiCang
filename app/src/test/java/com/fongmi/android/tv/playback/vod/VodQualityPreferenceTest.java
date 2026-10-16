package com.fongmi.android.tv.playback.vod;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class VodQualityPreferenceTest {
    @Test public void unsortedSourceOptionsChooseHighestResolution() {
        assertEquals(2, VodQualityPreference.highest(List.of("标清", "1080P", "4K", "高清"), 0));
        assertEquals(1, VodQualityPreference.highest(List.of("2160p HDR", "8K", "2K"), 0));
    }
    @Test public void namedTiersAndTraditionalLabelsAreRecognized() {
        assertEquals(1, VodQualityPreference.highest(List.of("標清", "藍光", "高清", "流暢"), 0));
        assertEquals(2, VodQualityPreference.highest(List.of("SD", "HD", "Full HD"), 0));
    }
    @Test public void explicitResolutionWinsOverMarketingWords() {
        assertEquals(1, VodQualityPreference.highest(List.of("超清 720P", "1080p", "4K片源 480p"), 0));
        assertEquals(1, VodQualityPreference.highest(List.of("蓝光 720P", "1080p"), 0));
    }
    @Test public void unknownAndEqualOptionsKeepDeclaredDefault() {
        assertEquals(1, VodQualityPreference.highest(List.of("原画", "默认", "线路三"), 1));
        assertEquals(1, VodQualityPreference.highest(List.of("1080P AVC", "1080P HEVC"), 1));
        assertEquals(0, VodQualityPreference.highest(List.of("HDR", "杜比"), 0));
    }
    @Test public void emptyUrlsAndEmptyListsAreSafe() {
        assertEquals(2, VodQualityPreference.highest(Arrays.asList(null, "标清", "高清"), 0));
        assertEquals(0, VodQualityPreference.highest(List.of(), 0));
        assertEquals(0, VodQualityPreference.highest(Arrays.asList(null, null), -1));
    }
    @Test public void eachEpisodeUsesItsOwnOrderAndSourceDefault() {
        assertEquals(1, VodQualityPreference.highest(List.of("720p", "2160p"), 0));
        assertEquals(0, VodQualityPreference.highest(List.of("2160p", "720p"), 1));
        assertEquals(0, VodQualityPreference.highest(List.of("单一地址"), 99));
    }
}
