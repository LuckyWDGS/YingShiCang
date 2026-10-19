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
    public void malformedJsonIsRejectedByParser() {
        assertThrows(JsonSyntaxException.class, () -> DanmakuApi.parseItems("{"));
    }

    @Test
    public void missingUrlsAndNullResponseAreEmpty() {
        assertEquals(0, DanmakuApi.parseItems("[{\"name\":\"missing\"},{\"url\":null}]").size());
        assertEquals(0, DanmakuApi.parseItems("null").size());
    }
}
