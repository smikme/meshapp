package com.meshtastic.client.components.map;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TileSourceTest {

    @Test
    void osmAllowsInteractiveHighZoomButForbidsBulkDownloads() {
        TileSource source = TileSource.OPEN_STREET_MAP;

        assertFalse(source.bulkDownloadAllowed());
        assertEquals(
                "https://tile.openstreetmap.org/14/9876/5432.png",
                source.tileUri(14, 9876, 5432).toString()
        );
    }

    @Test
    void sourceRequiresSafeIdAndCompleteUrlTemplate() {
        assertThrows(IllegalArgumentException.class, () -> new TileSource(
                "../unsafe", "https://example.test/{z}/{x}/{y}.png", "Example", 1, 19, false));
        assertThrows(IllegalArgumentException.class, () -> new TileSource(
                "safe", "https://example.test/{z}/{x}.png", "Example", 1, 19, false));
    }

    @Test
    void runtimeConfigurationCanReplaceHardCodedEndpointWithoutEnablingBulk() {
        Properties properties = new Properties();
        properties.setProperty(TileSource.PROPERTY_ID, "company-tiles");
        properties.setProperty(TileSource.PROPERTY_URL, "https://tiles.example.test/{z}/{x}/{y}.png");
        properties.setProperty(TileSource.PROPERTY_ATTRIBUTION, "© Example Maps");

        TileSource source = TileSource.configured(properties);

        assertEquals("company-tiles", source.id());
        assertEquals("© Example Maps", source.attribution());
        assertFalse(source.bulkDownloadAllowed());
    }
}
