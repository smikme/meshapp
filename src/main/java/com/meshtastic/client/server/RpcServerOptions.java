package com.meshtastic.client.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Command-line options for headless RPC server mode.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class RpcServerOptions {

    static final String ARG_RPC_SERVER = "--rpc-server";
    static final String ENV_BIND = "MESHAPP_RPC_BIND";
    static final String ENV_PORT = "MESHAPP_RPC_PORT";
    static final String ENV_KEY = "MESHAPP_RPC_KEY";

    private String bindAddress;
    private Integer port;
    private String accessKey;
    private boolean autoconnect = true;
    private boolean printAccessKey;
    private boolean help;

    private RpcServerOptions() {
    }

    static boolean isRpcServerMode(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (ARG_RPC_SERVER.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    static RpcServerOptions parse(String[] args) {
        RpcServerOptions options = new RpcServerOptions();
        List<String> values = args != null ? List.of(args) : List.of();
        for (int i = 0; i < values.size(); i++) {
            String arg = values.get(i);
            switch (arg) {
                case ARG_RPC_SERVER -> {
                    // Launcher routing flag; accepted here so tests and direct calls can pass full args.
                }
                case "--help", "-h" -> options.help = true;
                case "--rpc-bind", "--bind" -> options.bindAddress = requireValue(values, ++i, arg);
                case "--rpc-port", "--port" -> options.port = parsePort(requireValue(values, ++i, arg), arg);
                case "--rpc-key", "--key" -> options.accessKey = requireValue(values, ++i, arg).trim();
                case "--print-rpc-key" -> options.printAccessKey = true;
                case "--no-autoconnect" -> options.autoconnect = false;
                default -> throw new IllegalArgumentException("Unknown RPC server option: " + arg);
            }
        }
        return options;
    }

    boolean isHelp() {
        return help;
    }

    String bindAddressOverride() {
        return bindAddress;
    }

    Integer portOverride() {
        return port;
    }

    String accessKeyOverride() {
        return accessKey;
    }

    boolean autoconnect() {
        return autoconnect;
    }

    boolean printAccessKey() {
        return printAccessKey;
    }

    static String usage() {
        return """
                MeshApp RPC server mode

                Usage:
                  meshapp --rpc-server [options]

                Options:
                  --rpc-bind ADDRESS       Bind address, default saved value or 127.0.0.1
                  --rpc-port PORT          TCP port, default saved value or 44030
                  --rpc-key KEY            Access key; default saved key, or generate and save one
                  --print-rpc-key          Print the configured key to console logs
                  --no-autoconnect         Do not connect saved auto-connect profiles on startup
                  --help                   Show this help

                Environment overrides:
                  MESHAPP_RPC_BIND, MESHAPP_RPC_PORT, MESHAPP_RPC_KEY

                Example:
                  meshapp --rpc-server --rpc-bind 0.0.0.0 --rpc-port 44030
                """;
    }

    static String[] stripRpcServerFlag(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }
        List<String> stripped = new ArrayList<>(args.length);
        for (String arg : args) {
            if (!ARG_RPC_SERVER.equals(arg)) {
                stripped.add(arg);
            }
        }
        return stripped.toArray(String[]::new);
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

    private static int parsePort(String value, String option) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " requires a TCP port number: " + value, e);
        }
        if (parsed < 1 || parsed > 65_535) {
            throw new IllegalArgumentException(option + " must be between 1 and 65535");
        }
        return parsed;
    }
}
