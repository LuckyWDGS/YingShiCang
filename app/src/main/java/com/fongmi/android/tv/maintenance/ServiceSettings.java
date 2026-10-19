package com.fongmi.android.tv.maintenance;

import com.fongmi.android.tv.BuildConfig;
import com.github.catvod.utils.Prefers;

public final class ServiceSettings {
    private ServiceSettings() {}
    public static String customManifestUrl() { return Prefers.getString("ysc_update_url", BuildConfig.UPDATE_MANIFEST_URL).trim(); }
    public static String manifestUrl() { String value = customManifestUrl(); return value.isEmpty() ? GitHubRelease.API_URL : value; }
    public static String logsUrl() { return Prefers.getString("ysc_logs_url", BuildConfig.LOG_UPLOAD_URL); }
    public static void manifestUrl(String value) { Prefers.put("ysc_update_url", value.trim()); }
    public static void logsUrl(String value) { Prefers.put("ysc_logs_url", value.trim()); }
}
