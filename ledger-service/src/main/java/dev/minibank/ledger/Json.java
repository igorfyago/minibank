package dev.minibank.ledger;

/**
 * Hand-rolled JSON, both directions, for FLAT objects only.
 * DECISION: no JSON library yet. Our payloads are tiny and flat; a real
 * fleet would use a proper mapper and schemas — that upgrade is a later,
 * deliberate decision, not a reflex. Meanwhile: zero magic.
 */
final class Json {

    private Json() {}

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Extract "field":"value" (string) from a flat JSON object; null if absent. */
    static String str(String json, String field) {
        var m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    /** Extract "field":123 (bare number) from a flat JSON object; null if absent. */
    static String num(String json, String field) {
        var m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
