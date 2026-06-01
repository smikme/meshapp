package com.meshtastic.client.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;

/**
 * Owns the single connection to the embedded H2 database at
 * {@code ~/.meshapp/nodedb}.
 * <p>
 * Shared by services such as {@link MessageDbService} and {@link NodeCacheService}
 * instead of opening separate connections to the same database file.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DatabaseProvider {

    private static final Logger log = LoggerFactory.getLogger(DatabaseProvider.class);

    private static Connection connection;

    private DatabaseProvider() {}

    /**
     * Returns the shared database connection, creating it on first use.
     */
    public static synchronized Connection getConnection() {
        if (connection != null) {
            return connection;
        }
        try {
            Path dbFile = resolveDatabaseDirectory().resolve("nodedb.mv.db");
            boolean existed = Files.exists(dbFile);
            long sizeBytes = existed ? Files.size(dbFile) : 0;
            log.info("DB file {}: exists={}, size={} bytes", dbFile, existed, sizeBytes);

            connection = DriverManager.getConnection(resolveDatabaseUrl());
            log.info("Database connection established: {}", dbFile);
            DatabaseMigrator.migrate(connection);
        } catch (Exception e) {
            log.error("Failed to create database connection", e);
        }
        return connection;
    }

    /**
     * Closes the database connection.
     * <p>
     * Called once during application shutdown, after services have closed their
     * prepared statements.
     */
    public static synchronized void close() {
        if (connection == null) { return; }
        try {
            if (!connection.isClosed()) {
                connection.close();
                log.info("Database connection closed");
            }
        } catch (SQLException e) {
            log.error("Error closing database connection", e);
        }
        connection = null;
    }

    /**
     * Drops every object in the current database and recreates the base schema.
     * <p>
     * Used for a hard local-data reset. After this method returns, application
     * services must recreate their tables and prepared statements.
     */
    public static synchronized void resetDatabase() throws SQLException {
        Connection activeConnection = getConnection();
        if (activeConnection == null) {
            throw new SQLException("Database connection is not available");
        }

        try {
            if (!activeConnection.getAutoCommit()) {
                activeConnection.rollback();
                activeConnection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.debug("Failed to normalize autocommit before DB reset", e);
        }

        try (Statement stmt = activeConnection.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
        }
        log.info("All database objects dropped by explicit reset request");
        DatabaseMigrator.migrate(activeConnection);
    }

    private static Path resolveDatabaseDirectory() throws Exception {
        Path dbDir = Path.of(System.getProperty("user.home"), ".meshapp");
        Files.createDirectories(dbDir);
        return dbDir;
    }

    private static String resolveDatabaseUrl() throws Exception {
        return "jdbc:h2:" + resolveDatabaseDirectory().resolve("nodedb") + ";AUTO_SERVER=FALSE;TRACE_LEVEL_FILE=0";
    }
}
