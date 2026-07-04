package com.meshtastic.client.components;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.service.PacketMonitorService;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class PacketMonitorWindowTest {

    @BeforeEach
    void setLanguage() {
        I18n.setLanguageTagForTests(I18n.LANGUAGE_RU);
    }

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
        assertTrue(all.loraOnly());
        assertEquals(PacketLogEntry.Direction.INCOMING, incoming.direction());
        assertEquals(null, incoming.transportMechanism());
        assertTrue(incoming.loraOnly());
        assertEquals(PacketLogEntry.Direction.OUTGOING, outgoing.direction());
        assertEquals(null, outgoing.transportMechanism());
        assertTrue(outgoing.loraOnly());

        assertTrue(all.matches(packet(PacketLogEntry.Direction.INCOMING, "TRANSPORT_LORA")));
        assertTrue(all.matches(packet(PacketLogEntry.Direction.OUTGOING, "MESHCORE_COMPANION")));
        assertFalse(all.matches(packet(PacketLogEntry.Direction.INCOMING, PacketMonitorService.TRANSPORT_MQTT)));
        assertFalse(incoming.matches(packet(PacketLogEntry.Direction.OUTGOING, "TRANSPORT_LORA")));
        assertTrue(outgoing.matches(packet(PacketLogEntry.Direction.OUTGOING, "TRANSPORT_LORA")));
    }

    @Test
    void shouldResolveMqttRouteFiltersToTransportAndDirection() {
        PacketMonitorWindow.RouteFilterSelection all =
                PacketMonitorWindow.resolveRouteFilterSelection("Все MQTT");
        PacketMonitorWindow.RouteFilterSelection legacyAll =
                PacketMonitorWindow.resolveRouteFilterSelection("Все MQTT (входящие/исходящие)");
        PacketMonitorWindow.RouteFilterSelection incoming =
                PacketMonitorWindow.resolveRouteFilterSelection("Входящие MQTT");
        PacketMonitorWindow.RouteFilterSelection outgoing =
                PacketMonitorWindow.resolveRouteFilterSelection("Исходящие MQTT");

        assertEquals(null, all.direction());
        assertEquals(PacketMonitorService.TRANSPORT_MQTT, all.transportMechanism());
        assertFalse(all.loraOnly());
        assertEquals(PacketMonitorService.TRANSPORT_MQTT, legacyAll.transportMechanism());
        assertEquals(PacketLogEntry.Direction.INCOMING, incoming.direction());
        assertEquals(PacketMonitorService.TRANSPORT_MQTT, incoming.transportMechanism());
        assertEquals(PacketLogEntry.Direction.OUTGOING, outgoing.direction());
        assertEquals(PacketMonitorService.TRANSPORT_MQTT, outgoing.transportMechanism());

        assertTrue(all.matches(packet(PacketLogEntry.Direction.INCOMING, PacketMonitorService.TRANSPORT_MQTT)));
        assertTrue(all.matches(packet(PacketLogEntry.Direction.OUTGOING, PacketMonitorService.TRANSPORT_MQTT)));
        assertFalse(all.matches(packet(PacketLogEntry.Direction.INCOMING, "TRANSPORT_LORA")));
        assertTrue(incoming.matches(packet(PacketLogEntry.Direction.INCOMING, PacketMonitorService.TRANSPORT_MQTT)));
        assertFalse(incoming.matches(packet(PacketLogEntry.Direction.OUTGOING, PacketMonitorService.TRANSPORT_MQTT)));
        assertTrue(outgoing.matches(packet(PacketLogEntry.Direction.OUTGOING, PacketMonitorService.TRANSPORT_MQTT)));
    }

    @Test
    void shouldBuildSeparateLoraAndMqttRouteOptions() {
        assertEquals(
                List.of(
                        "Все LoRa",
                        "Входящие",
                        "Исходящие",
                        "Все MQTT",
                        "Входящие MQTT",
                        "Исходящие MQTT"),
                PacketMonitorWindow.buildRouteFilterOptions());
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

    private static PacketLogEntry packet(PacketLogEntry.Direction direction, String transportMechanism) {
        return new PacketLogEntry(
                "!owner",
                1_700_000_000_000L,
                direction,
                "TEXT_MESSAGE_APP",
                transportMechanism,
                "!11111111",
                "!ffffffff",
                "payload",
                new byte[]{1});
    }
}
