package com.fongmi.android.tv.maintenance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import static org.junit.Assert.*;

public class GitHubReleaseTest {
    private static final String APP = "com.yingshicang.player";
    private static final String ROOT = "https://github.com/LuckyWDGS/YingShiCang/releases/";
    private static final String ARM64 = "YingShiCang-1.0.3-tv-arm64-v8a.apk";
    private static final String ARM32 = "YingShiCang-1.0.3-tv-armeabi-v7a.apk";
    private static final String HASH64 = "a".repeat(64);
    private static final String HASH32 = "b".repeat(64);

    private static JsonObject publication() {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("versionName", "1.0.3");
        manifest.addProperty("versionCode", 59);
        manifest.addProperty("applicationId", APP);
        manifest.addProperty("flavor", "leanback");
        manifest.addProperty("releaseUrl", ROOT + "tag/v1.0.3");
        JsonArray packages = new JsonArray();
        packages.add(pkg("arm64-v8a", ARM64, 94663608, HASH64));
        packages.add(pkg("armeabi-v7a", ARM32, 81655018, HASH32));
        manifest.add("packages", packages);
        return manifest;
    }

    private static JsonObject pkg(String abi, String file, long size, String sha) {
        JsonObject value = new JsonObject();
        value.addProperty("abi", abi);
        value.addProperty("file", file);
        value.addProperty("bytes", size);
        value.addProperty("sha256", sha);
        return value;
    }

    private static JsonObject release(String manifest) {
        JsonObject value = new JsonObject();
        value.addProperty("url", "https://api.github.com/repos/LuckyWDGS/YingShiCang/releases/123");
        value.addProperty("html_url", ROOT + "tag/v1.0.3");
        value.addProperty("tag_name", "v1.0.3");
        value.addProperty("draft", false);
        value.addProperty("prerelease", false);
        value.addProperty("published_at", "2026-10-18T01:02:03Z");
        value.addProperty("body", "修复播放问题。\n\n支持遥控器操作。");
        JsonArray assets = new JsonArray();
        assets.add(asset("release-manifest.json", manifest.getBytes(StandardCharsets.UTF_8).length, null));
        assets.add(asset(ARM64, 94663608, HASH64));
        assets.add(asset(ARM32, 81655018, HASH32));
        value.add("assets", assets);
        return value;
    }

    private static JsonObject asset(String name, long size, String hash) {
        JsonObject asset = new JsonObject();
        asset.addProperty("name", name);
        asset.addProperty("state", "uploaded");
        asset.addProperty("size", size);
        asset.addProperty("browser_download_url", ROOT + "download/v1.0.3/" + name);
        if (hash != null) asset.addProperty("digest", "sha256:" + hash);
        return asset;
    }

    private static UpdateManifest selectFrom(JsonObject release, String manifest, String... abis) throws IOException {
        return GitHubRelease.parse(release.toString()).select(manifest, APP, "leanback", 30, abis);
    }

    private static UpdateManifest select(JsonObject manifest, String... abis) throws IOException {
        String json = manifest.toString();
        return selectFrom(release(json), json, abis);
    }

    @Test public void acceptsActualLegacyAggregateAndChoosesDeviceAbiOrder() throws Exception {
        JsonObject manifest = publication();
        String json = manifest.toString();
        GitHubRelease release = GitHubRelease.parse(release(json).toString());
        assertEquals(ROOT + "download/v1.0.3/release-manifest.json", release.manifestUrl());
        UpdateManifest arm64 = release.select(json, APP, "leanback", 23, new String[]{"arm64-v8a", "armeabi-v7a"});
        assertEquals(1, arm64.schemaVersion);
        assertEquals(59, arm64.versionCode);
        assertEquals("1.0.3", arm64.versionName);
        assertEquals(APP, arm64.packageName);
        assertEquals("leanback", arm64.flavor);
        assertEquals(23, arm64.minSdk);
        assertEquals(94663608, arm64.size);
        assertEquals(ROOT + "download/v1.0.3/" + ARM64, arm64.url);
        assertArrayEquals(new String[]{"arm64-v8a"}, arm64.abis);
        assertEquals(HASH64, arm64.sha256);
        assertEquals("修复播放问题。\n\n支持遥控器操作。", arm64.notes);
        assertEquals(HASH32, select(manifest, "armeabi-v7a").sha256);
        assertEquals(HASH32, select(manifest, "armeabi-v7a", "arm64-v8a").sha256);
    }

