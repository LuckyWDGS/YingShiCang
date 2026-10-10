package com.fongmi.android.tv;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import androidx.fragment.app.FragmentActivity;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.maintenance.*;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;

/** Separately authenticated publisher updates; never downloads upstream FongMi APKs. */
public class Updater implements UpdateListener {
    private static final AtomicBoolean CHECKING = new AtomicBoolean();
    private static long lastCheck;
    private UpdateDialog dialog;
    private UpdateManifest manifest;
    private volatile Call call;
    private volatile boolean canceled;
    private boolean manual;
    private File pending;
    private WeakReference<FragmentActivity> owner;
    public static Updater create() { return new Updater(); }
    public Updater force() { manual = true; return this; }
    public void start(FragmentActivity activity) {
        String endpoint = ServiceSettings.manifestUrl();
        if (endpoint.isEmpty()) { if (manual) Notify.show("尚未配置更新服务，请在诊断与维护中设置版本清单地址"); return; }
        if (!manual && (!Setting.getUpdate() || android.os.SystemClock.elapsedRealtime() - lastCheck < 21_600_000L && lastCheck > 0)) return;
        if (!CHECKING.compareAndSet(false, true)) { if (manual) Notify.show("正在检查更新"); return; }
        owner = new WeakReference<>(activity);
        MaintenanceTasks.execute(() -> {
            try {
                call = MaintenanceHttp.CLIENT.newCall(new Request.Builder().url(MaintenanceHttp.validate(endpoint)).build());
                try (Response response = call.execute()) { manifest = UpdateManifest.parse(MaintenanceHttp.text(response, 65_536)); }
                manifest.compatible(BuildConfig.APPLICATION_ID, BuildConfig.FLAVOR, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS);
                MaintenanceHttp.validate(manifest.url);
                lastCheck = android.os.SystemClock.elapsedRealtime();
                if (manifest.versionCode <= BuildConfig.VERSION_CODE) { if (manual) message("当前已是最新版本"); return; }
                App.post(() -> {
                    FragmentActivity target = owner.get();
                    if (target == null || target.isFinishing() || target.isDestroyed()) return;
                    dialog = UpdateDialog.create().title("发现新版本 " + manifest.versionName)
                            .desc((manifest.notes == null ? "" : manifest.notes) + "\n" + String.format(java.util.Locale.ROOT, "%.1f MB", manifest.size / 1_000_000.0))
                            .listener(this).show(target);
                });
            } catch (Exception error) {
                DiagnosticLog.write(App.get(), "update_check_failed", error.getClass().getSimpleName());
                if (manual) message("检查更新失败：" + error.getMessage());
            } finally { CHECKING.set(false); }
        });
    }
    private void message(String text) { App.post(() -> Notify.show(text)); }
    @Override public void onConfirm(View button) {
        FragmentActivity target = owner.get();
        if (target == null || canceled) return;
        if (pending != null) { install(target, pending); return; }
        button.setEnabled(false);
        MaintenanceTasks.execute(() -> {
            File file = new File(App.get().getCacheDir(), "ysc-update-" + manifest.versionCode + ".apk");
            try {
                if (UpdateStorage.availableFor(file) < manifest.size * 2 + 20_000_000) throw new IOException("安装空间不足");
                call = MaintenanceHttp.CLIENT.newCall(new Request.Builder().url(MaintenanceHttp.validate(manifest.url)).build());
                long total = 0; int progress = -1;
                try (Response response = call.execute()) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("下载 HTTP " + response.code());
                    try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(file)) {
                        byte[] bytes = new byte[32768]; int n;
                        while ((n = in.read(bytes)) != -1) {
                            if (canceled) throw new InterruptedIOException("已取消");
                            total += n;
                            if (total > manifest.size) throw new IOException("安装包超过清单大小");
                            out.write(bytes, 0, n);
                            int next = (int)(total * 100 / manifest.size);
                            if (next != progress) { progress = next; App.post(() -> { if (!canceled && dialog != null) dialog.setProgress(next); }); }
                        }
                        out.getFD().sync();
                    }
                }
                ApkVerifier.verify(file, manifest);
                if (canceled) throw new InterruptedIOException("已取消");
                pending = file;
                App.post(() -> { button.setEnabled(true); FragmentActivity host = owner.get(); if (host != null && !canceled) install(host, file); });
            } catch (Exception error) {
                file.delete();
                if (!canceled) { message("更新未安装：" + error.getMessage()); App.post(() -> button.setEnabled(true)); }
            }
        });
    }
    private void install(FragmentActivity activity, File file) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                Notify.show("请允许安装来源，返回后再次选择更新");
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + BuildConfig.APPLICATION_ID)));
                return;
            }
            FileUtil.openFile(file);
            if (dialog != null) dialog.dismiss();
        } catch (Exception error) { Notify.show("无法启动安装：" + error.getMessage()); }
    }
    @Override public void onCancel(View button) {
        canceled = true;
        if (call != null) call.cancel();
        if (dialog != null) dialog.dismiss();
        // Later cancellation must not permanently turn off future checks.
    }
}
