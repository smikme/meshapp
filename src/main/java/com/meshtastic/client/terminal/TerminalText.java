package com.meshtastic.client.terminal;

import com.meshtastic.client.utils.UnicodeTextUtils;

import java.util.ArrayList;
import java.util.List;

final class TerminalText {

    private static final int KEYCAP = 0x20E3;
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int VARIATION_SELECTOR_15 = 0xFE0E;
    private static final int VARIATION_SELECTOR_16 = 0xFE0F;

    private TerminalText() {
    }

    static String render(String value) {
        String sanitized = UnicodeTextUtils.sanitize(value);
        if (sanitized == null || sanitized.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder(sanitized.length());
        for (int i = 0; i < sanitized.length(); ) {
            int codePoint = sanitized.codePointAt(i);
            if (codePoint == '\t') {
                out.append("    ");
            } else if (Character.isISOControl(codePoint)) {
                out.append(' ');
            } else {
                out.appendCodePoint(codePoint);
                if (needsEmojiVariationSelector(codePoint)
                        && !hasVariationSelectorAt(sanitized, i + Character.charCount(codePoint))) {
                    out.appendCodePoint(VARIATION_SELECTOR_16);
                }
            }
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }

    static List<String> wrap(String value, int width) {
        if (width <= 0) {
            return List.of();
        }

        String rendered = render(value);
        if (rendered.isEmpty()) {
            return List.of("");
        }

        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        for (int i = 0; i < rendered.length(); ) {
            int end = nextClusterEnd(rendered, i);
            String cluster = rendered.substring(i, end);
            int clusterWidth = displayWidthCluster(cluster);
            if (lineWidth > 0 && lineWidth + clusterWidth > width) {
                lines.add(line.toString());
                line.setLength(0);
                lineWidth = 0;
            }
            line.append(cluster);
            lineWidth += clusterWidth;
            i = end;
        }
        lines.add(line.toString());
        return lines;
    }

    static String fit(String value, int width) {
        if (width <= 0) {
            return "";
        }

        String rendered = render(value);
        StringBuilder out = new StringBuilder(Math.min(rendered.length(), width));
        int used = 0;
        for (int i = 0; i < rendered.length(); ) {
            int end = nextClusterEnd(rendered, i);
            String cluster = rendered.substring(i, end);
            int clusterWidth = displayWidthCluster(cluster);
            if (used + clusterWidth > width) {
                break;
            }
            out.append(cluster);
            used += clusterWidth;
            i = end;
        }
        return out.toString();
    }

    static String padRight(String value, int width) {
        String fitted = fit(value, width);
        int padding = Math.max(0, width - displayWidth(fitted));
        return padding == 0 ? fitted : fitted + " ".repeat(padding);
    }

    static int displayWidth(String value) {
        String rendered = render(value);
        int width = 0;
        for (int i = 0; i < rendered.length(); ) {
            int end = nextClusterEnd(rendered, i);
            width += displayWidthCluster(rendered.substring(i, end));
            i = end;
        }
        return width;
    }

    static int nextClusterEnd(String text, int start) {
        int keycapEnd = keycapEnd(text, start);
        if (keycapEnd > start) {
            return keycapEnd;
        }

        int emojiEnd = emojiSequenceEnd(text, start);
        if (emojiEnd > start) {
            return emojiEnd;
        }

        int codePoint = text.codePointAt(start);
        int end = start + Character.charCount(codePoint);
        while (end < text.length()) {
            int next = text.codePointAt(end);
            if (isCombiningMark(next) || isVariationSelector(next)) {
                end += Character.charCount(next);
            } else {
                break;
            }
        }
        return end;
    }

    private static int emojiSequenceEnd(String text, int start) {
        int first = text.codePointAt(start);
        if (!isEmojiStart(text, start)) {
            return -1;
        }

        int end = emojiUnitEnd(text, start);
        if (isRegionalIndicator(first)) {
            if (end < text.length() && isRegionalIndicator(text.codePointAt(end))) {
                end = emojiUnitEnd(text, end);
            }
            return end;
        }

        while (end < text.length()) {
            int next = text.codePointAt(end);
            if (next != ZERO_WIDTH_JOINER) {
                break;
            }
            int afterJoiner = end + Character.charCount(next);
            if (afterJoiner >= text.length() || !isEmojiStart(text, afterJoiner)) {
                break;
            }
            end = emojiUnitEnd(text, afterJoiner);
        }
        return end;
    }

    private static int emojiUnitEnd(String text, int start) {
        int codePoint = text.codePointAt(start);
        int end = start + Character.charCount(codePoint);
        while (end < text.length()) {
            int next = text.codePointAt(end);
            if (isVariationSelector(next) || isSkinToneModifier(next) || isTagCharacter(next)) {
                end += Character.charCount(next);
            } else {
                break;
            }
        }
        return end;
    }

    private static int keycapEnd(String text, int start) {
        int codePoint = text.codePointAt(start);
        if (!(codePoint == '#' || codePoint == '*' || Character.isDigit(codePoint))) {
            return -1;
        }
        int end = start + Character.charCount(codePoint);
        if (end < text.length() && isVariationSelector(text.codePointAt(end))) {
            end += Character.charCount(text.codePointAt(end));
        }
        if (end < text.length() && text.codePointAt(end) == KEYCAP) {
            return end + Character.charCount(KEYCAP);
        }
        return -1;
    }

    private static int displayWidthCluster(String cluster) {
        if (keycapEnd(cluster, 0) == cluster.length() || emojiSequenceEnd(cluster, 0) == cluster.length()) {
            return 2;
        }

        int width = 0;
        for (int i = 0; i < cluster.length(); ) {
            int codePoint = cluster.codePointAt(i);
            if (isZeroWidthCodePoint(codePoint)) {
                i += Character.charCount(codePoint);
                continue;
            }
            width += isWideCodePoint(codePoint) ? 2 : 1;
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private static boolean isEmojiStart(String text, int start) {
        int codePoint = text.codePointAt(start);
        return isRegionalIndicator(codePoint)
                || isSupplementaryEmoji(codePoint)
                || isBmpEmojiWithPresentation(text, start);
    }

    private static boolean isSupplementaryEmoji(int codePoint) {
        return codePoint >= 0x1F000 && codePoint <= 0x1FAFF;
    }

    private static boolean isBmpEmojiWithPresentation(String text, int start) {
        int codePoint = text.codePointAt(start);
        int nextIndex = start + Character.charCount(codePoint);
        return (isBmpEmojiBase(codePoint) && hasVariationSelectorAt(text, nextIndex))
                || needsEmojiVariationSelector(codePoint);
    }

    private static boolean hasVariationSelectorAt(String text, int index) {
        return index < text.length() && isVariationSelector(text.codePointAt(index));
    }

    private static boolean needsEmojiVariationSelector(int codePoint) {
        return switch (codePoint) {
            case 0x203C, 0x2049, 0x2122, 0x2139,
                 0x2194, 0x2195, 0x2196, 0x2197, 0x2198, 0x2199,
                 0x21A9, 0x21AA, 0x231A, 0x231B, 0x2328, 0x23CF,
                 0x23E9, 0x23EA, 0x23EB, 0x23EC, 0x23ED, 0x23EE, 0x23EF,
                 0x23F0, 0x23F1, 0x23F2, 0x23F3, 0x23F8, 0x23F9, 0x23FA,
                 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25C0, 0x25FB, 0x25FC,
                 0x25FD, 0x25FE, 0x2600, 0x2601, 0x2602, 0x2603, 0x2604,
                 0x260E, 0x2611, 0x2614, 0x2615, 0x2618, 0x261D, 0x2620,
                 0x2622, 0x2623, 0x2626, 0x262A, 0x262E, 0x262F, 0x2638,
                 0x2639, 0x263A, 0x2640, 0x2642, 0x2648, 0x2649, 0x264A,
                 0x264B, 0x264C, 0x264D, 0x264E, 0x264F, 0x2650, 0x2651,
                 0x2652, 0x2653, 0x2660, 0x2663, 0x2665, 0x2666, 0x2668,
                 0x267B, 0x267E, 0x267F, 0x2692, 0x2693, 0x2694, 0x2695,
                 0x2696, 0x2697, 0x2699, 0x269B, 0x269C, 0x26A0, 0x26A1,
                 0x26AA, 0x26AB, 0x26B0, 0x26B1, 0x26BD, 0x26BE, 0x26C4,
                 0x26C5, 0x26C8, 0x26CE, 0x26CF, 0x26D1, 0x26D3, 0x26D4,
                 0x26E9, 0x26EA, 0x26F0, 0x26F1, 0x26F2, 0x26F3, 0x26F4,
                 0x26F5, 0x26F7, 0x26F8, 0x26F9, 0x26FA, 0x26FD, 0x2702,
                 0x2705, 0x2708, 0x2709, 0x270A, 0x270B, 0x270C, 0x270D,
                 0x270F, 0x2712, 0x2714, 0x2716, 0x271D, 0x2721, 0x2728,
                 0x2733, 0x2734, 0x2744, 0x2747, 0x274C, 0x274E, 0x2753,
                 0x2754, 0x2755, 0x2757, 0x2763, 0x2764, 0x2795, 0x2796,
                 0x2797, 0x27A1, 0x27B0, 0x27BF, 0x2B05, 0x2B06, 0x2B07,
                 0x2B1B, 0x2B1C, 0x2B50, 0x2B55, 0x3030, 0x303D, 0x3297,
                 0x3299 -> true;
            default -> false;
        };
    }

    private static boolean isBmpEmojiBase(int codePoint) {
        return (codePoint >= 0x203C && codePoint <= 0x303D)
                || codePoint == 0x3297
                || codePoint == 0x3299;
    }

    private static boolean isVariationSelector(int codePoint) {
        return codePoint == VARIATION_SELECTOR_15 || codePoint == VARIATION_SELECTOR_16;
    }

    private static boolean isSkinToneModifier(int codePoint) {
        return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    private static boolean isTagCharacter(int codePoint) {
        return codePoint >= 0xE0020 && codePoint <= 0xE007F;
    }

    private static boolean isZeroWidthCodePoint(int codePoint) {
        return codePoint == ZERO_WIDTH_JOINER
                || isVariationSelector(codePoint)
                || isSkinToneModifier(codePoint)
                || isTagCharacter(codePoint)
                || isCombiningMark(codePoint);
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isWideCodePoint(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F)
                || codePoint == 0x2329
                || codePoint == 0x232A
                || (codePoint >= 0x2E80 && codePoint <= 0xA4CF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFE10 && codePoint <= 0xFE19)
                || (codePoint >= 0xFE30 && codePoint <= 0xFE6F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFF60)
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                || (codePoint >= 0x20000 && codePoint <= 0x3FFFD);
    }
}
