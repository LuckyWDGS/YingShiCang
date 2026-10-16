package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.bean.Result;

public record PlaybackResult<T>(T request, Result result, Throwable error) {

    public PlaybackResult(T request, Result result) {
        this(request, result, null);
    }
}
