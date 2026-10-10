package com.fongmi.android.tv.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.math.BigDecimal;

/** Search errors must not be silently converted into a successful empty catalogue. */
public final class SearchResponseSchema {
    private SearchResponseSchema() {}

    public static JsonObject json(String body) throws IOException {
        if (body == null || body.trim().isEmpty()) throw new IOException("Empty source search response");
        try {
            var value = JsonParser.parseString(body);
            if (!value.isJsonObject()) throw new IOException("Invalid source search schema");
            JsonObject object = value.getAsJsonObject();
            if (!object.has("list") || !object.get("list").isJsonArray())
                throw new IOException("Source search list unavailable");
            var error = object.get("error");
            if (error != null && !error.isJsonNull()
                    && !(error.isJsonPrimitive() && (error.getAsString().isEmpty()
                    || "false".equalsIgnoreCase(error.getAsString()) || "0".equals(error.getAsString()))))
                throw new IOException("Source search returned an error");
            // Only explicit HTTP-style failures are portable across provider envelopes.
            // Custom success conventions such as code 0/1 and unknown values remain valid.
            if (httpError(object.get("code")) || httpError(object.get("status")))
                throw new IOException("Source search returned an error");
            boolean valid = object.getAsJsonArray("list").isEmpty();
            for (var row : object.getAsJsonArray("list")) {
                if (!row.isJsonObject()) throw new IOException("Invalid source search row");
                JsonObject item = row.getAsJsonObject();
                valid |= text(item, "vod_id") && text(item, "vod_name");
            }
            if (!valid) throw new IOException("Source search rows have no usable identity");
            return object;
        } catch (RuntimeException error) {
            // Do not retain provider text in an exception; it may contain private URLs.
            throw new IOException("Invalid source search JSON");
        }
    }

    private static boolean httpError(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return false;
        var primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber() && !primitive.isString()) return false;
        try {
            int code = new BigDecimal(primitive.getAsString().trim()).intValueExact();
            return code >= 400 && code <= 599;
        } catch (NumberFormatException | ArithmeticException ignored) {
            return false;
        }
    }

    private static boolean text(JsonObject item, String key) {
        var value = item.get(key);
        return value != null && value.isJsonPrimitive()
                && (value.getAsJsonPrimitive().isString() || value.getAsJsonPrimitive().isNumber())
                && !value.getAsString().trim().isEmpty();
    }
}
