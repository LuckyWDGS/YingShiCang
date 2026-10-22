package com.fongmi.android.tv.utils;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.databinding.ViewProgressBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class Notify {

    public static final String DEFAULT = "default";
    public static final int ID = 9527;
    private static final long NOTICE_MS = 3500;
    private final Runnable mHide = this::hideNotice;
    private AlertDialog mDialog;
    private Toast mToast;
    private View mNotice;
    private WindowManager mNoticeManager;
    private Lifecycle mNoticeOwner;
    // The notice window belongs to its activity; close it with the activity.
    private final LifecycleEventObserver mHostObserver = (source, event) -> {
        if (event == Lifecycle.Event.ON_DESTROY) hideNotice();
    };

    private static class Loader {
        static volatile Notify INSTANCE = new Notify();
    }

    private static Notify get() {
        return Loader.INSTANCE;
    }

    public static void createChannel() {
        NotificationManagerCompat notifyMgr = NotificationManagerCompat.from(App.get());
        notifyMgr.createNotificationChannel(new NotificationChannelCompat.Builder(DEFAULT, NotificationManagerCompat.IMPORTANCE_LOW).setName("TV").build());
    }

    public static String getError(int resId, Throwable e) {
        if (TextUtils.isEmpty(e.getMessage())) return ResUtil.getString(resId);
        return ResUtil.getString(resId) + "\n" + e.getMessage();
    }

    public static void show(Notification notification) {
        if (ContextCompat.checkSelfPermission(App.get(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManagerCompat.from(App.get()).notify(ID, notification);
    }

    public static void show(int resId) {
        if (resId != 0) show(ResUtil.getString(resId));
    }

    public static void show(String text) {
        if (!TextUtils.isEmpty(text)) get().makeText(text);
    }

    public static void progress(Context context) {
        dismiss();
        get().create(context);
    }

    public static void dismiss() {
        try {
            if (get().mDialog != null) get().mDialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    private void create(Context context) {
        ViewProgressBinding binding = ViewProgressBinding.inflate(LayoutInflater.from(context));
        mDialog = new MaterialAlertDialogBuilder(context).setView(binding.getRoot()).create();
        mDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        mDialog.show();
    }

    private void makeText(String text) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            App.post(() -> makeText(text));
            return;
        }
        if (mToast != null) mToast.cancel();
        mToast = null;
        hideNotice();
        if (showNotice(App.activity(), text)) return;
        mToast = Toast.makeText(App.get(), text, Toast.LENGTH_LONG);
        mToast.show();
    }

    /**
     * Android 11+ ignores gravity on text toasts and some systems (HyperOS in landscape) place them
     * mid-screen, so a foreground activity shows the message in its own untouchable window at the
     * bottom centre, above any dialog already open.
     */
    private boolean showNotice(Activity activity, String text) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return fallback("no_activity");
        // Only once the activity window is attached; its WindowManager then supplies the activity token.
        if (activity.getWindow().getDecorView().getWindowToken() == null) return fallback("no_window");
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(0xFFF2F2F2);
        view.setTextSize(15);
        view.setGravity(Gravity.CENTER);
        view.setMaxWidth((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.7f));
        view.setPadding(ResUtil.dp2px(20), ResUtil.dp2px(10), ResUtil.dp2px(20), ResUtil.dp2px(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6262626);
        background.setCornerRadius(ResUtil.dp2px(12));
        view.setBackground(background);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = ResUtil.dp2px(40);
        params.windowAnimations = android.R.style.Animation_Toast;
        params.setTitle("Notice");
        try {
            activity.getWindowManager().addView(view, params);
        } catch (RuntimeException e) {
            return fallback(e.getClass().getSimpleName());
        }
        mNotice = view;
        mNoticeManager = activity.getWindowManager();
        view.postDelayed(mHide, NOTICE_MS);
        if (activity instanceof LifecycleOwner owner) {
            mNoticeOwner = owner.getLifecycle();
            mNoticeOwner.addObserver(mHostObserver);
        }
        return true;
    }

    /** Why a message went to a system toast instead (a HyperOS device showed toasts where notices were expected). */
    private static boolean fallback(String reason) {
        DiagnosticLog.write(App.get(), "notice_fallback", reason);
        return false;
    }

    private void hideNotice() {
        View view = mNotice;
        WindowManager manager = mNoticeManager;
        if (mNoticeOwner != null) mNoticeOwner.removeObserver(mHostObserver);
        mNotice = null;
        mNoticeManager = null;
        mNoticeOwner = null;
        if (view == null || manager == null) return;
        view.removeCallbacks(mHide);
        try {
            manager.removeViewImmediate(view);
        } catch (RuntimeException ignored) {
            // The host window is already gone.
        }
    }
}
