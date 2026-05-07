package com.meshtastic.client.service;

import com.google.protobuf.ByteString;
import com.meshtastic.client.TestEnvironmentSupport;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class DatabaseResetServiceTest {

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void resetAllDataDropsPersistedContentAndRecreatesSchema() throws Exception {
        MessageDbService messageDbService = MessageDbService.getInstance();
        NodeCacheService nodeCacheService = NodeCacheService.getInstance();
        PacketMonitorService packetMonitorService = PacketMonitorService.getInstance();

        messageDbService.save(message("before-reset", 101), "channel", "0", "!owner");

        NodeData node = new NodeData(0x12345678);
        node.setNodeId("!12345678");
        node.setLongName("Reset me");
        nodeCacheService.update(node);

        packetMonitorService.startCapture();
        packetMonitorService.recordPacket(PacketLogEntry.Direction.INCOMING, samplePacket(202, "before-reset"), "!owner", null);

        DatabaseResetService.resetAllData();

        assertTrue(messageDbService.loadLast("channel", "0", 10, "!owner").isEmpty());
        assertEquals(0, nodeCacheService.countNodesInDb());
        assertEquals(0, packetMonitorService.countAllPackets());

        Connection connection = DatabaseProvider.getConnection();
        assertTrue(tableExists(connection, "SCHEMA_VERSION"));
        assertTrue(tableExists(connection, "MESSAGES"));
        assertTrue(tableExists(connection, "NODES"));
        assertTrue(tableExists(connection, "TELEMETRY_HISTORY"));
        assertTrue(tableExists(connection, "LORA_PACKET_LOGS"));

        messageDbService.save(message("after-reset", 303), "channel", "0", "!owner");
        assertEquals(List.of("after-reset"),
                messageDbService.loadLast("channel", "0", 10, "!owner")
                        .stream()
                        .map(MeshMessage::getText)
                        .toList());
    }

    private static MeshMessage message(String text, int packetId) {
        MeshMessage message = new MeshMessage("!00000001", "!ffffffff", 0, text, 1, false);
        message.setPacketId(packetId);
        return message;
    }

    private static MeshProtos.MeshPacket samplePacket(int id, String payload) {
        return MeshProtos.MeshPacket.newBuilder()
                .setFrom(1)
                .setTo(0xFFFFFFFF)
                .setId(id)
                .setRxTime(1_730_000_000)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                        .setPayload(ByteString.copyFromUtf8(payload))
                        .build())
                .build();
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }
}
