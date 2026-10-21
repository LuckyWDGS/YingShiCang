package com.fongmi.android.tv.utils;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class Clock {

    // Ticks on the main thread at each wall-clock second; no Timer thread per start().
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private DateTimeFormatter format;
    private Callback callback;
    private TextView view;
    private String text;
    private boolean running;

    public static Clock create() {
        return new Clock();
    }

    public static Clock create(TextView view) {
        return new Clock().view(view).format("HH:mm:ss");
    }

    public Clock() {
    }

    public Clock view(TextView view) {
        this.view = view;
        this.text = null;
        return this;
    }

    public Clock format(String format) {
        this.format = DateTimeFormatter.ofPattern(format, Locale.getDefault());
        this.text = null;
        return this;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void start() {
        stop();
        running = true;
        tick();
    }

    private void tick() {
        if (!running) return;
        doJob();
        handler.postDelayed(tick, 1000 - System.currentTimeMillis() % 1000);
    }

    private void doJob() {
        try {
            long time = System.currentTimeMillis();
            if (callback != null) callback.onTimeChanged(time);
            if (view == null) return;
            // A minute-level clock changes once a minute; setText on every tick would relayout and redraw for nothing.
            String next = format.format(LocalDateTime.now());
            if (next.equals(text)) return;
            text = next;
            view.setText(next);
        } catch (Exception ignored) {
        }
    }

    public Clock stop() {
        running = false;
        handler.removeCallbacks(tick);
        return this;
    }

    public void release() {
        stop();
        callback = null;
    }

    public interface Callback {

        void onTimeChanged(long time);
    }
}
