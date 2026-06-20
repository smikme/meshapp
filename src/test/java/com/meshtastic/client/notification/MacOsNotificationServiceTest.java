package com.meshtastic.client.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacOsNotificationServiceTest {

    @Test
    void recognizesTopLevelAppBundlePath() {
        assertTrue(MacOsNotificationService.hasAppBundleAncestor("/Applications/MeshApp.app"));
    }

    @Test
    void recognizesJpackageRuntimeInsideAppBundle() {
        assertTrue(MacOsNotificationService.hasAppBundleAncestor(
                "/Applications/MeshApp.app/Contents/runtime/Contents/Home/bin"));
    }

    @Test
    void rejectsBareJdkBundlePath() {
        assertFalse(MacOsNotificationService.hasAppBundleAncestor(
                "/opt/homebrew/Cellar/openjdk/25/libexec/openjdk.jdk/Contents/Home/bin"));
    }

    @Test
    void selfUpdateLauncherAllowsNativeNotifications() {
        var context = new MacOsNotificationService.MacOsNotificationContext(
                "/Applications/MeshApp.app/Contents/runtime/Contents/Home/bin",
                null,
                null,
                "/Applications/MeshApp.app"
        );

        assertEquals("main-bundle-inside-app", context.nativeNotificationReason());
    }

    @Test
    void selfUpdateLauncherIsFallbackWhenMainBundleIsRawJava() {
        var context = new MacOsNotificationService.MacOsNotificationContext(
                "/Users/ks/.sdkman/candidates/java/current/bin",
                null,
                null,
                "/Applications/MeshApp.app"
        );

        assertEquals("self-update-launcher-app", context.nativeNotificationReason());
    }

    @Test
    void bundleIdentifierAloneDoesNotAllowNativeNotifications() {
        var context = new MacOsNotificationService.MacOsNotificationContext(
                "/Users/ks/.sdkman/candidates/java/current/bin",
                null,
                "com.meshtastic.meshapp",
                null
        );

        assertNull(context.nativeNotificationReason());
    }

    @Test
    void unrelatedProcessFallsBackToOsascript() {
        var context = new MacOsNotificationService.MacOsNotificationContext(
                "/Users/ks/.sdkman/candidates/java/current/bin",
                "net.java.openjdk.cmd",
                "com.openai.codex",
                null
        );

        assertNull(context.nativeNotificationReason());
    }
}
