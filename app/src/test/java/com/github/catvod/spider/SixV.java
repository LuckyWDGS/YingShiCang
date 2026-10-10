package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;

/** Test-only protocol identity for the audited SixV compatibility gate. */
public class SixV extends Spider {
    @Override public String searchContent(String key, boolean quick) { return "{\"list\":[]}"; }
}
