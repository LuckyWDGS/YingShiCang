package android.text;

/** The android.jar stub throws in JVM tests; this stands in for the two calls the bean classes make. */
public final class TextUtils {

    private TextUtils() {
    }

    public static boolean isEmpty(CharSequence text) {
        return text == null || text.length() == 0;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        return a == b || (a != null && b != null && a.toString().contentEquals(b));
    }
}
