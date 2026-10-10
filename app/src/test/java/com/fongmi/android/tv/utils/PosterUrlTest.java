package com.fongmi.android.tv.utils;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class PosterUrlTest {
    @Test public void repairsObservedDuplicateAndMalformedPrefix() {
        assertEquals("https://img.ffzy888.com/upload/a.jpg", PosterUrl.normalize("/空https://img.ffzy888.com/https://img.ffzy888.com/upload/a.jpg"));
        assertEquals("https://image.example/poster.jpg", PosterUrl.normalize(" //image.example/poster.jpg "));
    }
    @Test public void preservesProxyQueriesAndExplicitHeaders() {
        String proxy = "https://proxy.example/?url=https://img.ffzy888.com/a.jpg@Referer=https://origin.example/";
        assertEquals(proxy, PosterUrl.normalize(proxy));
        assertEquals("data:image/png;base64,test", PosterUrl.normalize("data:image/png;base64,test"));
        assertEquals("", PosterUrl.normalize(null));
    }
}
