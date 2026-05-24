package com.meshtastic.client.terminal;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.ProtocolType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal command-line parser for terminal mode.
 */
final class TerminalOptions {

    private String host;
    private int port = 4403;
    private String serialPort;
    private int baudRate = 115200;
    private String bleAddress;
    private String name;
    private ProtocolType protocol = ProtocolType.MESHTASTIC;
    private boolean help;

    private TerminalOptions() {}

    static TerminalOptions parse(String[] args) {
        TerminalOptions options = new TerminalOptions();
        List<String> values = args != null ? List.of(args) : List.of();
        for (int i = 0; i < values.size(); i++) {
            String arg = values.get(i);
            switch (arg) {
                case "--help", "-h" -> options.help = true;
                case "--host" -> options.host = requireValue(values, ++i, arg);
                case "--port" -> options.port = parseInt(requireValue(values, ++i, arg), arg);
                case "--serial" -> options.serialPort = requireValue(values, ++i, arg);
                case "--baud" -> options.baudRate = parseInt(requireValue(values, ++i, arg), arg);
                case "--ble" -> options.bleAddress = requireValue(values, ++i, arg);
                case "--name" -> options.name = requireValue(values, ++i, arg);
                case "--protocol" -> options.protocol = parseProtocol(requireValue(values, ++i, arg));
                default -> throw new IllegalArgumentException("Unknown terminal option: " + arg);
            }
        }
        return options;
    }

    boolean isHelp() {
        return help;
    }

    boolean hasInlineConnection() {
        return host != null || serialPort != null || bleAddress != null;
    }

    ConnectionEntry toConnectionEntry() {
        if (bleAddress != null) {
            ConnectionEntry entry = new ConnectionEntry(displayName("Terminal BLE"), bleAddress, displayName("BLE"));
            entry.setProtocol(protocol);
            return entry;
        }
        if (serialPort != null) {
            ConnectionEntry entry = new ConnectionEntry(
                    displayName("Terminal Serial"),
                    serialPort,
                    baudRate,
                    ConnectionType.SERIAL);
            entry.setProtocol(protocol);
            return entry;
        }
        if (host != null) {
            ConnectionEntry entry = new ConnectionEntry(displayName("Terminal TCP"), host, port);
            entry.setProtocol(protocol);
            return entry;
        }
        throw new IllegalStateException("No inline connection was provided");
    }

    static String usage() {
        return """
                MeshApp terminal mode

                Usage:
                  meshapp --terminal [options]

                Options:
                  --host HOST              Add and connect a temporary TCP profile
                  --port PORT              TCP port, default 4403
                  --serial PORT            Add and connect a temporary serial profile
                  --baud RATE              Serial baud rate, default 115200
                  --ble ADDRESS            Add and connect a temporary BLE profile
                  --protocol TYPE          meshtastic, meshcore-kiss, meshcore-companion
                  --name NAME              Temporary profile name
                  --help                   Show this help

                In the TUI press / for commands, ? for help, q to quit.
                """;
    }

    private String displayName(String fallback) {
        return name != null && !name.isBlank() ? name : fallback;
    }

    private static String requireValue(List<String> args, int index, String option) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        String value = args.get(index);
        if (value.startsWith("--")) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return value;
    }

    private static int parseInt(String value, String option) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " requires a number: " + value, e);
        }
    }

    private static ProtocolType parseProtocol(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "meshtastic" -> ProtocolType.MESHTASTIC;
            case "meshcore-kiss" -> ProtocolType.MESHCORE_KISS;
            case "meshcore-companion" -> ProtocolType.MESHCORE_COMPANION;
            default -> throw new IllegalArgumentException("Unknown protocol: " + value);
        };
    }

    static String[] stripTerminalFlag(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }
        List<String> stripped = new ArrayList<>(args.length);
        for (String arg : args) {
            if (!"--terminal".equals(arg)) {
                stripped.add(arg);
            }
        }
        return stripped.toArray(String[]::new);
    }
}
