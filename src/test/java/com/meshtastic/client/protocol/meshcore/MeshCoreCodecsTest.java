package com.meshtastic.client.protocol.meshcore;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class MeshCoreCodecsTest {
    @Test
    void messageTextAndNamesKeepWhitespace() {
        assertEquals("  ", MeshCoreCompanionFrames.text(new byte[] {8, 32, 32}, 1));
        assertEquals("", MeshCoreCompanionFrames.text(new byte[] {8}, 1));
        assertEquals(
                " x ",
                MeshCoreCompanionFrames.nullTerminatedText(new byte[] {32, 120, 32, 0}, 0, 4));
    }

    @Test
    void channelKeysAndOfficialSharingLinksAreCompatible() {
        assertEquals(
                "8b3387e9c5cdea6ac9e5edbaa115cd72",
                HexFormat.of().formatHex(MeshCoreCommands.channelKey("Public", null)));
        assertEquals(
                "9cd8fcf22a47333b591d96a2b848b73f",
                HexFormat.of().formatHex(MeshCoreCommands.channelKey("#test", null)));
        var channel =
                MeshCoreSharing.channelFromLink(
                        2,
                        "meshcore://channel/add?name=Public&secret=8b3387e9c5cdea6ac9e5edbaa115cd72");
        assertEquals("Public", channel.name());
        assertEquals(2, channel.index());
        assertEquals(
                "meshcore://channel/add?name=Public&secret=8b3387e9c5cdea6ac9e5edbaa115cd72",
                MeshCoreSharing.channelLink(channel));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MeshCoreSharing.channelFromLink(
                                0, "meshcore://channel/add?name=x&secret=00"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MeshCoreSharing.channelFromLink(
                                0, MeshCoreSharing.channelLink(channel) + "&secret=00"));
    }

    @Test
    void contactLinkKeepsFullKeyAndUnicodeName() {
        String key = "11".repeat(32);
        var c =
                MeshCoreSharing.contactFromLink(
                        "meshcore://contact/add?name=%D0%A3%D0%B7%D0%B5%D0%BB+%F0%9F%93%A1&public_key="
                                + key
                                + "&type=2");
        assertEquals("mc:" + key, c.id());
        assertEquals("Узел 📡", c.name());
        assertEquals(255, c.encodedPathLength());
        assertEquals(2, c.type());
    }

    @Test
    void payloadEncodersUseFirmwareUnitsAndLittleEndian() {
        assertArrayEquals(
                new byte[] {11, 0x50, 0x45, 0x0d, 0, 0x24, (byte) 0xf4, 0, 0, 7, 5, 1},
                MeshCoreCommands.radio(869.712, 62.5, 7, 5, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> MeshCoreCommands.channel(0, "я".repeat(16), new byte[16]));
        assertThrows(
                IllegalArgumentException.class, () -> MeshCoreCommands.position(Double.NaN, 0));
    }

    @Test
    void telemetryDecodesBigEndianSignedAndMultiValueReadings() {
        byte[] lpp = HexFormat.of().parseHex("0174019f0267ff9c0368640488002710004e20000064");
        var readings = MeshCoreTelemetry.decode(lpp);
        assertEquals(4, readings.size());
        assertEquals(List.of(4.15), readings.get(0).values());
        assertEquals(List.of(-10.0), readings.get(1).values());
        assertEquals(List.of(50.0), readings.get(2).values());
        assertEquals(List.of(1.0, 2.0, 1.0), readings.get(3).values());
        assertThrows(
                IllegalArgumentException.class,
                () -> MeshCoreTelemetry.decode(new byte[] {1, 116, 0}));
        assertArrayEquals(
                new byte[] {1, 2, 3},
                MeshCoreTelemetry.decode(new byte[] {1, (byte) 250, 1, 2, 3}).getFirst().raw());
    }
}
