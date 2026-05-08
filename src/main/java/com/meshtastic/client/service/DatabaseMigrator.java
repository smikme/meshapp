package com.meshtastic.client.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Централизованное версионирование и миграция схемы H2 БД.
 * <p>
 * Хранит номер версии схемы в таблице {@code schema_version} (одна строка, одно число).
 * При запуске проверяет текущую версию и выполняет необходимые миграции последовательно.
 * <p>
 * Если таблица {@code schema_version} отсутствует — БД считается устаревшей.
 * Известные таблицы приложения мигрируются с сохранением данных, чужие legacy-объекты
 * удаляются ({@code DROP ALL OBJECTS}) и схема создаётся с нуля.
 * <p>
 * Вызывается из {@link DatabaseProvider#getConnection()} при первом создании соединения.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DatabaseMigrator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    /** Текущая версия схемы. Увеличивается при каждом изменении схемы. */
    static final int CURRENT_VERSION = 11;
    private static final Pattern CONNECTION_NODE_ID_PATTERN =
            Pattern.compile("\"nodeId\"\\s*:\\s*\"(![0-9a-fA-F]{8})\"");
    private static final List<String> APPLICATION_TABLES = List.of(
            "MESSAGES",
            "CHAT_READ_COUNTS",
            "NODES",
            "TELEMETRY_HISTORY",
            "MESSAGE_REACTIONS",
            "LORA_PACKET_LOGS"
    );

    private DatabaseMigrator() {}

    /**
     * Точка входа. Проверяет версию схемы и выполняет миграции при необходимости.
     *
     * @param connection активное соединение с H2 БД
     */
    public static void migrate(Connection connection) {
        try {
            if (!schemaVersionTableExists(connection)) {
                List<String> tables = listAllTables(connection);
                if (tables.isEmpty()) {
                    log.info("schema_version not found — database is empty (fresh install or incompatible H2 file)");
                    dropAll(connection);
                    createSchemaVersionTable(connection);
                    setVersion(connection, CURRENT_VERSION);
                    log.info("Database reset complete, schema version set to {}", CURRENT_VERSION);
                    return;
                }
                if (containsApplicationTables(tables)) {
                    log.info("schema_version not found — legacy app database with tables: {}, preserving data", tables);
                    createSchemaVersionTable(connection);
                    setVersion(connection, 1);
                } else {
                    log.info("schema_version not found — legacy database with tables: {}", tables);
                    dropAll(connection);
                    createSchemaVersionTable(connection);
                    setVersion(connection, CURRENT_VERSION);
                    log.info("Database reset complete, schema version set to {}", CURRENT_VERSION);
                    return;
                }
            }

            int version = getCurrentVersion(connection);
            if (version == CURRENT_VERSION) {
                log.info("Database schema is up to date (version {})", version);
                return;
            }

            log.info("Database schema version {} → migrating to {}", version, CURRENT_VERSION);

            // Последовательные миграции: v0→v1, v1→v2, ...
            if (version < 2) { migrateToV2(connection); version = 2; }
            if (version < 3) { migrateToV3(connection); version = 3; }
            if (version < 4) { migrateToV4(connection); version = 4; }
            if (version < 5) { migrateToV5(connection); version = 5; }
            if (version < 6) { migrateToV6(connection); version = 6; }
            if (version < 7) { migrateToV7(connection); version = 7; }
            if (version < 8) { migrateToV8(connection); version = 8; }
            if (version < 9) { migrateToV9(connection); version = 9; }
            if (version < 10) { migrateToV10(connection); version = 10; }
            if (version < 11) { migrateToV11(connection); version = 11; }

            setVersion(connection, CURRENT_VERSION);
            log.info("Database migration complete, schema version = {}", CURRENT_VERSION);
        } catch (SQLException e) {
            log.error("Database migration failed", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Утилиты
    // ═══════════════════════════════════════════════════════════

    private static boolean schemaVersionTableExists(Connection connection) throws SQLException {
        try (ResultSet rs = connection.getMetaData()
                .getTables(null, null, "SCHEMA_VERSION", null)) {
            return rs.next();
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet rs = connection.getMetaData()
                .getTables(null, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = connection.getMetaData()
                .getColumns(null, null, tableName, columnName)) {
            return rs.next();
        }
    }

    private static void createSchemaVersionTable(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE schema_version (version INT NOT NULL)");
            stmt.execute("INSERT INTO schema_version (version) VALUES (0)");
        }
    }

    private static int getCurrentVersion(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version")) {
            if (rs.next()) { return rs.getInt("version"); }
        }
        return 0;
    }

    private static void setVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE schema_version SET version = ?")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    /** v3: колонка owner_node_id для изоляции данных между устройствами. */
    private static void migrateToV3(Connection connection) throws SQLException {
        String ownerNodeId = inferOwnerNodeId();
        try (Statement stmt = connection.createStatement()) {
            if (tableExists(connection, "MESSAGES")) {
                stmt.execute("ALTER TABLE messages ADD COLUMN IF NOT EXISTS owner_node_id VARCHAR(20) NOT NULL DEFAULT ''");
                backfillOwnerNodeId(connection, "messages", ownerNodeId);
                stmt.execute("DROP INDEX IF EXISTS idx_msg_chat");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_msg_chat ON messages (owner_node_id, chat_type, chat_key, id)");
            }
            if (tableExists(connection, "CHAT_READ_COUNTS")) {
                stmt.execute("ALTER TABLE chat_read_counts ADD COLUMN IF NOT EXISTS owner_node_id VARCHAR(20) NOT NULL DEFAULT ''");
                backfillOwnerNodeId(connection, "chat_read_counts", ownerNodeId);
                dropPrimaryKeyIfPresent(stmt, "chat_read_counts");
                stmt.execute("ALTER TABLE chat_read_counts ADD PRIMARY KEY (owner_node_id, chat_type, chat_key)");
            }
        }
        log.info("Migration v3: added 'owner_node_id' column without deleting messages/read counts");
    }

    /** v4: колонка owner_node_id для изоляции телеметрии между устройствами. */
    private static void migrateToV4(Connection connection) throws SQLException {
        if (!tableExists(connection, "TELEMETRY_HISTORY")) {
            log.info("Migration v4: skipped telemetry owner isolation because telemetry_history is absent");
            return;
        }
        String ownerNodeId = inferOwnerNodeId();
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE telemetry_history ADD COLUMN IF NOT EXISTS owner_node_id VARCHAR(20) NOT NULL DEFAULT ''");
            backfillOwnerNodeId(connection, "telemetry_history", ownerNodeId);
            stmt.execute("DROP INDEX IF EXISTS idx_telemetry_node_ts");
            stmt.execute("CREATE INDEX idx_telemetry_node_ts ON telemetry_history (owner_node_id, node_id, ts)");
        }
        log.info("Migration v4: added 'owner_node_id' to telemetry_history without deleting telemetry data");
    }

    /** v5: колонка ignored для игнорируемых нод. */
    private static void migrateToV5(Connection connection) throws SQLException {
        if (!tableExists(connection, "NODES")) {
            log.info("Migration v5: skipped ignored column because nodes table is absent");
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE nodes ADD COLUMN IF NOT EXISTS ignored BOOLEAN DEFAULT FALSE");
        }
        log.info("Migration v5: added 'ignored' column to nodes");
    }

    /** v6: отдельная таблица реакций на сообщения. */
    private static void migrateToV6(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS message_reactions (
                        id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
                        owner_node_id      VARCHAR(20) NOT NULL DEFAULT '',
                        chat_type          VARCHAR(10) NOT NULL,
                        chat_key           VARCHAR(20) NOT NULL,
                        target_packet_id   INT NOT NULL,
                        reaction_packet_id INT DEFAULT 0,
                        from_node_id       VARCHAR(20) NOT NULL,
                        emoji              VARCHAR(16) NOT NULL,
                        timestamp          BIGINT NOT NULL,
                        outgoing           BOOLEAN NOT NULL,
                        status             VARCHAR(20),
                        error_reason       VARCHAR(100),
                        sender_name        VARCHAR(100)
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_reaction_chat_target
                    ON message_reactions (owner_node_id, chat_type, chat_key, target_packet_id, id)
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_reaction_packet
                    ON message_reactions (reaction_packet_id)
                    """);
        }
        log.info("Migration v6: created 'message_reactions' table and indexes");
    }

    /**
     * v7: нормализация legacy-кэша hops_away.
     * <p>
     * До v7 приложение сохраняло {@code hops_away = 0} и для прямых соседей,
     * и для нод с неизвестным hop count, потому что presence optional-поля
     * терялся при сериализации в {@link com.meshtastic.client.model.NodeData}.
     * Старые записи невозможно восстановить точно, поэтому переводим все
     * legacy-ноли в {@code NULL}. Актуальные direct-neighbor значения будут
     * заново заполнены на следующем config exchange.
     */
    private static void migrateToV7(Connection connection) throws SQLException {
        if (!tableExists(connection, "NODES") || !columnExists(connection, "NODES", "HOPS_AWAY")) {
            log.info("Migration v7: skipped legacy hops normalization because nodes.hops_away is absent");
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE nodes SET hops_away = NULL WHERE hops_away = 0");
        }
        log.info("Migration v7: normalized legacy nodes.hops_away=0 values to NULL");
    }

    /** v8: журнал LoRa-пакетов для подсистемы мониторинга. */
    private static void migrateToV8(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS lora_packet_logs (
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
                    CREATE INDEX IF NOT EXISTS idx_lora_owner_ts
                    ON lora_packet_logs (owner_node_id, captured_at)
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_lora_type
                    ON lora_packet_logs (packet_type)
                    """);
        }
        log.info("Migration v8: created 'lora_packet_logs' table and indexes");
    }

    /** v9: transport_mechanism для точного различения радио/API/MQTT путей. */
    private static void migrateToV9(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS lora_packet_logs (
                        id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
                        owner_node_id       VARCHAR(20) NOT NULL DEFAULT '',
                        captured_at         BIGINT NOT NULL,
                        direction           VARCHAR(10) NOT NULL,
                        packet_type         VARCHAR(40) NOT NULL,
                        transport_mechanism VARCHAR(40),
                        from_node           VARCHAR(160),
                        to_node             VARCHAR(160),
                        payload_text        CLOB,
                        packet_bytes        BLOB NOT NULL
                    )
                    """);
            stmt.execute("""
                    ALTER TABLE lora_packet_logs
                    ADD COLUMN IF NOT EXISTS transport_mechanism VARCHAR(40)
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_lora_owner_ts
                    ON lora_packet_logs (owner_node_id, captured_at)
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_lora_type
                    ON lora_packet_logs (packet_type)
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_lora_transport
                    ON lora_packet_logs (transport_mechanism)
                    """);
        }
        log.info("Migration v9: added 'transport_mechanism' to lora_packet_logs");
    }

    /** v10: флаг MQTT-доставки для сообщений чата. */
    private static void migrateToV10(Connection connection) throws SQLException {
        if (!tableExists(connection, "MESSAGES")) {
            log.info("Migration v10: skipped messages.via_mqtt because messages table is absent");
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE messages ADD COLUMN IF NOT EXISTS via_mqtt BOOLEAN DEFAULT FALSE");
        }
        log.info("Migration v10: added 'via_mqtt' to messages");
    }

    /** v11: индекс для поиска сообщений по packet_id внутри owner/chat scope. */
    private static void migrateToV11(Connection connection) throws SQLException {
        if (!tableExists(connection, "MESSAGES")) {
            log.info("Migration v11: skipped message packet lookup index because messages table is absent");
            return;
        }
        if (!columnExists(connection, "MESSAGES", "OWNER_NODE_ID")
                || !columnExists(connection, "MESSAGES", "CHAT_TYPE")
                || !columnExists(connection, "MESSAGES", "CHAT_KEY")
                || !columnExists(connection, "MESSAGES", "PACKET_ID")) {
            log.info("Migration v11: skipped message packet lookup index because messages table is missing required columns");
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_msg_chat_packet
                    ON messages (owner_node_id, chat_type, chat_key, packet_id, id)
                    """);
        }
        log.info("Migration v11: added scoped message packet lookup index");
    }

    /** v2: колонка favorite для избранных нод. */
    private static void migrateToV2(Connection connection) throws SQLException {
        if (!tableExists(connection, "NODES")) {
            log.info("Migration v2: skipped favorite column because nodes table is absent");
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE nodes ADD COLUMN IF NOT EXISTS favorite BOOLEAN DEFAULT FALSE");
        }
        log.info("Migration v2: added 'favorite' column to nodes");
    }

    private static boolean containsApplicationTables(List<String> tables) {
        return tables.stream().anyMatch(APPLICATION_TABLES::contains);
    }

    private static void backfillOwnerNodeId(Connection connection, String tableName, String ownerNodeId) throws SQLException {
        if (ownerNodeId == null || ownerNodeId.isBlank()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE " + tableName + " SET owner_node_id = ? WHERE owner_node_id IS NULL OR owner_node_id = ''")) {
            ps.setString(1, ownerNodeId);
            int updated = ps.executeUpdate();
            if (updated > 0) {
                log.info("Migration: backfilled {}.owner_node_id for {} rows with {}", tableName, updated, ownerNodeId);
            }
        }
    }

    private static void dropPrimaryKeyIfPresent(Statement stmt, String tableName) throws SQLException {
        try {
            stmt.execute("ALTER TABLE " + tableName + " DROP PRIMARY KEY");
        } catch (SQLException e) {
            String state = e.getSQLState();
            if (!"90057".equals(state) && !"90083".equals(state)) {
                throw e;
            }
        }
    }

    private static String inferOwnerNodeId() {
        Path connections = Path.of(System.getProperty("user.home", "."), ".meshapp", "connections.json");
        if (!Files.isRegularFile(connections)) {
            return "";
        }
        try {
            Matcher matcher = CONNECTION_NODE_ID_PATTERN.matcher(Files.readString(connections));
            if (matcher.find()) {
                return matcher.group(1).toLowerCase(Locale.ROOT);
            }
        } catch (Exception e) {
            log.debug("Failed to infer owner node id from {}", connections, e);
        }
        return "";
    }

    private static List<String> listAllTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData()
                .getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) { tables.add(rs.getString("TABLE_NAME")); }
        }
        return tables;
    }

    private static void dropAll(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
        }
        log.info("All database objects dropped");
    }
}
