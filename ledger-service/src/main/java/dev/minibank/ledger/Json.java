package dev.minibank.ledger;

/**
 * Hand-rolled JSON, both directions, for FLAT objects only.
 * DECISION: no JSON library yet. Our payloads are tiny and flat; a real
 * fleet would use a proper mapper and schemas · that upgrade is a later,
 * deliberate decision, not a reflex. Meanwhile: zero magic.
 *
 * Public because the broker service uses it too. Worth being precise about
 * what that does and does not mean: sharing a JSON escaper across services
 * is sharing a LIBRARY, which is ordinary. Sharing a database would be
 * sharing STATE, which is the boundary this bank does not cross.
 */
public final class Json {

    private Json() {}

    public static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Extract "field":"value" (string) from a flat JSON object; null if absent. */
    /**
     * Every value for a field, in document order.
     *
     * str() returns only the FIRST match in the whole document, which is right
     * for a flat object and useless for an array of them: a clearing batch of
     * two hundred lines would read back as one. Still a scanner rather than a
     * parser, so it cannot tell a nested field from a top-level one, and for
     * the flat arrays these messages carry that is sufficient. Stated here so
     * the limit is a known cost rather than a surprise.
     */
    public static java.util.List<String> each(String json, String field) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (json == null) return out;
        String needle = "\"" + field + "\"";
        int from = 0;
        while (true) {
            int i = json.indexOf(needle, from);
            if (i < 0) return out;
            String v = str(json.substring(i), field);
            if (v != null) out.add(v);
            from = i + needle.length();
        }
    }

    public static String str(String json, String field) {
        var m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    /** Extract "field":123 (bare number) from a flat JSON object; null if absent. */
    public static String num(String json, String field) {
        var m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
