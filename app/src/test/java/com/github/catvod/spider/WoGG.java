package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;

/** Test-only shape of the audited delegate; it performs no source/network requests. */
public class WoGG extends Spider {
    private String O;
    private static boolean n;

    public WoGG(String address) { O = address; }
    public void getKey() { }
    public void getSite() { }
    public String refTry(String value) { return value; }
    @Override public void init(Context context, String extension) { }
    @Override public String searchContent(String keyword, boolean quick) { return ""; }

    public String address() { return O; }
    public void replace(String address) { O = address; }
    public static boolean retryFlag() { return n; }
    public static void retryFlag(boolean value) { n = value; }

    // Deliberately equal instances prove the registry uses object identity, not plugin equality.
    @Override public boolean equals(Object value) { return value instanceof WoGG; }
    @Override public int hashCode() { return 1; }
}
