package com.meshtastic.client.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Replaces JavaFX CSS JUL formatting with a lightweight bridge to the app log.
 *
 * <p>JavaFX reports CSS conversion warnings through {@code javafx.css}. The
 * default JUL console formatter reads {@link LogRecord#getSourceClassName()},
 * which forces {@code LogRecord.inferCaller()} to walk the stack on the JavaFX
 * application thread. Repeated CSS warnings during {@code applyCss()} can then
 * become visible UI stalls.</p>
 */
public final class JavaFxCssWarningGuard {

    private static final Logger log = LoggerFactory.getLogger(JavaFxCssWarningGuard.class);
    private static final String CSS_LOGGER_NAME = "javafx.css";
    private static final Duration REPEAT_LOG_INTERVAL = Duration.ofMinutes(1);
    private static final int MAX_TRACKED_WARNINGS = 256;
    private static final AtomicBoolean installed = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, WarningState> warningStates = new ConcurrentHashMap<>();

    private JavaFxCssWarningGuard() {}

    public static void install() {
        if (!installed.compareAndSet(false, true)) {
            return;
        }

        java.util.logging.Logger cssLogger = java.util.logging.Logger.getLogger(CSS_LOGGER_NAME);
        cssLogger.setUseParentHandlers(false);
        cssLogger.setLevel(Level.WARNING);
        for (Handler handler : cssLogger.getHandlers()) {
            cssLogger.removeHandler(handler);
            closeQuietly(handler);
        }

        CssWarningHandler handler = new CssWarningHandler();
        handler.setLevel(Level.WARNING);
        cssLogger.addHandler(handler);
        log.debug("Installed JavaFX CSS warning guard");
    }

    static void resetForTests() {
        java.util.logging.Logger cssLogger = java.util.logging.Logger.getLogger(CSS_LOGGER_NAME);
        for (Handler handler : cssLogger.getHandlers()) {
            cssLogger.removeHandler(handler);
            closeQuietly(handler);
        }
        cssLogger.setUseParentHandlers(true);
        cssLogger.setLevel(null);
        warningStates.clear();
        installed.set(false);
    }

    private static void closeQuietly(Handler handler) {
        try {
            handler.close();
        } catch (RuntimeException ignored) {
            // Best effort cleanup of any direct JUL handlers previously attached to javafx.css.
        }
    }

    private static final class CssWarningHandler extends Handler {
        @Override
        public void publish(LogRecord record) {
            if (record == null || !isLoggable(record)) {
                return;
            }

            String message = renderMessage(record);
            if (message.isBlank()) {
                message = "<empty JavaFX CSS warning>";
            }
            if (warningStates.size() > MAX_TRACKED_WARNINGS) {
                warningStates.clear();
            }

            WarningState state = warningStates.computeIfAbsent(message, ignored -> new WarningState());
            int suppressedCount = state.markAndGetSuppressedCount(System.currentTimeMillis());
            if (suppressedCount < 0) {
                return;
            }

            if (suppressedCount > 0) {
                log.warn("JavaFX CSS warning: {} ({} repeated warning(s) suppressed)",
                        message, suppressedCount);
                return;
            }
            log.warn("JavaFX CSS warning: {}", message);
        }

        @Override
        public void flush() {
            // SLF4J/logback owns flushing.
        }

        @Override
        public void close() {
            warningStates.clear();
        }

        private String renderMessage(LogRecord record) {
            String message = Objects.toString(record.getMessage(), "");
            Object[] parameters = record.getParameters();
            if (parameters != null && parameters.length > 0) {
                try {
                    message = MessageFormat.format(message, parameters);
                } catch (IllegalArgumentException ignored) {
                    // Keep the raw message if JUL-style parameter formatting fails.
                }
            }

            Throwable thrown = record.getThrown();
            if (thrown == null) {
                return message;
            }
            String thrownMessage = thrown.getMessage();
            if (thrownMessage == null || thrownMessage.isBlank()) {
                return message + " [" + thrown.getClass().getName() + "]";
            }
            return message + " [" + thrown.getClass().getName() + ": " + thrownMessage + "]";
        }
    }

    private static final class WarningState {
        private long lastLoggedMillis;
        private int suppressedCount;

        synchronized int markAndGetSuppressedCount(long nowMillis) {
            if (lastLoggedMillis == 0
                    || nowMillis - lastLoggedMillis >= REPEAT_LOG_INTERVAL.toMillis()) {
                int result = suppressedCount;
                suppressedCount = 0;
                lastLoggedMillis = nowMillis;
                return result;
            }

            if (suppressedCount < Integer.MAX_VALUE) {
                suppressedCount++;
            }
            return -1;
        }
    }
}
