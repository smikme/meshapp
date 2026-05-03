package com.meshtastic.client.tray;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
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

    @Test
    void loadsFixedLinuxTrayImage() throws IOException {
        BufferedImage image = TrayIconResources.loadLinuxTrayImage();

        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
        assertEquals(0, (image.getRGB(0, 0) >>> 24));
    }

    @Test
    void extractMacOsTrayIconCopiesBundledPng() throws IOException {
        Path extracted = TrayIconResources.extractMacOsTrayIcon();

        assertTrue(Files.exists(extracted));
        assertTrue(Files.size(extracted) > 0);

        try (InputStream input = Files.newInputStream(extracted)) {
            assertEquals(0x89, input.read());
            assertEquals('P', input.read());
            assertEquals('N', input.read());
            assertEquals('G', input.read());
        }
    }
}
