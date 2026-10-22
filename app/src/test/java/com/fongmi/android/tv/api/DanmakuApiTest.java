package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Danmaku;
import com.google.gson.JsonSyntaxException;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DanmakuApiTest {

    @Test
    public void parseItemsDropsEmptyEntriesAndKeepsFirstUrlOnly() {
        List<Danmaku> items = DanmakuApi.parseItems("["
                + "{\"name\":\"first\",\"url\":\"https://a.example/1\"},"
                + "{\"name\":\"duplicate\",\"url\":\"https://a.example/1\"},"
                + "{\"name\":\"empty\",\"url\":\"\"},"
                + "null,"
                + "{\"name\":\"second\",\"url\":\"https://a.example/2\"}] ");
        assertEquals(2, items.size());
        assertEquals("first", items.get(0).getName());
        assertEquals("https://a.example/2", items.get(1).getUrl());
    }

    @Test
    public void parseItemsBoundsLargeResponses() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 160; i++) {
            if (i > 0) json.append(',');
            json.append("{\"url\":\"https://a.example/").append(i).append("\"}");
        }
        json.append(']');
        assertEquals(128, DanmakuApi.parseItems(json.toString()).size());
    }

    @Test
    public void episodeNumbersAreReadFromCommonLabels() {
        assertEquals(3, DanmakuApi.episodeNumber("第3集"));
        assertEquals(3, DanmakuApi.episodeNumber("第 03 集"));
        assertEquals(13, DanmakuApi.episodeNumber("第十三集"));
        assertEquals(20, DanmakuApi.episodeNumber("第二十话"));
        assertEquals(7, DanmakuApi.episodeNumber("EP07"));
        assertEquals(12, DanmakuApi.episodeNumber("S01E12"));
        assertEquals(5, DanmakuApi.episodeNumber("05"));
        assertEquals(8, DanmakuApi.episodeNumber("某剧 8集"));
        assertEquals(-1, DanmakuApi.episodeNumber("正片"));
        assertEquals(-1, DanmakuApi.episodeNumber(null));
    }

    @Test
    public void searchPicksThePlayingEpisodeNotJustTheFirstResult() {
        List<Danmaku> items = List.of(Danmaku.from("公路求生 第1集", "https://a.example/1"),
                Danmaku.from("公路求生 第2集", "https://a.example/2"), Danmaku.from("公路求生 第3集", "https://a.example/3"));
        assertEquals("https://a.example/3", DanmakuApi.pick(items, "第3集").getUrl());
        assertEquals("https://a.example/1", DanmakuApi.pick(items, "正片").getUrl());
        assertEquals("https://a.example/1", DanmakuApi.pick(items, "第9集").getUrl());
    }

    @Test
    public void malformedJsonIsRejectedByParser() {
        assertThrows(JsonSyntaxException.class, () -> DanmakuApi.parseItems("{"));
    }

    @Test
    public void missingUrlsAndNullResponseAreEmpty() {
        assertEquals(0, DanmakuApi.parseItems("[{\"name\":\"missing\"},{\"url\":null}]").size());
        assertEquals(0, DanmakuApi.parseItems("null").size());
    }
}
