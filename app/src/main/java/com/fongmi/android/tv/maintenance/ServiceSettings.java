package com.fongmi.android.tv.maintenance;

import com.fongmi.android.tv.BuildConfig;
import com.github.catvod.utils.Prefers;

public final class ServiceSettings {
    private ServiceSettings() {}
    public static String customManifestUrl() { return Prefers.getString("ysc_update_url", BuildConfig.UPDATE_MANIFEST_URL).trim(); }
    public static String manifestUrl() { String value = customManifestUrl(); return value.isEmpty() ? GitHubRelease.API_URL : value; }
    /** Built into the release; an address saved by older versions (ysc_logs_url) is no longer used. */
    public static String logsUrl() { return BuildConfig.LOG_UPLOAD_URL; }
    public static void manifestUrl(String value) { Prefers.put("ysc_update_url", value.trim()); }
}
