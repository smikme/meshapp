package com.meshtastic.client.logging;

import com.meshtastic.client.TestEnvironmentSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaFxCssWarningGuardTest {

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        SessionCrashLogManager.resetForTests();
        SessionCrashLogManager.prepareForLaunch();
        UiLogAppender.clearBuffer();
        JavaFxCssWarningGuard.resetForTests();
    }

    @AfterEach
    void tearDown() {
        JavaFxCssWarningGuard.resetForTests();
        UiLogAppender.clearBuffer();
        SessionCrashLogManager.resetForTests();
    }

    @Test
    void bridgesCssWarningsToSessionLogWithoutParentJulFormatter() throws Exception {
        JavaFxCssWarningGuard.install();

        java.util.logging.Logger cssLogger = java.util.logging.Logger.getLogger("javafx.css");
        assertFalse(cssLogger.getUseParentHandlers());

        cssLogger.warning("synthetic css issue");

        String activeLog = Files.readString(SessionCrashLogManager.getActiveLogPath());
        assertTrue(activeLog.contains("WARN"));
        assertTrue(activeLog.contains("JavaFX CSS warning: synthetic css issue"));
    }

    @Test
    void handlerDoesNotInspectCallerFields() {
        JavaFxCssWarningGuard.install();

        LogRecord record = new LogRecord(Level.WARNING, "caller fields must not be read") {
            @Override
            public String getSourceClassName() {
                throw new AssertionError("source class lookup triggers caller inference");
            }

            @Override
            public String getSourceMethodName() {
                throw new AssertionError("source method lookup triggers caller inference");
            }
        };

        for (Handler handler : java.util.logging.Logger.getLogger("javafx.css").getHandlers()) {
            handler.publish(record);
        }

        assertTrue(UiLogAppender.getBuffer().stream()
                .anyMatch(entry -> entry.getMessage().contains("caller fields must not be read")));
    }
}
