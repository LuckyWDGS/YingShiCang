package com.fongmi.android.tv.ui.dialog;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.maintenance.ReleaseNotes;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

public class UpdateDialog extends BaseAlertDialog {

    private DialogUpdateBinding binding;
    private UpdateListener listener;
    private String title;
    private String desc;
    private Phase phase = Phase.AVAILABLE;
    private String status;
    private int progress;
    private long downloaded = -1;
    private long total = -1;
    private boolean focusCancelPending;
    private boolean mandatory;

    // DONE: a manual check found nothing newer; CHECK_FAILED: it could not find out.
    private enum Phase { AVAILABLE, CHECKING, DOWNLOADING, VERIFYING, READY, ERROR, MANUAL, DONE, CHECK_FAILED }

    public static UpdateDialog create() {
        return new UpdateDialog();
    }

    public UpdateDialog version(String current, String latest) {
        this.title = "发现新版本 " + latest;
        return this;
    }

    /** Opens before anything is known: a manual check shows what it is waiting on. */
    public UpdateDialog checking(String current) {
        title = "检查更新";
        desc = "当前版本 " + current;
        phase = Phase.CHECKING;
        status = "正在获取版本信息…";
        return this;
    }

    /** The release the check found, filled into the open dialog. */
    public void available(String latest, String notes, long size, boolean mandatory, String reason) {
        version(null, latest).notes(notes);
        if (size > 0) packageSize(size);
        mandatory(mandatory, reason);
        phase = Phase.AVAILABLE;
        status = null;
        if (getDialog() != null) getDialog().setTitle(title);
        if (binding != null) binding.desc.setText(desc);
        render();
    }

    public void setCheckResult(String message) {
        phase = Phase.DONE;
        status = message;
        render();
    }

    public void setCheckFailed(String message) {
        phase = Phase.CHECK_FAILED;
        status = message;
        render();
    }

    public void setChecking(String message) {
        phase = Phase.CHECKING;
        status = message;
        render();
    }

    public UpdateDialog notes(String notes) {
        String plain = ReleaseNotes.plain(notes);
        this.desc = "更新内容\n\n" + (plain.isEmpty() ? "此版本未填写更新说明" : plain);
        return this;
    }

    public UpdateDialog packageSize(long bytes) {
        this.desc += "\n\n安装包 " + String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
        return this;
    }

    /** A mandatory update cannot be postponed; only a failed or impossible download offers to continue for now. */
    public UpdateDialog mandatory(boolean mandatory, String reason) {
        this.mandatory = mandatory;
        if (mandatory) this.desc = "重要更新：" + (reason == null || reason.isEmpty() ? "当前版本已停止支持，请更新后继续使用" : reason) + "\n\n" + desc;
        return this;
    }

    public UpdateDialog listener(UpdateListener listener) {
        this.listener = listener;
        return this;
    }

