package com.meshtastic.client.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class UnicodeTextUtilsTest {

    @Test
    void sanitizeDropsOrphanSurrogates() {
        assertEquals("AB", UnicodeTextUtils.sanitize("A\uD83DB"));
        assertEquals("AB", UnicodeTextUtils.sanitize("A\uDC00B"));
    }

    @Test
    void codePointHelpersDoNotSplitSurrogatePairs() {
        String text = "A😀B";

        assertEquals(1, UnicodeTextUtils.clampToCodePointBoundary(text, 2));
        assertEquals(1, UnicodeTextUtils.previousCodePointBoundary(text, 3));
        assertEquals(3, UnicodeTextUtils.nextCodePointBoundary(text, 1));
    }

    @Test
    void truncateWithSuffixPreservesWholeEmoji() {
        String text = "a".repeat(59) + "😀" + "z";
        assertEquals("a".repeat(59) + "😀…", UnicodeTextUtils.truncateWithSuffix(text, 60, "…"));
    }

    @Test
    void sanitizeForJavaFxDisplayKeepsEmojiAndFormattingCodePoints() {
        assertEquals("A😀B\u200DC\u0301D\u0007E",
                UnicodeTextUtils.sanitizeForJavaFxDisplay("A😀B\u200DC\u0301D\u0007E"));
    }

    @Test
    void sanitizeForJavaFxDisplayOnlyDropsOrphanSurrogates() {
        assertEquals("AB😀",
                UnicodeTextUtils.sanitizeForJavaFxDisplay("A\uD83DB😀"));
    }

    @Test
    void sanitizeForJavaFxDisplayPreservesWhitespace() {
        assertEquals(" A\t\tB \n\n C ",
                UnicodeTextUtils.sanitizeForJavaFxDisplay(" A\t\tB \n\n C "));
    }
}
