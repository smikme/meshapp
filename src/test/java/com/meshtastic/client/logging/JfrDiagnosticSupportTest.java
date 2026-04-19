package com.meshtastic.client.logging;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrDiagnosticSupportTest {

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
}
