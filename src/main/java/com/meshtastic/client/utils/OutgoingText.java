package com.meshtastic.client.utils;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Prepares chat text for transmission and measures its UTF-8 payload size.
 *
 * <p>Optional Cyrillic compaction replaces selected homoglyphs with ASCII while
 * preserving links, email addresses and command text. Callers retain the original
 * draft and history text. Returned sizes exclude protocol and authentication overhead.
 */
public final class OutgoingText {
    // Explicit mapping from Meshtastic Android PR #4491 (including Ү → Y and З → 3).
    // https://github.com/meshtastic/Meshtastic-Android/pull/4491
    private static final String CYRILLIC = "ЅІЈАВЕКМНОРСТХаеорсухѕіјҮЗ";
    private static final String ASCII =    "SIJABEKMHOPCTXaeopcyxsijY3";

    // Preserve command lines, including their arguments, and machine-readable tokens.
    private static final Pattern PROTECTED = Pattern.compile(
            "(?m)^\\h*[/@!][\\p{L}\\p{N}_-]+[^\\r\\n]*"
                    + "|(?<![\\p{L}\\p{N}_-])(?i:[a-z][a-z0-9+.-]*://|mailto:|www\\.)[^\\s<>]+"
                    + "|(?<![^\\s<>])[^\\s<>]+@[^\\s<>]+"
                    + "|(?U)(?<![\\p{L}\\p{N}_-])[\\p{L}\\p{N}_-]+(?:\\.[\\p{L}\\p{N}_-]+)+"
                    + "(?:[/:?#][^\\s<>]*)?"
                    + "|[/@!][\\p{L}\\p{N}_-]+");

    private OutgoingText() {}

    /**
     * Prepares text using the current application compaction preference.
     *
     * @param text original text, or {@code null} for an empty payload
     * @return non-null text ready for UTF-8 encoding
     * @see #prepare(String, boolean)
     */
    public static String prepare(String text) {
        return prepare(text, AppPreferences.isCyrillicCompactionEnabled());
    }

    /**
     * Applies the Meshtastic homoglyph table to unprotected portions of the text.
     * Whitespace and unmapped characters are preserved; no trimming is performed.
     *
     * @param text original text, or {@code null} for an empty payload
     * @param compact whether Cyrillic homoglyph substitution is enabled
     * @return transformed text, the original text when disabled, or an empty string for {@code null}
     */
    public static String prepare(String text, boolean compact) {
        if (text == null) { return ""; }
        if (!compact || text.isEmpty()) { return text; }
        StringBuilder result = new StringBuilder(text.length());
        var protectedText = PROTECTED.matcher(text);
        int offset = 0;
        while (protectedText.find()) {
            appendCompact(result, text, offset, protectedText.start());
            result.append(text, protectedText.start(), protectedText.end());
            offset = protectedText.end();
        }
        appendCompact(result, text, offset, text.length());
        return result.toString();
    }

    /**
     * Appends an unprotected substring with the configured character substitutions.
     *
     * @param result destination buffer
     * @param text source text
     * @param start inclusive UTF-16 offset
     * @param end exclusive UTF-16 offset
     */
    private static void appendCompact(StringBuilder result, String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char character = text.charAt(i);
            int index = CYRILLIC.indexOf(character);
            result.append(index < 0 ? character : ASCII.charAt(index));
        }
    }

    /**
     * Counts the transmitted text bytes using the current application preference.
     *
     * @param text original text, or {@code null}
     * @return UTF-8 payload size, excluding protocol overhead; zero for {@code null}
     */
    public static int byteLength(String text) {
        return byteLength(text, AppPreferences.isCyrillicCompactionEnabled());
    }

    /**
     * Counts exactly the UTF-8 bytes produced by {@link #prepare(String, boolean)}.
     *
     * @param text original text, or {@code null}
     * @param compact whether Cyrillic homoglyph substitution is enabled
     * @return UTF-8 payload size, excluding protocol overhead; zero for {@code null}
     */
    public static int byteLength(String text, boolean compact) {
        return prepare(text, compact).getBytes(StandardCharsets.UTF_8).length;
    }
}
