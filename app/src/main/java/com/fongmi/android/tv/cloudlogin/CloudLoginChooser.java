package com.fongmi.android.tv.cloudlogin;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import com.fongmi.android.tv.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers the cloud-drive plugin's "选择任意网盘扫码后即可播放" chooser and gives it the app's own look.
 *
 * <p>The plugin owns QR generation, polling and (encrypted) login storage, so the app only picks an
 * option: a drive that already has login data on this device first, otherwise the first one. It picks
 * once per {@link #REPEAT_WINDOW_MS}; if the user backs out of the QR to change drive, the chooser that
 * reappears is left for them. While the playback screen is unfocused the windows are checked
 * periodically, because that reappearing chooser does not change the playback screen's focus. Only a
 * window titled exactly like the chooser is touched. Its content is replaced by
 * {@link CloudLoginChooserView}, which forwards every pick and cancel to the plugin's own views.</p>
 */
public final class CloudLoginChooser {

    static final String TITLE = "选择任意网盘扫码后即可播放";
    static final String ITEM_SUFFIX = "扫码登录";
    static final long REPEAT_WINDOW_MS = 120_000;
    private static final long POLL_MS = 400;
    private static final long POLL_LIMIT_MS = 300_000;
    private static final int STYLED = R.id.cloud_login_chooser_styled;
    private static final int POLLING = R.id.cloud_login_chooser_polling;

    private static long lastPick = -REPEAT_WINDOW_MS;

    private CloudLoginChooser() {
    }

    /** Called when a playback screen loses window focus, which is what a plugin dialog causes. */
    public static void onFocusLost(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        View decor = activity.getWindow().getDecorView();
        if (decor.getTag(POLLING) != null) return;
        Context context = activity.getApplicationContext();
        long until = SystemClock.elapsedRealtime() + POLL_LIMIT_MS;
        decor.setTag(POLLING, Boolean.TRUE);
        decor.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed() || activity.hasWindowFocus() || SystemClock.elapsedRealtime() > until) {
                    decor.setTag(POLLING, null);
                    return;
                }
                try {
                    scan(context);
                } catch (RuntimeException ignored) {
                    // Never let a plugin dialog's unexpected structure affect playback.
                }
                decor.postDelayed(this, POLL_MS);
            }
        }, 150);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static void scan(Context context) {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            if (!root.isAttachedToWindow() || !root.isShown()) continue;
            List<View> titles = find(root, TITLE, false);
            if (titles.isEmpty()) continue;
            List<View> items = find(root, ITEM_SUFFIX, true);
            if (items.isEmpty()) return;
            List<String> labels = new ArrayList<>();
            for (View item : items) labels.add(((TextView) item).getText().toString());
            int index = choose(labels, hasQuarkLogin(context), hasBaiduLogin(context));
            if (root.getTag(STYLED) == null) {
                root.setTag(STYLED, Boolean.TRUE);
                replace(root, items, index);
            }
            if (SystemClock.elapsedRealtime() - lastPick < REPEAT_WINDOW_MS) return;
            if (click(items.get(index))) lastPick = SystemClock.elapsedRealtime();
            return;
        }
    }

    /** A drive that already has login data wins; otherwise the first option. */
    static int choose(List<String> labels, boolean quark, boolean baidu) {
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (quark && label.contains("夸")) return i;
            if (baidu && label.contains("嘟嘟")) return i;
        }
        return 0;
    }

    private static boolean hasQuarkLogin(Context context) {
        String value = context.getSharedPreferences("spUtils", Context.MODE_PRIVATE).getString("quark_ck", "");
        return value != null && !value.isEmpty();
    }

    private static boolean hasBaiduLogin(Context context) {
        File file = new File(context.getFilesDir(), "mybd_cookie");
        return file.isFile() && file.length() > 0;
    }

    /**
     * Shows the app's own chooser inside the plugin's dialog window. The plugin's views stay attached
     * (only hidden) so picks and cancel are forwarded to them and the plugin's flow is unchanged.
     */
    private static void replace(View root, List<View> items, int focus) {
        ViewGroup content = root.findViewById(android.R.id.content);
        if (content == null) return;
        List<String> names = new ArrayList<>();
        for (View item : items) {
            String label = ((TextView) item).getText().toString().trim();
            names.add(label.substring(0, label.length() - ITEM_SUFFIX.length()).trim());
        }
        List<View> cancels = find(root, "取消", false);
        View cancel = cancels.isEmpty() ? null : cancels.get(0);
        CloudLoginChooserView view = new CloudLoginChooserView(root.getContext(), names, focus, new CloudLoginChooserView.Actions() {
            @Override
            public void pick(int index) {
                lastPick = SystemClock.elapsedRealtime();
                click(items.get(index));
            }

            @Override
            public void cancel() {
                if (cancel != null) cancel.performClick();
                else root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            }
        });
        for (int i = 0; i < content.getChildCount(); i++) content.getChildAt(i).setVisibility(View.GONE);
        FrameLayout.LayoutParams panel = CloudLoginChooserView.layoutParams(root.getContext());
        content.addView(view, panel);
        root.setBackground(new ColorDrawable(Color.TRANSPARENT));
        root.setPadding(0, 0, 0, 0);
        // Dialog themes force a fixed window width on wide screens; size the window to the panel exactly.
        if (root.getLayoutParams() instanceof WindowManager.LayoutParams params) {
            params.width = panel.width;
            WindowManager manager = (WindowManager) root.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (manager != null) manager.updateViewLayout(root, params);
        }
    }


    /** Visible TextViews whose text equals (or, for options, ends with) the given text, in layout order. */
    private static List<View> find(View view, String text, boolean suffix) {
        List<View> result = new ArrayList<>();
        collect(view, text, suffix, result);
        return result;
    }

    private static void collect(View view, String text, boolean suffix, List<View> result) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView textView) {
            String value = textView.getText() == null ? "" : textView.getText().toString().trim();
            if (suffix ? value.endsWith(text) && value.length() > text.length() : value.equals(text)) result.add(view);
        }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), text, suffix, result);
    }

    /** List rows go through their AdapterView so the dialog's own item listener runs. */
    private static boolean click(View item) {
        View child = item;
        for (ViewParent parent = item.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent instanceof AdapterView<?> list) {
                int position = list.getPositionForView(child);
                if (position == AdapterView.INVALID_POSITION) return false;
                return list.performItemClick(child, position, list.getItemIdAtPosition(position));
            }
            child = (View) parent;
        }
        for (View view = item; view != null; view = view.getParent() instanceof View parent ? parent : null) {
            if (view.isClickable() && view.isEnabled()) return view.performClick();
        }
        // A separate radio button in the same row (custom layouts).
        if (item.getParent() instanceof ViewGroup row) {
            for (int i = 0; i < row.getChildCount(); i++) {
                View sibling = row.getChildAt(i);
                if (sibling != item && sibling.isClickable() && sibling.isEnabled()) return sibling.performClick();
            }
        }
        return false;
    }
}
