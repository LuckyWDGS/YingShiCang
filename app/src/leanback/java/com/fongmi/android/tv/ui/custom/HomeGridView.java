package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;

/** Home's cards: {@link CardGridView} navigation, and cards no taller than the visible list. */
public final class HomeGridView extends CardGridView {

    public HomeGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Keep the entire focused card and its footer inside the visible list. */
    public int fitCardHeight(int naturalHeight) {
        // Includes the 12dp row spacing supplied by HomeActivity's decoration.
        int available = getMeasuredHeight() - getPaddingTop() - getPaddingBottom()
                - Math.round(12 * getResources().getDisplayMetrics().density);
        return available > 0 ? Math.min(naturalHeight, available) : naturalHeight;
    }
}
