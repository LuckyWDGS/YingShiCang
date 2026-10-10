package com.fongmi.android.tv.api.compat;

import com.fongmi.android.tv.api.SearchRequestScope;
import com.fongmi.android.tv.api.SearchResponseSchema;
import com.github.catvod.crawler.Spider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Request;

/** Repairs the observed SixV form-search mismatch without replacing its detail/player plugin. */
public final class SixVSearchCompatibility {
    private static final String DELEGATE = "com.github.catvod.spider.SixV";
    private static final String TRANSPARENT_GUARD = "com.github.catvod.spider.BaseSpiderGuard";
    private static final String USER_AGENT = "Mozilla/5.0";

    private SixVSearchCompatibility() {}

    /** Branding is deliberately not used to select a compatibility protocol. */
    public static boolean supports(Spider spider, String extension) {
        return base(extension) != null && isSixV(spider);
    }

    /** Only a genuine empty first-page result may enter this compatibility path. */
    public static boolean emptyFirstPage(String body, String page) {
        if (!"1".equals(page)) return false;
        if (body == null || body.trim().isEmpty()) return true;
        try {
            return SearchResponseSchema.json(body).getAsJsonArray("list").isEmpty();
        } catch (IOException error) { return false; }
    }

    /** Shares the calling source's deadline and cancellation, including homepage/form requests. */
    public static String search(String extension, String keyword, SearchRequestScope scope) throws IOException {
        HttpUrl base = base(extension);
        if (base == null) throw new IOException("Source form base unavailable");
        scope.checkCancellation();
        String home = scope.execute(new Request.Builder().url(base).header("User-Agent", USER_AGENT).build());
        Form form = form(base, home);
        FormBody.Builder body = new FormBody.Builder();
        form.fields.forEach(body::add);
        body.add("keyboard", keyword);
        String html = scope.execute(new Request.Builder().url(form.action)
                .header("User-Agent", USER_AGENT).header("Referer", base.toString()).post(body.build()).build());
        scope.checkCancellation();
        return results(base, html).toString();
    }

    static HttpUrl base(String extension) {
        if (extension == null) return null;
        HttpUrl url = HttpUrl.parse(extension.trim());
        return url != null && url.username().isEmpty() && url.password().isEmpty()
                && url.query() == null && url.fragment() == null ? url : null;
    }

    static Form form(HttpUrl base, String html) throws IOException {
        Document document = Jsoup.parse(html, base.toString());
        for (Element form : document.select("form")) {
            if (!"post".equalsIgnoreCase(form.attr("method")) || form.select("input[name=keyboard]").isEmpty()) continue;
            HttpUrl action = base.resolve(form.attr("action"));
            // The form endpoint is checked, rather than following arbitrary embedded POST actions.
            if (!sameOrigin(base, action) || !"/e/search/so.php".equals(action.encodedPath())
                    || action.query() != null || action.fragment() != null) continue;
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            for (Element input : form.select("input[name]")) {
                String type = input.attr("type");
                if (!"hidden".equalsIgnoreCase(type) || input.hasAttr("disabled")) continue;
                String name = input.attr("name");
                if (!name.equals("keyboard")) fields.put(name, input.attr("value"));
            }
            if (!"title".equals(fields.get("show")) || !"article".equals(fields.get("tbname"))) continue;
            return new Form(action, fields);
        }
        throw new IOException("Source search form unavailable");
    }

    static JsonObject results(HttpUrl base, String html) throws IOException {
        Document document = Jsoup.parse(html, base.toString());
        Element main = document.selectFirst(".mainleft .masonry");
        if (main == null) throw new IOException("Source form search schema unavailable");
        JsonArray rows = new JsonArray();
        Set<String> ids = new HashSet<>();
        for (Element card : main.select("li.post")) {
            Element anchor = card.selectFirst(".article h2 a[href]");
            if (anchor == null) continue;
            HttpUrl detail = base.resolve(anchor.attr("href"));
            if (!article(base, detail)) continue;
            String title = anchor.text().trim();
            if (title.isEmpty() || !ids.add(detail.encodedPath())) continue;
            JsonObject row = new JsonObject();
            // The original SixV detail/player protocol accepts the relative article path.
            row.addProperty("vod_id", detail.encodedPath());
            row.addProperty("vod_name", title);
            Element image = card.selectFirst(".thumbnail img");
            HttpUrl picture = image == null ? null : base.resolve(image.attr("src"));
            row.addProperty("vod_pic", picture == null ? "" : picture.toString());
            Element category = card.selectFirst(".info_category a");
            row.addProperty("type_name", category == null ? "" : category.text().trim());
            rows.add(row);
        }
        if (!main.select("li.post").isEmpty() && rows.isEmpty()) throw new IOException("Source form search rows unavailable");
        JsonObject result = new JsonObject();
        result.add("list", rows);
        result.addProperty("page", 1);
        // This repair provides the confirmed first-page protocol; optional delegate pagination is preserved.
        result.addProperty("pagecount", 1);
        return result;
    }

    private static boolean article(HttpUrl base, HttpUrl detail) {
        return sameOrigin(base, detail) && detail.query() == null && detail.fragment() == null
                && detail.encodedPath().matches("/(?:[A-Za-z0-9_-]+/)+[0-9]+\\.html");
    }

    private static boolean sameOrigin(HttpUrl base, HttpUrl url) {
        return url != null && base.scheme().equals(url.scheme()) && base.host().equals(url.host())
                && base.port() == url.port() && url.username().isEmpty() && url.password().isEmpty();
    }

    private static boolean isSixV(Spider spider) {
        IdentityHashMap<Spider, Boolean> seen = new IdentityHashMap<>();
        for (int depth = 0; spider != null && depth < 8; depth++) {
            if (seen.put(spider, Boolean.TRUE) != null) return false;
            if (DELEGATE.equals(spider.getClass().getName())) return true;
            try {
                Class<?> declaring = spider.getClass().getMethod("searchContent", String.class, boolean.class).getDeclaringClass();
                // An arbitrary member is not proof that a wrapper actually searches SixV.
                // Only the audited guard's direct first-page forwarding is transparent.
                if (!TRANSPARENT_GUARD.equals(declaring.getName())) return false;
                spider = transparentDelegate(spider);
            } catch (ReflectiveOperationException | RuntimeException error) {
                return false;
            }
        }
        return false;
    }

    private static Spider transparentDelegate(Spider wrapper) throws IllegalAccessException {
        Field delegate = null;
        for (Class<?> type = wrapper.getClass(); type != null && type != Spider.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Spider.class.isAssignableFrom(field.getType())) continue;
                if (delegate != null) return null;
                delegate = field;
            }
        }
        if (delegate == null) return null;
        delegate.setAccessible(true);
        return (Spider) delegate.get(wrapper);
    }

    static final class Form {
        final HttpUrl action;
        final Map<String, String> fields;
        Form(HttpUrl action, Map<String, String> fields) { this.action = action; this.fields = fields; }
    }
}
