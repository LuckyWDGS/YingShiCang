package com.fongmi.android.tv.maintenance;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import java.util.Locale;

/**
 * Mirrors the update download in a system notification so its progress stays visible outside the
 * dialog. On Android 16+ it asks to be promoted to a Live Update, which systems may show as a
 * status-bar chip or floating bar; elsewhere it is an ordinary progress notification.
 */
public final class UpdateNotification {

    private static final int ID = 9528;
    private static final String CHANNEL = "update";
    private final String version;
    private int shown = -2;

    public UpdateNotification(String version) {
        this.version = version;
    }

    /** Percent 0-100; only changes are posted. */
    public void progress(int percent, long received, long total) {
        if (percent == shown) return;
        shown = percent;
        String text = total > 0 ? String.format(Locale.getDefault(), "%d%%  ·  %.1f MB / %.1f MB", percent, received / 1_000_000.0, total / 1_000_000.0)
                : String.format(Locale.getDefault(), "%d%%", percent);
        post("正在下载 影视仓 " + version, text, percent, false);
    }

    public void verifying() {
        shown = -1;
        post("正在校验 影视仓 " + version, "下载完成，正在校验安装包…", 100, true);
    }

    public void cancel() {
        shown = -2;
        NotificationManager manager = App.get().getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(ID);
    }

    private void post(String title, String text, int percent, boolean indeterminate) {
        Context context = App.get();
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null)
                manager.createNotificationChannel(new NotificationChannel(CHANNEL, "版本更新", NotificationManager.IMPORTANCE_LOW));
            manager.notify(ID, Build.VERSION.SDK_INT >= 36 ? liveUpdate(context, title, text, percent, indeterminate) : compat(context, title, text, percent, indeterminate));
        } catch (RuntimeException ignored) {
            // A notification is a convenience; the dialog still shows the progress.
        }
    }

    private static Notification compat(Context context, String title, String text, int percent, boolean indeterminate) {
        return new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
                .setProgress(100, percent, indeterminate).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS).setContentIntent(open(context)).build();
    }

    @androidx.annotation.RequiresApi(36)
    private static Notification liveUpdate(Context context, String title, String text, int percent, boolean indeterminate) {
        return new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.ProgressStyle().setProgress(percent).setProgressIndeterminate(indeterminate))
                .setOngoing(true).setOnlyAlertOnce(true).setRequestPromotedOngoing(true).setShortCriticalText(indeterminate ? "校验" : percent + "%")
                .setCategory(Notification.CATEGORY_PROGRESS).setContentIntent(open(context)).build();
    }

    /** Tapping it brings the app (and its update dialog) back to the front. */
    private static PendingIntent open(Context context) {
        PackageManager manager = context.getPackageManager();
        Intent intent = manager.getLaunchIntentForPackage(context.getPackageName());
        if (intent == null) intent = manager.getLeanbackLaunchIntentForPackage(context.getPackageName());
        if (intent == null) return null;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
