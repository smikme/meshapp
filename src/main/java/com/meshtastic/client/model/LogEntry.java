package com.meshtastic.client.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Строка лога для отображения в TableView.
 */
public class LogEntry {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final String time;
    private final String level;
    private final String message;
    private final String fullMessage;

    public LogEntry(long timestampMs, String level, String message) {
        this(timestampMs, level, message, message);
    }

    public LogEntry(long timestampMs, String level, String message, String fullMessage) {
        this.time = FMT.format(Instant.ofEpochMilli(timestampMs));
        this.level = level;
        this.message = summarize(message, fullMessage);
        this.fullMessage = normalizeFullMessage(fullMessage, this.message);
    }

    public String getTime()    { return time; }
    public String getLevel()   { return level; }
    public String getMessage() { return message; }
    public String getFullMessage() { return fullMessage; }

    private static String summarize(String message, String fullMessage) {
        String summary = firstNonBlankLine(message);
        if (summary == null) {
            summary = firstNonBlankLine(fullMessage);
        }
        return summary != null ? summary : "";
    }

    private static String normalizeFullMessage(String fullMessage, String fallback) {
        if (fullMessage != null && !fullMessage.isBlank()) {
            return fullMessage;
        }
        return fallback != null ? fallback : "";
    }

    private static String firstNonBlankLine(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n")) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return null;
    }
}
