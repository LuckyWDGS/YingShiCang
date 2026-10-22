package com.fongmi.android.tv;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
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
import com.github.catvod.net.NetTrace;

import java.io.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Response;

/** Publisher Releases and explicit custom manifests share the verified installation path. */
public class Updater implements UpdateListener {
    private static final AtomicReference<Updater> ACTIVE = new AtomicReference<>();
    private final AtomicBoolean downloading = new AtomicBoolean();
    private boolean github, waitingPermission, installerOpened, checkFailed;
    // Read on the check thread; a manual request may adopt a running launch check.
    private volatile boolean manual;
    private volatile String checkingStatus = "正在获取版本信息…";
    private String endpoint;
    private final UpdateNotification shade = new UpdateNotification("");
    private final LifecycleEventObserver lifecycle = (source, event) -> onLifecycle(event);

    private void onLifecycle(Lifecycle.Event event) {
        if (event == Lifecycle.Event.ON_DESTROY) close();
        else if (event == Lifecycle.Event.ON_RESUME) {
            if (waitingPermission) resumePermission();
            else if (installerOpened) {
                installerOpened = false;
                if (dialog != null) dialog.setReady("安装尚未完成，可再次选择安装");
            }
        }
    }
    private WeakReference<FragmentActivity> owner = new WeakReference<>(null);
    private UpdateDialog dialog;
    private UpdateManifest manifest;
    private volatile GitHubRelease release;
    private volatile Call call;
    private volatile boolean canceled;
    private volatile File transfer;
    private File pending;

    public static Updater create() { return new Updater(); }
    public Updater force() { manual = true; return this; }

    public void start(FragmentActivity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        // Every launch checks; the launch prompt itself appears only once per version.
        if (!manual && !Setting.getUpdate()) return;
        if (!ACTIVE.compareAndSet(null, this)) {
            if (!manual) return;
            // A manual request joins a check already running (often the launch check on a slow network).
            Updater running = ACTIVE.get();
            if (running != null && running.adopt(activity)) return;
            if (!ACTIVE.compareAndSet(null, this)) {
                Notify.show("更新正在进行，请先完成或关闭更新窗口");
                return;
            }
        }
        owner = new WeakReference<>(activity);
        activity.getLifecycle().addObserver(lifecycle);
        endpoint = ServiceSettings.manifestUrl();
        github = GitHubRelease.API_URL.equals(endpoint);
        if (manual) showChecking(activity);
        MaintenanceTasks.execute(() -> check(endpoint));
    }

    /** Main thread: a manual request takes over a check that has not shown anything yet. */
    private boolean adopt(FragmentActivity activity) {
        if (canceled || dialog != null || activity.isFinishing() || activity.isDestroyed()) return false;
        FragmentActivity previous = owner.get();
        if (previous != null) previous.getLifecycle().removeObserver(lifecycle);
        owner = new WeakReference<>(activity);
        activity.getLifecycle().addObserver(lifecycle);
        manual = true;
        showChecking(activity);
        return true;
    }

    /** A manual check opens the dialog at once, showing what it is waiting on. */
    private void showChecking(FragmentActivity activity) {
        shade.checking(checkingStatus);
        if (activity.getSupportFragmentManager().isStateSaved()) {
            Notify.show("正在检查更新…");
            return;
        }
        dialog = UpdateDialog.create().checking(BuildConfig.VERSION_NAME).listener(this).show(activity);
        dialog.setChecking(checkingStatus);
    }

    /** Check thread: the step (or retry) a manual check is waiting on, in the dialog and the notification. */
    private void checking(String text) {
        checkingStatus = text;
        App.post(() -> {
            if (!manual || host() == null || pending != null || downloading.get()) return;
            if (dialog != null) dialog.setChecking(text);
            shade.checking(text);
        });
    }

    private void track(Call current) {
        call = current;
        if (canceled) current.cancel();
    }

