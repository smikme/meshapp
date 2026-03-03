package com.meshtastic.client.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

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
public class DatabaseMigrator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    /** Текущая версия схемы. Увеличивается при каждом изменении схемы. */
    static final int CURRENT_VERSION = 1;

    private DatabaseMigrator() {}

    /**
     * Точка входа. Проверяет версию схемы и выполняет миграции при необходимости.
     *
     * @param connection активное соединение с H2 БД
     */
    public static void migrate(Connection connection) {
        try {
            if (!schemaVersionTableExists(connection)) {
                log.info("schema_version table not found — resetting database");
                dropAll(connection);
                createSchemaVersionTable(connection);
                setVersion(connection, CURRENT_VERSION);
                log.info("Database reset complete, schema version set to {}", CURRENT_VERSION);
                return;
            }

            int version = getCurrentVersion(connection);
            if (version == CURRENT_VERSION) {
                log.debug("Database schema is up to date (version {})", version);
                return;
            }

            log.info("Database schema version {} → migrating to {}", version, CURRENT_VERSION);

            // Последовательные миграции: v0→v1, v1→v2, ...
            // На данный момент миграций нет — версия 1 является начальной.
            // Пример будущей миграции:
            // if (version < 2) { migrateToV2(connection); version = 2; }

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
            if (rs.next()) return rs.getInt("version");
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

    private static void dropAll(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
        }
        log.info("All database objects dropped");
    }
}