    public UpdateDialog show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
        return this;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUpdateBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        setCancelable(false);
        return builder().setTitle(title).setView(getBinding().getRoot()).setPositiveButton(R.string.update_confirm, null).setNegativeButton(R.string.dialog_negative, null).setCancelable(false);
    }

    @Override
    protected void initView() {
        binding.desc.setText(desc);
        render();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (listener == null) {
            dismissAllowingStateLoss();
            return;
        }
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
            if (listener != null) listener.onCancel(view);
        });
        if (dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (listener != null) listener.onConfirm(view);
        });
        binding.descriptionScroll.post(this::limitDescriptionHeight);
        render();
    }

    public void setProgress(int progress) {
        setProgress(progress, -1, -1);
    }

    public void setProgress(int progress, long downloaded, long total) {
        if (phase != Phase.CHECKING && phase != Phase.DOWNLOADING && phase != Phase.VERIFYING) focusCancelPending = true;
        phase = Phase.DOWNLOADING;
        this.progress = Math.max(0, Math.min(100, progress));
        this.downloaded = downloaded;
        this.total = total;
        status = "正在下载安装包…";
        render();
    }

    public void setVerifying() {
        phase = Phase.VERIFYING;
        progress = 100;
        status = "下载完成，正在校验安装包…";
        render();
    }

    public void setReady(String message) {
        phase = Phase.READY;
        progress = 100;
        status = message;
        render();
    }

    public void setError(String message) {
        phase = Phase.ERROR;
        status = message;
        render();
    }

    /** The package list could not be fetched: announce the version and offer to try again. */
    public void setManual() {
        setManual(null);
    }

    public void setManual(String message) {
        phase = Phase.MANUAL;
        status = message;
        render();
    }

    /** Retrying the package list after a failed fetch. */
    public void setChecking() {
        if (phase != Phase.CHECKING) focusCancelPending = true;
        phase = Phase.CHECKING;
        status = "正在连接下载服务器…";
        render();
    }

    /** The phone dialog writes the size into its text only when created; kept for the shared Updater API. */
    public void setPackageSize(long bytes) {
    }

    private void limitDescriptionHeight() {
        if (binding == null || !isAdded()) return;
        float density = getResources().getDisplayMetrics().density;
        int maximum = Math.min((int) (180 * density), (int) (getResources().getDisplayMetrics().heightPixels * 0.25f));
        ViewGroup.LayoutParams params = binding.descriptionScroll.getLayoutParams();
        params.height = Math.min(maximum, Math.max((int) (48 * density), binding.desc.getMeasuredHeight()));
        binding.descriptionScroll.setLayoutParams(params);
    }

    private void render() {
        if (binding == null) return;
        boolean manual = phase == Phase.MANUAL;
        binding.transfer.setVisibility(phase == Phase.AVAILABLE || manual && status == null ? View.GONE : View.VISIBLE);
        boolean outcome = manual || phase == Phase.DONE || phase == Phase.CHECK_FAILED;
        binding.progressBar.setVisibility(outcome ? View.GONE : View.VISIBLE);
        binding.progressText.setVisibility(outcome || phase == Phase.CHECKING ? View.GONE : View.VISIBLE);
        binding.status.setAccessibilityLiveRegion(phase == Phase.ERROR || phase == Phase.READY
                ? View.ACCESSIBILITY_LIVE_REGION_POLITE : View.ACCESSIBILITY_LIVE_REGION_NONE);
        binding.status.setText(status);
        binding.progressBar.setIndeterminate(phase == Phase.VERIFYING || phase == Phase.CHECKING);
        binding.progressBar.setProgress(progress);
        binding.progressText.setText(progressText());
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null) return;
        Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (confirm == null || cancel == null) return;
        boolean busy = phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING;
        confirm.setVisibility(phase == Phase.DONE ? View.GONE : View.VISIBLE);
        confirm.setEnabled(!busy);
        confirm.setFocusable(!busy);
        confirm.setText(phase == Phase.READY ? "安装" : phase == Phase.CHECK_FAILED ? "重试" : phase == Phase.ERROR || manual ? "重试下载"
                : phase == Phase.AVAILABLE ? "下载并安装" : getString(R.string.update_confirm));
        cancel.setVisibility(!mandatory || phase == Phase.ERROR || manual ? View.VISIBLE : View.GONE);
        cancel.setText(mandatory ? "暂时继续使用" : manual || phase == Phase.DONE ? "知道了" : phase == Phase.READY || phase == Phase.ERROR || phase == Phase.CHECK_FAILED ? "关闭"
                : phase == Phase.DOWNLOADING ? "取消下载" : phase == Phase.VERIFYING || phase == Phase.CHECKING ? "取消"
                : "暂不更新");
        if (focusCancelPending) {
            cancel.requestFocus();
            focusCancelPending = false;
        }
    }

    private String progressText() {
        if (total > 0 && downloaded >= 0) return String.format(Locale.getDefault(), "%1$d%%  ·  %2$s / %3$s",
                progress, size(Math.min(downloaded, total)), size(total));
        return String.format(Locale.getDefault(), "%1$d%%", progress);
    }

    private static String size(long bytes) {
        if (bytes < 1000) return String.format(Locale.getDefault(), "%d B", bytes);
        if (bytes < 1_000_000) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1000.0);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0);
    }
}
