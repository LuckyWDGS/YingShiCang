package com.fongmi.android.tv.maintenance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The public, published release and its existing multi-ABI publication manifest. */
public final class GitHubRelease {
    public static final String API_URL = "https://api.github.com/repos/LuckyWDGS/YingShiCang/releases/latest";
    public static final int MAX_RELEASE_BYTES = 1_048_576;
    public static final int MAX_MANIFEST_BYTES = 262_144;
    private static final long MAX_APK_BYTES = 300_000_000;
    private static final String WEB_ROOT = "https://github.com/LuckyWDGS/YingShiCang/releases/";
    private static final String API_ROOT = "https://api.github.com/repos/LuckyWDGS/YingShiCang/releases/";
    private static final String MANIFEST = "release-manifest.json";
    private static final String NO_NOTES = "此版本未提供更新说明。";

    private final String tag;
    private final String versionName;
    private final String notes;
    private final Map<String, Asset> assets;
    private final Asset manifest;

    private GitHubRelease(String tag, String versionName, String notes, Map<String, Asset> assets) {
        this.tag = tag;
        this.versionName = versionName;
        this.notes = notes;
        this.assets = assets;
        this.manifest = assets.get(MANIFEST);
    }

    public static GitHubRelease parse(String releaseJson) throws IOException {
        try {
            JsonObject release = object(releaseJson, MAX_RELEASE_BYTES, "GitHub 版本信息");
            if (bool(release, "draft") || bool(release, "prerelease"))
                throw new IOException("只支持已发布的正式版本");
            String published = string(release, "published_at");
            if (!published.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"))
                throw new IOException("该版本尚未正式发布");
            String tag = string(release, "tag_name");
            if (!tag.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+"))
                throw new IOException("发布标签不是有效的正式版本号");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            String api = string(release, "url");
            if (!api.startsWith(API_ROOT) || !api.substring(API_ROOT.length()).matches("[1-9][0-9]*")
                    || !string(release, "html_url").equals(WEB_ROOT + "tag/" + tag))
                throw new IOException("版本信息不属于当前应用的 GitHub 仓库");

            JsonArray list = array(release, "assets");
            if (list.size() == 0 || list.size() > 100) throw new IOException("正式版本没有可用的安装附件");
            Map<String, Asset> assets = new HashMap<>();
            for (JsonElement element : list) {
                if (!element.isJsonObject()) throw new IOException("版本附件格式错误");
                JsonObject item = element.getAsJsonObject();
                String name = string(item, "name");
                if (!safeFile(name)) throw new IOException("版本附件名称无效");
                String url = string(item, "browser_download_url");
                if (!url.equals(WEB_ROOT + "download/" + tag + "/" + name))
                    throw new IOException("版本附件下载地址不属于当前正式版本");
                long size = number(item, "size", 1, MAX_APK_BYTES);
                if (!string(item, "state").equals("uploaded")) throw new IOException("版本附件尚未上传完成");
                String digest = optionalString(item, "digest");
                if (digest != null && !digest.matches("(?i)sha256:[a-f0-9]{64}"))
                    throw new IOException("版本附件 SHA-256 格式错误");
                Asset asset = new Asset(url, size, digest == null ? null : digest.substring(7));
                if (assets.put(name, asset) != null) throw new IOException("版本包含重名附件，无法安全选择安装包");
            }
            Asset manifest = assets.get(MANIFEST);
            if (manifest == null) throw new IOException("正式版本缺少 release-manifest.json 更新清单");
            if (manifest.size > MAX_MANIFEST_BYTES) throw new IOException("版本清单过大");
            String notes = optionalString(release, "body");
            return new GitHubRelease(tag, version, notes == null || notes.trim().isEmpty() ? NO_NOTES : notes, assets);
        } catch (RuntimeException error) {
            throw new IOException("GitHub 版本信息格式错误", error);
        }
    }

    public String manifestUrl() {
        return manifest.url;
    }

    public UpdateManifest select(String manifestJson, String appId, String flavor, int sdk, String[] supportedAbis) throws IOException {
        try {
            byte[] bytes = boundedBytes(manifestJson, MAX_MANIFEST_BYTES, "版本清单");
            if (bytes.length != manifest.size || manifest.sha256 != null && !manifest.sha256.equalsIgnoreCase(sha256(bytes)))
                throw new IOException("版本清单大小或 SHA-256 与 GitHub 附件不符");
            JsonObject value = object(manifestJson, MAX_MANIFEST_BYTES, "版本清单");
            if (value.has("schemaVersion") && number(value, "schemaVersion", 1, 1) != 1)
                throw new IOException("不支持此版本清单格式");
            if (!string(value, "versionName").equals(versionName))
                throw new IOException("更新清单版本与发布标签不符");
            if (appId == null || flavor == null || !string(value, "applicationId").equals(appId)
                    || !string(value, "flavor").equals(flavor))
                throw new IOException("更新不属于当前应用或设备版本");
            if (value.has("releaseUrl") && !string(value, "releaseUrl").equals(WEB_ROOT + "tag/" + tag))
                throw new IOException("版本清单不属于当前正式版本");
            int code = (int) number(value, "versionCode", 1, Integer.MAX_VALUE);
            int minSdk = value.has("minSdk") ? (int) number(value, "minSdk", 23, Integer.MAX_VALUE) : 23;
            if (sdk < minSdk) throw new IOException("更新需要更高 Android 版本");
            JsonArray packages = array(value, "packages");
            if (packages.size() == 0 || packages.size() > 32) throw new IOException("版本清单没有可用的安装包");
            Map<String, UpdateManifest> available = new HashMap<>();
            Set<String> files = new HashSet<>();
            for (JsonElement element : packages) {
                if (!element.isJsonObject()) throw new IOException("安装包清单格式错误");
                JsonObject item = element.getAsJsonObject();
                String abi = string(item, "abi");
                if (!knownAbi(abi)) throw new IOException("版本清单包含未知处理器架构");
                String file = string(item, "file");
                if (!safeFile(file) || !file.endsWith(".apk")) throw new IOException("安装包文件名无效");
                Asset asset = assets.get(file);
                if (asset == null) throw new IOException("更新清单对应的安装包尚未发布");
                long size = number(item, "bytes", 1, MAX_APK_BYTES);
                String sha = string(item, "sha256");
                if (!sha.matches("[a-fA-F0-9]{64}")) throw new IOException("安装包 SHA-256 格式错误");
                if (size != asset.size || asset.sha256 != null && !asset.sha256.equalsIgnoreCase(sha))
                    throw new IOException("安装包大小或 SHA-256 与 GitHub 附件不符");
                if (!files.add(file) || available.containsKey(abi)) throw new IOException("版本清单包含重复的安装包或处理器架构");
                UpdateManifest update = new UpdateManifest();
                update.schemaVersion = 1;
                update.packageName = appId;
                update.flavor = flavor;
                update.versionCode = code;
                update.versionName = versionName;
                update.minSdk = minSdk;
                update.size = size;
                update.url = asset.url;
                update.sha256 = sha.toLowerCase(Locale.ROOT);
                update.abis = new String[]{abi};
                update.notes = notes;
                available.put(abi, update);
            }
            if (supportedAbis != null) for (String abi : supportedAbis) {
                UpdateManifest update = available.get(abi);
                if (update != null) return update;
            }
            throw new IOException("该正式版本没有适合当前处理器的安装包");
        } catch (RuntimeException error) {
            throw new IOException("版本清单格式错误", error);
        }
    }

    private static JsonObject object(String json, int limit, String label) throws IOException {
        boundedBytes(json, limit, label);
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setStrictness(Strictness.STRICT);
        JsonElement value = JsonParser.parseReader(reader);
        if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException(label + "格式错误");
        return value.getAsJsonObject();
    }

    private static byte[] boundedBytes(String text, int limit, String label) throws IOException {
        if (text == null || text.isEmpty() || text.length() > limit) throw new IOException(label + "为空或过大");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new IOException(label + "过大");
        return bytes;
    }

    private static String string(JsonObject object, String name) throws IOException {
        String value = optionalString(object, name);
        if (value == null || value.isEmpty()) throw new IOException("版本信息缺少有效字段：" + name);
        return value;
    }

    private static String optionalString(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            throw new IOException("版本信息字段格式错误：" + name);
        return element.getAsString();
    }

    private static boolean bool(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean())
            throw new IOException("版本信息缺少有效字段：" + name);
        return element.getAsBoolean();
    }

    private static long number(JsonObject object, String name, long min, long max) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()
                || !element.getAsString().matches("[0-9]+")) throw new IOException("版本信息数字格式错误：" + name);
        long number = Long.parseLong(element.getAsString());
        if (number < min || number > max) throw new IOException("版本信息数字超出范围：" + name);
        return number;
    }

    private static JsonArray array(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) throw new IOException("版本信息缺少附件列表：" + name);
        return value.getAsJsonArray();
    }

    private static boolean safeFile(String name) {
        return name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}") && !name.contains("..");
    }

    private static boolean knownAbi(String abi) {
        return "arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi) || "x86".equals(abi) || "x86_64".equals(abi);
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte value : hash) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("设备不支持 SHA-256 校验", error);
        }
    }

    private static final class Asset {
        final String url;
        final long size;
        final String sha256;

        Asset(String url, long size, String sha256) {
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }
    }
}
