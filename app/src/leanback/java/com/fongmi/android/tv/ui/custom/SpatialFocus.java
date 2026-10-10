package com.fongmi.android.tv.ui.custom;

import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Wrapped chip rows (Flexbox) let RecyclerView jump to the previous/next row on LEFT/RIGHT.
 * Search the whole window geometrically instead so row edges move to the neighbouring column.
 */
public class SpatialFocus implements View.OnKeyListener {

    private static final SpatialFocus INSTANCE = new SpatialFocus();

    public static void attach(View view) {
        view.setOnKeyListener(INSTANCE);
    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent event) {
        int direction = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : 0;
        if (direction == 0 || !(view.getRootView() instanceof ViewGroup)) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        View next = FocusFinder.getInstance().findNextFocus((ViewGroup) view.getRootView(), view, direction);
        if (next != null) next.requestFocus(direction);
        return true;
    }
}
