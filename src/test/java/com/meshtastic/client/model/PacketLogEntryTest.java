package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class PacketLogEntryTest {

    @Test
    void routeTextCombinesDirectionAndTransport() {
        PacketLogEntry incomingLora = new PacketLogEntry(
                "!owner",
                1_710_000_000_000L,
                PacketLogEntry.Direction.INCOMING,
                "TEXT_MESSAGE_APP",
                "TRANSPORT_LORA",
                "!from",
                "!to",
                "\"hello\"",
                new byte[0]
        );

        PacketLogEntry outgoingLora = new PacketLogEntry(
                "!owner",
                1_710_000_000_000L,
                PacketLogEntry.Direction.OUTGOING,
                "TEXT_MESSAGE_APP",
                "TRANSPORT_LORA",
                "!from",
                "!to",
                "\"hello\"",
                new byte[0]
        );

        PacketLogEntry outgoingAlt = new PacketLogEntry(
                "!owner",
                1_710_000_000_000L,
                PacketLogEntry.Direction.OUTGOING,
                "TELEMETRY_APP",
                "TRANSPORT_LORA_ALT2",
                "!from",
                "!to",
                "\"hello\"",
                new byte[0]
        );

        assertEquals("Входящий / LoRa", incomingLora.getRouteText());
        assertEquals("Исходящий / LoRa", outgoingLora.getRouteText());
        assertEquals("Исходящий / LoRa alt 2", outgoingAlt.getRouteText());
    }
}
