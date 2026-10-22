package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
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
    private boolean wasBusy;
    private boolean mandatory;
    private String reason;

    private long checkingSince;
    private final Runnable tick = this::tick;

    // DONE: a manual check found nothing newer; CHECK_FAILED: it could not find out.
    private enum Phase { AVAILABLE, CHECKING, DOWNLOADING, VERIFYING, READY, ERROR, MANUAL, DONE, CHECK_FAILED }

    public static UpdateDialog create() {
        return new UpdateDialog();
    }

    public UpdateDialog version(String current, String latest) {
        this.current = current;
        this.latest = latest;
        return this;
    }

    /** Opens before anything is known: a manual check shows what it is waiting on instead of a toast. */
    public UpdateDialog checking(String current) {
        this.current = current;
        this.latest = null;
        phase = Phase.CHECKING;
        status = "正在获取版本信息…";
        return this;
    }

    /** The release the check found, filled into the open dialog. */
    public void available(String latest, String notes, long size, boolean mandatory, String reason) {
        this.latest = latest;
        this.notes = notes;
        this.size = size;
        this.mandatory = mandatory;
        this.reason = reason;
        phase = Phase.AVAILABLE;
        status = null;
        if (binding == null) return;
        bindHeader();
        render();
        binding.getRoot().post(this::fitNotes);
        binding.confirm.requestFocus();
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
        bindHeader();
        render();
    }

    /** Header and notes for the known release; only the current version while a check is still running. */
    private void bindHeader() {
        boolean known = latest != null;
        binding.title.setText(known ? "发现新版本" : "检查更新");
        binding.version.setText(known ? String.format(Locale.getDefault(), "当前版本 %1$s  →  新版本 %2$s", current, latest) : "当前版本 " + current);
        setVisible(binding.content, known);
        binding.size.setVisibility(known && size > 0 ? View.VISIBLE : View.GONE);
        binding.size.setText("安装包 " + size(size));
        binding.important.setVisibility(known && mandatory ? View.VISIBLE : View.GONE);
        binding.importantText.setText("重要更新：" + (reason == null || reason.isEmpty() ? "当前版本已停止支持，请更新后继续使用" : reason));
        if (known && binding.notes.getChildCount() == 0) renderNotes();
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
        binding.transferCancel.setOnClickListener(this::onCancel);
    }

    public void setProgress(int progress) {
        setProgress(progress, -1, -1);
    }

    public void setProgress(int progress, long downloaded, long total) {
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
        setChecking("正在连接下载服务器…");
    }

    /** Still checking; the text says which step (or retry) it is waiting on. */
    public void setChecking(String message) {
        phase = Phase.CHECKING;
        status = message;
        render();
    }

    public void setPackageSize(long bytes) {
        size = bytes;
        if (binding == null) return;
        binding.size.setVisibility(size > 0 ? View.VISIBLE : View.GONE);
        binding.size.setText("安装包 " + size(size));
    }

    @Override
    public void onStart() {
        super.onStart();
        if (listener == null) {
            dismissAllowingStateLoss();
            return;
        }
        setWidth(0.5f);
        render();
        binding.getRoot().post(this::fitNotes);
        if (phase == Phase.AVAILABLE) binding.confirm.requestFocus();
    }

    /**
     * The notes take whatever height is left once everything else in the dialog is laid out, so the
     * buttons stay on screen when the download progress appears on a short (phone-height) display.
     */
    private void fitNotes() {
        if (binding == null || !isAdded()) return;
        View root = binding.getRoot();
        int width = root.getWidth();
        if (width == 0) return;
        float density = getResources().getDisplayMetrics().density;
        View decor = requireActivity().getWindow().getDecorView();
        int screen = decor.getHeight() > 0 ? decor.getHeight() : getResources().getDisplayMetrics().heightPixels;
        int notesWidth = binding.descriptionScroll.getWidth() > 0 ? binding.descriptionScroll.getWidth() : width - root.getPaddingLeft() - root.getPaddingRight();
        binding.notes.measure(View.MeasureSpec.makeMeasureSpec(notesWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int content = binding.notes.getMeasuredHeight();
        ViewGroup.LayoutParams params = binding.descriptionScroll.getLayoutParams();
        params.height = 0;
        binding.descriptionScroll.setLayoutParams(params);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int chrome = root.getMeasuredHeight();
        int available = (int) (screen * 0.94f) - chrome;
        int maximum = Math.max((int) (56 * density), Math.min((int) (200 * density), available));
        params.height = Math.min(maximum, content);
        binding.descriptionScroll.setLayoutParams(params);
        // Only a list that actually overflows needs a D-pad stop to scroll it.
        binding.descriptionScroll.setFocusable(content > maximum);
    }

    private void render() {
        if (binding == null) return;
        boolean busy = phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING;
        boolean manual = phase == Phase.MANUAL;
        // Progress takes the buttons' slot while busy (Back cancels), so the window never resizes.
        setVisible(binding.transfer, busy);
        setVisible(binding.buttons, !busy);
        // Not focused by default: a stray remote OK must not cancel a running download.
        setVisible(binding.transferCancel, busy && !mandatory);
        boolean message = !busy && phase != Phase.AVAILABLE && status != null && !status.isEmpty();
        if (setVisible(binding.status, message)) binding.getRoot().post(this::fitNotes);
        binding.status.setText(message ? status : null);
        binding.status.setAccessibilityLiveRegion(phase == Phase.ERROR || phase == Phase.READY
                ? View.ACCESSIBILITY_LIVE_REGION_POLITE : View.ACCESSIBILITY_LIVE_REGION_NONE);
        binding.transferText.setText(busy ? status : null);
        binding.progressBar.setIndeterminate(phase == Phase.VERIFYING || phase == Phase.CHECKING);
        binding.progressBar.setProgress(progress);
        setVisible(binding.progressText, phase == Phase.DOWNLOADING || phase == Phase.CHECKING);
        binding.progressText.setText(progressText());
        if (phase == Phase.CHECKING) startTicking();
        else checkingSince = 0;
        // Mandatory: no postponing; continuing for now is offered only after a failed or impossible download.
        boolean fallback = phase == Phase.ERROR || manual;
        boolean cancelVisible = !mandatory || fallback;
        boolean confirmVisible = phase != Phase.DONE;
        setVisible(binding.cancel, cancelVisible);
        setVisible(binding.confirm, confirmVisible);
        setMargin(binding.confirm, false, cancelVisible ? ResUtil.dp2px(6) : 0);
        setMargin(binding.cancel, true, confirmVisible ? ResUtil.dp2px(6) : 0);
        binding.confirmIcon.setVisibility(phase == Phase.AVAILABLE || phase == Phase.ERROR || manual ? View.VISIBLE : View.GONE);
        binding.confirmText.setText(phase == Phase.READY ? "安装" : phase == Phase.CHECK_FAILED ? "重试" : phase == Phase.ERROR || manual ? "重试下载" : "下载并安装");
        binding.cancel.setText(mandatory ? "暂时继续使用" : manual || phase == Phase.DONE ? "知道了"
                : phase == Phase.READY || phase == Phase.ERROR || phase == Phase.CHECK_FAILED ? "关闭" : "暂不更新");
        binding.backHint.setText(mandatory ? "返回键退出应用" : phase == Phase.DOWNLOADING ? "返回键取消下载" : busy ? "返回键取消" : "返回键关闭");
        binding.descriptionScroll.setNextFocusDownId(busy ? View.NO_ID : confirmVisible ? R.id.confirm : R.id.cancel);
        // A dialog opened in touch mode cannot take focus yet; the first remote key restores this default.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.confirm.setFocusedByDefault(confirmVisible);
            binding.cancel.setFocusedByDefault(!confirmVisible);
        }
        // Leaving a check or download, the buttons come back: put the remote on the main action.
        if (wasBusy && !busy) (confirmVisible ? binding.confirm : binding.cancel).requestFocus();
        wasBusy = busy;
    }

    /** Changes visibility only when needed; returns whether it changed. */
    private static boolean setVisible(View view, boolean visible) {
        int target = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() == target) return false;
        view.setVisibility(target);
        return true;
    }

    private static void setMargin(View view, boolean start, int margin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if ((start ? params.getMarginStart() : params.getMarginEnd()) == margin) return;
        if (start) params.setMarginStart(margin);
        else params.setMarginEnd(margin);
        view.setLayoutParams(params);
    }

    private void startTicking() {
        if (checkingSince != 0 || binding == null) return;
        checkingSince = SystemClock.elapsedRealtime();
        binding.getRoot().removeCallbacks(tick);
        binding.getRoot().postDelayed(tick, 1000);
    }

    /** Counts the wait once a second, so a slow network still visibly moves. */
    private void tick() {
        if (binding == null || phase != Phase.CHECKING || checkingSince == 0) return;
        binding.progressText.setText(progressText());
        binding.getRoot().postDelayed(tick, 1000);
    }

    private String progressText() {
        if (phase == Phase.CHECKING) {
            long seconds = checkingSince == 0 ? 0 : (SystemClock.elapsedRealtime() - checkingSince) / 1000;
            return seconds < 1 ? "" : "已等待 " + seconds + " 秒";
        }
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
