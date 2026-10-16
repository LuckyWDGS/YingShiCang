package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import androidx.media3.ui.PlayerSeekView;
import com.fongmi.android.tv.R;

/** TV VOD styling while retaining the shared timeline and scrub implementation. */
public class VodSeekView extends PlayerSeekView {
    public VodSeekView(Context context, AttributeSet attrs) {
        super(context, attrs, 0, R.layout.view_vod_seek, androidx.media3.ui.R.id.exo_position,
                androidx.media3.ui.R.id.exo_progress, androidx.media3.ui.R.id.exo_duration);
    }
}
