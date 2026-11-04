package com.fongmi.android.tv.cloudlogin;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Flag;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Which lines of a title are cloud drives the user has scanned in. The source plugin owns the logins
 * and keeps them encrypted, so the app only checks that a drive's stored login exists.
 */
public final class DriveLogins {

    private static final String QUARK = "quark";
    private static final String UC = "uc";
    private static final String BAIDU = "baidu";

    private DriveLogins() {
    }

    /** The cloud-drive family a line (flag) name refers to; anything else is "other". */
    public static String family(String flag) {
        String value = flag == null ? "" : flag.toLowerCase(Locale.ROOT);
        if (value.contains("夸克") || value.contains("夸父") || value.contains("quark")) return QUARK;
        if (value.contains("uc")) return UC;
        if (value.contains("百度") || value.contains("嘟嘟") || value.contains("baidu")) return BAIDU;
        if (value.contains("阿里") || value.contains("ali")) return "ali";
        if (value.contains("115")) return "115";
        if (value.contains("123")) return "123";
        if (value.contains("迅雷") || value.contains("xunlei")) return "xunlei";
        if (value.contains("天翼") || value.contains("189")) return "tianyi";
        if (value.contains("移动") || value.contains("139")) return "yidong";
        return "other";
    }

    private static boolean needsScan(String family) {
        return QUARK.equals(family) || UC.equals(family) || BAIDU.equals(family);
    }

    static boolean hasQuark(Context context) {
        return !prefs(context).getString("quark_ck", "").isEmpty();
    }

    static boolean hasBaidu(Context context) {
        File file = new File(context.getFilesDir(), "mybd_cookie");
        return file.isFile() && file.length() > 0;
    }

    // Not seen with a real UC login yet: named like the plugin's quark_ck, quark_fid and quark_ut.
    private static boolean hasUc(Context context) {
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).startsWith(UC) && entry.getValue() instanceof String value && !value.isEmpty()) return true;
        }
        File[] files = context.getFilesDir().listFiles((dir, name) -> name.startsWith("uc_"));
        if (files != null) for (File file : files) if (file.length() > 0) return true;
        return false;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("spUtils", Context.MODE_PRIVATE);
    }

    private static boolean scanned(String family) {
        Context context = App.get();
        return switch (family) {
            case QUARK -> hasQuark(context);
            case UC -> hasUc(context);
            case BAIDU -> hasBaidu(context);
            default -> false;
        };
    }

    /** Scanned-in drives first, the source's order otherwise, so automatic line changes reach them first. */
    public static void prefer(List<Flag> flags) {
        prefer(flags, DriveLogins::scanned);
    }

    static void prefer(List<Flag> flags, Predicate<String> scanned) {
        flags.sort(Comparator.comparing(flag -> !scanned.test(family(flag.getFlag()))));
    }

    /** The line to open: the saved one, unless it is a drive not scanned in and another drive is. */
    public static Flag entry(List<Flag> flags, Flag saved) {
        return entry(flags, saved, DriveLogins::scanned);
    }

    static Flag entry(List<Flag> flags, Flag saved, Predicate<String> scanned) {
        Flag first = flags.get(0);
        if (saved == null) return first;
        String family = family(saved.getFlag());
        return needsScan(family) && !scanned.test(family) && scanned.test(family(first.getFlag())) ? first : saved;
    }

    /** For the diagnostics log: drive families only, never line or title names. */
    public static String describe(List<Flag> flags) {
        List<String> lines = new ArrayList<>();
        for (Flag flag : flags) {
            String family = family(flag.getFlag());
            lines.add(scanned(family) ? family + "*" : family);
        }
        return String.join(",", lines);
    }
}
