package com.meshtastic.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeAccessRelauncherTest {
    @Test
    void recognizesJavaFxAmongCombinedNativeAccessModules() {
        assertTrue(NativeAccessRelauncher.hasJavaFxNativeAccess(List.of(
                "--enable-native-access=ALL-UNNAMED,javafx.graphics")));
        assertFalse(NativeAccessRelauncher.hasJavaFxNativeAccess(List.of(
                "--enable-native-access=ALL-UNNAMED")));
    }

    @Test
    void relaunchCommandReplacesIncompleteNativeAccessArgument() {
        List<String> command = NativeAccessRelauncher.relaunchCommand(
                List.of("-Xmx512m", "--enable-native-access=ALL-UNNAMED"),
                new String[]{"--no-single-instance"});

        assertEquals(1, command.stream()
                .filter(arg -> arg.startsWith("--enable-native-access="))
                .count());
        assertTrue(command.contains(NativeAccessRelauncher.REQUIRED_ARG));
        assertTrue(command.contains("javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED"));
        assertTrue(command.contains("--no-single-instance"));
    }
}
