package com.meshtastic.client.logging;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class JfrDiagnosticSupportTest {

    @AfterEach
    void clearJfrProperty() {
        System.clearProperty(JfrDiagnosticSupport.JFR_ENABLED_PROPERTY);
    }

    @Test
    void applyPlatformSafetyWorkaroundsDisablesThreadSamplingOnMacOs() throws Exception {
        Recording recording = new Recording(Configuration.getConfiguration("profile"));

        JfrDiagnosticSupport.applyPlatformSafetyWorkarounds(recording, "Mac OS X", "26.4.1");

        assertEquals("false", recording.getSettings().get("jdk.ExecutionSample#enabled"));
        assertEquals("false", recording.getSettings().get("jdk.NativeMethodSample#enabled"));
    }

    @Test
    void applyPlatformSafetyWorkaroundsLeavesNonMacProfileUntouched() throws Exception {
        Recording recording = new Recording(Configuration.getConfiguration("profile"));
        String executionSampleSetting = recording.getSettings().get("jdk.ExecutionSample#enabled");
        String nativeMethodSampleSetting = recording.getSettings().get("jdk.NativeMethodSample#enabled");

        JfrDiagnosticSupport.applyPlatformSafetyWorkarounds(recording, "Linux", "6.8.0");

        assertEquals(executionSampleSetting, recording.getSettings().get("jdk.ExecutionSample#enabled"));
        assertEquals(nativeMethodSampleSetting, recording.getSettings().get("jdk.NativeMethodSample#enabled"));
    }

    @Test
    void shouldDisableExecutionSamplingMatchesMacNamesOnly() {
        assertTrue(JfrDiagnosticSupport.shouldDisableExecutionSampling("Mac OS X"));
        assertTrue(JfrDiagnosticSupport.shouldDisableExecutionSampling("macOS"));
        assertFalse(JfrDiagnosticSupport.shouldDisableExecutionSampling("Windows 11"));
    }

    @Test
    void isEnabledIsFalseByDefaultAndTrueOnlyWhenPropertyIsSet() {
        assertFalse(JfrDiagnosticSupport.isEnabled(null, null, false));
        assertTrue(JfrDiagnosticSupport.isEnabled(null, null, true));

        System.setProperty(JfrDiagnosticSupport.JFR_ENABLED_PROPERTY, "true");
        assertTrue(JfrDiagnosticSupport.isEnabled(
                System.getProperty(JfrDiagnosticSupport.JFR_ENABLED_PROPERTY),
                null,
                false));

        System.setProperty(JfrDiagnosticSupport.JFR_ENABLED_PROPERTY, "false");
        assertFalse(JfrDiagnosticSupport.isEnabled(
                System.getProperty(JfrDiagnosticSupport.JFR_ENABLED_PROPERTY),
                null,
                true));
    }

    @Test
    void envValueOverridesPreferenceWhenPropertyIsAbsent() {
        assertTrue(JfrDiagnosticSupport.isEnabled(null, "true", false));
        assertFalse(JfrDiagnosticSupport.isEnabled(null, "false", true));
    }
}
