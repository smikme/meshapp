package com.meshtastic.client.protocol.rpc;

import com.google.gson.JsonObject;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.service.PacketMonitorService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class RemotePacketMonitorJsonTest {

    @Test
    void roundTripsPacketPageAndEventEntry() {
        PacketLogEntry entry = new PacketLogEntry(
                "!owner",
                1_700_000_000_123L,
                PacketLogEntry.Direction.INCOMING,
                "TEXT_MESSAGE_APP",
                "TRANSPORT_LORA",
                "!11111111",
                "!ffffffff",
                "hello",
                new byte[] {1, 2, 3});
        entry.setId(42);

        PacketMonitorService.PacketPage page = new PacketMonitorService.PacketPage(
                List.of(entry),
                true,
                false,
                5,
                9);

        PacketMonitorService.PacketPage parsedPage =
                RemotePacketMonitorJson.parsePage(RemotePacketMonitorJson.pageToJson(page));
        PacketLogEntry parsedEntry = parsedPage.entries().getFirst();

        assertTrue(parsedPage.hasNewer());
        assertFalse(parsedPage.hasOlder());
        assertEquals(5, parsedPage.totalMatchingCount());
        assertEquals(9, parsedPage.totalStoredCount());
        assertEquals(42, parsedEntry.getId());
        assertEquals("!owner", parsedEntry.getOwnerNodeId());
        assertEquals(1_700_000_000_123L, parsedEntry.getCapturedAt());
        assertEquals(PacketLogEntry.Direction.INCOMING, parsedEntry.getDirection());
        assertEquals("TEXT_MESSAGE_APP", parsedEntry.getPacketType());
        assertEquals("TRANSPORT_LORA", parsedEntry.getTransportMechanism());
        assertEquals("!11111111", parsedEntry.getFromNode());
        assertEquals("!ffffffff", parsedEntry.getToNode());
        assertEquals("hello", parsedEntry.getPayloadText());
        assertArrayEquals(new byte[] {1, 2, 3}, parsedEntry.getPacketBytes());

        PacketLogEntry eventEntry = RemotePacketMonitorJson.parseEventEntry(
                RemotePacketMonitorJson.entryEvent(entry));
        assertEquals(42, eventEntry.getId());
        assertArrayEquals(new byte[] {1, 2, 3}, eventEntry.getPacketBytes());
    }

    @Test
    void roundTripsQueryAndCursorParams() {
        PacketMonitorService.PacketQuery query = new PacketMonitorService.PacketQuery(
                PacketLogEntry.Direction.OUTGOING,
                "POSITION_APP",
                "TRANSPORT_LORA_ALT1",
                "!12345678",
                100L,
                200L);
        PacketMonitorService.PageCursor cursor = new PacketMonitorService.PageCursor(150L, 77L);

        JsonObject params = RemotePacketMonitorJson.pageParams("older", query, cursor, 25);
        PacketMonitorService.PacketQuery parsedQuery = RemotePacketMonitorJson.parseQuery(params.get("query"));
        PacketMonitorService.PageCursor parsedCursor = RemotePacketMonitorJson.parseCursor(params.get("cursor"));

        assertEquals("older", params.get("request").getAsString());
        assertEquals(25, params.get("limit").getAsInt());
        assertEquals(PacketLogEntry.Direction.OUTGOING, parsedQuery.direction());
        assertEquals("POSITION_APP", parsedQuery.packetType());
        assertEquals("TRANSPORT_LORA_ALT1", parsedQuery.transportMechanism());
        assertEquals("!12345678", parsedQuery.searchText());
        assertEquals(100L, parsedQuery.capturedAtFromMillis());
        assertEquals(200L, parsedQuery.capturedAtToMillis());
        assertEquals(150L, parsedCursor.capturedAt());
        assertEquals(77L, parsedCursor.id());
    }
}
