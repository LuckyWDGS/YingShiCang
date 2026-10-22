package com.fongmi.android.tv.utils;

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

/**
 * The app's one status notification. The cast receiver's foreground service keeps it posted while the
 * app runs (idle text: casting is available); longer waits such as an update check or download replace
 * its content with their progress, then hand it back. On Android 16+ progress asks to be promoted to a
 * Live Update, which systems may show as a status-bar chip or floating bar.
 */
public final class StatusNotification {

    public static final int ID = Notify.ID + 1;
    private static final String PROGRESS = "update";
    private static volatile boolean service;

    private StatusNotification() {
    }

    /** Whether a foreground service currently owns the notification (so it must never be removed). */
    public static void service(boolean running) {
        service = running;
    }

    public static Notification idle(Context context) {
        return new NotificationCompat.Builder(context, Notify.DEFAULT).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name)).setContentText("投屏接收已开启，可从手机或电脑投屏到本机")
                .setOngoing(true).setSilent(true).setContentIntent(open(context)).build();
    }

    /** A task in progress: percent 0-100, or indeterminate while its length is unknown. */
    public static void progress(String title, String text, int percent, boolean indeterminate, String chip) {
        Context context = App.get();
        if (!allowed(context)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(PROGRESS) == null)
                manager.createNotificationChannel(new NotificationChannel(PROGRESS, "后台任务进度", NotificationManager.IMPORTANCE_LOW));
            manager.notify(ID, Build.VERSION.SDK_INT >= 36 ? liveUpdate(context, title, text, percent, indeterminate, chip)
                    : compat(context, title, text, percent, indeterminate));
        } catch (RuntimeException ignored) {
            // A notification is a convenience; the screen still shows the progress.
        }
    }

    /** The task ended: back to the idle text while the service runs, otherwise gone. */
    public static void done() {
        Context context = App.get();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            if (service && allowed(context)) manager.notify(ID, idle(context));
            else manager.cancel(ID);
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean allowed(Context context) {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private static Notification compat(Context context, String title, String text, int percent, boolean indeterminate) {
        return new NotificationCompat.Builder(context, PROGRESS).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
                .setProgress(100, percent, indeterminate).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS).setContentIntent(open(context)).build();
    }

    @androidx.annotation.RequiresApi(36)
    private static Notification liveUpdate(Context context, String title, String text, int percent, boolean indeterminate, String chip) {
        return new Notification.Builder(context, PROGRESS).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.ProgressStyle().setProgress(percent).setProgressIndeterminate(indeterminate))
                .setOngoing(true).setOnlyAlertOnce(true).setRequestPromotedOngoing(true).setShortCriticalText(chip)
                .setCategory(Notification.CATEGORY_PROGRESS).setContentIntent(open(context)).build();
    }

    /** Tapping it brings the app (and any dialog it shows) back to the front. */
    private static PendingIntent open(Context context) {
        PackageManager manager = context.getPackageManager();
        Intent intent = manager.getLaunchIntentForPackage(context.getPackageName());
        if (intent == null) intent = manager.getLeanbackLaunchIntentForPackage(context.getPackageName());
        if (intent == null) return null;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
