package com.meshtastic.client.terminal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalTextTest {

    @Test
    void renderKeepsSupplementaryEmojiCharacters() {
        String source = "hi " + codePoints(0x1F600) + " " + codePoints(0x1F44D, 0x1F3FD);

        assertEquals(source, TerminalText.render(source));
    }

    @Test
    void renderKeepsUnknownEmojiCharacters() {
        String source = "unknown " + codePoints(0x1FAE8);

        assertEquals(source, TerminalText.render(source));
    }

    @Test
    void renderKeepsJoinedEmojiCharacters() {
        String source = codePoints(0x1F9D1, 0x200D, 0x1F4BB);

        assertEquals(source, TerminalText.render(source));
    }

    @Test
    void renderAddsEmojiPresentationSelectorForBmpEmoji() {
        assertEquals(codePoints(0x2705, 0xFE0F), TerminalText.render(codePoints(0x2705)));
    }

    @Test
    void renderKeepsPlainBmpSymbolsWhenTheyAreNotEmoji() {
        String source = "copy " + codePoints(0x00A9);

        assertEquals(source, TerminalText.render(source));
    }

    @Test
    void fitDoesNotSplitEmoji() {
        assertEquals("abc " + codePoints(0x1F600), TerminalText.fit("abc " + codePoints(0x1F600) + " def", 6));
    }

    @Test
    void wrapDoesNotSplitEmoji() {
        List<String> lines = TerminalText.wrap("ok " + codePoints(0x1F600) + " done", 5);

        assertEquals(List.of("ok " + codePoints(0x1F600), " done"), lines);
    }

    private static String codePoints(int... values) {
        StringBuilder out = new StringBuilder(values.length * 2);
        for (int value : values) {
            out.appendCodePoint(value);
        }
        return out.toString();
    }
}
