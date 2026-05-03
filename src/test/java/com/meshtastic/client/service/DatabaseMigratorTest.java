package com.meshtastic.client.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class DatabaseMigratorTest {

    @TempDir
    Path tempDir;
    private String originalUserHome;

    @BeforeEach
    void isolateUserHome() throws Exception {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        Files.createDirectories(tempDir.resolve(".meshapp"));
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void migrateWithoutSchemaVersionDropsLegacyObjectsAndSetsCurrentVersion() throws Exception {
        try (Connection connection = openConnection("legacy-reset")) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE legacy_table (id INT PRIMARY KEY)");
                stmt.execute("INSERT INTO legacy_table (id) VALUES (1)");
            }

            DatabaseMigrator.migrate(connection);

            assertTrue(tableExists(connection, "SCHEMA_VERSION"));
            assertFalse(tableExists(connection, "LEGACY_TABLE"));
            assertEquals(DatabaseMigrator.CURRENT_VERSION, schemaVersion(connection));
        }
    }

    @Test
    void migrateFromV2AddsOwnerIsolationAndIgnoredColumnWithoutDeletingHistory() throws Exception {
        try (Connection connection = openConnection("upgrade-v2")) {
            createSchemaVersion(connection, 2);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                        CREATE TABLE messages (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            chat_type VARCHAR(10) NOT NULL,
                            chat_key VARCHAR(20) NOT NULL,
                            from_node_id VARCHAR(20) NOT NULL,
                            to_node_id VARCHAR(20) NOT NULL,
                            channel_idx INT NOT NULL,
                            text CLOB,
                            timestamp BIGINT NOT NULL,
                            outgoing BOOLEAN NOT NULL,
                            packet_id INT DEFAULT 0,
                            status VARCHAR(20),
                            error_reason VARCHAR(100),
                            reply_id INT DEFAULT 0,
                            reply_text CLOB,
                            hop_start INT DEFAULT 0,
                            hop_limit INT DEFAULT 0,
                            sender_name VARCHAR(100),
                            system_msg BOOLEAN DEFAULT FALSE,
                            rx_rssi INT DEFAULT 0,
                            rx_snr REAL DEFAULT 0
                        )
                        """);
                stmt.execute("CREATE INDEX idx_msg_chat ON messages (chat_type, chat_key, id)");
                stmt.execute("INSERT INTO messages (chat_type, chat_key, from_node_id, to_node_id, channel_idx, text, timestamp, outgoing) VALUES ('channel', '0', '!1', '!ffffffff', 0, 'hello', 1, FALSE)");

                stmt.execute("""
                        CREATE TABLE chat_read_counts (
                            chat_type VARCHAR(10) NOT NULL,
                            chat_key VARCHAR(20) NOT NULL,
                            read_count INT NOT NULL DEFAULT 0,
                            PRIMARY KEY (chat_type, chat_key)
                        )
                        """);
                stmt.execute("INSERT INTO chat_read_counts (chat_type, chat_key, read_count) VALUES ('channel', '0', 3)");

                stmt.execute("""
                        CREATE TABLE telemetry_history (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            ts BIGINT NOT NULL,
                            node_id VARCHAR(20) NOT NULL
                        )
                        """);
                stmt.execute("CREATE INDEX idx_telemetry_node_ts ON telemetry_history (node_id, ts)");
                stmt.execute("INSERT INTO telemetry_history (ts, node_id) VALUES (100, '!abc')");

                stmt.execute("""
                        CREATE TABLE nodes (
                            node_id VARCHAR(20) PRIMARY KEY,
                            node_num INT,
                            long_name VARCHAR(100),
                            short_name VARCHAR(10),
                            role VARCHAR(30),
                            hw_model VARCHAR(50),
                            latitude DOUBLE,
                            longitude DOUBLE,
                            altitude INT,
                            snr REAL,
                            last_heard INT,
                            battery_level INT,
                            voltage REAL,
                            hops_away INT
                        )
                        """);
            }

            DatabaseMigrator.migrate(connection);

            assertEquals(DatabaseMigrator.CURRENT_VERSION, schemaVersion(connection));
            assertTrue(columnExists(connection, "MESSAGES", "OWNER_NODE_ID"));
            assertTrue(columnExists(connection, "CHAT_READ_COUNTS", "OWNER_NODE_ID"));
            assertTrue(columnExists(connection, "TELEMETRY_HISTORY", "OWNER_NODE_ID"));
            assertTrue(columnExists(connection, "NODES", "IGNORED"));

            assertEquals(1, countRows(connection, "messages"));
            assertEquals(1, countRows(connection, "chat_read_counts"));
            assertEquals(1, countRows(connection, "telemetry_history"));
            assertEquals("", firstOwnerNodeId(connection, "messages"));
            assertEquals("", firstOwnerNodeId(connection, "chat_read_counts"));
            assertEquals("", firstOwnerNodeId(connection, "telemetry_history"));
        }
    }

    @Test
    void migrateFromV5CreatesMessageReactionsTable() throws Exception {
        try (Connection connection = openConnection("upgrade-v5")) {
            createSchemaVersion(connection, 5);

            DatabaseMigrator.migrate(connection);

            assertEquals(DatabaseMigrator.CURRENT_VERSION, schemaVersion(connection));
            assertTrue(tableExists(connection, "MESSAGE_REACTIONS"));
            assertTrue(columnExists(connection, "MESSAGE_REACTIONS", "TARGET_PACKET_ID"));
            assertTrue(indexExists(connection, "IDX_REACTION_CHAT_TARGET"));
            assertTrue(indexExists(connection, "IDX_REACTION_PACKET"));
        }
    }

    @Test
    void migrateFromV6NormalizesLegacyZeroHopCacheValues() throws Exception {
        try (Connection connection = openConnection("upgrade-v6")) {
            createSchemaVersion(connection, 6);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                        CREATE TABLE nodes (
                            node_id VARCHAR(20) PRIMARY KEY,
                            node_num INT,
                            hops_away INT
                        )
                        """);
                stmt.execute("INSERT INTO nodes (node_id, node_num, hops_away) VALUES ('!direct?', 1, 0)");
                stmt.execute("INSERT INTO nodes (node_id, node_num, hops_away) VALUES ('!relay', 2, 2)");
                stmt.execute("INSERT INTO nodes (node_id, node_num, hops_away) VALUES ('!unknown', 3, NULL)");
            }

            DatabaseMigrator.migrate(connection);

            assertEquals(DatabaseMigrator.CURRENT_VERSION, schemaVersion(connection));
            assertTrue(hopsAwayIsNull(connection, "!direct?"));
            assertEquals(2, hopsAwayValue(connection, "!relay"));
            assertTrue(hopsAwayIsNull(connection, "!unknown"));
        }
    }

    @Test
    void migrateFromV7CreatesLoraPacketMonitorTable() throws Exception {
        try (Connection connection = openConnection("upgrade-v7")) {
            createSchemaVersion(connection, 7);

            DatabaseMigrator.migrate(connection);

            assertEquals(DatabaseMigrator.CURRENT_VERSION, schemaVersion(connection));
            assertTrue(tableExists(connection, "LORA_PACKET_LOGS"));
            assertTrue(columnExists(connection, "LORA_PACKET_LOGS", "PACKET_BYTES"));
            assertTrue(columnExists(connection, "LORA_PACKET_LOGS", "TRANSPORT_MECHANISM"));
            assertTrue(indexExists(connection, "IDX_LORA_OWNER_TS"));
            assertTrue(indexExists(connection, "IDX_LORA_TYPE"));
            assertTrue(indexExists(connection, "IDX_LORA_TRANSPORT"));
        }
    }

    @Test
    void migrateFromV8AddsTransportMechanismToLoraPacketMonitorTable() throws Exception {
        try (Connection connection = openConnection("upgrade-v8")) {
            createSchemaVersion(connection, 8);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                        CREATE TABLE lora_packet_logs (
                            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                            owner_node_id VARCHAR(20) NOT NULL DEFAULT '',
                            captured_at   BIGINT NOT NULL,
                            direction     VARCHAR(10) NOT NULL,
                            packet_type   VARCHAR(40) NOT NULL,
                            from_node     VARCHAR(160),
                            to_node       VARCHAR(160),
                            payload_text  CLOB,
                            packet_bytes  BLOB NOT NULL
                        )
                        """);
                stmt.execute("""
                        CREATE INDEX idx_lora_owner_ts
                        ON lora_packet_logs (owner_node_id, captured_at)
                        """);
                stmt.execute("""
                        CREATE INDEX idx_lora_type
                        ON lora_packet_logs (packet_type)
                        """);
            }

            DatabaseMigrator.migrate(connection);

            assertEquals(DatabaseMigrator.CURRENT_VERSION, schemaVersion(connection));
            assertTrue(columnExists(connection, "LORA_PACKET_LOGS", "TRANSPORT_MECHANISM"));
            assertTrue(indexExists(connection, "IDX_LORA_TRANSPORT"));
        }
    }

    @Test
    void migrateFromV9AddsViaMqttToMessages() throws Exception {
        try (Connection connection = openConnection("upgrade-v9")) {
            createSchemaVersion(connection, 9);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                        CREATE TABLE messages (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            text CLOB
                        )
                        """);
                stmt.execute("INSERT INTO messages (text) VALUES ('legacy')");
            }

            DatabaseMigrator.migrate(connection);

            assertEquals(DatabaseMigrator.CURRENT_VERSION, schemaVersion(connection));
            assertTrue(columnExists(connection, "MESSAGES", "VIA_MQTT"));
            assertFalse(firstMessageViaMqtt(connection));
        }
    }

    @Test
    void migrateLegacyAppDatabaseWithoutSchemaVersionPreservesMessages() throws Exception {
        try (Connection connection = openConnection("legacy-app-preserve")) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                        CREATE TABLE messages (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            chat_type VARCHAR(10) NOT NULL,
                            chat_key VARCHAR(20) NOT NULL,
                            from_node_id VARCHAR(20) NOT NULL,
                            to_node_id VARCHAR(20) NOT NULL,
                            channel_idx INT NOT NULL,
                            text CLOB,
                            timestamp BIGINT NOT NULL,
                            outgoing BOOLEAN NOT NULL
                        )
                        """);
                stmt.execute("INSERT INTO messages (chat_type, chat_key, from_node_id, to_node_id, channel_idx, text, timestamp, outgoing) VALUES ('channel', '0', '!1', '!ffffffff', 0, 'legacy hello', 1, FALSE)");
            }

            DatabaseMigrator.migrate(connection);

            assertEquals(DatabaseMigrator.CURRENT_VERSION, schemaVersion(connection));
            assertTrue(columnExists(connection, "MESSAGES", "OWNER_NODE_ID"));
            assertEquals(1, countRows(connection, "messages"));
            assertEquals("legacy hello", firstMessageText(connection));
        }
    }

    private Connection openConnection(String name) throws SQLException {
        return DriverManager.getConnection("jdbc:h2:" + tempDir.resolve(name) + ";AUTO_SERVER=FALSE;TRACE_LEVEL_FILE=0");
    }

    private static void createSchemaVersion(Connection connection, int version) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE schema_version (version INT NOT NULL)");
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO schema_version (version) VALUES (?)")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    private static int schemaVersion(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            return rs.next();
        }
    }

    private static int countRows(Connection connection, String tableName) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean indexExists(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME = ?")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean hopsAwayIsNull(Connection connection, String nodeId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT hops_away FROM nodes WHERE node_id = ?")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                rs.getInt(1);
                return rs.wasNull();
            }
        }
    }

    private static int hopsAwayValue(Connection connection, String nodeId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT hops_away FROM nodes WHERE node_id = ?")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static boolean firstMessageViaMqtt(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT via_mqtt FROM messages ORDER BY id LIMIT 1")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static String firstOwnerNodeId(Connection connection, String tableName) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT owner_node_id FROM " + tableName + " ORDER BY 1 LIMIT 1")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String firstMessageText(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT text FROM messages ORDER BY id LIMIT 1")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
