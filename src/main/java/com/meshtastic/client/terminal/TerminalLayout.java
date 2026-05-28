package com.meshtastic.client.terminal;

import com.googlecode.lanterna.TerminalSize;

import java.util.ArrayList;
import java.util.List;

/**
 * Layout and collection utilities shared by terminal rendering code.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TerminalLayout {

    private static final TerminalSize DEFAULT_TERMINAL_SIZE = new TerminalSize(100, 30);

    private TerminalLayout() {
    }

    static TerminalSize initialTerminalSize() {
        int columns = readTerminalSizeEnv("COLUMNS", DEFAULT_TERMINAL_SIZE.getColumns());
        int rows = readTerminalSizeEnv("LINES", DEFAULT_TERMINAL_SIZE.getRows());
        return new TerminalSize(Math.max(40, columns), Math.max(12, rows));
    }

    static int leftPaneWidth(TerminalSize size) {
        return clamp(size.getColumns() / 3, 28, 44);
    }

    static int inputTopRow(TerminalSize size) {
        return Math.max(2, size.getRows() - 2);
    }

    static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    static <T> List<T> tail(List<T> list, int maxItems) {
        if (list == null || list.isEmpty() || maxItems <= 0) {
            return List.of();
        }
        int from = Math.max(0, list.size() - maxItems);
        return new ArrayList<>(list.subList(from, list.size()));
    }

    private static int readTerminalSizeEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
