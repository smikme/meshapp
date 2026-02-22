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

    public LogEntry(long timestampMs, String level, String message) {
        this.time = FMT.format(Instant.ofEpochMilli(timestampMs));
        this.level = level;
        this.message = message;
    }

    public String getTime()    { return time; }
    public String getLevel()   { return level; }
    public String getMessage() { return message; }
}
