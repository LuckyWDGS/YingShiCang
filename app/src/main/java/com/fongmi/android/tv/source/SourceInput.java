package com.fongmi.android.tv.source;

import java.net.URI;
import java.net.IDN;

/** Normalizes remote TVBox configuration addresses without changing URL identity. */
public final class SourceInput {
    public static String url(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty() || value.length() > 4096) throw new IllegalArgumentException("请输入有效的配置链接");
        try {
            if (value.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)))
                throw new IllegalArgumentException();
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawAuthority() == null || uri.getRawAuthority().contains("@") || uri.getFragment() != null)
                throw new IllegalArgumentException();
            // java.net.URI does not recognize Unicode DNS names as hosts. Convert only
            // the authority so signed paths, query escaping and existing URL identities survive.
            if (uri.getHost() == null) {
                String authority = uri.getRawAuthority();
                int colon = authority.lastIndexOf(':');
                String host = colon < 0 ? authority : authority.substring(0, colon);
                String port = colon < 0 ? "" : authority.substring(colon);
                String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
                int start = value.indexOf("://") + 3;
                value = value.substring(0, start) + ascii + port + value.substring(start + authority.length());
                uri = new URI(value);
            }
            if (uri.getHost() == null || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawAuthority().endsWith(":")) throw new IllegalArgumentException();
            return value;
        } catch (Exception error) { throw new IllegalArgumentException("配置链接需以 http:// 或 https:// 开头，且不含空格"); }
    }
    public static String name(String input, String url) {
        String value = input == null ? "" : input.trim();
        if (value.length() > 60 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("线路名称限 60 个字符");
        return value.isEmpty() ? host(url) : value;
    }

    public static String host(String url) { return IDN.toUnicode(URI.create(url(url)).getHost()); }
}
