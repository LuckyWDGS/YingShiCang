package com.fongmi.android.tv;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.maintenance.*;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;

import java.io.*;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Response;

/** Publisher Releases and explicit custom manifests share the verified installation path. */
public class Updater implements UpdateListener {
    private static final AtomicReference<Updater> ACTIVE = new AtomicReference<>();
    private static volatile long lastCheck, lastAttempt;
    private final AtomicBoolean downloading = new AtomicBoolean();
    private boolean manual, github, waitingPermission, installerOpened;
    private final LifecycleEventObserver lifecycle = (source, event) -> onLifecycle(event);

    private void onLifecycle(Lifecycle.Event event) {
        if (event == Lifecycle.Event.ON_DESTROY) close();
        else if (event == Lifecycle.Event.ON_RESUME) {
            if (waitingPermission) resumePermission();
            else if (installerOpened) {
                installerOpened = false;
                if (dialog != null) dialog.setReady("安装尚未完成，可再次选择安装或关闭");
            }
        }
    }
    private WeakReference<FragmentActivity> owner = new WeakReference<>(null);
    private UpdateDialog dialog;
    private UpdateManifest manifest;
    private volatile Call call;
    private volatile boolean canceled;
    private volatile File transfer;
    private File pending;

    public static Updater create() { return new Updater(); }
    public Updater force() { manual = true; return this; }

    public void start(FragmentActivity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        long now = SystemClock.elapsedRealtime();
        if (!manual && (!Setting.getUpdate() || lastCheck > 0 && now - lastCheck < 21_600_000L
                || lastAttempt > 0 && now - lastAttempt < 120_000L)) return;
        if (!ACTIVE.compareAndSet(null, this)) {
            if (manual) Notify.show("更新正在进行，请先完成或关闭更新窗口");
            return;
        }
        owner = new WeakReference<>(activity);
        activity.getLifecycle().addObserver(lifecycle);
        lastAttempt = now;
        String endpoint = ServiceSettings.manifestUrl();
        github = GitHubRelease.API_URL.equals(endpoint);
        if (manual) Notify.show("正在检查更新…");
        MaintenanceTasks.execute(() -> check(endpoint));
    }

    private void track(Call current) {
        call = current;
        if (canceled) current.cancel();
    }

    private String fetch(String url, int cap) throws IOException {
        try (Response response = UpdateHttp.execute(url, github, this::track)) {
            return MaintenanceHttp.text(response, cap);
        }
    }

