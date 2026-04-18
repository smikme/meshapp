package com.meshtastic.client.utils;

/**
 * Утилиты для безопасной работы с пользовательским Unicode-текстом в JavaFX.
 *
 * <p>Помогают не создавать строки с одиночными суррогатами и не резать текст
 * внутри суррогатных пар при обрезке, позиционировании каретки и измерении.
 */
public final class UnicodeTextUtils {

    private UnicodeTextUtils() {}

    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        StringBuilder sanitized = null;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);

            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    if (sanitized != null) {
                        sanitized.append(ch).append(value.charAt(i + 1));
                    }
                    i++;
                    continue;
                }
                if (sanitized == null) {
                    sanitized = new StringBuilder(value.length());
                    sanitized.append(value, 0, i);
                }
                continue;
            }

            if (Character.isLowSurrogate(ch)) {
                if (sanitized == null) {
                    sanitized = new StringBuilder(value.length());
                    sanitized.append(value, 0, i);
                }
                continue;
            }

            if (sanitized != null) {
                sanitized.append(ch);
            }
        }

        return sanitized == null ? value : sanitized.toString();
    }

    public static int clampToCodePointBoundary(String text, int index) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int clamped = Math.max(0, Math.min(index, text.length()));
        if (clamped > 0
                && clamped < text.length()
                && Character.isLowSurrogate(text.charAt(clamped))
                && Character.isHighSurrogate(text.charAt(clamped - 1))) {
            return clamped - 1;
        }
        return clamped;
    }

    public static int previousCodePointBoundary(String text, int index) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int clamped = clampToCodePointBoundary(text, index);
        if (clamped <= 0) {
            return 0;
        }

        int previous = clamped - 1;
        if (previous > 0
                && Character.isLowSurrogate(text.charAt(previous))
                && Character.isHighSurrogate(text.charAt(previous - 1))) {
            return previous - 1;
        }
        return previous;
    }

    public static int nextCodePointBoundary(String text, int index) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int clamped = clampToCodePointBoundary(text, index);
        if (clamped >= text.length()) {
            return text.length();
        }

        if (clamped + 1 < text.length()
                && Character.isHighSurrogate(text.charAt(clamped))
                && Character.isLowSurrogate(text.charAt(clamped + 1))) {
            return clamped + 2;
        }
        return clamped + 1;
    }

    public static String prefixByCodePoints(String text, int maxCodePoints) {
        String sanitized = sanitize(text);
        if (sanitized == null) {
            return null;
        }
        if (sanitized.isEmpty() || maxCodePoints <= 0) {
            return "";
        }

        int codePoints = sanitized.codePointCount(0, sanitized.length());
        if (codePoints <= maxCodePoints) {
            return sanitized;
        }

        int end = sanitized.offsetByCodePoints(0, maxCodePoints);
        return sanitized.substring(0, end);
    }

    public static String suffixByCodePoints(String text, int maxCodePoints) {
        String sanitized = sanitize(text);
        if (sanitized == null) {
            return null;
        }
        if (sanitized.isEmpty() || maxCodePoints <= 0) {
            return "";
        }

        int codePoints = sanitized.codePointCount(0, sanitized.length());
        if (codePoints <= maxCodePoints) {
            return sanitized;
        }

        int start = sanitized.offsetByCodePoints(sanitized.length(), -maxCodePoints);
        return sanitized.substring(start);
    }

    public static String truncateWithSuffix(String text, int maxCodePoints, String suffix) {
        String sanitized = sanitize(text);
        if (sanitized == null) {
            return null;
        }
        if (maxCodePoints <= 0) {
            return sanitize(suffix);
        }

        int codePoints = sanitized.codePointCount(0, sanitized.length());
        if (codePoints <= maxCodePoints) {
            return sanitized;
        }

        return prefixByCodePoints(sanitized, maxCodePoints) + sanitize(suffix);
    }
}
