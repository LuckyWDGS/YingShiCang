package com.fongmi.android.tv.cloudlogin;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** The app's own face for the plugin's cloud-drive chooser; every action is forwarded to the plugin's views. */
final class CloudLoginChooserView extends LinearLayout {

    interface Actions {
        void pick(int index);

        void cancel();
    }

    private static final int TEXT = 0xFFF2F2F2;
    private static final int TEXT_SECONDARY = 0xFF9A9A9A;
    private static final int TEXT_HINT = 0xFF6E6E6E;
    private static final int CARD = 0xFF1E1E1E;
    private static final int CARD_FOCUSED = 0xFF2A2A2A;
    // Same provider accents as the TV multi-QR cards (cloud_login.xml).
    private static final int[] ACCENTS = {0xFFFFB870, 0xFF65B7FF, 0xFFB79AFF};

    private final float density;
    private final LinearLayout cards;

    CloudLoginChooserView(Context context, List<String> names, int focus, Actions actions) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        boolean narrow = context.getResources().getConfiguration().screenWidthDp < 600;
        int pad = dp(narrow ? 24 : 32);
        setPadding(pad, dp(28), pad, dp(24));
        GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF1D1D1D, 0xFF121212});
        panel.setCornerRadius(dp(24));
        panel.setStroke(Math.max(1, dp(1)), 0x1FFFFFFF);
        setBackground(panel);

        addView(text("选择网盘扫码播放", 24, TEXT, true));
        TextView subtitle = text("任选一个网盘扫码登录，即可自动播放", 15, TEXT_SECONDARY, false);
        subtitle.setSingleLine(false);
        subtitle.setMaxLines(2);
        subtitle.setLineSpacing(dp(3), 1f);
        LayoutParams subtitleParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(8);
        addView(subtitle, subtitleParams);

        cards = new LinearLayout(context);
        cards.setOrientation(HORIZONTAL);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        // Room for the focused card's 4% zoom so its ring is never clipped at the row edge.
        cards.setPadding(dp(8), dp(8), dp(8), dp(8));
        LayoutParams cardsParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardsParams.topMargin = dp(16);
        cardsParams.leftMargin = -dp(8);
        cardsParams.rightMargin = -dp(8);
        addView(cards, cardsParams);
        setClipChildren(false);
        setClipToPadding(false);
        for (int i = 0; i < names.size(); i++) {
            View card = card(names.get(i), ACCENTS[i % ACCENTS.length]);
            int index = i;
            card.setOnClickListener(v -> actions.pick(index));
            LayoutParams params = new LayoutParams(0, dp(148), 1);
            if (i > 0) params.leftMargin = dp(16);
            cards.addView(card, params);
        }

        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(text(isTelevision(context) ? "左右选择网盘，按确定开始扫码" : "点击任一网盘开始扫码", 13, TEXT_HINT, false),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        TextView cancel = text("取消", 16, TEXT, false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(28), 0, dp(28), 0);
        cancel.setMinHeight(dp(44));
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setBackground(states(0x1AFFFFFF, 0x2EFFFFFF, dp(22)));
        cancel.setOnClickListener(v -> actions.cancel());
        footer.addView(cancel, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)));
        LayoutParams footerParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(16);
        addView(footer, footerParams);

        View first = cards.getChildAt(Math.max(0, Math.min(focus, cards.getChildCount() - 1)));
        if (first != null) post(first::requestFocus);
    }

    private static boolean isTelevision(Context context) {
        UiModeManager mode = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        return mode != null && mode.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION
                || context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private View card(String name, int accent) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(states(CARD, CARD_FOCUSED, dp(18)));
        card.setOnFocusChangeListener((v, focused) -> v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(120).start());

        TextView badge = text(name.isEmpty() ? "?" : name.substring(0, 1), 22, accent, true);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor((accent & 0x00FFFFFF) | 0x2E000000);
        circle.setStroke(Math.max(1, dp(1)), (accent & 0x00FFFFFF) | 0x66000000);
        badge.setBackground(circle);
        card.addView(badge, new LayoutParams(dp(52), dp(52)));

        TextView title = text(name, 20, TEXT, true);
        LayoutParams titleParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(12);
        card.addView(title, titleParams);
        TextView caption = text("扫码登录", 13, TEXT_SECONDARY, false);
        LayoutParams captionParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = dp(4);
        card.addView(caption, captionParams);
        return card;
    }

    /** Rest fill, and a brighter fill with a white 2dp ring when focused or pressed. */
    private Drawable states(int rest, int active, int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, rounded(active, radius, TEXT, dp(2)));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(active, radius, 0, 0));
        states.addState(new int[]{}, rounded(rest, radius, 0, 0));
        return states;
    }

    private static GradientDrawable rounded(int color, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, stroke);
        return drawable;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setSingleLine(true);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    /** Panel width: comfortable on a TV, never wider than a phone screen minus its margins. */
    static FrameLayout.LayoutParams layoutParams(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int screen = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(Math.round(720 * density), screen - Math.round(48 * density));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER;
        return params;
    }
}
