package com.fongmi.android.tv.maintenance;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class UpdateManifestTest {
    private String valid() { return "{\"schemaVersion\":1,\"packageName\":\"app\",\"flavor\":\"leanback\",\"versionCode\":2,\"versionName\":\"0.2\",\"minSdk\":24,\"size\":100,\"url\":\"https://example.org/app.apk\",\"abis\":[\"arm64-v8a\"],\"sha256\":\"" + "a".repeat(64) + "\"}"; }
    @Test public void rejectsHtmlAndMissingHash() {
        assertThrows(IOException.class, () -> UpdateManifest.parse("<html>Git repository</html>"));
        assertThrows(IOException.class, () -> UpdateManifest.parse("{}"));
    }
    @Test public void rejectsWrongAppFlavorSdkAndAbi() throws Exception {
        UpdateManifest m = UpdateManifest.parse(valid());
        m.compatible("app", "leanback", 30, new String[]{"arm64-v8a"});
        assertThrows(IOException.class, () -> m.compatible("other", "leanback", 30, new String[]{"arm64-v8a"}));
        assertThrows(IOException.class, () -> m.compatible("app", "mobile", 30, new String[]{"arm64-v8a"}));
        assertThrows(IOException.class, () -> m.compatible("app", "leanback", 23, new String[]{"arm64-v8a"}));
        assertThrows(IOException.class, () -> m.compatible("app", "leanback", 30, new String[]{"x86"}));
    }
}
