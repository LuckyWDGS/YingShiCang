package com.fongmi.android.tv.cloudlogin;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.VisibleForTesting;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Flag;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which lines of a title are cloud drives the user has scanned in. The source plugin owns the logins
 * and keeps them encrypted, so the app only checks that a drive's stored login exists.
 */
public final class DriveLogins {

    private static final String QUARK = "quark";
    private static final String UC = "uc";
    private static final String BAIDU = "baidu";
    private static final int FAILING_PLAYS = 3;

    private static final Predicate<String> LOGIN_CHECK = DriveLogins::hasLogin;
    private static Predicate<String> scanned = LOGIN_CHECK;

    /** Only explicit authentication failures are evidence of a possibly expired login. */
    public record Status(String name, boolean scanned, boolean failing, boolean known) {
        public Status(String name, boolean scanned, boolean failing) { this(name, scanned, failing, true); }
        String text() {
            return name + " " + (!known ? "状态未知" : !scanned ? "未扫码" : failing ? "可能已失效" : "已扫码");
        }
    }

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

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("spUtils", Context.MODE_PRIVATE);
    }

    private static boolean hasLogin(String family) {
        Context context = App.get();
        return switch (family) {
            case QUARK -> hasQuark(context);
            // The encrypted plugin's UC login storage is not established. Cache names are not proof.
            case UC -> false;
            case BAIDU -> hasBaidu(context);
            default -> false;
        };
    }

    /** Scanned-in drives first, the source's order otherwise, so automatic line changes reach them first. */
    public static void prefer(List<Flag> flags) {
        prefer(flags, scanned);
    }

    static void prefer(List<Flag> flags, Predicate<String> scanned) {
        flags.sort(Comparator.comparing(flag -> !scanned.test(family(flag.getFlag()))));
    }

    /** The line to open: the saved one, unless it is a drive not scanned in and another drive is. */
    public static Flag entry(List<Flag> flags, Flag saved) {
        if (saved != null && scanned == LOGIN_CHECK && UC.equals(family(saved.getFlag()))) return saved;
        return entry(flags, saved, scanned);
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
            lines.add(scanned.test(family) ? family + "*" : family);
        }
        return String.join(",", lines);
    }

    /** The drive families among these lines that are scanned in, looked up once for a whole row of lines. */
    public static Set<String> scannedAmong(List<Flag> flags) {
        Set<String> families = new HashSet<>();
        for (Flag flag : flags) families.add(family(flag.getFlag()));
        families.removeIf(family -> !needsScan(family) || !scanned.test(family));
        return families;
    }

    /** A line of a scanned-in drive got an address or failed to. */
    public static void played(String flag, boolean ok) {
        if (!ok) return;
        record(flag, true);
    }

    public static void failed(String flag, Throwable error) {
        if (authenticationFailure(error)) record(flag, false);
    }

    static boolean authenticationFailure(Throwable error) {
        if (com.fongmi.android.tv.resource.ResourceHealth.isCancellation(error)) return false;
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            // A generic 401/403 or a broken share says nothing about the drive login.
            String message = error.getMessage();
            if (message == null) continue;
            String text = message.toLowerCase(Locale.ROOT).replace(" ", "");
            if (text.contains("登录已过期") || text.contains("登录失效") || text.contains("登录过期")
                    || text.contains("cookie已失效") || text.contains("cookie过期")
                    || text.contains("请重新扫码") || text.contains("请先登录")) return true;
        }
        return false;
    }

    private static synchronized void record(String flag, boolean ok) {
        String family = family(flag);
        if (!needsScan(family) || !scanned.test(family)) return;
        int failed = Prefers.getInt(failedKey(family));
        int now = ok ? 0 : Math.min(failed + 1, FAILING_PLAYS);
        if (now != failed) Prefers.put(failedKey(family), now);
    }

    public static List<Status> statuses() {
        return List.of(status("夸克", QUARK), status("百度", BAIDU), status("UC", UC));
    }

    private static Status status(String name, String family) {
        return new Status(name, scanned.test(family), Prefers.getInt(failedKey(family)) >= FAILING_PLAYS, !UC.equals(family));
    }

    private static String failedKey(String family) {
        return "drive_auth_failed_" + family;
    }

    public static String summary(List<Status> statuses) {
        List<String> parts = new ArrayList<>();
        for (Status status : statuses) parts.add(status.text());
        return String.join(" · ", parts);
    }

    @VisibleForTesting
    public static void setScanned(Predicate<String> value) {
        scanned = value == null ? LOGIN_CHECK : value;
    }
}
