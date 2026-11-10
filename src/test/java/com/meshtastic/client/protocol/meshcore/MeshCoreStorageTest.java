package com.meshtastic.client.protocol.meshcore;

import static org.junit.jupiter.api.Assertions.*;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.service.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

// H2 schema is created at runtime and covered by database integration tests.
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
class MeshCoreStorageTest {
    @TempDir Path home;

    @BeforeEach
    void setup() {
        TestEnvironmentSupport.setUserHome(home);
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void cleanup() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void wideningPreservesExistingRowsAndKeys() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:h2:mem:meshcore-migration")) {
            try (var s = db.createStatement()) {
                s.execute(
                        "CREATE TABLE chat_threads(owner_node_id VARCHAR(20),chat_type VARCHAR(10),chat_key VARCHAR(20),PRIMARY KEY(owner_node_id,chat_type,chat_key))");
                s.execute(
                        "INSERT INTO chat_threads VALUES('mc:111111111111','dm','mc:222222222222')");
            }
            DatabaseMigrator.widenMeshCoreIdentifiers(db);
            try (var s =
                    db.prepareStatement("UPDATE chat_threads SET owner_node_id=?,chat_key=? WHERE owner_node_id='mc:111111111111' AND chat_type='dm' AND chat_key='mc:222222222222'")) {
                s.setString(1, "mc:" + "11".repeat(32));
                s.setString(2, "mc:" + "22".repeat(32));
                assertEquals(1, s.executeUpdate());
            }
            try (var s = db.createStatement();
                    var rows = s.executeQuery("SELECT owner_node_id FROM chat_threads")) {
                assertTrue(rows.next());
                assertEquals(67, rows.getString(1).length());
            }
        }
    }

    @Test
    void aliasesAndStatusUpdatesRemainScopedToOwner() throws Exception {
        var messages = MessageDbService.getInstance();
        var storage = new MeshCoreStorage();
        String owner = "mc:" + "aa".repeat(32),
                other = "mc:" + "cc".repeat(32),
                peer = "mc:" + "bb".repeat(32),
                shortPeer = peer.substring(0, 15);
        MeshMessage a = new MeshMessage(owner, shortPeer, 0, "first", 1, true);
        a.setPacketId(12345);
        a.setStatus(MeshMessage.DeliveryStatus.SENDING);
        messages.save(a, "dm", shortPeer, owner);
        MeshMessage b = new MeshMessage(other, shortPeer, 0, "second", 2, true);
        b.setPacketId(12345);
        b.setStatus(MeshMessage.DeliveryStatus.SENDING);
        messages.save(b, "dm", shortPeer, other);
        storage.bindAlias(owner, peer, false);
        messages.updateStatusById(a.getDbId(), owner, MeshMessage.DeliveryStatus.CONFIRMED, null);
        assertThrows(
                IllegalStateException.class,
                () ->
                        messages.updateStatusById(
                                b.getDbId(), owner, MeshMessage.DeliveryStatus.CONFIRMED, null));
        try (var s =
                DatabaseProvider.getConnection()
                        .prepareStatement("SELECT to_node_id,status FROM messages WHERE id=?")) {
            s.setLong(1, a.getDbId());
            try (var r = s.executeQuery()) {
                r.next();
                assertEquals(peer, r.getString(1));
                assertEquals("CONFIRMED", r.getString(2));
            }
            s.setLong(1, b.getDbId());
            try (var r = s.executeQuery()) {
                r.next();
                assertEquals(shortPeer, r.getString(1));
                assertEquals("SENDING", r.getString(2));
            }
        }
    }

    @Test
    void identifiersAreStableAcrossStorageInstancesAndIsolatedByOwner() {
        var first = new MeshCoreStorage();
        int a = first.messageId("mc:one", "in:one");
        int b = first.messageId("mc:two", "in:one");
        assertTrue(a > 0);
        assertNotEquals(a, b);
        assertEquals(a, new MeshCoreStorage().messageId("mc:one", "in:one"));
    }
}
