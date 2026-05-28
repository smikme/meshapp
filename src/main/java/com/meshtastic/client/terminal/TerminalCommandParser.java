package com.meshtastic.client.terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * Small parser for whitespace-separated terminal commands.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TerminalCommandParser {

    private TerminalCommandParser() {
    }

    static List<String> splitCommand(String command) {
        List<String> result = new ArrayList<>();
        for (String part : command.trim().split("\\s+")) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }
        return result;
    }
}
