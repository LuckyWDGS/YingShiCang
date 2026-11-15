package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.widget.HorizontalScrollView;

/**
 * The player's action row. Its include gives it the id "action", which other views on the same screen
 * also carry; when the screen is rebuilt (font size, density, restore after the process was killed),
 * the state another "action" view saved reached HorizontalScrollView, which casts it and crashed
 * (Lenovo tablet on Android 17, 2026-11-12; reproduced on LDPlayer with a font size change). State
 * that is not its own is left alone; the scroll position needs no restoring anyway.
 */
public final class ActionScrollView extends HorizontalScrollView {

    public ActionScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
        Parcelable state = getId() == NO_ID ? null : container.get(getId());
        if (state == null || state.getClass().getName().startsWith(HorizontalScrollView.class.getName())) {
            super.dispatchRestoreInstanceState(container);
            return;
        }
        container.remove(getId());
        try {
            super.dispatchRestoreInstanceState(container);
        } finally {
            container.put(getId(), state);
        }
    }
}
