package com.fongmi.android.tv.api;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class SiteApiTimeoutTest {
    @Test public void sourceRequestsUseTheConfiguredCallDeadline() {
        assertEquals(1234, SiteApi.sourceClient(1234).callTimeoutMillis());
        assertEquals(1, SiteApi.sourceClient(0).callTimeoutMillis());
        assertEquals(TimeUnit.SECONDS.toMillis(30), SiteApi.sourceClient(TimeUnit.SECONDS.toMillis(30)).callTimeoutMillis());
    }
}
