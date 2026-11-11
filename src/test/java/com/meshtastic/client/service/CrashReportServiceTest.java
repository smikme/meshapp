package com.meshtastic.client.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class CrashReportServiceTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC);
    private final CrashReportService.CrashContext context =
            new CrashReportService.CrashContext("v2.4.0", 2004000, "Linux", "6.8", "x86_64");

    @Test
    void preparesPersistentArchiveAndIssueFormWithoutSendingAnything() throws Exception {
        Path source = directory.resolve("session.log");
        Files.writeString(source, "fatal: boom");
        var result = new CrashReportService(directory.resolve("reports"), clock)
                .prepareCrashReport(source, "Ошибка & вопрос?", context);
        assertTrue(Files.exists(source));
        assertTrue(Files.exists(result.archive()));
        assertEquals("github.com", result.issueUrl().getHost());
        assertEquals("/smikme/meshapp/issues/new", result.issueUrl().getPath());
        String query = URLDecoder.decode(result.issueUrl().getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(query.contains("Ошибка & вопрос?"));
        assertTrue(query.contains("2004000"));
        assertFalse(query.contains(directory.toString()));
        try (ZipFile zip = new ZipFile(result.archive().toFile())) {
            assertEquals("fatal: boom", new String(zip.getInputStream(zip.getEntry("diagnostics/session.log")).readAllBytes(), StandardCharsets.UTF_8));
            assertNotNull(zip.getEntry("report.md"));
        }
    }

    @Test
    void preservesCompleteCommentAndNestedDiagnosticsWhenIssueUrlWouldBeTooLong() throws Exception {
        Path source = directory.resolve("bundle");
        Files.createDirectories(source.resolve("nested"));
        Files.writeString(source.resolve("nested/session.log"), "error");
        String comment = "Ошибка 🛰️ ".repeat(3000);
        var result = new CrashReportService(directory.resolve("reports"), clock)
                .prepareProblemReport(source, comment, context);
        assertTrue(result.issueUrl().toString().length() < 6500);
        assertTrue(Files.readString(result.report()).contains(comment.trim()));
        try (ZipFile zip = new ZipFile(result.archive().toFile())) {
            assertNotNull(zip.getEntry("diagnostics/nested/session.log"));
        }
    }

    @Test
    void repeatedExportsNeverOverwriteEarlierReport() throws Exception {
        Path source = directory.resolve("session.log");
        Files.writeString(source, "first");
        var service = new CrashReportService(directory.resolve("reports"), clock);
        var first = service.prepareProblemReport(source, "first", context);
        var second = service.prepareProblemReport(source, "second", context);
        assertNotEquals(first.archive(), second.archive());
        assertTrue(Files.readString(first.report()).contains("first"));
        assertTrue(Files.readString(second.report()).contains("second"));
    }

    @Test
    void missingPayloadFailsWithoutCreatingEmptyReport() {
        var service = new CrashReportService(directory.resolve("reports"), clock);
        assertThrows(java.io.IOException.class, () -> service.prepareCrashReport(directory.resolve("missing"), "", context));
        assertFalse(Files.exists(directory.resolve("reports")));
    }
}
