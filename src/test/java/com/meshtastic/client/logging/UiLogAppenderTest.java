package com.meshtastic.client.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.meshtastic.client.model.LogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiLogAppenderTest {

    @AfterEach
    void tearDown() {
        UiLogAppender.clearLiveListener();
        UiLogAppender.clearBuffer();
    }

    @Test
    void preservesThrowableStacktraceInBufferedEntry() {
        UiLogAppender.clearLiveListener();
        UiLogAppender.clearBuffer();
        UiLogAppender appender = new UiLogAppender();
        appender.start();

        RuntimeException boom = new RuntimeException("boom");
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test");
        LoggingEvent event = new LoggingEvent(
                UiLogAppenderTest.class.getName(),
                logger,
                Level.ERROR,
                "Uncaught exception in thread 'JavaFX Application Thread'",
                boom,
                null
        );
        event.setTimeStamp(1_775_588_451_757L);

        appender.doAppend(event);

        LogEntry entry = UiLogAppender.getBuffer().getLast();
        assertEquals("Uncaught exception in thread 'JavaFX Application Thread'", entry.getMessage());
        assertTrue(entry.getFullMessage().contains("java.lang.RuntimeException: boom"));
        assertTrue(entry.getFullMessage().contains("UiLogAppenderTest"));
    }
}
