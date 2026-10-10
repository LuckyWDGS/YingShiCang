package com.fongmi.android.tv.maintenance;

import com.google.gson.Gson;
import java.io.IOException;

/** Small versioned publication contract; Codeup Git URL is never a manifest. */
public final class UpdateManifest {
    public int schemaVersion;
    public String packageName, flavor, versionName, url, sha256, notes;
    public int versionCode, minSdk;
    public long size;
    public String[] abis;

    public static UpdateManifest parse(String json) throws IOException {
        try {
            UpdateManifest value = new Gson().fromJson(json, UpdateManifest.class);
            if (value == null || value.schemaVersion != 1 || value.versionCode <= 0 || value.minSdk < 23
                    || value.size <= 0 || value.size > 300_000_000 || value.abis == null || value.abis.length == 0
                    || value.sha256 == null || !value.sha256.matches("[a-fA-F0-9]{64}")
                    || value.url == null || value.packageName == null || value.flavor == null || value.versionName == null)
                throw new IOException("版本清单缺少字段或格式错误");
            return value;
        } catch (RuntimeException error) { throw new IOException("不是有效的版本清单", error); }
    }
    public void compatible(String appId, String deviceFlavor, int sdk, String[] supportedAbis) throws IOException {
        if (!packageName.equals(appId) || !flavor.equals(deviceFlavor)) throw new IOException("更新不属于当前应用或设备版本");
        if (sdk < minSdk) throw new IOException("更新需要更高 Android 版本");
        for (String abi : abis) for (String supported : supportedAbis) if (supported.equals(abi)) return;
        throw new IOException("更新不支持当前处理器架构");
    }
}
