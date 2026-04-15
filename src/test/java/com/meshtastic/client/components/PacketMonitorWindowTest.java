package com.meshtastic.client.components;

import com.meshtastic.client.model.PacketLogEntry;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketMonitorWindowTest {

    @Test
    void shouldKeepSelectedTypeInOptionsWhenFilterListRefreshes() {
        List<String> options = PacketMonitorWindow.buildTypeFilterOptions(
                List.of("NODEINFO_APP"),
                "TEXT_MESSAGE_APP");

        assertEquals(List.of("Все типы", "NODEINFO_APP", "TEXT_MESSAGE_APP"), options);
        assertEquals("TEXT_MESSAGE_APP",
                PacketMonitorWindow.resolveTypeFilterSelection("TEXT_MESSAGE_APP", options));
    }

    @Test
    void shouldAvoidDuplicatingSelectedTypeWhenItAlreadyExists() {
        List<String> options = PacketMonitorWindow.buildTypeFilterOptions(
                List.of("TEXT_MESSAGE_APP", "NODEINFO_APP"),
                "TEXT_MESSAGE_APP");

        assertEquals(List.of("Все типы", "TEXT_MESSAGE_APP", "NODEINFO_APP"), options);
    }

    @Test
    void shouldResolveLoraRouteFilterToDirection() {
        PacketMonitorWindow.RouteFilterSelection all =
                PacketMonitorWindow.resolveRouteFilterSelection("Все LoRa");
        PacketMonitorWindow.RouteFilterSelection incoming =
                PacketMonitorWindow.resolveRouteFilterSelection("Входящие");
        PacketMonitorWindow.RouteFilterSelection outgoing =
                PacketMonitorWindow.resolveRouteFilterSelection("Исходящие");

        assertEquals(null, all.direction());
        assertEquals(null, all.transportMechanism());
        assertEquals(PacketLogEntry.Direction.INCOMING, incoming.direction());
        assertEquals(null, incoming.transportMechanism());
        assertEquals(PacketLogEntry.Direction.OUTGOING, outgoing.direction());
        assertEquals(null, outgoing.transportMechanism());
    }

    @Test
    void shouldFormatExportProgressTextWithPercent() {
        assertEquals(
                "Экспорт: 250 / 1000 (25%)",
                PacketMonitorWindow.formatExportProgressText(250, 1000)
        );
    }

    @Test
    void shouldCenterWindowOnNearestScreenWhenItIsFullyOutsideVisibleArea() {
        Rectangle2D primary = new Rectangle2D(0, 0, 1920, 1080);
        Rectangle2D secondary = new Rectangle2D(1920, 0, 1920, 1080);

        PacketMonitorWindow.WindowBounds bounds = PacketMonitorWindow.normalizeWindowBounds(
                4100, 100, 1260, 860,
                List.of(primary, secondary),
                primary
        );

        assertEquals(2250.0, bounds.x());
        assertEquals(110.0, bounds.y());
        assertEquals(1260.0, bounds.width());
        assertEquals(860.0, bounds.height());
    }

    @Test
    void shouldClampWindowBackInsideIntersectingScreen() {
        Rectangle2D primary = new Rectangle2D(0, 0, 1920, 1080);

        PacketMonitorWindow.WindowBounds bounds = PacketMonitorWindow.normalizeWindowBounds(
                1700, 100, 500, 600,
                List.of(primary),
                primary
        );

        assertEquals(1420.0, bounds.x());
        assertEquals(100.0, bounds.y());
        assertEquals(500.0, bounds.width());
        assertEquals(600.0, bounds.height());
    }

    @Test
    void shouldShrinkWindowToFitFallbackScreenWhenSavedSizeIsTooLarge() {
        Rectangle2D primary = new Rectangle2D(0, 0, 1280, 720);

        PacketMonitorWindow.WindowBounds bounds = PacketMonitorWindow.normalizeWindowBounds(
                Double.NaN, Double.NaN, 2000, 1400,
                List.of(primary),
                primary
        );

        assertEquals(0.0, bounds.x());
        assertEquals(0.0, bounds.y());
        assertEquals(1280.0, bounds.width());
        assertEquals(720.0, bounds.height());
    }
}
