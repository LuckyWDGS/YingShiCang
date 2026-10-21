package com.fongmi.android.tv.utils;

import android.net.TrafficStats;
import android.view.View;
import android.widget.TextView;

import com.fongmi.android.tv.App;

import java.text.DecimalFormat;

public class Traffic {

    private static final DecimalFormat format = new DecimalFormat("#.0");
    private static final int UID = App.get().getApplicationInfo().uid;
    private static final String UNIT_KB = " KB/s";
    private static final String UNIT_MB = " MB/s";
    private static final Meter METER = new Meter();

    public static void setSpeed(TextView view) {
        if (!isSupported()) return;
        view.setVisibility(View.VISIBLE);
        view.setText(METER.read());
    }

    public static boolean isSupported() {
        return TrafficStats.getUidRxBytes(UID) != TrafficStats.UNSUPPORTED;
    }

    public static void reset() {
        METER.reset();
    }

    static String format(long kbPerSecond) {
        return kbPerSecond < 1000 ? kbPerSecond + UNIT_KB : format.format(kbPerSecond / 1024f) + UNIT_MB;
    }

    /** Independent sampler, so a second reader (e.g. the More panel) does not skew the loading indicator. */
    public static class Meter {

        private long lastTotalRxBytes;
        private long lastTimeStamp;

        public String read() {
            long nowTimeStamp = System.currentTimeMillis();
            long nowTotalRxBytes = TrafficStats.getUidRxBytes(UID) / 1024;
            long speed = lastTimeStamp == 0 ? 0 : (nowTotalRxBytes - lastTotalRxBytes) * 1000 / Math.max(nowTimeStamp - lastTimeStamp, 1);
            lastTimeStamp = nowTimeStamp;
            lastTotalRxBytes = nowTotalRxBytes;
            return format(Math.max(speed, 0));
        }

        public void reset() {
            lastTotalRxBytes = 0;
            lastTimeStamp = 0;
        }
    }
}
