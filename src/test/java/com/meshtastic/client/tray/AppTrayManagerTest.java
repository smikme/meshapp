package com.meshtastic.client.tray;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppTrayManagerTest {

    @Test
    void closingWindowMinimizesOnlyWhenTrayIsAvailableAndPreferenceIsEnabled() {
        assertTrue(AppTrayManager.shouldMinimizeToTray(true, true));
        assertFalse(AppTrayManager.shouldMinimizeToTray(true, false));
        assertFalse(AppTrayManager.shouldMinimizeToTray(false, true));
        assertFalse(AppTrayManager.shouldMinimizeToTray(false, false));
    }
}
