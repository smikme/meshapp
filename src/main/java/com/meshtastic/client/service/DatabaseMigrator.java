package com.meshtastic.client.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
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
    static final int CURRENT_VERSION = 18;
    private static final String LEGACY_TRACEROUTE_PREFIX = "\uD83D\uDD0D Traceroute → ";
    private static final Pattern CONNECTION_NODE_ID_PATTERN =
            Pattern.compile("\"nodeId\"\\s*:\\s*\"(![0-9a-fA-F]{8})\"");
    private static final List<String> APPLICATION_TABLES = List.of(
            "MESSAGES",
            "CHAT_READ_COUNTS",
            "NODES",
            "TELEMETRY_HISTORY",
            "MESSAGE_REACTIONS",
            "TRACEROUTE_RESULTS",
            "LORA_PACKET_LOGS",
            "LUA_SCRIPTS",
            "LUA_SCRIPT_KV"
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
            if (version < 2) { normalizeLegacyV1Schema(connection); migrateToV2(connection); version = 2; }
            if (version < 3) { migrateToV3(connection); version = 3; }
            if (version < 4) { migrateToV4(connection); version = 4; }
            if (version < 5) { migrateToV5(connection); version = 5; }
            if (version < 6) { migrateToV6(connection); version = 6; }
            if (version < 7) { migrateToV7(connection); version = 7; }
            if (version < 8) { migrateToV8(connection); version = 8; }
            if (version < 9) { migrateToV9(connection); version = 9; }
            if (version < 10) { migrateToV10(connection); version = 10; }
            if (version < 11) { migrateToV11(connection); version = 11; }
            if (version < 12) { migrateToV12(connection); version = 12; }
            if (version < 13) { migrateToV13(connection); version = 13; }
            if (version < 14) { migrateToV14(connection); version = 14; }
            if (version < 15) { migrateToV15(connection); version = 15; }
            if (version < 16) { migrateToV16(connection); version = 16; }
            if (version < 17) { migrateToV17(connection); version = 17; }
            if (version < 18) { migrateToV18(connection); version = 18; }

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

    /**
     * Older builds created the app tables before schema_version existed, and used
     * numeric node columns (from_num/to_num/node_num). When preserving such a DB,
     * we label it as v1, so v1 must be tolerant of both the old numeric layout and
     * the newer string-node layout.
     */
    private static void normalizeLegacyV1Schema(Connection connection) throws SQLException {
        normalizeLegacyMessages(connection);
        normalizeLegacyChatReadCounts(connection);
        normalizeLegacyNodes(connection);
        normalizeLegacyTelemetry(connection);
    }

    private static void normalizeLegacyMessages(Connection connection) throws SQLException {
        if (!tableExists(connection, "MESSAGES")) {
            return;
        }

        try (Statement stmt = connection.createStatement()) {
            addColumnIfMissing(stmt, connection, "MESSAGES", "PACKET_ID", "packet_id INT DEFAULT 0");
            addColumnIfMissing(stmt, connection, "MESSAGES", "STATUS", "status VARCHAR(20)");
            addColumnIfMissing(stmt, connection, "MESSAGES", "ERROR_REASON", "error_reason VARCHAR(100)");
            addColumnIfMissing(stmt, connection, "MESSAGES", "REPLY_ID", "reply_id INT DEFAULT 0");
            addColumnIfMissing(stmt, connection, "MESSAGES", "REPLY_TEXT", "reply_text CLOB");
            addColumnIfMissing(stmt, connection, "MESSAGES", "HOP_START", "hop_start INT DEFAULT 0");
            addColumnIfMissing(stmt, connection, "MESSAGES", "HOP_LIMIT", "hop_limit INT DEFAULT 0");
            addColumnIfMissing(stmt, connection, "MESSAGES", "SENDER_NAME", "sender_name VARCHAR(100)");
            addColumnIfMissing(stmt, connection, "MESSAGES", "SYSTEM_MSG", "system_msg BOOLEAN DEFAULT FALSE");
            addColumnIfMissing(stmt, connection, "MESSAGES", "RX_RSSI", "rx_rssi INT DEFAULT 0");
            addColumnIfMissing(stmt, connection, "MESSAGES", "RX_SNR", "rx_snr REAL DEFAULT 0");

            if (!columnExists(connection, "MESSAGES", "FROM_NODE_ID")) {
                stmt.execute("ALTER TABLE messages ADD COLUMN from_node_id VARCHAR(20) NOT NULL DEFAULT ''");
            }
            if (!columnExists(connection, "MESSAGES", "TO_NODE_ID")) {
                stmt.execute("ALTER TABLE messages ADD COLUMN to_node_id VARCHAR(20) NOT NULL DEFAULT ''");
            }
        }

        if (columnExists(connection, "MESSAGES", "FROM_NUM") || columnExists(connection, "MESSAGES", "TO_NUM")) {
            backfillMessageNodeIds(connection);
        }

        if (!isCharacterColumn(connection, "MESSAGES", "CHAT_KEY")) {
            normalizeMessageChatKeys(connection);
        }
    }

    private static void normalizeLegacyChatReadCounts(Connection connection) throws SQLException {
        if (!tableExists(connection, "CHAT_READ_COUNTS")
                || !columnExists(connection, "CHAT_READ_COUNTS", "CHAT_KEY")
                || isCharacterColumn(connection, "CHAT_READ_COUNTS", "CHAT_KEY")) {
            return;
        }

        List<String[]> updates = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT chat_type, chat_key FROM chat_read_counts")) {
            while (rs.next()) {
                String chatType = rs.getString("chat_type");
                int oldKey = rs.getInt("chat_key");
                updates.add(new String[]{
                        chatType,
                        String.valueOf(oldKey),
                        normalizeChatKey(chatType, oldKey)
                });
            }
        }

        try (Statement stmt = connection.createStatement()) {
            dropPrimaryKeyIfPresent(stmt, "chat_read_counts");
            stmt.execute("ALTER TABLE chat_read_counts ALTER COLUMN chat_key SET DATA TYPE VARCHAR(20)");
        }

        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE chat_read_counts
                SET chat_key = ?
                WHERE chat_type = ? AND chat_key = ?
                """)) {
            for (String[] update : updates) {
                ps.setString(1, update[2]);
                ps.setString(2, update[0]);
                ps.setString(3, update[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void normalizeLegacyNodes(Connection connection) throws SQLException {
        if (!tableExists(connection, "NODES")) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            if (!columnExists(connection, "NODES", "NODE_ID")) {
                stmt.execute("ALTER TABLE nodes ADD COLUMN node_id VARCHAR(20)");
            }
            addColumnIfMissing(stmt, connection, "NODES", "NODE_NUM", "node_num INT");
        }
        if (columnExists(connection, "NODES", "NODE_NUM")) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("""
                         SELECT node_num
                         FROM nodes
                         WHERE (node_id IS NULL OR node_id = '') AND node_num IS NOT NULL
                         """);
                 PreparedStatement ps = connection.prepareStatement("""
                         UPDATE nodes
                         SET node_id = ?
                         WHERE node_num = ? AND (node_id IS NULL OR node_id = '')
                         """)) {
                while (rs.next()) {
                    int nodeNum = rs.getInt("node_num");
                    ps.setString(1, nodeIdFromInt(nodeNum));
                    ps.setInt(2, nodeNum);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private static void normalizeLegacyTelemetry(Connection connection) throws SQLException {
        if (!tableExists(connection, "TELEMETRY_HISTORY")) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            if (!columnExists(connection, "TELEMETRY_HISTORY", "NODE_ID")) {
                stmt.execute("ALTER TABLE telemetry_history ADD COLUMN node_id VARCHAR(20) NOT NULL DEFAULT ''");
            }
        }
        if (columnExists(connection, "TELEMETRY_HISTORY", "NODE_NUM")) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("""
                         SELECT id, node_num
                         FROM telemetry_history
                         WHERE (node_id IS NULL OR node_id = '')
                         """);
                 PreparedStatement ps = connection.prepareStatement("""
                         UPDATE telemetry_history
                         SET node_id = ?
                         WHERE id = ?
                         """)) {
                while (rs.next()) {
                    ps.setString(1, nodeIdFromInt(rs.getInt("node_num")));
                    ps.setLong(2, rs.getLong("id"));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private static void backfillMessageNodeIds(Connection connection) throws SQLException {
        boolean hasFromNum = columnExists(connection, "MESSAGES", "FROM_NUM");
        boolean hasToNum = columnExists(connection, "MESSAGES", "TO_NUM");
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT id%s%s
                     FROM messages
                     WHERE from_node_id = '' OR to_node_id = ''
                     """.formatted(hasFromNum ? ", from_num" : "", hasToNum ? ", to_num" : ""));
             PreparedStatement ps = connection.prepareStatement("""
                     UPDATE messages
                     SET from_node_id = CASE WHEN from_node_id = '' THEN ? ELSE from_node_id END,
                         to_node_id = CASE WHEN to_node_id = '' THEN ? ELSE to_node_id END
                     WHERE id = ?
                     """)) {
            while (rs.next()) {
                String fromNodeId = hasFromNum ? nodeIdFromInt(rs.getInt("from_num")) : "";
                String toNodeId = hasToNum ? nodeIdFromInt(rs.getInt("to_num")) : "";
                ps.setString(1, fromNodeId);
                ps.setString(2, toNodeId);
                ps.setLong(3, rs.getLong("id"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void normalizeMessageChatKeys(Connection connection) throws SQLException {
        List<String[]> updates = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, chat_type, chat_key FROM messages")) {
            while (rs.next()) {
                updates.add(new String[]{
                        String.valueOf(rs.getLong("id")),
                        String.valueOf(rs.getInt("chat_key")),
                        normalizeChatKey(rs.getString("chat_type"), rs.getInt("chat_key"))
                });
            }
        }

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP INDEX IF EXISTS idx_msg_chat");
            stmt.execute("ALTER TABLE messages ALTER COLUMN chat_key SET DATA TYPE VARCHAR(20)");
        }

        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE messages
                SET chat_key = ?
                WHERE id = ?
                """)) {
            for (String[] update : updates) {
                ps.setString(1, update[2]);
                ps.setLong(2, Long.parseLong(update[0]));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void addColumnIfMissing(Statement stmt,
                                           Connection connection,
                                           String tableName,
                                           String columnName,
                                           String columnDefinition) throws SQLException {
        if (!columnExists(connection, tableName, columnName)) {
            stmt.execute("ALTER TABLE " + tableName.toLowerCase(Locale.ROOT) + " ADD COLUMN " + columnDefinition);
        }
    }

    private static boolean isCharacterColumn(Connection connection, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = connection.getMetaData()
                .getColumns(null, null, tableName, columnName)) {
            if (!rs.next()) {
                return false;
            }
            int type = rs.getInt("DATA_TYPE");
            return type == Types.CHAR
                    || type == Types.VARCHAR
                    || type == Types.LONGVARCHAR
                    || type == Types.NCHAR
                    || type == Types.NVARCHAR
                    || type == Types.LONGNVARCHAR;
        }
    }

    private static String normalizeChatKey(String chatType, int oldKey) {
        return "dm".equals(chatType) ? nodeIdFromInt(oldKey) : String.valueOf(oldKey);
    }

    private static String nodeIdFromInt(int nodeNum) {
        return String.format(Locale.ROOT, "!%08x", nodeNum);
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

    /** v12: полнотекстовый индекс H2 для поиска по тексту сообщений. */
    private static void migrateToV12(Connection connection) throws SQLException {
        if (!tableExists(connection, "MESSAGES")) {
            log.info("Migration v12: skipped message fulltext index because messages table is absent");
            return;
        }
        if (!columnExists(connection, "MESSAGES", "ID")
                || !columnExists(connection, "MESSAGES", "TEXT")) {
            log.info("Migration v12: skipped message fulltext index because messages table is missing required columns");
            return;
        }
        MessageFullTextIndex.ensureExists(connection);
        log.info("Migration v12: created message fulltext index");
    }

    /** v13: Lua scripts and per-script key-value storage. */
    private static void migrateToV13(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS lua_scripts (
                        id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                        guid        VARCHAR(36) NOT NULL DEFAULT '',
                        icon        VARCHAR(32) NOT NULL DEFAULT '🤖',
                        name        VARCHAR(120) NOT NULL,
                        code        CLOB NOT NULL,
                        enabled     BOOLEAN NOT NULL DEFAULT TRUE,
                        node_id     VARCHAR(60) NOT NULL DEFAULT '',
                        bot_type    VARCHAR(30) NOT NULL DEFAULT 'AIR_BOT',
                        automation_name VARCHAR(80) NOT NULL DEFAULT '',
                        created_at  BIGINT NOT NULL,
                        updated_at  BIGINT NOT NULL,
                        last_run_at BIGINT DEFAULT 0,
                        last_status VARCHAR(20),
                        last_error  CLOB
                    )
                    """);
            stmt.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_lua_scripts_name
                    ON lua_scripts (name)
                    """);
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS guid VARCHAR(36) NOT NULL DEFAULT ''");
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS icon VARCHAR(32) NOT NULL DEFAULT '🤖'");
            backfillLuaScriptGuids(connection);
            backfillLuaScriptIcons(connection);
            stmt.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_lua_scripts_guid
                    ON lua_scripts (guid)
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS lua_script_kv (
                        script_id  BIGINT NOT NULL,
                        key_name   VARCHAR(200) NOT NULL,
                        value_text CLOB,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (script_id, key_name),
                        CONSTRAINT fk_lua_script_kv_script
                            FOREIGN KEY (script_id) REFERENCES lua_scripts(id)
                            ON DELETE CASCADE
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_lua_script_kv_script
                    ON lua_script_kv (script_id)
                    """);
        }
        log.info("Migration v13: created Lua script and KV tables");
    }

    /** v14: параметры запуска и тип Lua-бота для MeshApp IDE. */
    private static void migrateToV14(Connection connection) throws SQLException {
        if (!tableExists(connection, "LUA_SCRIPTS")) {
            log.info("Migration v14: skipped Lua script metadata because lua_scripts table is absent");
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS node_id VARCHAR(60) NOT NULL DEFAULT ''");
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS bot_type VARCHAR(30) NOT NULL DEFAULT 'AIR_BOT'");
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS automation_name VARCHAR(80) NOT NULL DEFAULT ''");
        }
        log.info("Migration v14: added Lua script node binding and bot metadata");
    }

    /** v15: separate Meshtastic external-power flag from battery percentage. */
    private static void migrateToV15(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            if (tableExists(connection, "NODES")) {
                stmt.execute("ALTER TABLE nodes ADD COLUMN IF NOT EXISTS externally_powered BOOLEAN DEFAULT FALSE");
            }
            if (tableExists(connection, "TELEMETRY_HISTORY")) {
                stmt.execute("ALTER TABLE telemetry_history ADD COLUMN IF NOT EXISTS externally_powered BOOLEAN DEFAULT FALSE");
            }
        }
        log.info("Migration v15: added external power flags for nodes and telemetry");
    }

    /** v16: стабильный GUID для каждого Lua-скрипта. */
    private static void migrateToV16(Connection connection) throws SQLException {
        if (!tableExists(connection, "LUA_SCRIPTS")) {
            log.info("Migration v16: skipped Lua script GUID because lua_scripts table is absent");
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS guid VARCHAR(36) NOT NULL DEFAULT ''");
            backfillLuaScriptGuids(connection);
            stmt.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_lua_scripts_guid
                    ON lua_scripts (guid)
                    """);
        }
        log.info("Migration v16: added Lua script GUID identifiers");
    }

    /** v17: emoji-иконка для каждого Lua-скрипта. */
    private static void migrateToV17(Connection connection) throws SQLException {
        if (!tableExists(connection, "LUA_SCRIPTS")) {
            log.info("Migration v17: skipped Lua script icon because lua_scripts table is absent");
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS icon VARCHAR(32) NOT NULL DEFAULT '🤖'");
            backfillLuaScriptIcons(connection);
        }
        log.info("Migration v17: added Lua script emoji icons");
    }

    /** v18: отдельное хранилище результатов traceroute. */
    private static void migrateToV18(Connection connection) throws SQLException {
        createTracerouteResultsTable(connection);
        backfillLegacyTracerouteMessages(connection);
        log.info("Migration v18: created traceroute_results table");
    }

    private static void createTracerouteResultsTable(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS traceroute_results (
                        id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
                        owner_node_id          VARCHAR(20) NOT NULL DEFAULT '',
                        chat_type              VARCHAR(10) NOT NULL DEFAULT '',
                        chat_key               VARCHAR(20) NOT NULL DEFAULT '',
                        source                 VARCHAR(80) NOT NULL DEFAULT '',
                        request_id             VARCHAR(120) NOT NULL DEFAULT '',
                        script_id              BIGINT DEFAULT 0,
                        target_node_num        BIGINT DEFAULT 0,
                        target_node_id         VARCHAR(20),
                        target_name            VARCHAR(120),
                        response_from_node_num BIGINT DEFAULT 0,
                        response_from_node_id  VARCHAR(20),
                        route_data             BLOB,
                        formatted_text         CLOB,
                        timestamp              BIGINT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_traceroute_owner_time
                    ON traceroute_results (owner_node_id, timestamp DESC, id DESC)
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_traceroute_request
                    ON traceroute_results (owner_node_id, request_id)
                    """);
        }
    }

    private static void backfillLegacyTracerouteMessages(Connection connection) throws SQLException {
        if (!tableExists(connection, "MESSAGES")
                || !columnExists(connection, "MESSAGES", "ID")
                || !columnExists(connection, "MESSAGES", "OWNER_NODE_ID")
                || !columnExists(connection, "MESSAGES", "CHAT_TYPE")
                || !columnExists(connection, "MESSAGES", "CHAT_KEY")
                || !columnExists(connection, "MESSAGES", "TEXT")
                || !columnExists(connection, "MESSAGES", "TIMESTAMP")
                || !columnExists(connection, "MESSAGES", "SYSTEM_MSG")) {
            log.info("Migration v18: skipped legacy traceroute backfill because messages table is incomplete");
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO traceroute_results (
                    owner_node_id, chat_type, chat_key, source, request_id, script_id,
                    target_name, route_data, formatted_text, timestamp
                )
                SELECT m.owner_node_id, m.chat_type, m.chat_key,
                       'legacy.messages',
                       CONCAT('legacy:', m.id),
                       0,
                       '',
                       NULL,
                       m.text,
                       m.timestamp
                FROM messages m
                WHERE m.system_msg = TRUE
                  AND CAST(m.text AS VARCHAR) LIKE ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM traceroute_results tr
                      WHERE tr.owner_node_id = m.owner_node_id
                        AND tr.request_id = CONCAT('legacy:', m.id)
                  )
                """)) {
            ps.setString(1, LEGACY_TRACEROUTE_PREFIX + "%");
            int inserted = ps.executeUpdate();
            if (inserted > 0) {
                log.info("Migration v18: backfilled {} legacy traceroute messages", inserted);
            }
        }
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

    private static void backfillLuaScriptGuids(Connection connection) throws SQLException {
        if (!tableExists(connection, "LUA_SCRIPTS") || !columnExists(connection, "LUA_SCRIPTS", "GUID")) {
            return;
        }
        List<GuidUpdate> updates = new ArrayList<>();
        Set<String> usedGuids = new HashSet<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, guid FROM lua_scripts ORDER BY id")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String rawGuid = rs.getString("guid");
                String normalizedGuid = normalizeGuid(rawGuid);
                if (normalizedGuid.isBlank() || !usedGuids.add(normalizedGuid)) {
                    updates.add(new GuidUpdate(id, newGuid(usedGuids)));
                } else if (!normalizedGuid.equals(rawGuid)) {
                    updates.add(new GuidUpdate(id, normalizedGuid));
                }
            }
        }
        if (updates.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE lua_scripts SET guid = ? WHERE id = ?
                """)) {
            for (GuidUpdate update : updates) {
                ps.setString(1, update.guid());
                ps.setLong(2, update.scriptId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        log.info("Migration: populated Lua script GUID for {} rows", updates.size());
    }

    private static void backfillLuaScriptIcons(Connection connection) throws SQLException {
        if (!tableExists(connection, "LUA_SCRIPTS") || !columnExists(connection, "LUA_SCRIPTS", "ICON")) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE lua_scripts SET icon = ?
                WHERE icon IS NULL OR TRIM(icon) = ''
                """)) {
            ps.setString(1, "🤖");
            int updated = ps.executeUpdate();
            if (updated > 0) {
                log.info("Migration: populated Lua script icon for {} rows", updated);
            }
        }
    }

    private static String normalizeGuid(String guid) {
        if (guid == null || guid.isBlank()) {
            return "";
        }
        try {
            return UUID.fromString(guid.trim()).toString();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String newGuid(Set<String> usedGuids) {
        String guid;
        do {
            guid = UUID.randomUUID().toString();
        } while (!usedGuids.add(guid));
        return guid;
    }

    private record GuidUpdate(long scriptId, String guid) {}

    private static void dropPrimaryKeyIfPresent(Statement stmt, String tableName) throws SQLException {
        try {
            stmt.execute("ALTER TABLE " + tableName + " DROP PRIMARY KEY");
        } catch (SQLException e) {
            String state = e.getSQLState();
            int errorCode = e.getErrorCode();
            if (errorCode != 42112
                    && !"42112".equals(state)
                    && !"90057".equals(state)
                    && !"90083".equals(state)) {
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
