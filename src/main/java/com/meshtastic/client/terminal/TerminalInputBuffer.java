package com.meshtastic.client.terminal;

/**
 * Mutable terminal chat input buffer with code-point aware cursor movement.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TerminalInputBuffer {

    private String text = "";
    private int caret;

    String text() {
        return text;
    }

    int caret() {
        return caret;
    }

    int byteLength() {
        return TerminalInputLimits.textByteLength(text);
    }

    boolean overLimit(int maxBytes) {
        return byteLength() > maxBytes;
    }

    boolean insert(String value, int maxBytes) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        String next = text.substring(0, caret) + value + text.substring(caret);
        if (TerminalInputLimits.textByteLength(next) > maxBytes) {
            return false;
        }
        text = next;
        caret += value.length();
        return true;
    }

    void moveLeft() {
        caret = caret > 0 ? text.offsetByCodePoints(caret, -1) : 0;
    }

    void moveRight() {
        caret = caret < text.length() ? text.offsetByCodePoints(caret, 1) : text.length();
    }

    void home() {
        caret = 0;
    }

    void end() {
        caret = text.length();
    }

    void backspace() {
        if (caret <= 0 || text.isEmpty()) {
            return;
        }
        int previous = text.offsetByCodePoints(caret, -1);
        text = text.substring(0, previous) + text.substring(caret);
        caret = previous;
    }

    void delete() {
        if (caret >= text.length()) {
            return;
        }
        int next = text.offsetByCodePoints(caret, 1);
        text = text.substring(0, caret) + text.substring(next);
    }

    void clear() {
        text = "";
        caret = 0;
    }

    void trimToLimit(int maxBytes) {
        while (TerminalInputLimits.textByteLength(text) > maxBytes && !text.isEmpty()) {
            int end = text.offsetByCodePoints(text.length(), -1);
            text = text.substring(0, end);
            caret = Math.min(caret, text.length());
        }
    }
}
