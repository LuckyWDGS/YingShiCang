package com.fongmi.android.tv.maintenance;

import java.io.IOException;
import okhttp3.HttpUrl;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateHttpTest {
    @Test public void acceptsOfficialApiAndObservedAssetHosts() throws Exception {
        for (String host : new String[]{"api.github.com", "github.com", "release-assets.githubusercontent.com",
                "objects.githubusercontent.com"})
            assertEquals(host, UpdateHttp.validate("https://" + host + "/release", true).host());
    }

    @Test public void rejectsUntrustedGithubTargetsAndCredentials() {
        for (String url : new String[]{"https://github.com.evil.example/release", "https://evil.example/",
                "https://githubusercontent.com/", "https://github.com:8443/release",
                "https://user:password@github.com/release", "http://github.com/release", null, "not a URL"})
            assertThrows(IOException.class, () -> UpdateHttp.validate(url, true));
    }

    @Test public void permitsCustomHttpsWithoutWeakeningTransport() throws Exception {
        assertEquals("updates.example", UpdateHttp.validate("https://updates.example:8443/version.json", false).host());
        for (String url : new String[]{"http://updates.example/version.json", "http://localhost/version.json",
                "https://user:password@updates.example/version.json"})
            assertThrows(IOException.class, () -> UpdateHttp.validate(url, false));
    }

    @Test public void followsRelativeAndOfficialAssetRedirects() throws Exception {
        HttpUrl initial = UpdateHttp.validate("https://github.com/LuckyWDGS/YingShiCang/releases/latest", true);
        assertEquals("https://github.com/LuckyWDGS/YingShiCang/releases/download/v1.0.3/app.apk",
                UpdateHttp.redirect(initial, "download/v1.0.3/app.apk", true).toString());
        HttpUrl asset = UpdateHttp.redirect(initial,
                "https://release-assets.githubusercontent.com/github-production-release-asset/123?token=private", true);
        assertEquals("release-assets.githubusercontent.com", asset.host());
        assertEquals("private", asset.queryParameter("token"));
    }

    @Test public void rejectsUntrustedDowngradeMissingAndCredentialRedirects() throws Exception {
        HttpUrl initial = UpdateHttp.validate("https://github.com/release", true);
        for (String location : new String[]{"https://evil.example/app.apk", "http://github.com/app.apk",
                "//user:password@github.com/app.apk", "https://github.com:8443/app.apk", "", " ", null})
            assertThrows(IOException.class, () -> UpdateHttp.redirect(initial, location, true));
        HttpUrl custom = UpdateHttp.validate("https://updates.example/release.json", false);
        assertEquals("cdn.example", UpdateHttp.redirect(custom, "https://cdn.example/app.apk", false).host());
        assertThrows(IOException.class, () -> UpdateHttp.redirect(custom, "http://cdn.example/app.apk", false));
    }

    @Test public void onlyRecognizesGetRedirectStatuses() {
        for (int code : new int[]{301, 302, 303, 307, 308}) assertTrue(UpdateHttp.isRedirect(code));
        for (int code : new int[]{200, 204, 300, 304, 305, 400, 404, 429}) assertFalse(UpdateHttp.isRedirect(code));
    }

    @Test public void distinguishesRateLimitsAndMissingReleasesFromOtherHttpFailures() throws Exception {
        HttpUrl api = UpdateHttp.validate("https://api.github.com/repos/LuckyWDGS/YingShiCang/releases/latest", true);
        assertTrue(UpdateHttp.apiError(api, 403, "0", null).contains("受限"));
        assertTrue(UpdateHttp.apiError(api, 403, null, "60").contains("受限"));
        assertTrue(UpdateHttp.apiError(api, 429, null, null).contains("受限"));
        assertTrue(UpdateHttp.apiError(api, 403, "10", null).contains("拒绝"));
        assertTrue(UpdateHttp.apiError(api, 404, null, null).contains("未找到"));
        assertNull(UpdateHttp.apiError(api, 200, null, null));
        assertNull(UpdateHttp.apiError(api, 503, null, null));
        assertNull(UpdateHttp.apiError(UpdateHttp.validate("https://updates.example/app.apk", false), 404, null, null));
    }
}
