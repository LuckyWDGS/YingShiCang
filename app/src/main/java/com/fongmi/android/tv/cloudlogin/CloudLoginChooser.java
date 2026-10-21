package com.fongmi.android.tv.cloudlogin;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inspector.WindowInspector;
import android.widget.AdapterView;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers the cloud-drive plugin's "选择任意网盘扫码后即可播放" chooser so its QR code shows at once.
 *
 * <p>The plugin owns QR generation, polling and (encrypted) login storage, so the app only picks an
 * option: a drive that already has login data on this device first, otherwise the first one. It acts
 * once per {@link #REPEAT_WINDOW_MS}; if the user backs out of the QR to change drive, the chooser that
 * reappears is left for them. Only a window titled exactly like the chooser is touched.</p>
 */
public final class CloudLoginChooser {

    static final String TITLE = "选择任意网盘扫码后即可播放";
    static final String ITEM_SUFFIX = "扫码登录";
    static final long REPEAT_WINDOW_MS = 120_000;
    private static final long[] SCAN_DELAYS_MS = {150, 400, 900};

    private static long lastPick = -REPEAT_WINDOW_MS;

    private CloudLoginChooser() {
    }

    /** Called when a playback screen loses window focus, which is what a plugin dialog causes. */
    public static void onFocusLost(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        View decor = activity.getWindow().getDecorView();
        Context context = activity.getApplicationContext();
        for (long delay : SCAN_DELAYS_MS) decor.postDelayed(() -> scan(context), delay);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static void scan(Context context) {
        if (SystemClock.elapsedRealtime() - lastPick < REPEAT_WINDOW_MS) return;
        for (View root : WindowInspector.getGlobalWindowViews()) {
            if (!root.isAttachedToWindow() || !root.isShown() || find(root, TITLE, false).isEmpty()) continue;
            List<View> items = find(root, ITEM_SUFFIX, true);
            if (items.isEmpty()) return;
            List<String> labels = new ArrayList<>();
            for (View item : items) labels.add(((TextView) item).getText().toString());
            int index = choose(labels, hasQuarkLogin(context), hasBaiduLogin(context));
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
