package com.meshtastic.client.terminal;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.graphics.TextGraphics;

import java.util.EnumSet;

/**
 * Low-level Lanterna writing helpers that preserve emoji and wide-character layout.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TerminalScreenWriter {

    private TerminalScreenWriter() {
    }

    static void putString(TextGraphics g, int x, int y, String value, SGR modifier, SGR... modifiers) {
        EnumSet<SGR> previousModifiers = EnumSet.copyOf(g.getActiveModifiers());
        g.clearModifiers();
        g.enableModifiers(modifier);
        if (modifiers != null && modifiers.length > 0) {
            g.enableModifiers(modifiers);
        }
        putString(g, x, y, value);
        g.setModifiers(previousModifiers);
    }

    static void putString(TextGraphics g, int x, int y, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        TerminalSize size = g.getSize();
        if (y < 0 || y >= size.getRows() || x >= size.getColumns()) {
            return;
        }

        String rendered = TerminalText.render(value);
        int column = x;
        for (int i = 0; i < rendered.length(); ) {
            int end = TerminalText.nextClusterEnd(rendered, i);
            String cluster = rendered.substring(i, end);
            int width = TerminalText.displayWidth(cluster);
            if (column + width <= 0) {
                column += width;
                i = end;
                continue;
            }
            if (column < 0 || column + width > size.getColumns()) {
                break;
            }
            for (TextCharacter character : TextCharacter.fromString(
                    cluster,
                    g.getForegroundColor(),
                    g.getBackgroundColor(),
                    EnumSet.copyOf(g.getActiveModifiers()))) {
                g.setCharacter(column, y, character);
                column += character.isDoubleWidth() ? 2 : 1;
            }
            i = end;
        }
    }

    static void putChar(TextGraphics g, int x, int y, char ch) {
        g.setCharacter(x, y, ch);
    }
}
