package com.fongmi.android.tv.utils;

/** Repairs only malformed forms actually observed in source image fields. */
public final class PosterUrl {
    private PosterUrl() {}
    public static String normalize(String value) {
        if (value == null) return "";
        String url = value.trim();
        if (url.startsWith("/空https://") || url.startsWith("/空http://")) url = url.substring(2);
        if (url.startsWith("//")) url = "https:" + url;
        for (String host : new String[]{"img.ffzy888.com", "img.liangzipic.com"}) {
            String origin = "https://" + host;
            if (url.startsWith(origin + "/" + origin + "/")) url = url.substring(origin.length() + 1);
        }
        return url;
    }
}
