package com.meshtastic.client.service;

import com.google.protobuf.ByteString;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.PacketLogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketMonitorServiceTest {

    @TempDir
    Path tempHome;

    private PacketMonitorService service;

    @BeforeEach
    void setUp() {
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
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8("hello mesh"))
                        .build())
                .build();

        MeshProtos.MeshPacket outgoing = MeshProtos.MeshPacket.newBuilder()
                .setFrom(0x12345678)
                .setTo(0x22222222)
                .setId(202)
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
        assertEquals("Local Base (!12345678)", first.getFromNode());
        assertEquals("Peer B (!22222222)", first.getToNode());
        assertTrue(first.getPayloadText().contains("Relay"));
        assertArrayEquals(outgoing.toByteArray(), first.getPacketBytes());

        PacketLogEntry second = entries.getLast();
        assertEquals(PacketLogEntry.Direction.INCOMING, second.getDirection());
        assertEquals("TEXT_MESSAGE_APP", second.getPacketType());
        assertEquals("Peer A (!11111111)", second.getFromNode());
        assertEquals("Вещание (!ffffffff)", second.getToNode());
        assertEquals("\"hello mesh\"", second.getPayloadText());
        assertEquals(1_710_000_000_000L, second.getCapturedAt());
        assertArrayEquals(incoming.toByteArray(), second.getPacketBytes());
    }

    @Test
    void clearRemovesPersistedPacketLogs() {
        service.startCapture();

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(1)
                .setTo(2)
                .setId(303)
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
