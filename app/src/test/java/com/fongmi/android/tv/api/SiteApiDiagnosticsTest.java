package com.fongmi.android.tv.api;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.SocketTimeoutException;

import org.junit.Test;

public class SiteApiDiagnosticsTest {

    @Test
    public void lineNamesMapToDriveFamiliesOnly() {
        assertEquals("quark", SiteApi.drive("夸克原画"));
        assertEquals("baidu", SiteApi.drive("嘟嘟"));
        assertEquals("uc", SiteApi.drive("UC"));
        assertEquals("other", SiteApi.drive("某个线路"));
        assertEquals("other", SiteApi.drive(null));
    }

    @Test
    public void failureNamesTheRootCauseWithoutItsMessage() {
        IOException wrapped = new IOException("https://secret.example/play?id=1", new SocketTimeoutException("timeout"));
        assertEquals("IOException(other) cause=SocketTimeoutException(timeout)", SiteApi.failure(wrapped));
        assertEquals("IOException", SiteApi.failure(new IOException()));
    }
}
