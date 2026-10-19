package com.fongmi.android.tv.ui.dialog;

import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.fongmi.android.tv.impl.UpdateListener;
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

    private enum Phase { AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }

    public static UpdateDialog create() {
        return new UpdateDialog();
    }

    public UpdateDialog title(String title) {
        this.title = title;
        return this;
    }

    public UpdateDialog desc(String desc) {
        this.desc = desc;
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
        return builder().setView(getBinding().getRoot()).setCancelable(false);
    }

    @Override
    protected void initView() {
        binding.version.setText(title);
        binding.desc.setText(desc);
        render();
    }

    @Override
    protected void initEvent() {
        binding.confirm.setOnClickListener(this::onConfirm);
        binding.cancel.setOnClickListener(this::onCancel);
    }

    public void setProgress(int progress) {
        setProgress(progress, -1, -1);
    }

    public void setProgress(int progress, long downloaded, long total) {
        if (phase != Phase.DOWNLOADING && phase != Phase.VERIFYING) focusCancelPending = true;
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

    @Override
    public void onStart() {
        super.onStart();
        if (listener == null) {
            dismissAllowingStateLoss();
            return;
        }
        setWidth(0.6f);
        binding.descriptionScroll.post(this::limitDescriptionHeight);
        render();
        if (phase == Phase.AVAILABLE) binding.confirm.requestFocus();
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
        boolean busy = phase == Phase.DOWNLOADING || phase == Phase.VERIFYING;
        boolean transferVisible = phase != Phase.AVAILABLE;
        binding.transfer.setVisibility(transferVisible ? View.VISIBLE : View.GONE);
        binding.status.setAccessibilityLiveRegion(phase == Phase.ERROR || phase == Phase.READY
                ? View.ACCESSIBILITY_LIVE_REGION_POLITE : View.ACCESSIBILITY_LIVE_REGION_NONE);
        binding.status.setText(status);
        binding.progressBar.setIndeterminate(phase == Phase.VERIFYING);
        binding.progressBar.setProgress(progress);
        binding.progressText.setText(progressText());
        binding.confirm.setEnabled(!busy);
        binding.confirm.setFocusable(!busy);
        binding.confirm.setAlpha(busy ? 0.55f : 1f);
        binding.confirm.setText(phase == Phase.READY ? "安装" : phase == Phase.ERROR ? "重试下载"
                : phase == Phase.AVAILABLE ? "下载并安装" : getString(R.string.update_confirm));
        binding.cancel.setText(phase == Phase.READY || phase == Phase.ERROR ? "关闭"
                : phase == Phase.DOWNLOADING ? "取消下载" : phase == Phase.VERIFYING ? "取消"
                : "暂不更新");
        if (focusCancelPending) {
            binding.cancel.requestFocus();
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

    private void onConfirm(View view) {
        if (listener != null) listener.onConfirm(view);
    }

    private void onCancel(View view) {
        if (listener != null) listener.onCancel(view);
    }
}
