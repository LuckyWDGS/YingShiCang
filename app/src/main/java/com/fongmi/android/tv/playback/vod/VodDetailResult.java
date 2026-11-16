package com.fongmi.android.tv.playback.vod;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Result;

/** cached: the copy kept from the last time the title was opened; the source's own answer follows. */
public record VodDetailResult(String key, String id, Result result, boolean cached) {

    public VodDetailResult(String key, String id, Result result) {
        this(key, id, result, false);
    }

    public boolean matches(String key, String id) {
        return TextUtils.equals(this.key, key) && TextUtils.equals(this.id, id);
    }
}
