package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;

import androidx.media3.ui.PlayerSeekView;

import com.fongmi.android.tv.R;

/** Default seek styling with a continuous track; chapters become optional highlight dots instead of gaps. */
public class ContinuousSeekView extends PlayerSeekView {
    public ContinuousSeekView(Context context, AttributeSet attrs) {
        super(context, attrs, 0, R.layout.view_continuous_seek, androidx.media3.ui.R.id.exo_position,
                androidx.media3.ui.R.id.exo_progress, androidx.media3.ui.R.id.exo_duration);
    }
}
