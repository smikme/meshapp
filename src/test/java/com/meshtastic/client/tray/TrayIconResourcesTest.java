package com.meshtastic.client.tray;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrayIconResourcesTest {

    @Test
    void choosesSmallestAvailableBaseIconAtOrAboveTargetSize() {
        assertEquals(16, TrayIconResources.chooseSourceIconSize(16));
        assertEquals(32, TrayIconResources.chooseSourceIconSize(18));
        assertEquals(32, TrayIconResources.chooseSourceIconSize(24));
        assertEquals(64, TrayIconResources.chooseSourceIconSize(48));
        assertEquals(128, TrayIconResources.chooseSourceIconSize(72));
        assertEquals(256, TrayIconResources.chooseSourceIconSize(256));
        assertEquals(256, TrayIconResources.chooseSourceIconSize(512));
    }

    @Test
    void scalesImageToRequestedTraySlotSize() throws IOException {
        BufferedImage image = TrayIconResources.loadScaledImage(24, 22);

        assertEquals(24, image.getWidth());
        assertEquals(22, image.getHeight());
    }
}