    /** Small metadata fetches retry twice after a dropped connection, reset or timeout. */
    private String fetch(String url, int cap) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try (Response response = UpdateHttp.execute(url, github, this::track, attempt > 0)) {
                return MaintenanceHttp.text(response, cap);
            } catch (IOException error) {
                if (canceled || attempt >= 2 || !UpdateHttp.transientFailure(error)) throw error;
                DiagnosticLog.write(App.get(), "update_fetch_retry", NetTrace.reason(error) + " attempt=" + (attempt + 1));
                checking("网络较慢，正在第 " + (attempt + 1) + " 次重试…");
                try {
                    Thread.sleep(1000L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("已取消");
                }
            }
        }
    }

    private void check(String endpoint) {
        try {
            // A process killed by the installer cannot remove its previous verified cache file.
            File[] expired = App.get().getCacheDir().listFiles((dir, name) -> name.startsWith("ysc-update-") && name.endsWith(".apk"));
            if (expired != null) for (File item : expired)
                if (System.currentTimeMillis() - item.lastModified() > 86_400_000L) item.delete();
            if (github) {
                checking("正在获取版本信息…");
                GitHubRelease release = GitHubRelease.parse(fetch(endpoint, GitHubRelease.MAX_RELEASE_BYTES));
                if (!UpdateNotice.isNewer(release.versionName())) {
                    UpdateNotice.clear();
                    result("当前已是最新版本（本机 " + BuildConfig.VERSION_NAME + "）", false);
                    return;
                }
                UpdateNotice.found(release.versionName());
                this.release = release;
                checking("发现新版本 " + release.versionName() + "，正在获取安装包信息…");
                try {
                    manifest = release.select(fetch(release.manifestUrl(), GitHubRelease.MAX_MANIFEST_BYTES),
                            BuildConfig.APPLICATION_ID, BuildConfig.FLAVOR, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS);
                } catch (Exception error) {
                    // api.github.com is often reachable where github.com downloads are not (mainland China).
                    DiagnosticLog.write(App.get(), "update_manifest_failed", NetTrace.reason(error));
                    if (!canceled) offerManual(release.versionName(), release.notes());
                    return;
                }
            } else {
                checking("正在获取更新信息…");
                manifest = UpdateManifest.parse(fetch(endpoint, 65_536));
                manifest.compatible(BuildConfig.APPLICATION_ID, BuildConfig.FLAVOR, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS);
            }
            MaintenanceHttp.validate(manifest.url);
            if (canceled) return;
            if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                UpdateNotice.clear();
                result("当前已是最新版本（本机 " + BuildConfig.VERSION_NAME + "）", false);
                return;
            }
            UpdateNotice.found(manifest.versionName);
            boolean mandatory = UpdateNotice.mandatory(manifest.notes, manifest.versionName);
            App.post(() -> {
                FragmentActivity target = host();
                if (target == null) return;
                shade.cancel();
                if (dialog != null) {
                    // The manual check's dialog is already open: fill in what it found.
                    dialog.available(manifest.versionName, manifest.notes, manifest.size, mandatory, ReleaseNotes.importantReason(manifest.notes));
                    return;
                }
                if (target.getSupportFragmentManager().isStateSaved() || !prompt(manifest.versionName, mandatory)) { close(); return; }
                dialog = UpdateDialog.create().version(BuildConfig.VERSION_NAME, manifest.versionName)
                        .notes(manifest.notes).packageSize(manifest.size).mandatory(mandatory, ReleaseNotes.importantReason(manifest.notes))
                        .listener(this).show(target);
            });
        } catch (Exception error) {
            DiagnosticLog.write(App.get(), "update_check_failed", NetTrace.reason(error));
            if (!canceled) result("检查更新失败：" + reason(error), true);
        }
    }

    /** A newer release exists but its package cannot be fetched here; announce it without a download. */
    private void offerManual(String version, String notes) {
        boolean mandatory = UpdateNotice.mandatory(notes, version);
        App.post(() -> {
            FragmentActivity target = host();
            if (target == null) return;
            shade.cancel();
            if (dialog != null) {
                dialog.available(version, notes, -1, mandatory, ReleaseNotes.importantReason(notes));
                dialog.setManual();
                return;
            }
            if (target.getSupportFragmentManager().isStateSaved() || !prompt(version, mandatory)) { close(); return; }
            dialog = UpdateDialog.create().version(BuildConfig.VERSION_NAME, version)
                    .notes(notes).mandatory(mandatory, ReleaseNotes.importantReason(notes)).listener(this).show(target);
            // Manual-install guidance is intentionally left out for now.
            dialog.setManual();
        });
    }

    /** Optional releases prompt once per version at launch; mandatory ones on every launch until installed. */
    private boolean prompt(String version, boolean mandatory) {
        return manual || mandatory || UpdateNotice.firstPrompt(version);
    }

    private FragmentActivity host() {
        FragmentActivity target = owner.get();
        return canceled || target == null || target.isFinishing() || target.isDestroyed() ? null : target;
    }

    /** The check ended without a release to offer; a manual check says so in its dialog. */
    private void result(String text, boolean failed) {
        App.post(() -> {
            shade.cancel();
            if (manual && host() != null && dialog != null) {
                checkFailed = failed;
                if (failed) dialog.setCheckFailed(text);
                else dialog.setCheckResult(text);
                return;
            }
            if (manual && host() != null) Notify.show(text);
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
        if (target == null) return;
        if (checkFailed) {
            // 重试 after a failed check runs the whole check again in the same dialog.
            checkFailed = false;
            showCheckingAgain();
            return;
        }
        if (manifest == null) { retryManifest(); return; }
        if (pending != null) { install(target); return; }
        if (!downloading.compareAndSet(false, true)) return;
        dialog.setProgress(0, 0, manifest.size);
        MaintenanceTasks.execute(this::download);
    }

    private void showCheckingAgain() {
        checkingStatus = "正在获取版本信息…";
        dialog.setChecking(checkingStatus);
        shade.checking(checkingStatus);
        MaintenanceTasks.execute(() -> check(endpoint));
    }

    /** 重试下载 after the package list could not be fetched: fetch it again, then download right away. */
    private void retryManifest() {
        GitHubRelease current = release;
        if (current == null || dialog == null || !downloading.compareAndSet(false, true)) return;
        dialog.setChecking();
        MaintenanceTasks.execute(() -> {
            boolean started = false;
            try {
                UpdateManifest selected = current.select(fetch(current.manifestUrl(), GitHubRelease.MAX_MANIFEST_BYTES),
                        BuildConfig.APPLICATION_ID, BuildConfig.FLAVOR, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS);
                MaintenanceHttp.validate(selected.url);
                if (canceled) return;
                if (selected.versionCode <= BuildConfig.VERSION_CODE) {
                    UpdateNotice.clear();
                    result("当前已是最新版本（本机 " + BuildConfig.VERSION_NAME + "）", false);
                    return;
                }
                manifest = selected;
                App.post(() -> {
                    if (host() == null || dialog == null) return;
                    dialog.setPackageSize(selected.size);
                    dialog.setProgress(0, 0, selected.size);
                });
                started = true;
                download();
            } catch (Exception error) {
                DiagnosticLog.write(App.get(), "update_manifest_failed", NetTrace.reason(error));
                if (!canceled) App.post(() -> {
                    if (host() != null && dialog != null) dialog.setManual("仍无法连接下载服务器：" + reason(error));
                });
            } finally {
                if (!started) downloading.set(false);
            }
        });
    }

    private void download() {
        File file = null;
        shade.version(manifest.versionName);
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
                            App.post(() -> {
                                if (host() == null) return;
                                if (dialog != null) dialog.setProgress(next, received, manifest.size);
                                shade.progress(next, received, manifest.size);
                            });
                        }
                    }
                    out.getFD().sync();
                }
            }
            if (canceled) throw new InterruptedIOException("已取消");
            App.post(() -> {
                if (host() == null) return;
                if (dialog != null) dialog.setVerifying();
                shade.verifying();
            });
            ApkVerifier.verify(file, manifest);
            if (canceled) throw new InterruptedIOException("已取消");
            File verified = file;
            App.post(() -> {
                shade.cancel();
                if (host() == null) { verified.delete(); return; }
                pending = verified;
                transfer = null;
                dialog.setReady("下载及校验完成，等待确认安装");
                install(host());
            });
        } catch (Exception error) {
            if (file != null) file.delete();
            App.post(shade::cancel);
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
        shade.cancel();
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
