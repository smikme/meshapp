package com.meshtastic.client.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Централизованное версионирование и миграция схемы H2 БД.
 * <p>
 * Хранит номер версии схемы в таблице {@code schema_version} (одна строка, одно число).
 * При запуске проверяет текущую версию и выполняет необходимые миграции последовательно.
 * <p>
 * Если таблица {@code schema_version} отсутствует — БД считается устаревшей,
 * все объекты удаляются ({@code DROP ALL OBJECTS}), и схема создаётся с нуля.
 * <p>
 * Вызывается из {@link DatabaseProvider#getConnection()} при первом создании соединения.
 */
public final class DatabaseMigrator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    /** Текущая версия схемы. Увеличивается при каждом изменении схемы. */
    static final int CURRENT_VERSION = 5;

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
                } else {
                    log.info("schema_version not found — legacy database with tables: {}", tables);
                }
                dropAll(connection);
                createSchemaVersionTable(connection);
                setVersion(connection, CURRENT_VERSION);
                log.info("Database reset complete, schema version set to {}", CURRENT_VERSION);
                return;
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
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM messages");
            stmt.execute("DELETE FROM chat_read_counts");
            stmt.execute("ALTER TABLE messages ADD COLUMN IF NOT EXISTS owner_node_id VARCHAR(20) NOT NULL DEFAULT ''");
            stmt.execute("ALTER TABLE chat_read_counts ADD COLUMN IF NOT EXISTS owner_node_id VARCHAR(20) NOT NULL DEFAULT ''");
            stmt.execute("ALTER TABLE chat_read_counts DROP PRIMARY KEY");
            stmt.execute("ALTER TABLE chat_read_counts ADD PRIMARY KEY (owner_node_id, chat_type, chat_key)");
            stmt.execute("DROP INDEX IF EXISTS idx_msg_chat");
            stmt.execute("CREATE INDEX idx_msg_chat ON messages (owner_node_id, chat_type, chat_key, id)");
        }
        log.info("Migration v3: added 'owner_node_id' column, cleared old messages and read counts");
    }

    /** v4: колонка owner_node_id для изоляции телеметрии между устройствами. */
    private static void migrateToV4(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM telemetry_history");
            stmt.execute("ALTER TABLE telemetry_history ADD COLUMN IF NOT EXISTS owner_node_id VARCHAR(20) NOT NULL DEFAULT ''");
            stmt.execute("DROP INDEX IF EXISTS idx_telemetry_node_ts");
            stmt.execute("CREATE INDEX idx_telemetry_node_ts ON telemetry_history (owner_node_id, node_id, ts)");
        }
        log.info("Migration v4: added 'owner_node_id' to telemetry_history, cleared old telemetry data");
    }

    /** v5: колонка ignored для игнорируемых нод. */
    private static void migrateToV5(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE nodes ADD COLUMN IF NOT EXISTS ignored BOOLEAN DEFAULT FALSE");
        }
        log.info("Migration v5: added 'ignored' column to nodes");
    }

    /** v2: колонка favorite для избранных нод. */
    private static void migrateToV2(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE nodes ADD COLUMN IF NOT EXISTS favorite BOOLEAN DEFAULT FALSE");
        }
        log.info("Migration v2: added 'favorite' column to nodes");
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
