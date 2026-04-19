package com.meshtastic.client.forms;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.logging.SessionCrashLogManager;
import com.meshtastic.client.logging.UiLogAppender;
import com.meshtastic.client.model.LogEntry;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormLogsTest {

    @TempDir
    Path tempHome;

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        invokeStatic(SessionCrashLogManager.class, "resetForTests");
        SessionCrashLogManager.prepareForLaunch();
        UiLogAppender.clearLiveListener();
        UiLogAppender.clearBuffer();
    }

    @AfterEach
    void tearDown() {
        UiLogAppender.clearLiveListener();
        UiLogAppender.clearBuffer();
        invokeStatic(SessionCrashLogManager.class, "resetForTests");
    }

    @Test
    void pauseFreezesVisibleLogViewUntilResumeReloadsBufferedEntries() {
        UiLogAppender appender = new UiLogAppender();
        appender.start();
        FormLogs form = onFxThread(FormLogs::new);
        onFxThread(() -> {
            form.formOpen();
            return null;
        });

        appendEvent(appender, "first");
        waitForFxEvents();
        assertEquals(List.of("first"), visibleMessages(form));

        onFxThread(() -> {
            invoke(form, "toggleLogViewUpdates");
            return null;
        });

        appendEvent(appender, "second");
        waitForFxEvents();
        assertEquals(List.of("first"), visibleMessages(form));
        assertTrue(UiLogAppender.getBuffer().stream().anyMatch(entry -> "second".equals(entry.getMessage())));

        onFxThread(() -> {
            invoke(form, "toggleLogViewUpdates");
            return null;
        });
        waitForFxEvents();
        assertEquals(List.of("first", "second"), visibleMessages(form));

        appendEvent(appender, "third");
        waitForFxEvents();
        assertEquals(List.of("first", "second", "third"), visibleMessages(form));

        onFxThread(() -> {
            form.formClose();
            return null;
        });
    }

    private static void appendEvent(UiLogAppender appender, String message) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("form-logs-test");
        LoggingEvent event = new LoggingEvent(
                FormLogsTest.class.getName(),
                logger,
                Level.INFO,
                message,
                null,
                null
        );
        event.setTimeStamp(System.currentTimeMillis());
        appender.doAppend(event);
    }

    private static List<String> visibleMessages(FormLogs form) {
        return onFxThread(() -> {
            @SuppressWarnings("unchecked")
            ObservableList<LogEntry> entries = (ObservableList<LogEntry>) readField(form, "logData");
            return entries.stream().map(LogEntry::getMessage).toList();
        });
    }

    private static void invoke(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke method " + methodName, e);
        }
    }

    private static void invokeStatic(Class<?> type, String methodName) {
        try {
            Method method = type.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke static method " + methodName, e);
        }
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read field " + fieldName, e);
        }
    }

    private static void waitForFxEvents() {
        onFxThread(() -> null);
    }

    private static <T> T onFxThread(FxSupplier<T> supplier) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        await(latch);
        if (failure.get() != null) {
            throw new AssertionError("JavaFX task failed", failure.get());
        }
        return result.get();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for JavaFX task");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for JavaFX task", e);
        }
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }
}
