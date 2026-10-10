package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;

/** Test-only equivalent of the directly forwarding guard verified in the real plugin. */
public class BaseSpiderGuard extends Spider {
    private Spider delegate;

    public BaseSpiderGuard(Spider delegate) { this.delegate = delegate; }

    public void setDelegate(Spider delegate) { this.delegate = delegate; }

    @Override public void init(android.content.Context context, String extend) throws Exception {
        delegate.init(context, extend);
    }

    @Override public String searchContent(String key, boolean quick) throws Exception {
        return delegate.searchContent(key, quick);
    }

    @Override public String searchContent(String key, boolean quick, String page) throws Exception {
        return delegate.searchContent(key, quick, page);
    }
    @Override public String categoryContent(String tid, String page, boolean filter, java.util.HashMap<String, String> extend) throws Exception {
        return delegate.categoryContent(tid, page, filter, extend);
    }
}
