package com.fongmi.android.tv.api;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class SearchResponseSchemaTest {
    @Test public void genuineNoMatchRemainsAnEmptySuccess() throws Exception {
        assertEquals(0, SearchResponseSchema.json("{\"list\":[],\"page\":1}").getAsJsonArray("list").size());
    }

    @Test public void validHitsPreservePluginFieldsAndIdentity() throws Exception {
        var object = SearchResponseSchema.json("{\"list\":[{\"vod_id\":\"origin-id\",\"vod_name\":\"片名\",\"vod_pic\":\"\"}],\"pagecount\":4}");
        assertEquals("origin-id", object.getAsJsonArray("list").get(0).getAsJsonObject().get("vod_id").getAsString());
        assertEquals(4, object.get("pagecount").getAsInt());
    }

    @Test public void blankErrorHtmlAndMalformedRowsFailWithoutExposingPayload() {
        String[] invalid = {null, "", "  ", "<html>private token</html>", "{broken", "[]", "null",
                "{}", "{\"code\":500,\"message\":\"private token\"}", "{\"list\":null}",
                "{\"list\":{}}", "{\"list\":[null]}", "{\"list\":[\"private token\"]}", "{\"list\":[{}]}",
                "{\"list\":[{\"vod_id\":{},\"vod_name\":\"x\"}]}",
                "{\"list\":[{\"vod_id\":true,\"vod_name\":\"x\"}]}",
                "{\"error\":\"private token\",\"list\":[]}"};
        for (String body : invalid) {
            try {
                SearchResponseSchema.json(body);
                fail("Invalid response was counted as a no-match");
            } catch (IOException expected) {
                assertFalse(expected.getMessage().contains("private token"));
                assertNull(expected.getCause());
            }
        }
    }

    @Test public void numericIdentityAndPartialUsableRowsRemainCompatible() throws Exception {
        var parsed = SearchResponseSchema.json("{\"list\":[{}, {\"vod_id\":42,\"vod_name\":\"片名\"}]}");
        assertEquals(2, parsed.getAsJsonArray("list").size());
        assertEquals(0, SearchResponseSchema.json("{\"error\":false,\"list\":[]}").getAsJsonArray("list").size());
    }

    @Test public void explicitHttpStyleErrorsCannotBecomeEmptySuccesses() {
        String[] errors = {"400", "500", "599", "\"403\"", "500.0"};
        for (String field : new String[]{"code", "status"}) {
            for (String error : errors) {
                try {
                    SearchResponseSchema.json("{\"" + field + "\":" + error + ",\"list\":[],\"message\":\"private token\"}");
                    fail("HTTP-style failure was counted as a no-match");
                } catch (IOException expected) {
                    assertFalse(expected.getMessage().contains("private token"));
                    assertNull(expected.getCause());
                }
            }
        }
    }

    @Test public void knownSuccessAndUnknownProviderCodesRemainCompatible() throws Exception {
        String[] codes = {"0", "1", "200", "399", "600", "777", "500.5", "\"custom-success\"", "false", "null"};
        for (String field : new String[]{"code", "status"}) {
            for (String code : codes) {
                assertEquals(0, SearchResponseSchema.json("{\"" + field + "\":" + code + ",\"list\":[]}")
                        .getAsJsonArray("list").size());
            }
        }
    }
}
