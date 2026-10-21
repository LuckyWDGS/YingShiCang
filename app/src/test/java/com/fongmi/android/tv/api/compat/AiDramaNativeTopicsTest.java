package com.fongmi.android.tv.api.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class AiDramaNativeTopicsTest {
    private static JsonObject tag(int id, String name) {
        JsonObject tag = new JsonObject(); tag.addProperty("category_id", id); tag.addProperty("name", name);
        tag.addProperty("schema", "provider://categorySearch?categoryId=" + id); return tag;
    }
    private static JsonObject row(String id, JsonObject... tags) {
        JsonObject row = new JsonObject(); row.addProperty("series_id", id); row.addProperty("title", "真实作品 " + id);
        row.addProperty("cover", "https://fixture.invalid/" + id); row.addProperty("video_desc", "来源简介");
        row.addProperty("episode_cnt", 132); row.addProperty("content_type", 1004);
        JsonArray schema = new JsonArray(); for (JsonObject tag : tags) schema.add(tag);
        row.addProperty("category_schema", schema.toString()); return row;
    }
    private static JsonObject response(int page, boolean more, JsonObject... rows) {
        JsonObject response = new JsonObject(), base = new JsonObject(), data = new JsonObject();
        response.addProperty("code", 0); base.addProperty("StatusCode", 0); response.add("BaseResp", base);
        data.addProperty("has_more", more); data.addProperty("next_offset", page * 18);
        JsonArray values = new JsonArray(); for (JsonObject row : rows) values.add(row);
        data.add("video_data", values); response.add("data", data); return response;
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked task) throws Exception {
        try { task.run(); fail("Unverified native data accepted"); }
        catch (IOException expected) { assertNull(expected.getCause()); assertFalse(expected.getMessage().contains("private")); }
    }

    @Test public void choicesUseCurrentLabelsAndStableCapabilityOrderRatherThanRecommendationOrder() throws Exception {
        var input = response(1, true, row("a", tag(739, "另一真实名"), tag(379, "来源新标签"), tag(379, "来源新标签")),
                row("b", tag(21, "第三项"), tag(379, "来源新标签"), tag(527, "未证实空候选"), tag(999, "未审计")));
        var choices = AiDramaNativeTopics.choices(input);
        assertEquals(List.of("379", "21", "739"), List.copyOf(choices.keySet()));
        assertEquals(Map.of("379", "来源新标签", "739", "另一真实名", "21", "第三项"), choices);
        try { choices.put("1", "forged"); fail(); } catch (UnsupportedOperationException expected) { }
        assertTrue(AiDramaNativeTopics.choices(response(1, false)).isEmpty());
    }

    @Test public void conflictingNamesWithinOrAcrossRowsCannotForgeAChoice() throws Exception {
        rejects(() -> AiDramaNativeTopics.choices(response(1, true, row("a", tag(379, "甲"), tag(379, "乙")))));
        rejects(() -> AiDramaNativeTopics.choices(response(1, true, row("a", tag(379, "甲")), row("b", tag(379, "乙")))));
    }

    @Test public void emptyDeclarationsFailButContentDependentSubsetsAreUsable() throws Exception {
        assertFalse(AiDramaNativeTopics.complete(AiDramaNativeTopics.choices(response(1, false))));
        var declarations = new JsonObject[]{tag(379, "求生"), tag(21, "修真"), tag(37, "穿越"), tag(19, "系统"),
                tag(36, "重生"), tag(1334, "诡秘"), tag(598, "异能"), tag(739, "逆袭"), tag(599, "异界"),
                tag(79, "年代"), tag(425, "搞笑"), tag(1, "都市"), tag(758, "古代"), tag(28, "萌宝")};
        for (int missing = 0; missing < declarations.length; missing++) {
            var present = new java.util.ArrayList<JsonObject>();
            for (int index = 0; index < declarations.length; index++)
                if (index != missing) present.add(declarations[index]);
            var partial = AiDramaNativeTopics.choices(response(1, false, row("current", present.toArray(new JsonObject[0]))));
            assertEquals(13, partial.size());
            // Today's recommendations simply did not carry this tag; the other 13 remain usable.
            assertTrue(AiDramaNativeTopics.complete(partial));
        }
        var full = AiDramaNativeTopics.choices(response(1, true, row("current", declarations)));
        assertEquals(14, full.size()); assertTrue(AiDramaNativeTopics.complete(full));
        declarations[0] = tag(379, "当前来源新名");
        var renamed = AiDramaNativeTopics.choices(response(1, false, row("current", declarations)));
        assertTrue(AiDramaNativeTopics.complete(renamed));
        assertEquals("当前来源新名", renamed.get("379"));
    }

    @Test public void taxonomyRequiresPositiveIntegerIdsAndBoundedRealStringNames() throws Exception {
        for (String invalid : List.of("\"379\"", "379.0", "3.79e2", "true", "-1", "0", "2147483648")) {
            var t = tag(379, "标签"); t.add("category_id", JsonParser.parseString(invalid));
            rejects(() -> AiDramaNativeTopics.choices(response(1, true, row("a", t))));
        }
        for (String name : List.of("", " ", "x\ny", "x".repeat(81)))
            rejects(() -> AiDramaNativeTopics.choices(response(1, true, row("a", tag(379, name)))));
        var t = tag(379, "标签"); t.addProperty("name", 123);
        rejects(() -> AiDramaNativeTopics.choices(response(1, true, row("a", t))));
    }

    @Test public void malformedOrExcessiveSchemaIsRejectedWithoutPayloadDetails() throws Exception {
        for (String schema : List.of("private malformed secret", "{}", "null", "[1]", "x".repeat(32769))) {
            var r = row("a", tag(379, "来源")); r.addProperty("category_schema", schema);
            rejects(() -> AiDramaNativeTopics.choices(response(1, true, r)));
        }
        var r = row("a", tag(379, "来源")); var tags = new JsonArray();
        for (int i = 0; i < 65; i++) tags.add(tag(379, "来源"));
        r.addProperty("category_schema", tags.toString());
        rejects(() -> AiDramaNativeTopics.choices(response(1, true, r)));
    }

    @Test public void requestMatchesAuditedBodyWithStringThemeIdsAndIndependentFreshArrays() throws Exception {
        var body = AiDramaNativeTopics.request("comic", 2, "379");
        assertEquals(JsonParser.parseString("{\"filter_ids\":\"\",\"req_scene\":\"comic_series\",\"offset\":18,"
                + "\"need_selector_panel\":false,\"limit\":18,\"select_items\":{\"category_dim_epoch\":[],"
                + "\"online_time\":[],\"gender\":[],\"category_dim_role\":[],\"genre\":[\"comic_series\"],"
                + "\"sort\":[\"hot_score\"],\"category_dim_theme\":[\"379\"]},\"session_id\":\"\","
                + "\"req_type\":\"only_content\",\"client_req_type\":3}"), body);
        assertTrue(body.getAsJsonObject("select_items").getAsJsonArray("category_dim_theme").get(0).getAsJsonPrimitive().isString());
        body.getAsJsonObject("select_items").getAsJsonArray("genre").add("ai_series");
        assertEquals(1, AiDramaNativeTopics.request("comic", 2, "379").getAsJsonObject("select_items").getAsJsonArray("genre").size());
        var metadata = AiDramaNativeTopics.request("comic", 1, "");
        assertEquals(0, metadata.get("offset").getAsInt());
        assertTrue(metadata.getAsJsonObject("select_items").getAsJsonArray("category_dim_theme").isEmpty());
    }

    @Test public void requestsRejectAiGuessesUnsupportedIdsAndUnboundedPages() throws Exception {
        for (String id : List.of("ai", "ai_series", "comic_series", "other", ""))
            rejects(() -> AiDramaNativeTopics.request(id, 1, "379"));
        for (String topic : List.of("0379", "379.0", "native:379", "../379", "527", "11", "830", "999"))
            rejects(() -> AiDramaNativeTopics.request("comic", 1, topic));
        rejects(() -> AiDramaNativeTopics.request("comic", 1, null));
        rejects(() -> AiDramaNativeTopics.request("comic", 2, ""));
        for (int page : List.of(-1, 0, 501, Integer.MAX_VALUE))
            rejects(() -> AiDramaNativeTopics.request("comic", page, "379"));
    }

    @Test public void pagesPreserveOriginalIdentityMetadataLabelsAndNativeHasMoreWithoutInventedTotal() throws Exception {
        var source = response(2, true, row("native-1", tag(755, "脑洞"), tag(379, "求生"), tag(36, "重生")),
                row("native-2", tag(379, "求生")));
        var before = source.deepCopy();
        var result = AiDramaNativeTopics.pageResult(source, 2, "379");
        assertEquals(before, source); assertEquals(2, result.get("page").getAsInt());
        assertEquals(9999, result.get("pagecount").getAsInt()); assertFalse(result.has("total"));
        var first = result.getAsJsonArray("list").get(0).getAsJsonObject();
        assertEquals("native-1", first.get("vod_id").getAsString()); assertEquals("真实作品 native-1", first.get("vod_name").getAsString());
        assertEquals("https://fixture.invalid/native-1", first.get("vod_pic").getAsString());
        assertEquals("来源简介", first.get("vod_content").getAsString()); assertEquals("全132集", first.get("vod_remarks").getAsString());
        assertEquals("脑洞/求生/重生", first.get("vod_class").getAsString());
    }

    @Test public void ignoredFiltersAndConflictingDeclarationsRejectTheWholePage() throws Exception {
        rejects(() -> AiDramaNativeTopics.pageResult(response(1, true, row("a", tag(379, "求生")),
                row("b", tag(5549, "冒险求生"))), 1, "379"));
        rejects(() -> AiDramaNativeTopics.pageResult(response(1, true, row("a", tag(379, "求生")),
                row("b", tag(379, "另一个名"))), 1, "379"));
        rejects(() -> AiDramaNativeTopics.pageResult(response(1, true), 1, ""));
    }

    @Test public void successfulHttpStatusCannotHideApiFailureOrMalformedStatusTypes() throws Exception {
        for (String bad : List.of("1", "\"0\"", "0.0", "false", "null")) {
            var source = response(1, true); source.add("code", JsonParser.parseString(bad));
            rejects(() -> AiDramaNativeTopics.choices(source));
            var base = response(1, true); base.getAsJsonObject("BaseResp").add("StatusCode", JsonParser.parseString(bad));
            rejects(() -> AiDramaNativeTopics.choices(base));
        }
        var source = response(1, true); source.remove("BaseResp");
        rejects(() -> AiDramaNativeTopics.choices(source));
    }

    @Test public void paginationChecksRealNonterminalOffsetAndLeavesTerminalOffsetRuleUninvented() throws Exception {
        var source = response(2, true, row("a", tag(379, "求生")));
        source.getAsJsonObject("data").addProperty("next_offset", 18);
        rejects(() -> AiDramaNativeTopics.pageResult(source, 2, "379"));
        for (String bad : List.of("\"true\"", "1", "null")) {
            var value = response(1, true); value.getAsJsonObject("data").add("has_more", JsonParser.parseString(bad));
            rejects(() -> AiDramaNativeTopics.choices(value));
        }
        var last = response(3, false, row("last", tag(379, "求生")));
        last.getAsJsonObject("data").addProperty("next_offset", 37);
        assertEquals(3, AiDramaNativeTopics.pageResult(last, 3, "379").get("pagecount").getAsInt());
        last.getAsJsonObject("data").addProperty("next_offset", -1);
        rejects(() -> AiDramaNativeTopics.pageResult(last, 3, "379"));
        assertEquals(3, AiDramaNativeTopics.pageResult(response(3, false), 3, "379").get("pagecount").getAsInt());
    }

    @Test public void wrongRowsAndMissingOrInvalidOriginalIdentityRemainErrors() throws Exception {
        var wrong = row("a", tag(379, "求生")); wrong.addProperty("content_type", 1);
        rejects(() -> AiDramaNativeTopics.choices(response(1, true, wrong)));
        for (String key : List.of("series_id", "title", "episode_cnt")) {
            var value = row("a", tag(379, "求生")); value.remove(key);
            rejects(() -> AiDramaNativeTopics.pageResult(response(1, true, value), 1, "379"));
        }
        var value = row("a", tag(379, "求生")); value.addProperty("series_id", " ");
        rejects(() -> AiDramaNativeTopics.pageResult(response(1, true, value), 1, "379"));
        var huge = response(1, true); for (int i = 0; i < 19; i++) huge.getAsJsonObject("data").getAsJsonArray("video_data").add(row("x" + i, tag(379, "求生")));
        rejects(() -> AiDramaNativeTopics.choices(huge));
    }
}
