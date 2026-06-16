package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class DatabaseProviderTest {

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
    void getConnectionRunsRecoveryExecutorAndCreatesUsableDatabaseWhenFileIsCorrupt() throws Exception {
        Path dbDir = tempHome.resolve(".meshapp");
        Files.createDirectories(dbDir);
        Path dbFile = dbDir.resolve("nodedb.mv.db");
        Files.writeString(dbFile, "not an h2 database", StandardCharsets.UTF_8);

        List<DatabaseProvider.RecoveryStep> recoverySteps = new ArrayList<>();
        DatabaseProvider.setRecoveryExecutor((detectedDbFile, task) -> {
            assertEquals(dbFile, detectedDbFile);
            task.run((step, path) -> recoverySteps.add(step));
        });

        Connection connection = DatabaseProvider.getConnection();

        assertNotNull(connection);
        assertTrue(Files.exists(dbFile));
        assertTrue(recoverySteps.contains(DatabaseProvider.RecoveryStep.MOVING_CORRUPT_DATABASE),
                () -> "recovery steps: " + recoverySteps);
        assertTrue(recoverySteps.contains(DatabaseProvider.RecoveryStep.EXPORTING_SQL),
                () -> "recovery steps: " + recoverySteps);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS recovery_probe (id INT PRIMARY KEY)");
            stmt.execute("MERGE INTO recovery_probe (id) KEY(id) VALUES (1)");
        }

        List<Path> recoveryDirs;
        try (Stream<Path> stream = Files.list(dbDir)) {
            recoveryDirs = stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("db-recovery-"))
                    .toList();
        }
        assertEquals(1, recoveryDirs.size());
        assertTrue(Files.exists(recoveryDirs.getFirst().resolve("nodedb.mv.db")));
    }

    @Test
    void getConnectionDoesNotRunRecoveryExecutorForHealthyDatabase() throws Exception {
        Path dbDir = tempHome.resolve(".meshapp");
        Files.createDirectories(dbDir);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:" + dbDir.resolve("nodedb") + ";AUTO_SERVER=FALSE;TRACE_LEVEL_FILE=0")) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE healthy_probe (id INT PRIMARY KEY)");
            }
        }

        List<DatabaseProvider.RecoveryStep> recoverySteps = new ArrayList<>();
        DatabaseProvider.setRecoveryExecutor((dbFile, task) ->
                task.run((step, path) -> recoverySteps.add(step)));

        assertNotNull(DatabaseProvider.getConnection());
        assertFalse(recoverySteps.contains(DatabaseProvider.RecoveryStep.DETECTED),
                () -> "recovery steps: " + recoverySteps);
    }
}