    @Test public void skipsUnsupportedDeviceAbiBeforeSupportedFallback() throws Exception {
        assertEquals(HASH32, select(publication(), "x86_64", "armeabi-v7a").sha256);
        assertThrows(IOException.class, () -> select(publication(), "x86_64", "x86"));
        assertThrows(IOException.class, () -> select(publication(), "unknown"));
        String json = publication().toString();
        assertThrows(IOException.class, () -> GitHubRelease.parse(release(json).toString()).select(json, APP, "leanback", 30, null));
    }

    @Test public void validatesOptionalSchemaAndMinimumSdk() throws Exception {
        JsonObject manifest = publication();
        manifest.addProperty("schemaVersion", 1);
        manifest.addProperty("minSdk", 30);
        assertEquals(30, select(manifest, "arm64-v8a").minSdk);
        String json = manifest.toString();
        GitHubRelease release = GitHubRelease.parse(release(json).toString());
        assertThrows(IOException.class, () -> release.select(json, APP, "leanback", 29, new String[]{"arm64-v8a"}));
        manifest.addProperty("schemaVersion", 2);
        assertThrows(IOException.class, () -> select(manifest, "arm64-v8a"));
        manifest.addProperty("schemaVersion", "1");
        assertThrows(IOException.class, () -> select(manifest, "arm64-v8a"));
        manifest.remove("schemaVersion");
        manifest.addProperty("minSdk", 22);
        assertThrows(IOException.class, () -> select(manifest, "arm64-v8a"));
    }

    @Test public void rejectsVersionAppFlavorAndReleaseConflicts() {
        for (String field : new String[]{"versionName", "applicationId", "flavor", "releaseUrl"}) {
            JsonObject manifest = publication();
            manifest.addProperty(field, "other");
            assertThrows(field, IOException.class, () -> select(manifest, "arm64-v8a"));
        }
        JsonObject manifest = publication();
        manifest.addProperty("versionCode", 0);
        assertThrows(IOException.class, () -> select(manifest, "arm64-v8a"));
        manifest.addProperty("versionCode", 2147483648L);
        assertThrows(IOException.class, () -> select(manifest, "arm64-v8a"));
    }

    @Test public void rejectsDraftPrereleaseUnpublishedAndWrongTag() {
        String json = publication().toString();
        for (String field : new String[]{"draft", "prerelease"}) {
            JsonObject release = release(json);
            release.addProperty(field, true);
            assertThrows(IOException.class, () -> GitHubRelease.parse(release.toString()));
            release.addProperty(field, "false");
            assertThrows(IOException.class, () -> GitHubRelease.parse(release.toString()));
        }
        for (String field : new String[]{"published_at", "tag_name"}) {
            JsonObject release = release(json);
            release.remove(field);
            assertThrows(IOException.class, () -> GitHubRelease.parse(release.toString()));
        }
        JsonObject release = release(json);
        release.addProperty("tag_name", "v1.0.3-beta");
        assertThrows(IOException.class, () -> GitHubRelease.parse(release.toString()));
    }

    @Test public void rejectsWrongRepositoryAndUnsafeAssetUrls() {
        String json = publication().toString();
        for (String field : new String[]{"url", "html_url"}) {
            JsonObject release = release(json);
            release.addProperty(field, release.get(field).getAsString().replace("LuckyWDGS", "someone"));
            assertThrows(IOException.class, () -> GitHubRelease.parse(release.toString()));
        }
        for (String url : new String[]{"http://github.com/LuckyWDGS/YingShiCang/releases/download/v1.0.3/" + ARM64,
                ROOT + "download/v1.0.2/" + ARM64, ROOT + "download/v1.0.3/" + ARM64 + "?redirect=1",
                "https://github.com.evil.example/LuckyWDGS/YingShiCang/releases/download/v1.0.3/" + ARM64}) {
            JsonObject release = release(json);
            release.getAsJsonArray("assets").get(1).getAsJsonObject().addProperty("browser_download_url", url);
            assertThrows(IOException.class, () -> GitHubRelease.parse(release.toString()));
        }
    }

    @Test public void rejectsMissingDuplicateAndIncompleteAssets() {
        String json = publication().toString();
        JsonObject release = release(json);
        release.add("assets", new JsonArray());
        assertThrows(IOException.class, () -> GitHubRelease.parse(release.toString()));
        release.add("assets", release(json).getAsJsonArray("assets"));
        release.getAsJsonArray("assets").remove(0);
        assertThrows(IOException.class, () -> GitHubRelease.parse(release.toString()));
        release.add("assets", release(json).getAsJsonArray("assets"));
        release.getAsJsonArray("assets").add(release.getAsJsonArray("assets").get(0).deepCopy());
        assertThrows(IOException.class, () -> GitHubRelease.parse(release.toString()));
        release.add("assets", release(json).getAsJsonArray("assets"));
        release.getAsJsonArray("assets").get(1).getAsJsonObject().addProperty("state", "new");
        assertThrows(IOException.class, () -> GitHubRelease.parse(release.toString()));
    }

