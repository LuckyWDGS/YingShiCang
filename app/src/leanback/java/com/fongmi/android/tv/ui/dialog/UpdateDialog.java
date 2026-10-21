package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.ComponentDialog;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.fongmi.android.tv.databinding.ItemUpdateNoteBinding;
import com.fongmi.android.tv.databinding.ItemUpdateSectionBinding;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.maintenance.ReleaseNotes;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Locale;

public class UpdateDialog extends BaseAlertDialog {

    private DialogUpdateBinding binding;
    private UpdateListener listener;
    private String current;
    private String latest;
    private String notes;
    private long size = -1;
    private Phase phase = Phase.AVAILABLE;
    private String status;
    private int progress;
    private long downloaded = -1;
    private long total = -1;
    private boolean focusCancelPending;
    private boolean mandatory;
    private String reason;

    private enum Phase { AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR, MANUAL }

    public static UpdateDialog create() {
        return new UpdateDialog();
    }

    public UpdateDialog version(String current, String latest) {
        this.current = current;
        this.latest = latest;
        return this;
    }

    public UpdateDialog notes(String notes) {
        this.notes = notes;
        return this;
    }

    public UpdateDialog packageSize(long bytes) {
        this.size = bytes;
        return this;
    }

    /** A mandatory update cannot be postponed; only a failed or impossible download offers to continue for now. */
    public UpdateDialog mandatory(boolean mandatory, String reason) {
        this.mandatory = mandatory;
        this.reason = reason;
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
        // The layout draws its own rounded panel; Material's 80dp vertical insets would clip it on a 1080p TV.
        return builder().setView(getBinding().getRoot()).setBackground(new ColorDrawable(Color.TRANSPARENT))
                .setBackgroundInsetTop(0).setBackgroundInsetBottom(0).setBackgroundInsetStart(0).setBackgroundInsetEnd(0)
                .setCancelable(false);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        // Back runs the cancel button's action; the dispatcher also covers predictive back (enableOnBackInvokedCallback).
        if (dialog instanceof ComponentDialog component) component.getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onCancel(binding.cancel);
                // A mandatory update leaves the app instead of letting Back skip it.
                if (mandatory) requireActivity().finishAffinity();
            }
        });
        return dialog;
    }

    @Override
    protected void initView() {
        binding.version.setText(String.format(Locale.getDefault(), "当前版本 %1$s  →  新版本 %2$s", current, latest));
        binding.size.setVisibility(size > 0 ? View.VISIBLE : View.GONE);
        binding.size.setText("安装包 " + size(size));
        binding.important.setVisibility(mandatory ? View.VISIBLE : View.GONE);
        binding.importantText.setText("重要更新：" + (reason == null || reason.isEmpty() ? "当前版本已停止支持，请更新后继续使用" : reason));
        renderNotes();
        render();
    }

    private void renderNotes() {
        List<ReleaseNotes.Item> items = ReleaseNotes.items(notes);
        if (items.isEmpty()) items = List.of(new ReleaseNotes.Item("", "此版本未填写更新说明"));
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < items.size(); i++) {
            ReleaseNotes.Item item = items.get(i);
            // Each category is a small heading over its bullets; a divider separates categories.
            boolean section = i == 0 || !items.get(i - 1).tag().equals(item.tag());
            if (section && i > 0) addDivider();
            if (section && !item.tag().isEmpty()) {
                ItemUpdateSectionBinding heading = ItemUpdateSectionBinding.inflate(inflater, binding.notes, false);
                heading.getRoot().setText(item.tag());
                binding.notes.addView(heading.getRoot());
            }
            ItemUpdateNoteBinding row = ItemUpdateNoteBinding.inflate(inflater, binding.notes, false);
            row.text.setText(item.text());
            binding.notes.addView(row.getRoot());
        }
    }

    private void addDivider() {
        View line = new View(requireContext());
        line.setBackgroundColor(0xFF262626);
        androidx.appcompat.widget.LinearLayoutCompat.LayoutParams params = new androidx.appcompat.widget.LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(1));
        params.setMargins(ResUtil.dp2px(16), ResUtil.dp2px(8), ResUtil.dp2px(16), ResUtil.dp2px(2));
        binding.notes.addView(line, params);
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

    /** No package can be downloaded here: announce the version and offer only a close button. */
    public void setManual() {
        phase = Phase.MANUAL;
        focusCancelPending = true;
        render();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (listener == null) {
            dismissAllowingStateLoss();
            return;
        }
        setWidth(0.5f);
        binding.descriptionScroll.post(this::limitDescriptionHeight);
        render();
        if (phase == Phase.AVAILABLE) binding.confirm.requestFocus();
    }

    private void limitDescriptionHeight() {
        if (binding == null || !isAdded()) return;
        float density = getResources().getDisplayMetrics().density;
        int maximum = Math.min((int) (200 * density), (int) (getResources().getDisplayMetrics().heightPixels * 0.34f));
        int content = binding.notes.getMeasuredHeight();
        ViewGroup.LayoutParams params = binding.descriptionScroll.getLayoutParams();
        params.height = Math.min(maximum, content);
        binding.descriptionScroll.setLayoutParams(params);
        // Only a list that actually overflows needs a D-pad stop to scroll it.
        binding.descriptionScroll.setFocusable(content > maximum);
    }

    private void render() {
        if (binding == null) return;
        boolean busy = phase == Phase.DOWNLOADING || phase == Phase.VERIFYING;
        boolean manual = phase == Phase.MANUAL;
        boolean transferVisible = phase != Phase.AVAILABLE && !manual;
        binding.transfer.setVisibility(transferVisible ? View.VISIBLE : View.GONE);
        binding.status.setAccessibilityLiveRegion(phase == Phase.ERROR || phase == Phase.READY
                ? View.ACCESSIBILITY_LIVE_REGION_POLITE : View.ACCESSIBILITY_LIVE_REGION_NONE);
        binding.status.setText(status);
        binding.progressBar.setIndeterminate(phase == Phase.VERIFYING);
        binding.progressBar.setProgress(progress);
        binding.progressText.setText(progressText());
        // Mandatory: no postponing; continuing for now is offered only after a failed or impossible download.
        boolean fallback = phase == Phase.ERROR || manual;
        boolean cancelVisible = !mandatory || fallback;
        binding.confirm.setVisibility(manual ? View.GONE : View.VISIBLE);
        binding.cancel.setVisibility(cancelVisible ? View.VISIBLE : View.GONE);
        setMargin(binding.confirm, false, cancelVisible ? ResUtil.dp2px(6) : 0);
        setMargin(binding.cancel, true, manual ? 0 : ResUtil.dp2px(6));
        binding.confirm.setEnabled(!busy);
        binding.confirm.setFocusable(!busy);
        binding.confirm.setAlpha(busy ? 0.55f : 1f);
        binding.confirmIcon.setVisibility(phase == Phase.AVAILABLE || phase == Phase.ERROR ? View.VISIBLE : View.GONE);
        binding.confirmText.setText(phase == Phase.READY ? "安装" : phase == Phase.ERROR ? "重试下载"
                : phase == Phase.AVAILABLE ? "下载并安装" : getString(R.string.update_confirm));
        binding.cancel.setText(mandatory ? "暂时继续使用" : manual ? "知道了" : phase == Phase.READY || phase == Phase.ERROR ? "关闭"
                : phase == Phase.DOWNLOADING ? "取消下载" : phase == Phase.VERIFYING ? "取消"
                : "暂不更新");
        binding.backHint.setText(mandatory ? "返回键退出应用" : busy ? "返回键取消" : "返回键关闭");
        boolean confirmFirst = !manual && !busy;
        binding.descriptionScroll.setNextFocusDownId(manual ? R.id.cancel : R.id.confirm);
        // A dialog opened in touch mode cannot take focus yet; the first remote key restores this default.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.confirm.setFocusedByDefault(confirmFirst);
            binding.cancel.setFocusedByDefault(!confirmFirst);
        }
        if (focusCancelPending) {
            binding.cancel.requestFocus();
            focusCancelPending = false;
        }
    }

    private static void setMargin(View view, boolean start, int margin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (start) params.setMarginStart(margin);
        else params.setMarginEnd(margin);
        view.setLayoutParams(params);
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
