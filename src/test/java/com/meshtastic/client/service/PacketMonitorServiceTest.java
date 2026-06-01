package com.meshtastic.client.service;

import com.google.protobuf.ByteString;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.PacketLogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class PacketMonitorServiceTest {

    @TempDir
    Path tempHome;

    private PacketMonitorService service;

    @BeforeEach
    void setUp() {
        I18n.setLanguageTagForTests(I18n.LANGUAGE_RU);
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        service = PacketMonitorService.getInstance();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void recordPacketPersistsDecodedIncomingAndOutgoingPackets() {
        DeviceState state = deviceState();
        service.startCapture();

        MeshProtos.MeshPacket incoming = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setTo(0xFFFFFFFF)
                .setId(101)
                .setRxTime(1_710_000_000)
                .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8("hello mesh"))
                        .build())
                .build();

        MeshProtos.MeshPacket outgoing = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x12345678)
                .setTo(0x22222222)
                .setId(202)
                .setRxTime(1_710_000_010)
                .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.NODEINFO_APP)
                        .setPayload(MeshProtos.User.newBuilder()
                                .setLongName("Relay")
                                .setShortName("RLY")
                                .setId("!22222222")
                                .build()
                                .toByteString())
                        .build())
                .build();

        service.recordPacket(PacketLogEntry.Direction.INCOMING, incoming, "!12345678", state);
        service.recordPacket(PacketLogEntry.Direction.OUTGOING, outgoing, "!12345678", state);

        List<PacketLogEntry> entries = service.loadAll();

        assertEquals(2, entries.size());

        PacketLogEntry first = entries.getFirst();
        assertEquals(PacketLogEntry.Direction.OUTGOING, first.getDirection());
        assertEquals("NODEINFO_APP", first.getPacketType());
        assertEquals("Local Base (305419896)", first.getFromNode());
        assertEquals("Peer B (572662306)", first.getToNode());
        assertTrue(first.getPayloadText().contains("Relay"));
        assertArrayEquals(outgoing.toByteArray(), first.getPacketBytes());

        PacketLogEntry second = entries.getLast();
        assertEquals(PacketLogEntry.Direction.INCOMING, second.getDirection());
        assertEquals("TEXT_MESSAGE_APP", second.getPacketType());
        assertEquals("Peer A (286331153)", second.getFromNode());
        assertEquals("Вещание (4294967295)", second.getToNode());
        assertEquals("\"hello mesh\"", second.getPayloadText());
        assertEquals("TRANSPORT_LORA", second.getTransportMechanism());
        assertEquals("LoRa", second.getTransportText());
        assertEquals(1_710_000_000_000L, second.getCapturedAt());
        assertArrayEquals(incoming.toByteArray(), second.getPacketBytes());
    }

    @Test
    void recordRawPacketPersistsMeshCoreCompanionBytes() {
        service.startCapture();
        byte[] packet = new byte[]{0x08, 0x00, 0x01, 0x00, 0x11, 0x22};

        service.recordRawIncoming(
                "meshcore",
                "MeshCore Companion CHANNEL_MSG",
                "channel message",
                packet);

        List<PacketLogEntry> entries = service.loadAll();
        assertEquals(1, entries.size());
        PacketLogEntry entry = entries.getFirst();
        assertEquals(PacketLogEntry.Direction.INCOMING, entry.getDirection());
        assertEquals("MeshCore Companion CHANNEL_MSG", entry.getPacketType());
        assertEquals("MESHCORE_COMPANION", entry.getTransportMechanism());
        assertEquals("channel message", entry.getPayloadText());
        assertArrayEquals(packet, entry.getPacketBytes());
    }

    @Test
    void ignoresIncomingPacketFromLocalNodeWithoutRadio() {
        DeviceState state = deviceState();
        service.startCapture();

        MeshProtos.MeshPacket telemetry = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x12345678)
                .setTo(0xFFFFFFFF)
                .setId(303)
                .setRxTime(1_710_000_111)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TELEMETRY_APP)
                        .build())
                .build();

        service.recordPacket(PacketLogEntry.Direction.INCOMING, telemetry, "!12345678", state);

        assertTrue(service.loadAll().isEmpty());
    }

    @Test
    void reclassifiesIncomingPacketFromLocalNodeAsOutgoingWhenItArrivedOverLora() {
        DeviceState state = deviceState();
        service.startCapture();

        MeshProtos.MeshPacket position = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x12345678)
                .setTo(0xFFFFFFFF)
                .setId(305)
                .setRxTime(1_710_000_333)
                .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.POSITION_APP)
                        .build())
                .build();

        service.recordPacket(PacketLogEntry.Direction.INCOMING, position, "!12345678", state);

        List<PacketLogEntry> entries = service.loadAll();
        assertEquals(1, entries.size());
        assertEquals(PacketLogEntry.Direction.OUTGOING, entries.getFirst().getDirection());
        assertEquals("POSITION_APP", entries.getFirst().getPacketType());
    }

    @Test
    void ignoresIncomingPacketFromLocalNodeWhenMarkedInternalTransport() {
        DeviceState state = deviceState();
        service.startCapture();

        MeshProtos.MeshPacket telemetry = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x12345678)
                .setTo(0xFFFFFFFF)
                .setId(306)
                .setRxTime(1_710_000_444)
                .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_INTERNAL)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TELEMETRY_APP)
                        .build())
                .build();

        service.recordPacket(PacketLogEntry.Direction.INCOMING, telemetry, "!12345678", state);

        assertTrue(service.loadAll().isEmpty());
    }

    @Test
    void hidesLegacyIncomingRowsFromLocalNodeWithoutRadioOnServiceInit() throws Exception {
        MeshProtos.MeshPacket telemetry = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x12345678)
                .setTo(0xFFFFFFFF)
                .setId(304)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TELEMETRY_APP)
                        .build())
                .build();

        try (var ps = DatabaseProvider.getConnection().prepareStatement("""
                INSERT INTO lora_packet_logs (
                    owner_node_id, captured_at, direction, packet_type,
                    from_node, to_node, payload_text, packet_bytes
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, "!12345678");
            ps.setLong(2, 1_710_000_222_000L);
            ps.setString(3, PacketLogEntry.Direction.INCOMING.name());
            ps.setString(4, "TELEMETRY_APP");
            ps.setString(5, "Local Base (305419896)");
            ps.setString(6, "Вещание (4294967295)");
            ps.setString(7, "legacy telemetry");
            ps.setBytes(8, telemetry.toByteArray());
            ps.executeUpdate();
        }

        PacketMonitorService.closeIfInitialized();
        service = PacketMonitorService.getInstance();

        assertTrue(service.loadAll().isEmpty());
        assertEquals(0, service.countAllPackets());
    }

    @Test
    void normalizesLegacyIncomingRowsFromLocalNodeOverLoraToOutgoingOnServiceInit() throws Exception {
        MeshProtos.MeshPacket telemetry = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x12345678)
                .setTo(0xFFFFFFFF)
                .setId(305)
                .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TELEMETRY_APP)
                        .build())
                .build();

        try (var ps = DatabaseProvider.getConnection().prepareStatement("""
                INSERT INTO lora_packet_logs (
                    owner_node_id, captured_at, direction, packet_type,
                    transport_mechanism, from_node, to_node, payload_text, packet_bytes
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, "!12345678");
            ps.setLong(2, 1_710_000_333_000L);
            ps.setString(3, PacketLogEntry.Direction.INCOMING.name());
            ps.setString(4, "TELEMETRY_APP");
            ps.setString(5, "TRANSPORT_LORA");
            ps.setString(6, "Local Base (305419896)");
            ps.setString(7, "Вещание (4294967295)");
            ps.setString(8, "legacy lora telemetry");
            ps.setBytes(9, telemetry.toByteArray());
            ps.executeUpdate();
        }

        PacketMonitorService.closeIfInitialized();
        service = PacketMonitorService.getInstance();

        List<PacketLogEntry> entries = service.loadAll();
        assertEquals(1, entries.size());
        assertEquals(PacketLogEntry.Direction.OUTGOING, entries.getFirst().getDirection());
        assertEquals("TELEMETRY_APP", entries.getFirst().getPacketType());
    }

    @Test
    void backfillsLegacyTransportMechanismFromPacketBytesOnServiceInit() throws Exception {
        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setTo(0xFFFFFFFF)
                .setId(307)
                .setRxTime(1_710_000_555)
                .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8("legacy-transport"))
                        .build())
                .build();

        try (var ps = DatabaseProvider.getConnection().prepareStatement("""
                INSERT INTO lora_packet_logs (
                    owner_node_id, captured_at, direction, packet_type,
                    transport_mechanism, from_node, to_node, payload_text, packet_bytes
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, "!owner");
            ps.setLong(2, 1_710_000_555_000L);
            ps.setString(3, PacketLogEntry.Direction.INCOMING.name());
            ps.setString(4, "TEXT_MESSAGE_APP");
            ps.setString(5, "");
            ps.setString(6, "Peer A (286331153)");
            ps.setString(7, "Вещание (4294967295)");
            ps.setString(8, "\"legacy-transport\"");
            ps.setBytes(9, packet.toByteArray());
            ps.executeUpdate();
        }

        PacketMonitorService.closeIfInitialized();
        service = PacketMonitorService.getInstance();

        List<PacketLogEntry> entries = service.loadAll();
        assertEquals(1, entries.size());
        assertEquals("TRANSPORT_LORA", entries.getFirst().getTransportMechanism());
        assertEquals("LoRa", entries.getFirst().getTransportText());
    }

    @Test
    void clearRemovesPersistedPacketLogs() {
        service.startCapture();

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(1)
                .setTo(2)
                .setId(303)
                .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8("temp"))
                        .build())
                .build();

        service.recordPacket(PacketLogEntry.Direction.OUTGOING, packet, "!owner", null);
        assertEquals(1, service.loadAll().size());

        service.clear();

        assertTrue(service.loadAll().isEmpty());
    }

    @Test
    void storesOnlyLoraPackets() {
        service.startCapture();

        service.recordPacket(PacketLogEntry.Direction.INCOMING,
                textPacket(1, 1_710_000_100, "lora-only", MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA),
                "!owner", null);
        service.recordPacket(PacketLogEntry.Direction.INCOMING,
                textPacket(2, 1_710_000_101, "mqtt-only", MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_MQTT),
                "!owner", null);
        service.recordPacket(PacketLogEntry.Direction.INCOMING,
                textPacket(3, 1_710_000_102, "unspecified", null),
                "!owner", null);
        service.recordPacket(PacketLogEntry.Direction.OUTGOING,
                textPacket(4, 1_710_000_103, "outgoing-intent", null),
                "!owner", null);

        PacketMonitorService.PacketQuery loraQuery = new PacketMonitorService.PacketQuery(
                null, null, "TRANSPORT_LORA", null, null, null
        );
        PacketMonitorService.PacketPage loraPage = service.loadLatestPage(loraQuery, 200);
        assertEquals(2, loraPage.entries().size());
        assertEquals("\"outgoing-intent\"", loraPage.entries().getFirst().getPayloadText());
        assertEquals("\"lora-only\"", loraPage.entries().getLast().getPayloadText());

        PacketMonitorService.PacketQuery unspecifiedQuery = new PacketMonitorService.PacketQuery(
                null, null, PacketMonitorService.TRANSPORT_MECHANISM_UNSPECIFIED, null, null, null
        );
        PacketMonitorService.PacketPage unspecifiedPage = service.loadLatestPage(unspecifiedQuery, 200);
        assertTrue(unspecifiedPage.entries().isEmpty());

        PacketMonitorService.PacketQuery mqttQuery = new PacketMonitorService.PacketQuery(
                null, null, "TRANSPORT_MQTT", null, null, null
        );
        PacketMonitorService.PacketPage mqttPage = service.loadLatestPage(mqttQuery, 200);
        assertTrue(mqttPage.entries().isEmpty());
        assertEquals(2, service.countAllPackets());
    }

    @Test
    void loadAllAppliesPacketQueryFilters() {
        service.startCapture();

        service.recordPacket(PacketLogEntry.Direction.OUTGOING,
                textPacket(11, 1_710_000_201, "match-text", null),
                "!owner", null);
        service.recordPacket(PacketLogEntry.Direction.OUTGOING,
                MeshProtos.MeshPacket.newBuilder()
                        .setFrom(12)
                        .setTo(0xFFFFFFFF)
                        .setId(12)
                        .setDecoded(MeshProtos.Data.newBuilder()
                                .setPortnum(Portnums.PortNum.POSITION_APP)
                                .build())
                        .build(),
                "!owner", null);
        service.recordPacket(PacketLogEntry.Direction.INCOMING,
                textPacket(13, 1_710_000_203, "match-text", MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA),
                "!owner", null);

        PacketMonitorService.PacketQuery query = new PacketMonitorService.PacketQuery(
                PacketLogEntry.Direction.OUTGOING,
                "TEXT_MESSAGE_APP",
                null,
                "match-text",
                null,
                null
        );

        List<PacketLogEntry> entries = service.loadAll(query);

        assertEquals(1, entries.size());
        assertEquals(PacketLogEntry.Direction.OUTGOING, entries.getFirst().getDirection());
        assertEquals("TEXT_MESSAGE_APP", entries.getFirst().getPacketType());
        assertEquals("\"match-text\"", entries.getFirst().getPayloadText());
    }

    @Test
    void forEachMatchingBatchStreamsPacketsInFilteredBlocks() throws Exception {
        service.startCapture();

        service.recordPacket(PacketLogEntry.Direction.OUTGOING,
                textPacket(21, 1_710_000_301, "batch-1", null),
                "!owner", null);
        service.recordPacket(PacketLogEntry.Direction.OUTGOING,
                textPacket(22, 1_710_000_302, "batch-2", null),
                "!owner", null);
        service.recordPacket(PacketLogEntry.Direction.OUTGOING,
                textPacket(23, 1_710_000_303, "batch-3", null),
                "!owner", null);
        service.recordPacket(PacketLogEntry.Direction.INCOMING,
                textPacket(24, 1_710_000_304, "batch-ignored", MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA),
                "!owner", null);

        PacketMonitorService.PacketQuery query = new PacketMonitorService.PacketQuery(
                PacketLogEntry.Direction.OUTGOING,
                "TEXT_MESSAGE_APP",
                null,
                "batch-",
                null,
                null
        );

        List<Integer> batchSizes = new ArrayList<>();
        List<String> payloads = new ArrayList<>();
        long exported = service.forEachMatchingBatch(query, 2, batch -> {
            batchSizes.add(batch.size());
            for (PacketLogEntry entry : batch) {
                payloads.add(entry.getPayloadText());
            }
        });

        assertEquals(3L, exported);
        assertEquals(List.of(2, 1), batchSizes);
        assertEquals(List.of("\"batch-3\"", "\"batch-2\"", "\"batch-1\""), payloads);
    }

    @Test
    void backfillsLegacyOutgoingPacketsWithoutTransportAsLoraOnServiceInit() throws Exception {
        MeshProtos.MeshPacket outgoing = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x12345678)
                .setTo(0xFFFFFFFF)
                .setId(900)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8("legacy outgoing"))
                        .build())
                .build();

        try (var ps = DatabaseProvider.getConnection().prepareStatement("""
                INSERT INTO lora_packet_logs (
                    owner_node_id, captured_at, direction, packet_type,
                    transport_mechanism, from_node, to_node, payload_text, packet_bytes
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, "!12345678");
            ps.setLong(2, 1_710_000_900_000L);
            ps.setString(3, PacketLogEntry.Direction.OUTGOING.name());
            ps.setString(4, "TEXT_MESSAGE_APP");
            ps.setString(5, "");
            ps.setString(6, "Local Base (305419896)");
            ps.setString(7, "Вещание (4294967295)");
            ps.setString(8, "\"legacy outgoing\"");
            ps.setBytes(9, outgoing.toByteArray());
            ps.executeUpdate();
        }

        PacketMonitorService.closeIfInitialized();
        service = PacketMonitorService.getInstance();

        List<PacketLogEntry> entries = service.loadAll();
        assertEquals(1, entries.size());
        assertEquals(PacketLogEntry.Direction.OUTGOING, entries.getFirst().getDirection());
        assertEquals("TRANSPORT_LORA", entries.getFirst().getTransportMechanism());
        assertEquals("Исходящий / LoRa", entries.getFirst().getRouteText());
    }

    @Test
    void loadsPacketPagesBidirectionallyWithoutKeepingMoreThanWindowSize() {
        service.startCapture();

        for (int i = 1; i <= 260; i++) {
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(i)
                    .setTo(0xFFFFFFFF)
                    .setId(i)
                    .setRxTime(1_710_000_000 + i)
                    .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                            .setPayload(ByteString.copyFromUtf8("msg-" + i))
                            .build())
                    .build();

            service.recordPacket(PacketLogEntry.Direction.INCOMING, packet, "!owner", null);
        }

        PacketMonitorService.PacketQuery query = new PacketMonitorService.PacketQuery(null, null, null, null, null, null);
        PacketMonitorService.PacketPage latestPage = service.loadLatestPage(query, 200);

        assertEquals(200, latestPage.entries().size());
        assertFalse(latestPage.hasNewer());
        assertTrue(latestPage.hasOlder());
        assertEquals(260, latestPage.totalMatchingCount());
        assertEquals(260, latestPage.totalStoredCount());
        assertEquals("\"msg-260\"", latestPage.entries().getFirst().getPayloadText());
        assertEquals("\"msg-61\"", latestPage.entries().getLast().getPayloadText());

        PacketMonitorService.PacketPage olderPage = service.loadOlderPage(
                query,
                PacketMonitorService.PageCursor.fromEntry(latestPage.entries().getLast()),
                200
        );

        assertEquals(60, olderPage.entries().size());
        assertTrue(olderPage.hasNewer());
        assertFalse(olderPage.hasOlder());
        assertEquals("\"msg-60\"", olderPage.entries().getFirst().getPayloadText());
        assertEquals("\"msg-1\"", olderPage.entries().getLast().getPayloadText());

        PacketMonitorService.PacketPage newerPage = service.loadNewerPage(
                query,
                PacketMonitorService.PageCursor.fromEntry(olderPage.entries().getFirst()),
                200
        );

        assertEquals(200, newerPage.entries().size());
        assertFalse(newerPage.hasNewer());
        assertTrue(newerPage.hasOlder());
        assertEquals("\"msg-260\"", newerPage.entries().getFirst().getPayloadText());
        assertEquals("\"msg-61\"", newerPage.entries().getLast().getPayloadText());
    }

    @Test
    void loadsFixedFramesByOffset() {
        service.startCapture();

        for (int i = 1; i <= 430; i++) {
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(i)
                    .setTo(0xFFFFFFFF)
                    .setId(i)
                    .setRxTime(1_720_000_000 + i)
                    .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                            .setPayload(ByteString.copyFromUtf8("frame-" + i))
                            .build())
                    .build();
            service.recordPacket(PacketLogEntry.Direction.INCOMING, packet, "!owner", null);
        }

        PacketMonitorService.PacketQuery query = new PacketMonitorService.PacketQuery(null, null, null, null, null, null);

        PacketMonitorService.PacketPage firstFrame = service.loadPageFrame(query, 0, 200);
        assertFalse(firstFrame.hasNewer());
        assertTrue(firstFrame.hasOlder());
        assertEquals(200, firstFrame.entries().size());
        assertEquals("\"frame-430\"", firstFrame.entries().getFirst().getPayloadText());
        assertEquals("\"frame-231\"", firstFrame.entries().getLast().getPayloadText());

        PacketMonitorService.PacketPage secondFrame = service.loadPageFrame(query, 200, 200);
        assertTrue(secondFrame.hasNewer());
        assertTrue(secondFrame.hasOlder());
        assertEquals(200, secondFrame.entries().size());
        assertEquals("\"frame-230\"", secondFrame.entries().getFirst().getPayloadText());
        assertEquals("\"frame-31\"", secondFrame.entries().getLast().getPayloadText());

        PacketMonitorService.PacketPage lastFrame = service.loadPageFrame(query, 400, 200);
        assertTrue(lastFrame.hasNewer());
        assertFalse(lastFrame.hasOlder());
        assertEquals(30, lastFrame.entries().size());
        assertEquals("\"frame-30\"", lastFrame.entries().getFirst().getPayloadText());
        assertEquals("\"frame-1\"", lastFrame.entries().getLast().getPayloadText());
    }

    @Test
    void filtersPacketsByCapturedAtRange() {
        service.startCapture();

        service.recordPacket(PacketLogEntry.Direction.INCOMING,
                textPacket(1, toEpochSeconds(2024, 11, 11, 8, 0, 0), "range-1"), "!owner", null);
        service.recordPacket(PacketLogEntry.Direction.INCOMING,
                textPacket(2, toEpochSeconds(2024, 11, 11, 10, 30, 0), "range-2"), "!owner", null);
        service.recordPacket(PacketLogEntry.Direction.INCOMING,
                textPacket(3, toEpochSeconds(2024, 11, 11, 12, 30, 0), "range-3"), "!owner", null);

        long fromMillis = toEpochMillis(2024, 11, 11, 9, 0, 0);
        long toMillis = toEpochMillis(2024, 11, 11, 11, 0, 0);

        PacketMonitorService.PacketQuery query = new PacketMonitorService.PacketQuery(
                null,
                null,
                null,
                null,
                fromMillis,
                toMillis
        );

        PacketMonitorService.PacketPage page = service.loadLatestPage(query, 200);

        assertEquals(1, page.entries().size());
        assertEquals("\"range-2\"", page.entries().getFirst().getPayloadText());
        assertEquals(1, page.totalMatchingCount());
        assertEquals(3, page.totalStoredCount());
    }

    @Test
    void searchFindsPacketsByStandardNodeIdFormat() {
        DeviceState state = deviceState();
        service.startCapture();

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x11111111)
                .setTo(0xFFFFFFFF)
                .setId(404)
                .setRxTime(1_710_000_123)
                .setTransportMechanism(MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8("search-node"))
                        .build())
                .build();

        service.recordPacket(PacketLogEntry.Direction.INCOMING, packet, "!12345678", state);

        PacketMonitorService.PacketQuery fromQuery = new PacketMonitorService.PacketQuery(
                null,
                null,
                null,
                "!11111111",
                null,
                null
        );
        PacketMonitorService.PacketPage fromPage = service.loadLatestPage(fromQuery, 200);
        assertEquals(1, fromPage.entries().size());
        assertEquals("\"search-node\"", fromPage.entries().getFirst().getPayloadText());

        PacketMonitorService.PacketQuery broadcastQuery = new PacketMonitorService.PacketQuery(
                null,
                null,
                null,
                "!ffffffff",
                null,
                null
        );
        PacketMonitorService.PacketPage broadcastPage = service.loadLatestPage(broadcastQuery, 200);
        assertEquals(1, broadcastPage.entries().size());
        assertEquals("\"search-node\"", broadcastPage.entries().getFirst().getPayloadText());
    }

    @Test
    void clearingLoraLogsDoesNotDeleteChatMessages() {
        MessageDbService messageDbService = MessageDbService.getInstance();
        MeshMessage message = new MeshMessage("!11111111", "!ffffffff", 0, "chat survives", 1_710_000_000L, false);
        message.setPacketId(9001);
        messageDbService.save(message, "channel", "0", "!owner");

        service.startCapture();
        service.recordPacket(PacketLogEntry.Direction.INCOMING,
                textPacket(1, 1_710_000_100, "lora only"), "!owner", null);

        service.clear();

        assertTrue(service.loadAll().isEmpty());
        List<MeshMessage> messages = messageDbService.loadLast("channel", "0", 20, "!owner");
        assertEquals(1, messages.size());
        assertEquals("chat survives", messages.getFirst().getText());
    }

    private static MeshProtos.MeshPacket textPacket(int id, int rxTimeSeconds, String payload) {
        return textPacket(id, rxTimeSeconds, payload, MeshProtos.MeshPacket.TransportMechanism.TRANSPORT_LORA);
    }

    private static MeshProtos.MeshPacket textPacket(int id,
                                                    int rxTimeSeconds,
                                                    String payload,
                                                    MeshProtos.MeshPacket.TransportMechanism transportMechanism) {
        MeshProtos.MeshPacket.Builder builder = MeshProtos.MeshPacket.newBuilder()
                .setFrom(id)
                .setTo(0xFFFFFFFF)
                .setId(id)
                .setRxTime(rxTimeSeconds)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8(payload))
                        .build())
                ;
        if (transportMechanism != null) {
            builder.setTransportMechanism(transportMechanism);
        }
        return builder.build();
    }

    private static long toEpochMillis(int year, int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    private static int toEpochSeconds(int year, int month, int day, int hour, int minute, int second) {
        return (int) (toEpochMillis(year, month, day, hour, minute, second) / 1000);
    }

    private static DeviceState deviceState() {
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);

        NodeData me = state.getOrCreateNode(0x12345678);
        me.setLongName("Local Base");

        NodeData peerA = state.getOrCreateNode(0x11111111);
        peerA.setLongName("Peer A");

        NodeData peerB = state.getOrCreateNode(0x22222222);
        peerB.setLongName("Peer B");

        return state;
    }
}
