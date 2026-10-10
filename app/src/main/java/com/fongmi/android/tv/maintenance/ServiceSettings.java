package com.fongmi.android.tv.maintenance;

import com.fongmi.android.tv.BuildConfig;
import com.github.catvod.utils.Prefers;

public final class ServiceSettings {
    private ServiceSettings() {}
    public static String manifestUrl() { return Prefers.getString("ysc_update_url", BuildConfig.UPDATE_MANIFEST_URL); }
    public static String logsUrl() { return Prefers.getString("ysc_logs_url", BuildConfig.LOG_UPLOAD_URL); }
    public static void manifestUrl(String value) { Prefers.put("ysc_update_url", value.trim()); }
    public static void logsUrl(String value) { Prefers.put("ysc_logs_url", value.trim()); }
}
