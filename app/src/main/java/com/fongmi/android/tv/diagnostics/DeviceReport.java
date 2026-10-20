package com.fongmi.android.tv.diagnostics;

import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Base64;
import android.view.Display;
import android.view.WindowManager;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Device facts that explain playback bugs on real hardware: model, Android version, display and
 * the video decoders' declared limits. It holds no account, network, location or media data.
 */
public final class DeviceReport {
    private static final String[] VIDEO_TYPES = {"video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01", "video/dolby-vision"};
    private static final int MAX_DECODERS_PER_TYPE = 4;
    private static final int MAX_ENCODED_LENGTH = 8000;

    private DeviceReport() {}

    /** Base64url JSON for the X-Device-Info upload header; empty when it cannot be built. */
    public static String encode(Context context) {
        try {
            Map<String, Object> report = collect(context);
            String encoded = encode(report);
            if (encoded.length() > MAX_ENCODED_LENGTH) {
                report.remove("decoders");
                encoded = encode(report);
            }
            return encoded.length() > MAX_ENCODED_LENGTH ? "" : encoded;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String encode(Map<String, Object> report) {
        byte[] json = new Gson().toJson(report).getBytes(StandardCharsets.UTF_8);
        return Base64.encodeToString(json, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static Map<String, Object> collect(Context context) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("app", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") " + BuildConfig.FLAVOR);
        report.put("manufacturer", Build.MANUFACTURER);
        report.put("brand", Build.BRAND);
        report.put("model", Build.MODEL);
        report.put("device", Build.DEVICE);
        report.put("hardware", Build.HARDWARE);
        report.put("android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        report.put("abis", android.text.TextUtils.join(",", Build.SUPPORTED_ABIS));
        report.put("display", display(context));
        report.put("memory", memory(context));
        report.put("player", "engine=" + (PlayerSetting.isMpv() ? "mpv" : "exo")
                + " render=" + (PlayerSetting.getRender() == PlayerSetting.RENDER_TEXTURE ? "texture" : "surface")
                + " highestQuality=" + PlayerSetting.isHighestQualityPreferred());
        report.put("decoders", decoders());
        return report;
    }

    private static String display(Context context) {
        WindowManager manager = context.getSystemService(WindowManager.class);
        if (manager == null) return "unknown";
        Display display = manager.getDefaultDisplay();
        android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);
        StringBuilder text = new StringBuilder(size.x + "x" + size.y + " " + Math.round(display.getRefreshRate()) + "Hz "
                + context.getResources().getDisplayMetrics().densityDpi + "dpi");
        StringBuilder rates = new StringBuilder();
        for (Display.Mode mode : display.getSupportedModes()) {
            if (rates.length() > 0) rates.append(',');
            rates.append(mode.getPhysicalWidth()).append('x').append(mode.getPhysicalHeight()).append('@').append(Math.round(mode.getRefreshRate()));
        }
        return text.append(" modes=").append(rates).toString();
    }

    private static String memory(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return "unknown";
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return (info.totalMem / 1_048_576) + "MB lowRam=" + manager.isLowRamDevice() + " heap=" + manager.getLargeMemoryClass() + "MB";
    }

    private static Map<String, List<String>> decoders() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        for (String type : VIDEO_TYPES) {
            List<String> names = new ArrayList<>();
            for (MediaCodecInfo info : infos) {
                if (info.isEncoder() || names.size() >= MAX_DECODERS_PER_TYPE || !supports(info, type)) continue;
                names.add(describe(info, type));
            }
            if (!names.isEmpty()) result.put(type, names);
        }
        return result;
    }

    private static boolean supports(MediaCodecInfo info, String type) {
        for (String supported : info.getSupportedTypes()) if (supported.equalsIgnoreCase(type)) return true;
        return false;
    }

    private static String describe(MediaCodecInfo info, String type) {
        StringBuilder text = new StringBuilder(info.getName());
        if (Build.VERSION.SDK_INT >= 29) text.append(info.isHardwareAccelerated() ? " hw" : " sw");
        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
            MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
            if (video != null) {
                text.append(" max=").append(video.getSupportedWidths().getUpper()).append('x').append(video.getSupportedHeights().getUpper());
                text.append(" 4k30=").append(video.areSizeAndRateSupported(3840, 2160, 30));
                text.append(" 4k60=").append(video.areSizeAndRateSupported(3840, 2160, 60));
                text.append(" 1080p60=").append(video.areSizeAndRateSupported(1920, 1080, 60));
            }
            if ("video/hevc".equalsIgnoreCase(type)) text.append(" main10=").append(hasProfile(caps, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10));
        } catch (RuntimeException ignored) {
            text.append(" caps=unavailable");
        }
        return text.toString();
    }

    private static boolean hasProfile(MediaCodecInfo.CodecCapabilities caps, int profile) {
        for (MediaCodecInfo.CodecProfileLevel level : caps.profileLevels) if (level.profile == profile) return true;
        return false;
    }
}
