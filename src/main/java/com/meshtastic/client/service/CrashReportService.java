package com.meshtastic.client.service;

import com.meshtastic.client.i18n.I18n;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Prepares a local diagnostic archive and a GitHub issue form. No network uploads or tokens. */
public final class CrashReportService {
    private static final String ISSUE_FORM = "https://github.com/smikme/meshapp/issues/new";
    private final Path outputDirectory;
    private final Clock clock;

    public CrashReportService(Path outputDirectory, Clock clock) {
        this.outputDirectory = Objects.requireNonNull(outputDirectory);
        this.clock = Objects.requireNonNull(clock);
    }

    public static CrashReportService createDefault() {
        return new CrashReportService(Path.of(System.getProperty("user.home"), ".meshapp", "reports"),
                Clock.systemUTC());
    }

    public PreparedReport prepareCrashReport(Path logFile, String comment, CrashContext context) throws IOException {
        return prepare(logFile, comment, context, "crash");
    }

    public PreparedReport prepareProblemReport(Path logFile, String comment, CrashContext context) throws IOException {
        return prepare(logFile, comment, context, "problem");
    }

    private PreparedReport prepare(Path source, String comment, CrashContext context, String type) throws IOException {
        Objects.requireNonNull(source);
        Objects.requireNonNull(context);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IOException("Report payload not found: " + source);
        }
        Files.createDirectories(outputDirectory);
        Path folder = Files.createTempDirectory(outputDirectory, "meshapp-report-");
        Path archive = folder.resolve("diagnostics.zip");
        Path report = folder.resolve("report.md");
        String title = I18n.t("crashReport.issue.title." + type) + ": MeshApp " + context.applicationVersion();
        List<String> lines = new ArrayList<>();
        lines.add(I18n.t("crashReport.issue.bodyLead." + type));
        lines.add("");
        lines.add(I18n.t("crashReport.issue.label.applicationVersion", context.applicationVersion()));
        lines.add(I18n.t("crashReport.issue.label.versionCode", Integer.toString(context.versionCode())));
        lines.add(I18n.t("crashReport.issue.label.os", context.osName(), context.osVersion(), context.osArch()));
        lines.add(I18n.t("crashReport.issue.label.submittedAt", Instant.now(clock)));
        lines.add("");
        lines.add(I18n.t("crashReport.issue.attachment." + type));
        String metadata = String.join("\n", lines);
        String body = metadata + "\n\n" + I18n.t("crashReport.issue.label.userComment") + "\n"
                + (comment == null || comment.isBlank() ? I18n.t("crashReport.issue.comment.empty") : comment.trim());
        try {
            Files.writeString(report, "# " + title + "\n\n" + body + "\n", StandardCharsets.UTF_8);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                addFile(zip, report, "report.md");
                if (Files.isDirectory(source)) {
                    try (var files = Files.walk(source)) {
                        for (Path file : files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                            addFile(zip, file, "diagnostics/" + source.relativize(file).toString().replace('\\', '/'));
                        }
                    }
                } else {
                    addFile(zip, source, "diagnostics/" + source.getFileName());
                }
            }
            // Keep large comments in report.md; browser/issue-form URL limits are much smaller.
            String query = "?title=" + encode(title) + "&body=" + encode(body);
            if (query.length() > 6000) {
                query = "?title=" + encode(title) + "&body=" + encode(metadata);
            }
            return new PreparedReport(archive, report, URI.create(ISSUE_FORM + query));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(report);
            Files.deleteIfExists(folder);
            throw e;
        }
    }

    private static void addFile(ZipOutputStream zip, Path path, String name) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("Report preparation cancelled");
        }
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(path, zip);
        zip.closeEntry();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record CrashContext(String applicationVersion, int versionCode, String osName, String osVersion, String osArch) {}
    public record PreparedReport(Path archive, Path report, URI issueUrl) {}
}
