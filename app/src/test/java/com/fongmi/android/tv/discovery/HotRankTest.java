package com.fongmi.android.tv.discovery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HotRankTest {

    @Test
    public void doubanTitlesAreCleanedDeduplicatedAndBounded() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 14; i++) items.append(",{\"title\":\"剧").append(i).append("\"}");
        String body = "{\"subject_collection_items\":[{\"title\":\"嫁金钗\"},{\"title\":\"神探之痕迹‎\"},{\"title\":\"嫁金钗\"},{\"title\":\" \"},{\"id\":1}" + items + "]}";
        assertEquals("{\"data\":[{\"title\":\"嫁金钗\"},{\"title\":\"神探之痕迹\"},{\"title\":\"剧0\"},{\"title\":\"剧1\"},{\"title\":\"剧2\"},"
                + "{\"title\":\"剧3\"},{\"title\":\"剧4\"},{\"title\":\"剧5\"},{\"title\":\"剧6\"},{\"title\":\"剧7\"}]}", HotRank.fromDouban(body));
    }

    @Test
    public void emptyResponsesGiveNoWords() {
        assertEquals("", HotRank.fromDouban("{\"subject_collection_items\":[]}"));
        assertEquals("", HotRank.fromDouban("{\"msg\":\"rate_limit\"}"));
        assertEquals("", HotRank.from360("{\"data\":[{\"title\":\"战狼2\",\"cat\":1}]}", 2));
    }

    @Test
    public void rank360KeepsOnlyTheRequestedCategory() {
        assertEquals("{\"data\":[{\"title\":\"醒来\"}]}", HotRank.from360("{\"data\":[{\"title\":\"战狼2\",\"cat\":1},{\"title\":\"醒来\",\"cat\":2}]}", 2));
    }
}
