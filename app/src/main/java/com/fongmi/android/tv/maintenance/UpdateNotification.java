package com.fongmi.android.tv.maintenance;

import com.fongmi.android.tv.utils.StatusNotification;

import java.util.Locale;

/** Update check and download progress in the app's status notification, mirroring the dialog. */
public final class UpdateNotification {

    private int shown = -2;
    private String version;

    public UpdateNotification(String version) {
        this.version = version;
    }

    public void version(String version) {
        this.version = version;
    }

    /** Still finding out what is available; status says what it is waiting on. */
    public void checking(String status) {
        shown = -1;
        StatusNotification.progress("正在检查更新", status, 0, true, "检查");
    }

    /** Percent 0-100; only changes are posted. */
    public void progress(int percent, long received, long total) {
        if (percent == shown) return;
        shown = percent;
        String text = total > 0 ? String.format(Locale.getDefault(), "%d%%  ·  %.1f MB / %.1f MB", percent, received / 1_000_000.0, total / 1_000_000.0)
                : String.format(Locale.getDefault(), "%d%%", percent);
        StatusNotification.progress("正在下载 影视仓 " + version, text, percent, false, percent + "%");
    }

    public void verifying() {
        shown = -1;
        StatusNotification.progress("正在校验 影视仓 " + version, "下载完成，正在校验安装包…", 100, true, "校验");
    }

    public void cancel() {
        shown = -2;
        StatusNotification.done();
    }
}