    @Test public void rejectsMissingWrongOrRepeatedPackageNamesAndUnknownAbi() {
        for (String name : new String[]{"wrong.apk", "../" + ARM64, "https://example.org/app.apk", "notes.txt"}) {
            JsonObject manifest = publication();
            manifest.getAsJsonArray("packages").get(0).getAsJsonObject().addProperty("file", name);
            assertThrows(IOException.class, () -> select(manifest, "arm64-v8a"));
        }
        JsonObject unknown = publication();
        unknown.getAsJsonArray("packages").get(0).getAsJsonObject().addProperty("abi", "mips");
        assertThrows(IOException.class, () -> select(unknown, "arm64-v8a"));
        JsonObject duplicates = publication();
        duplicates.getAsJsonArray("packages").add(duplicates.getAsJsonArray("packages").get(0).deepCopy());
        assertThrows(IOException.class, () -> select(duplicates, "arm64-v8a"));
    }

    @Test public void validatesEveryPackageSizeAndDigestIncludingUnselectedAbi() {
        String json = publication().toString();
        for (String field : new String[]{"size", "digest"}) {
            JsonObject release = release(json);
            JsonObject arm32 = release.getAsJsonArray("assets").get(2).getAsJsonObject();
            if (field.equals("size")) arm32.addProperty(field, 100);
            else arm32.addProperty(field, "sha256:" + "c".repeat(64));
            assertThrows(IOException.class, () -> selectFrom(release, json, "arm64-v8a"));
        }
        JsonObject manifest = publication();
        manifest.getAsJsonArray("packages").get(0).getAsJsonObject().addProperty("sha256", "invalid");
        assertThrows(IOException.class, () -> select(manifest, "arm64-v8a"));
    }

    @Test public void usesManifestHashWhenOlderGitHubAssetHasNoDigest() throws Exception {
        String json = publication().toString();
        JsonObject release = release(json);
        release.getAsJsonArray("assets").get(1).getAsJsonObject().remove("digest");
        release.getAsJsonArray("assets").get(2).getAsJsonObject().add("digest", null);
        assertEquals(HASH64, selectFrom(release, json, "arm64-v8a").sha256);
        assertEquals(HASH32, selectFrom(release, json, "armeabi-v7a").sha256);
    }

    @Test public void checksManifestAssetBytesAndOptionalDigest() throws Exception {
        String json = publication().toString();
        JsonObject release = release(json);
        JsonObject manifestAsset = release.getAsJsonArray("assets").get(0).getAsJsonObject();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        manifestAsset.addProperty("digest", "sha256:" + hex);
        assertEquals(HASH64, selectFrom(release, json, "arm64-v8a").sha256);
        manifestAsset.addProperty("digest", "sha256:" + HASH64);
        assertThrows(IOException.class, () -> selectFrom(release, json, "arm64-v8a"));
        manifestAsset.remove("digest");
        assertThrows(IOException.class, () -> selectFrom(release, json + " ", "arm64-v8a"));
    }

    @Test public void doesNotInventNotesFromManifest() throws Exception {
        JsonObject manifest = publication();
        manifest.addProperty("notes", "未经发布的内容");
        String json = manifest.toString();
        JsonObject release = release(json);
        release.remove("body");
        assertEquals("此版本未提供更新说明。", selectFrom(release, json, "arm64-v8a").notes);
        release.addProperty("body", "  ");
        assertEquals("此版本未提供更新说明。", selectFrom(release, json, "arm64-v8a").notes);
    }

    @Test public void rejectsMalformedOversizedAndStringCoercedMetadata() {
        for (String bad : new String[]{"<html>error</html>", "null", "[]", "{}", "{} {}", "{/*comment*/}",
                " ".repeat(GitHubRelease.MAX_RELEASE_BYTES + 1)})
            assertThrows(IOException.class, () -> GitHubRelease.parse(bad));
        String json = publication().toString();
        JsonObject release = release(json);
        release.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("size", GitHubRelease.MAX_MANIFEST_BYTES + 1);
        assertThrows(IOException.class, () -> GitHubRelease.parse(release.toString()));
        JsonObject manifest = publication();
        manifest.addProperty("versionCode", "59");
        assertThrows(IOException.class, () -> select(manifest, "arm64-v8a"));
    }
}
