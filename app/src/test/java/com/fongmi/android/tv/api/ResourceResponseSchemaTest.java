package com.fongmi.android.tv.api;

import com.fongmi.android.tv.resource.ResourceHealth;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class ResourceResponseSchemaTest {
    @Test public void xmlChallengeAndErrorNodesAreNotSuccessfulEmptyDetails() throws Exception {
        ResourceResponseSchema.xml("<?xml version=\"1.0\"?><rss><list page=\"1\"/></rss>");
        ResourceResponseSchema.xml("<rss><list><video><id>1</id><name>Title</name><dl><dd flag=\"main\">01$https://example.test/v</dd></dl></video></list></rss>");
        for (String body : new String[]{"", "<html>challenge</html>", "<rss/>", "<rss><list>login required</list></rss>",
                "<rss><list><error>验证码</error></list></rss>", "<rss><list><video/></list></rss>"}) {
            try { ResourceResponseSchema.xml(body); fail(body); }
            catch (IOException error) { assertEquals(ResourceHealth.Status.TRANSIENT, ResourceHealth.failureStatus(error)); }
        }
    }
    @Test public void invalidPayloadIsTemporaryInsteadOfAnEmptyDetail() {
        for (String body : new String[]{"", "<html>challenge</html>", "{", "{}", "[]", "null",
                "{\"list\":null}", "{\"list\":[null]}", "{\"list\":[{}]}",
                "{\"list\":[],\"msg\":\"请登录后查看\"}",
                "{\"list\":[],\"code\":503}", "{\"list\":[],\"status\":404}",
                "{\"list\":[],\"success\":false}", "{\"list\":[],\"error\":{\"captcha\":true}}",
                "{\"list\":[{\"vod_id\":\"1\",\"vod_play_url\":{}}]}"}) {
            assertFailure(body, true, ResourceHealth.Status.TRANSIENT);
        }
    }

    @Test public void emptySuccessAndExplicitMissingAreDistinct() throws Exception {
        assertTrue(ResourceResponseSchema.json("{\"code\":1,\"msg\":\"success\",\"list\":[]}", true).getAsJsonArray("list").isEmpty());
        assertFailure("{\"code\":404,\"message\":\"资源不存在\"}", true, ResourceHealth.Status.INVALID);
        assertFailure("{\"msg\":\"分享已失效\"}", false, ResourceHealth.Status.INVALID);
        assertFailure("{\"code\":403,\"msg\":\"资源不存在，请登录\"}", false, ResourceHealth.Status.TRANSIENT);
    }

    @Test public void successMessageDoesNotTurnPlayableContentIntoAnError() throws Exception {
        assertFalse(ResourceResponseSchema.json("{\"msg\":\"ok\",\"list\":[{\"vod_id\":1,\"vod_play_from\":\"main\",\"vod_play_url\":\"1$https://example.test/v\"}]}", true).has("msg"));
        assertFalse(ResourceResponseSchema.json("{\"msg\":\"success\",\"url\":\"https://example.test/v\"}", false).has("msg"));
        assertFalse(ResourceResponseSchema.json("{\"msg\":\"获取成功\",\"url\":\"\"}", false).has("msg"));
    }

    @Test public void validUrlVariantsMatchTheProductionAdapter() throws Exception {
        for (String url : new String[]{"\"https://example.test/v\"", "\"\"", "[]",
                "[\"HD\",\"https://example.test/v\"]",
                "{\"values\":[{\"n\":\"HD\",\"v\":\"https://example.test/v\"}]}"})
            assertNotNull(ResourceResponseSchema.json("{\"msg\":\"success\",\"url\":" + url + "}", false));
    }

    @Test public void malformedPlayerUrlsRemainTemporary() {
        for (String url : new String[]{"null", "{}", "true", "123", "[\"HD\"]", "[{},{}]", "{\"values\":[{}]}"})
            assertFailure("{\"url\":" + url + "}", false, ResourceHealth.Status.TRANSIENT);
    }

    @Test public void emptyQualityDoesNotHideAnotherPlayableQuality() throws Exception {
        assertEquals(2, ResourceResponseSchema.json("{\"url\":[\"bad\",\"\",\"good\",\"https://example.test/v\"]}", false).getAsJsonArray("url").size());
        assertEquals(1, ResourceResponseSchema.json("{\"url\":{\"position\":-1,\"values\":[{\"v\":\"\"},{\"v\":\"https://example.test/v\"}]}}", false).getAsJsonObject("url").getAsJsonArray("values").size());
    }

    @Test public void providerErrorDetailsAreNotPropagated() {
        try { ResourceResponseSchema.json("{\"msg\":\"资源不存在 https://private.test/?token=secret\"}", true); fail(); }
        catch (IOException error) { assertEquals("Resource not found", error.getMessage()); }
    }

    private static void assertFailure(String body, boolean detail, ResourceHealth.Status status) {
        try { ResourceResponseSchema.json(body, detail); fail("Accepted invalid payload: " + body); }
        catch (IOException error) { assertEquals(body, status, ResourceHealth.failureStatus(error)); }
    }
}
