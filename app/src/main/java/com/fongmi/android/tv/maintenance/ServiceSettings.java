package com.fongmi.android.tv.maintenance;

import com.fongmi.android.tv.BuildConfig;

public final class ServiceSettings {
    private ServiceSettings() {}
    /** Built into the release; an address saved by older versions (ysc_logs_url) is no longer used. */
    public static String logsUrl() { return BuildConfig.LOG_UPLOAD_URL; }
}