    private void check(String endpoint) {
        try {
            // A process killed by the installer cannot remove its previous verified cache file.
            File[] expired = App.get().getCacheDir().listFiles((dir, name) -> name.startsWith("ysc-update-") && name.endsWith(".apk"));
            if (expired != null) for (File item : expired)
                if (System.currentTimeMillis() - item.lastModified() > 86_400_000L) item.delete();
            if (github) {
                GitHubRelease release = GitHubRelease.parse(fetch(endpoint, GitHubRelease.MAX_RELEASE_BYTES));
                manifest = release.select(fetch(release.manifestUrl(), GitHubRelease.MAX_MANIFEST_BYTES),
                        BuildConfig.APPLICATION_ID, BuildConfig.FLAVOR, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS);
            } else {
                manifest = UpdateManifest.parse(fetch(endpoint, 65_536));
                manifest.compatible(BuildConfig.APPLICATION_ID, BuildConfig.FLAVOR, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS);
            }
            MaintenanceHttp.validate(manifest.url);
            if (canceled) return;
            lastCheck = SystemClock.elapsedRealtime();
            if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                result(manual ? "当前已是最新版本（本机 " + BuildConfig.VERSION_NAME + "）" : null);
                return;
            }
            App.post(() -> {
                FragmentActivity target = host();
                if (target == null || target.getSupportFragmentManager().isStateSaved()) { close(); return; }
                String notes = manifest.notes == null || manifest.notes.trim().isEmpty()
                        ? "此版本未填写更新说明" : plainNotes(manifest.notes);
                dialog = UpdateDialog.create().title("发现新版本 " + manifest.versionName)
                        .desc("更新内容\n\n" + notes + "\n\n安装包 "
                                + String.format(Locale.ROOT, "%.1f MB", manifest.size / 1_000_000.0))
                        .listener(this).show(target);
            });
        } catch (Exception error) {
            DiagnosticLog.write(App.get(), "update_check_failed", error.getClass().getSimpleName());
            if (!canceled) result(manual ? "检查更新失败：" + reason(error) : null);
        }
    }

    private static String plainNotes(String text) {
        return text.replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\[([^\\]]+)]\\([^\\s)]+\\)", "$1").trim();
    }

    private FragmentActivity host() {
        FragmentActivity target = owner.get();
        return canceled || target == null || target.isFinishing() || target.isDestroyed() ? null : target;
    }

    private void result(String text) {
        App.post(() -> {
            if (host() != null && text != null) Notify.show(text);
            close();
        });
    }

    private static String reason(Exception error) {
        if (error instanceof java.net.UnknownHostException) return "网络连接失败，请稍后重试";
        if (error instanceof java.net.SocketTimeoutException) return "连接超时，请稍后重试";
        if (error instanceof javax.net.ssl.SSLException) return "安全连接失败，请检查网络和系统时间";
        String message = error.getMessage();
        // URLs from redirects may contain short-lived credentials; never display them.
        if (message == null || message.contains("://")) return "服务暂不可用，请稍后重试";
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    @Override public void onConfirm(View button) {
        FragmentActivity target = host();
        if (target == null || manifest == null) return;
        if (pending != null) { install(target); return; }
        if (!downloading.compareAndSet(false, true)) return;
        dialog.setProgress(0, 0, manifest.size);
        MaintenanceTasks.execute(this::download);
    }

    private void download() {
        File file = null;
        try {
            File directory = App.get().getCacheDir();
            if (UpdateStorage.availableFor(new File(directory, "ysc-update.apk")) < manifest.size * 2 + 20_000_000L)
                throw new IOException("安装空间不足，请清理空间后重试");
            file = File.createTempFile("ysc-update-" + manifest.versionCode + "-", ".apk", directory);
            transfer = file;
            long total = 0;
            int progress = -1;
            try (Response response = UpdateHttp.download(manifest.url, github, this::track)) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("下载失败（HTTP " + response.code() + "）");
                long declared = response.body().contentLength();
                if (declared >= 0 && declared != manifest.size) throw new IOException("安装包大小与发布清单不符");
                try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(file)) {
                    byte[] bytes = new byte[32768];
                    int n;
                    while ((n = in.read(bytes)) != -1) {
                        if (canceled) throw new InterruptedIOException("已取消");
                        total += n;
                        if (total > manifest.size) throw new IOException("安装包超过发布大小");
                        out.write(bytes, 0, n);
                        int next = (int) (total * 100 / manifest.size);
                        if (next != progress) {
                            progress = next;
                            long received = total;
                            App.post(() -> { if (host() != null && dialog != null) dialog.setProgress(next, received, manifest.size); });
                        }
                    }
                    out.getFD().sync();
                }
            }
            if (canceled) throw new InterruptedIOException("已取消");
            App.post(() -> { if (host() != null && dialog != null) dialog.setVerifying(); });
            ApkVerifier.verify(file, manifest);
            if (canceled) throw new InterruptedIOException("已取消");
            File verified = file;
            App.post(() -> {
                if (host() == null) { verified.delete(); return; }
                pending = verified;
                transfer = null;
                dialog.setReady("下载及校验完成，等待确认安装");
                install(host());
            });
        } catch (Exception error) {
            if (file != null) file.delete();
            if (!canceled) {
                String message = "更新未安装：" + reason(error);
                App.post(() -> { if (host() != null && dialog != null) dialog.setError(message); });
            }
        } finally {
            transfer = null;
            downloading.set(false);
        }
    }

    private void install(FragmentActivity activity) {
        if (activity == null || pending == null || !pending.isFile()) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                waitingPermission = true;
                dialog.setReady("请允许此应用安装更新；返回后将继续安装");
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + BuildConfig.APPLICATION_ID)));
                return;
            }
            FileUtil.openFile(pending);
            installerOpened = true;
            dialog.setReady("已打开系统安装界面；若取消，可再次选择安装");
        } catch (Exception error) {
            waitingPermission = false;
            dialog.setReady("无法启动安装：" + reason(error) + "；可再次选择安装");
        }
    }

    private void resumePermission() {
        waitingPermission = false;
        FragmentActivity target = host();
        if (target == null) return;
        if (Build.VERSION.SDK_INT < 26 || target.getPackageManager().canRequestPackageInstalls())
            App.post(() -> { if (host() != null) install(host()); });
        else if (dialog != null) dialog.setReady("尚未允许安装来源，选择安装可重新授权");
    }

    @Override public void onCancel(View button) { close(); }

    private void close() {
        if (canceled) return;
        canceled = true;
        waitingPermission = false;
        if (call != null) call.cancel();
        File partial = transfer;
        if (partial != null) partial.delete();
        // The external installer may still be reading a verified APK after the host is destroyed.
        if (pending != null && !installerOpened) pending.delete();
        FragmentActivity target = owner.get();
        if (target != null) target.getLifecycle().removeObserver(lifecycle);
        if (dialog != null) dialog.dismissAllowingStateLoss();
        owner.clear();
        ACTIVE.compareAndSet(this, null);
    }
}
