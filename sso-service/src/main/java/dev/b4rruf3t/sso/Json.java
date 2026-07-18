package dev.b4rruf3t.sso;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tiny JSON field extractor — copied from minibank's Json.java pattern. */
public final class Json {
    private Json() {}

    public static String str(String json, String field) {
        if (json == null) return null;
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    public static String num(String json, String field) {
        if (json == null) return null;
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
