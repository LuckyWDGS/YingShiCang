package com.fongmi.android.tv.diagnostics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What kind of error a JavaScript source's own code ended with, for the log. A TV logged 62 such starts
 * as QuickJSException alone (2026-11-23), which cannot tell a script written for a newer host (a missing
 * global such as net or dialog, newer syntax) from a broken one. Only the JavaScript error type and, for
 * a missing global or function, its bare identifier are kept: never the message, which may quote an
 * address or a title.
 */
public final class JsFailure {

    private static final Pattern TYPE = Pattern.compile("^\\s*(?:Uncaught\\s+)?((?:[A-Z][A-Za-z]{0,24})?Error)\\b");
    private static final Pattern NOT_DEFINED = Pattern.compile("(?:^|[\\s'\"`:])([A-Za-z_$][\\w$]{0,30})['\"`]? is not defined");
    private static final Pattern NOT_FUNCTION = Pattern.compile("(?:^|[\\s'\"`:.])([A-Za-z_$][\\w$]{0,30})['\"`]? is not a function");

    private JsFailure() {
    }

    /** " js=ReferenceError missing=net", " js=SyntaxError", " js=module", or nothing when the text says none of it. */
    public static String describe(String message) {
        if (message == null || message.isEmpty()) return "";
        String first = message.length() > 400 ? message.substring(0, 400) : message;
        int line = first.indexOf('\n');
        if (line >= 0) first = first.substring(0, line);
        StringBuilder text = new StringBuilder();
        Matcher type = TYPE.matcher(first);
        if (type.find()) text.append(" js=").append(type.group(1));
        Matcher name = NOT_DEFINED.matcher(first);
        if (name.find()) text.append(" missing=").append(name.group(1));
        else if ((name = NOT_FUNCTION.matcher(first)).find()) text.append(" notFunction=").append(name.group(1));
        else if (text.length() == 0 && first.toLowerCase(java.util.Locale.ROOT).contains("module")) text.append(" js=module");
        return text.toString();
    }
}
