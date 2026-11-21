package com.fongmi.android.tv.maintenance;

import java.io.IOException;
import okhttp3.HttpUrl;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateHttpTest {
    @Test public void acceptsOfficialApiAndObservedAssetHosts() throws Exception {
        for (String host : new String[]{"api.github.com", "github.com", "release-assets.githubusercontent.com",
                "objects.githubusercontent.com"})
            assertEquals(host, UpdateHttp.validate("https://" + host + "/release").host());
    }

    @Test public void rejectsUntrustedGithubTargetsAndCredentials() {
        for (String url : new String[]{"https://github.com.evil.example/release", "https://evil.example/",
                "https://githubusercontent.com/", "https://github.com:8443/release",
                "https://user:password@github.com/release", "http://github.com/release", null, "not a URL"})
            assertThrows(IOException.class, () -> UpdateHttp.validate(url));
    }

    @Test public void refusesEveryNonGithubServer() {
        // Updates come from GitHub only (user 2026-11-20): no mirror, no custom manifest host.
        for (String url : new String[]{"https://updates.example:8443/version.json", "https://203.0.113.5:18443/ysc/latest.json",
                "https://cdn.example/app.apk"})
            assertThrows(IOException.class, () -> UpdateHttp.validate(url));
    }

    @Test public void followsRelativeAndOfficialAssetRedirects() throws Exception {
        HttpUrl initial = UpdateHttp.validate("https://github.com/LuckyWDGS/YingShiCang/releases/latest");
        assertEquals("https://github.com/LuckyWDGS/YingShiCang/releases/download/v1.0.3/app.apk",
                UpdateHttp.redirect(initial, "download/v1.0.3/app.apk").toString());
        HttpUrl asset = UpdateHttp.redirect(initial,
                "https://release-assets.githubusercontent.com/github-production-release-asset/123?token=private");
        assertEquals("release-assets.githubusercontent.com", asset.host());
        assertEquals("private", asset.queryParameter("token"));
    }

    @Test public void rejectsUntrustedDowngradeMissingAndCredentialRedirects() throws Exception {
        HttpUrl initial = UpdateHttp.validate("https://github.com/release");
        for (String location : new String[]{"https://evil.example/app.apk", "http://github.com/app.apk",
                "//user:password@github.com/app.apk", "https://github.com:8443/app.apk", "", " ", null})
            assertThrows(IOException.class, () -> UpdateHttp.redirect(initial, location));
    }

    @Test public void onlyRecognizesGetRedirectStatuses() {
        for (int code : new int[]{301, 302, 303, 307, 308}) assertTrue(UpdateHttp.isRedirect(code));
        for (int code : new int[]{200, 204, 300, 304, 305, 400, 404, 429}) assertFalse(UpdateHttp.isRedirect(code));
    }

    @Test public void distinguishesRateLimitsAndMissingReleasesFromOtherHttpFailures() throws Exception {
        HttpUrl api = UpdateHttp.validate("https://api.github.com/repos/LuckyWDGS/YingShiCang/releases/latest");
        assertTrue(UpdateHttp.apiError(api, 403, "0", null).contains("受限"));
        assertTrue(UpdateHttp.apiError(api, 403, null, "60").contains("受限"));
        assertTrue(UpdateHttp.apiError(api, 429, null, null).contains("受限"));
        assertTrue(UpdateHttp.apiError(api, 403, "10", null).contains("拒绝"));
        assertTrue(UpdateHttp.apiError(api, 404, null, null).contains("未找到"));
        assertNull(UpdateHttp.apiError(api, 200, null, null));
        assertNull(UpdateHttp.apiError(api, 503, null, null));
        assertNull(UpdateHttp.apiError(UpdateHttp.validate("https://github.com/app.apk"), 404, null, null));
    }
}
