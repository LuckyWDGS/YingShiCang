package com.fongmi.android.tv.discovery;

import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiscoveryContentGateTest {
    @Test public void recognizesExplicitRestrictionsWithoutAudienceOrAppearanceGuesses() {
        for (String value : new String[]{"18+", "成人", "成人向", "情色电影", "倫理片", "限制级", "R18", "XXX", "Adult films", "NC-17"})
            assertTrue(value, DiscoveryContentGate.isAdult(value));
        for (String value : new String[]{"美女", "成人教育", "成人高考", "成人培训", "成人礼", "Adult education", "Adventure", "剧情", "家庭"})
            assertFalse(value, DiscoveryContentGate.isAdult(value));
        assertTrue(DiscoveryContentGate.isAdult("成人教育 18+"));
    }

    @Test public void inspectsOnlyExplicitMetadataAndNeverPlotNamesPerformersOrNumericScores() {
        assertFalse(DiscoveryContentGate.isAdultMetadata(JsonParser.parseString(
                "{\"title\":\"成人故事\",\"vod_content\":\"情色\",\"plot\":\"18+\",\"actors\":[\"XXX\"],\"rating\":{\"value\":18}}").getAsJsonObject()));
        for (String payload : new String[]{"{\"tags\":[\"家庭\",\"倫理\"]}", "{\"genre\":{\"name\":\"情色\"}}",
                "{\"subject\":{\"type_name\":\"成人电影\"}}", "{\"is_adult\":true}", "{\"age_rating\":18}",
                "{\"card_subtitle\":\"2024 / 韩国 / 情色\"}", "{\"content_rating\":\"R18\"}",
                "{\"vod_age\":18}", "{\"vod_adult\":1}", "{\"vod_rating\":\"限制级\"}"})
            assertTrue(payload, DiscoveryContentGate.isAdultMetadata(JsonParser.parseString(payload).getAsJsonObject()));
        assertFalse(DiscoveryContentGate.isAdultMetadata(JsonParser.parseString("{\"is_adult\":false,\"genres\":[\"成人教育\"],\"age_rating\":12}").getAsJsonObject()));
    }

    @Test public void preservesDistinctCatalogKindsAndGenreMovieLabels() {
        for (String label : new String[]{"电影", "动作片", "喜劇片", "伦理片", "情色片", "色情片", "成人片", "限制級片", "Movie", "Films"})
            assertTrue(label, DiscoveryContentGate.isMovie(label));
        for (String label : new String[]{"国产剧", "韩剧", "电视剧", "TV series", "短剧", "國產劇", "韓劇", "港劇", "日劇", "歐美劇"}) {
            assertTrue(label, DiscoveryContentGate.isTelevision(label));
            assertFalse(label, DiscoveryContentGate.isMovie(label));
        }
        assertTrue(DiscoveryContentGate.isVariety("综艺"));
        assertTrue(DiscoveryContentGate.isVariety("Variety shows"));
        assertFalse(DiscoveryContentGate.isMovie("视频"));
        assertFalse(DiscoveryContentGate.isMovie("成人"));
        assertFalse(DiscoveryContentGate.isMovie("伦理"));
        assertFalse(DiscoveryContentGate.isTelevision("成人"));
    }

    @Test public void numericAgeChoiceNeedsAnAgeDeclarationAndDoesNotReadReviewScores() {
        assertTrue(DiscoveryContentGate.isAdultAgeChoice("age_rating", "", "18", "opaque"));
        assertTrue(DiscoveryContentGate.isAdultAgeChoice("opaque", "年龄分级", "18岁", "opaque"));
        assertTrue(DiscoveryContentGate.isAdultAgeChoice("content-rating", "", "opaque", "18"));
        assertFalse(DiscoveryContentGate.isAdultAgeChoice("rating", "评分", "18", "18"));
        assertFalse(DiscoveryContentGate.isAdultAgeChoice("opaque", "类型", "18", "18"));
        assertFalse(DiscoveryContentGate.isAdultAgeChoice("year", "年代", "18", "18"));
        assertFalse(DiscoveryContentGate.isAdultAgeChoice("age_rating", "年龄", "12", "opaque"));
        assertFalse(DiscoveryContentGate.isAdultMetadata(JsonParser.parseString(
                "{\"type_id\":18,\"rating\":18,\"vod_score\":18,\"route\":18}").getAsJsonObject()));
    }
}
