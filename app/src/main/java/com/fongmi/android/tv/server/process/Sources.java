package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.source.SourceManager;
import com.fongmi.android.tv.server.impl.Process;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

public final class Sources implements Process {
    @Override public boolean isRequest(IHTTPSession session, String url) { return url.startsWith("/api/sources"); }
    private Response reply(Response.Status status, String message, JsonObject data) {
        data.addProperty("message", message);
        Response response = NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", data.toString());
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("X-Content-Type-Options", "nosniff");
        return response;
    }
    @Override public Response doResponse(IHTTPSession session, String url, Map<String,String> files) {
        Map<String,String> params = session.getParms();
        JsonObject data = new JsonObject();
        if (!url.equals("/api/sources")) return reply(Response.Status.NOT_FOUND, "接口不存在", data);
        try {
            if (session.getMethod() == NanoHTTPD.Method.POST) {
                Config item = SourceManager.save(params.get("name"), params.get("url"));
                data.addProperty("name", item.getDesc());
                if (item.getUrl().equals(VodConfig.getUrl())) return reply(Response.Status.OK, "已保存，电视正在使用该线路", data);
                if (SourceManager.busy()) return reply(Response.Status.OK, "已保存，电视正在切换其他线路，请稍后在「线路」中选择", data);
                // A newly added line is used right away; a failed switch rolls back and stays listed in 线路.
                App.post(() -> SourceManager.activate(item, new Callback() {}));
                return reply(Response.Status.OK, "已保存，电视正在切换到该线路", data);
            }
            if (session.getMethod() != NanoHTTPD.Method.GET) return reply(Response.Status.METHOD_NOT_ALLOWED, "请求方式不支持", data);
            JsonArray list = new JsonArray();
            for (Config item : Config.getAll(0)) {
                JsonObject row = new JsonObject(); row.addProperty("name", item.getDesc());
                row.addProperty("active", item.getUrl().equals(VodConfig.getUrl())); list.add(row);
            }
            data.add("items", list);
            return reply(Response.Status.OK, "", data);
        } catch (IllegalArgumentException error) { return reply(Response.Status.BAD_REQUEST, error.getMessage(), data); }
        catch (Exception error) { return reply(Response.Status.INTERNAL_ERROR,
                session.getMethod() == NanoHTTPD.Method.POST ? "保存失败，请重试" : "读取线路失败，请重试", data); }
    }
}
