package com.fongmi.android.tv.source;

import java.net.URI;

/** Normalizes remote TVBox configuration addresses without changing URL identity. */
public final class SourceInput {
    public static String url(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty() || value.length() > 4096) throw new IllegalArgumentException("请输入有效的配置链接");
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            return value;
        } catch (Exception error) { throw new IllegalArgumentException("配置链接需以 http:// 或 https:// 开头，且不含空格"); }
    }
    public static String name(String input, String url) {
        String value = input == null ? "" : input.trim();
        if (value.length() > 60 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("线路名称限 60 个字符");
        return value.isEmpty() ? URI.create(url).getHost() : value;
    }
}
