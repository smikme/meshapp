package com.meshtastic.client.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class NativeWinWindowControlTest {

    @Test
    void initDarkModeSupportDoesNotThrowWhenUxThemeOrdinalsAreUnavailable() {
        assertDoesNotThrow(NativeWinWindowControl::initDarkModeSupport);
        assertDoesNotThrow(NativeWinWindowControl::initDarkModeSupport);
    }
}
