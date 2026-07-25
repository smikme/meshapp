package com.meshtastic.client;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Ensures GUI launches cannot omit the native-access permission required by JavaFX. */
final class NativeAccessRelauncher {
    static final String REQUIRED_ARG = "--enable-native-access=javafx.graphics,ALL-UNNAMED";
    private static final String RELAUNCHED_PROPERTY = "meshapp.nativeAccessRelaunched";

    private NativeAccessRelauncher() {}

    static boolean relaunchIfRequired(String[] applicationArgs) {
        if (isHeadlessMode(applicationArgs) || hasJavaFxNativeAccess(currentJvmArgs())) {
            return false;
        }
        if (Boolean.getBoolean(RELAUNCHED_PROPERTY)) {
            throw new IllegalStateException("JavaFX native access is still unavailable after JVM relaunch");
        }

        List<String> command = relaunchCommand(currentJvmArgs(), applicationArgs);
        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("MeshApp JVM relaunch failed with exit code " + exitCode);
            }
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to relaunch MeshApp with JavaFX native access", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for MeshApp JVM relaunch", e);
        }
    }

    static List<String> relaunchCommand(List<String> currentArgs, String[] applicationArgs) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        currentArgs.stream()
                .filter(arg -> !arg.startsWith("--enable-native-access="))
                .forEach(command::add);
        command.add(REQUIRED_ARG);
        addJavaFxModuleAccess(command);
        command.add("-D" + RELAUNCHED_PROPERTY + "=true");
        command.add("-cp");
        command.add(System.getProperty("java.class.path", ""));
        command.add(MeshAppLauncher.class.getName());
        if (applicationArgs != null) {
            command.addAll(Arrays.asList(applicationArgs));
        }
        return List.copyOf(command);
    }

    private static void addJavaFxModuleAccess(List<String> command) {
        command.addAll(List.of(
                "--add-opens", "javafx.graphics/javafx.stage=ALL-UNNAMED",
                "--add-exports", "javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED",
                "--add-opens", "javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED",
                "--add-opens", "javafx.graphics/com.sun.javafx.tk.quantum=ALL-UNNAMED",
                "--add-opens", "javafx.graphics/com.sun.glass.ui=ALL-UNNAMED"
        ));
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            command.addAll(List.of("--add-opens", "javafx.graphics/com.sun.glass.ui.win=ALL-UNNAMED"));
        } else if (osName.contains("mac")) {
            command.addAll(List.of("--add-opens", "javafx.graphics/com.sun.glass.ui.mac=ALL-UNNAMED"));
        }
    }

    static boolean hasJavaFxNativeAccess(List<String> args) {
        return args.stream()
                .filter(arg -> arg.startsWith("--enable-native-access="))
                .map(arg -> arg.substring("--enable-native-access=".length()))
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .anyMatch("javafx.graphics"::equals);
    }

    private static List<String> currentJvmArgs() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments();
    }

    private static boolean isHeadlessMode(String[] args) {
        return args != null && Arrays.stream(args)
                .anyMatch(arg -> "--terminal".equals(arg) || "--rpc-server".equals(arg));
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
