package com.meshtastic.client.forms;

import com.meshtastic.client.model.LogEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class FormLogsFormattingTest {

    @Test
    void includesStacktraceInExportedLogText() {
        LogEntry entry = new LogEntry(
                1_775_588_451_757L,
                "ERROR",
                "Uncaught exception in thread 'JavaFX Application Thread'",
                "Uncaught exception in thread 'JavaFX Application Thread'\n"
                        + "java.lang.NullPointerException: boom\n"
                        + "\tat com.meshtastic.client.forms.FormNodes.showDetail(FormNodes.java:750)"
        );

        String exported = FormLogs.formatLogEntries(List.of(entry));

        assertTrue(exported.contains("ERROR: Uncaught exception in thread 'JavaFX Application Thread'"));
        assertTrue(exported.contains("java.lang.NullPointerException: boom"));
        assertTrue(exported.contains("at com.meshtastic.client.forms.FormNodes.showDetail"));
    }

    @Test
    void trimsVisibleLogEntriesFromTheHead() {
        List<Integer> entries = new ArrayList<>(List.of(1, 2, 3, 4, 5));

        FormLogs.trimVisibleLogEntries(entries, 3);

        assertEquals(List.of(3, 4, 5), entries);
    }
}
