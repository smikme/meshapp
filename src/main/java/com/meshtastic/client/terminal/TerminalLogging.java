package com.meshtastic.client.terminal;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import com.meshtastic.client.logging.UiLogAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Terminal mode owns stdout/stderr while Lanterna is active.
 */
final class TerminalLogging {

    private TerminalLogging() {}

    static void configureForTerminal() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            return;
        }

        for (Logger logger : context.getLoggerList()) {
            detachConsoleAppenders(logger);
        }
        ensureUiLogAppender(context);
    }

    private static void detachConsoleAppenders(Logger logger) {
        List<String> consoleAppenderNames = new ArrayList<>();
        Iterator<Appender<ILoggingEvent>> appenders = logger.iteratorForAppenders();
        while (appenders.hasNext()) {
            Appender<ILoggingEvent> appender = appenders.next();
            if (appender instanceof ConsoleAppender) {
                consoleAppenderNames.add(appender.getName());
            }
        }
        for (String appenderName : consoleAppenderNames) {
            logger.detachAppender(appenderName);
        }
    }

    private static void ensureUiLogAppender(LoggerContext context) {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Iterator<Appender<ILoggingEvent>> appenders = root.iteratorForAppenders();
        while (appenders.hasNext()) {
            if (appenders.next() instanceof UiLogAppender) {
                return;
            }
        }

        UiLogAppender appender = new UiLogAppender();
        appender.setContext(context);
        appender.setName("TERMINAL_LOG_BUFFER");
        appender.start();
        root.addAppender(appender);
    }
}
