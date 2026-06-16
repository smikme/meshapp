package com.meshtastic.client.service;

import org.h2.tools.Recover;
import org.h2.tools.RunScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

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
    private static final String DATABASE_NAME = "nodedb";
    private static final String DATABASE_FILE_NAME = DATABASE_NAME + ".mv.db";
    private static final String RECOVERY_DIR_PREFIX = "db-recovery-";
    private static final int H2_FILE_CORRUPTED_ERROR_CODE = 90030;
    private static final int H2_IO_EXCEPTION_ERROR_CODE = 90028;
    private static final DateTimeFormatter RECOVERY_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final RecoveryExecutor DIRECT_RECOVERY_EXECUTOR =
            (dbFile, task) -> task.run((step, path) -> {});

    private static Connection connection;
    private static RecoveryExecutor recoveryExecutor = DIRECT_RECOVERY_EXECUTOR;

    private DatabaseProvider() {}

    public enum RecoveryStep {
        DETECTED,
        MOVING_CORRUPT_DATABASE,
        EXPORTING_SQL,
        IMPORTING_SQL,
        CREATING_FRESH_DATABASE,
        COMPLETE
    }

    @FunctionalInterface
    public interface RecoveryProgress {
        void update(RecoveryStep step, Path path);
    }

    @FunctionalInterface
    public interface RecoveryTask {
        void run(RecoveryProgress progress) throws Exception;
    }

    @FunctionalInterface
    public interface RecoveryExecutor {
        void run(Path dbFile, RecoveryTask task) throws Exception;
    }

    public static synchronized void setRecoveryExecutor(RecoveryExecutor executor) {
        recoveryExecutor = executor != null ? executor : DIRECT_RECOVERY_EXECUTOR;
    }

    /**
     * Returns the shared database connection, creating it on first use.
     */
    public static synchronized Connection getConnection() {
        if (connection != null) {
            return connection;
        }
        try {
            Path dbDir = resolveDatabaseDirectory();
            Path dbFile = dbDir.resolve(DATABASE_FILE_NAME);
            logDatabaseFile(dbFile);

            try {
                connection = openAndMigrate(dbFile);
            } catch (SQLException e) {
                if (!isDatabaseCorruption(e)) {
                    throw e;
                }
                log.warn("Database file {} is corrupted; starting automatic recovery", dbFile, e);
                recoveryExecutor.run(dbFile, progress -> recoverCorruptedDatabase(dbDir, dbFile, progress));
                connection = openAndMigrate(dbFile);
            }
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
        return "jdbc:h2:" + resolveDatabaseDirectory().resolve(DATABASE_NAME) + ";AUTO_SERVER=FALSE;TRACE_LEVEL_FILE=0";
    }

    private static Connection openAndMigrate(Path dbFile) throws Exception {
        Connection newConnection = DriverManager.getConnection(resolveDatabaseUrl());
        log.info("Database connection established: {}", dbFile);
        DatabaseMigrator.migrate(newConnection);
        return newConnection;
    }

    private static void logDatabaseFile(Path dbFile) throws Exception {
        boolean existed = Files.exists(dbFile);
        long sizeBytes = existed ? Files.size(dbFile) : 0;
        log.info("DB file {}: exists={}, size={} bytes", dbFile, existed, sizeBytes);
    }

    private static boolean isDatabaseCorruption(SQLException e) {
        for (Throwable cursor = e; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof SQLException sqlException
                    && sqlException.getErrorCode() == H2_FILE_CORRUPTED_ERROR_CODE) {
                return true;
            }
            if (cursor instanceof SQLException sqlException
                    && sqlException.getErrorCode() == H2_IO_EXCEPTION_ERROR_CODE
                    && isMvStoreEof(e)) {
                return true;
            }
            String message = cursor.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("file corrupted")
                    || normalized.contains("file version error")
                    || normalized.contains("wrong database file version")
                    || normalized.contains("invalid file header")
                    || normalized.contains("not a database")
                    || (normalized.contains("reading from file") && normalized.contains("remaining"))
                    || normalized.contains("неправильный формат файла")
                    || normalized.contains("поврежден")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMvStoreEof(Throwable throwable) {
        for (Throwable cursor = throwable; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof EOFException) {
                return true;
            }
            String message = cursor.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("mvstore") && normalized.contains("eof")) {
                    return true;
                }
                if (normalized.contains("reading from file") && normalized.contains("remaining")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void recoverCorruptedDatabase(Path dbDir, Path dbFile, RecoveryProgress progress) throws Exception {
        progress.update(RecoveryStep.DETECTED, dbFile);
        if (!Files.exists(dbFile)) {
            log.warn("Database recovery skipped because {} no longer exists", dbFile);
            return;
        }

        String timestamp = RECOVERY_TIMESTAMP_FORMAT.format(Instant.now());
        Path recoveryDir = dbDir.resolve(RECOVERY_DIR_PREFIX + timestamp);
        recoveryDir = nextAvailablePath(recoveryDir);
        Files.createDirectories(recoveryDir);

        Path recoveryInput = recoveryDir.resolve(DATABASE_FILE_NAME);
        progress.update(RecoveryStep.MOVING_CORRUPT_DATABASE, recoveryInput);
        Files.move(dbFile, recoveryInput, StandardCopyOption.REPLACE_EXISTING);
        log.warn("Corrupted database moved to {}", recoveryInput);

        progress.update(RecoveryStep.EXPORTING_SQL, recoveryInput);
        try {
            Recover.execute(recoveryDir.toString(), DATABASE_NAME);
        } catch (SQLException e) {
            log.error("H2 recovery tool failed for {}; a fresh database will be created", recoveryInput, e);
        }

        Path recoveryScript = recoveryDir.resolve(DATABASE_NAME + ".h2.sql");
        if (!Files.exists(recoveryScript) || Files.size(recoveryScript) == 0) {
            log.warn("H2 recovery did not produce a usable SQL script at {}; a fresh database will be created",
                    recoveryScript);
            progress.update(RecoveryStep.CREATING_FRESH_DATABASE, dbFile);
            return;
        }

        progress.update(RecoveryStep.IMPORTING_SQL, recoveryScript);
        try {
            RunScript.execute(resolveDatabaseUrl(), "", "", recoveryScript.toString(), StandardCharsets.UTF_8, true);
            log.info("Database recovery script imported from {}", recoveryScript);
        } catch (SQLException e) {
            log.error("Failed to import H2 recovery script {}; a fresh database will be created", recoveryScript, e);
            Files.deleteIfExists(dbFile);
            progress.update(RecoveryStep.CREATING_FRESH_DATABASE, dbFile);
        }
        progress.update(RecoveryStep.COMPLETE, dbFile);
    }

    private static Path nextAvailablePath(Path preferredPath) {
        if (!Files.exists(preferredPath)) {
            return preferredPath;
        }
        Path parent = preferredPath.getParent();
        String fileName = preferredPath.getFileName().toString();
        for (int index = 1; ; index++) {
            Path candidate = parent.resolve(fileName + "-" + index);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }
}
