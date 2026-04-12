package com.meshtastic.client.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Отправляет в Gitea отчёты о сбоях и проблемах, создавая issue и прикладывая ZIP-архив session-лога.
 */
public final class CrashReportService {

    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter TITLE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final URI DEFAULT_API_BASE = URI.create("https://git.privatepractice.app/api/v1");
    private static final String DEFAULT_OWNER = "covox";
    private static final String DEFAULT_REPO = "meshapp";
    private static final String DEFAULT_TOKEN = "43793bf158aa8d912892c64e17e7bef8019e7bf4";

    private final URI apiBase;
    private final String owner;
    private final String repo;
    private final String token;
    private final HttpClient httpClient;
    private final Clock clock;

    public CrashReportService(URI apiBase,
                              String owner,
                              String repo,
                              String token,
                              HttpClient httpClient,
                              Clock clock) {
        this.apiBase = normalizeApiBase(Objects.requireNonNull(apiBase, "apiBase"));
        this.owner = requireText(owner, "owner");
        this.repo = requireText(repo, "repo");
        this.token = requireText(token, "token");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static CrashReportService createDefault() {
        return new CrashReportService(
                DEFAULT_API_BASE,
                DEFAULT_OWNER,
                DEFAULT_REPO,
                DEFAULT_TOKEN,
                HttpClient.newBuilder().build(),
                Clock.systemDefaultZone()
        );
    }

    public SubmissionResult submitCrashReport(Path logFile,
                                              String comment,
                                              CrashContext context) throws IOException, InterruptedException {
        return submitReport(logFile, comment, context, ReportType.CRASH);
    }

    public SubmissionResult submitProblemReport(Path logFile,
                                                String comment,
                                                CrashContext context) throws IOException, InterruptedException {
        return submitReport(logFile, comment, context, ReportType.PROBLEM);
    }

    private SubmissionResult submitReport(Path logFile,
                                          String comment,
                                          CrashContext context,
                                          ReportType reportType) throws IOException, InterruptedException {
        Objects.requireNonNull(logFile, "logFile");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(reportType, "reportType");
        if (!Files.isRegularFile(logFile)) {
            throw new IOException("Report log file not found: " + logFile);
        }

        Path archive = createArchive(logFile);
        try {
            long issueIndex = createIssue(comment, context, reportType);
            uploadIssueAsset(issueIndex, archive);
            return new SubmissionResult(issueIndex, issuePageUri(issueIndex));
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private long createIssue(String comment,
                             CrashContext context,
                             ReportType reportType) throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("title", buildIssueTitle(context, reportType));
        payload.addProperty("body", buildIssueBody(comment, context, reportType));

        HttpRequest request = HttpRequest.newBuilder(issueCollectionUri())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "token " + token)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new IOException("Gitea issue creation failed: HTTP " + response.statusCode() + " - " + response.body());
        }

        JsonObject issue = JsonParser.parseString(response.body()).getAsJsonObject();
        if (issue.has("index") && !issue.get("index").isJsonNull()) {
            return issue.get("index").getAsLong();
        }
        if (issue.has("number") && !issue.get("number").isJsonNull()) {
            return issue.get("number").getAsLong();
        }
        throw new IOException("Gitea issue response did not contain index/number");
    }

    private void uploadIssueAsset(long issueIndex, Path archive) throws IOException, InterruptedException {
        String boundary = "----MeshAppBoundary" + UUID.randomUUID();
        String fileName = archive.getFileName().toString();
        byte[] prefix = (
                "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"attachment\"; filename=\"" + escapeQuoted(fileName) + "\"\r\n"
                        + "Content-Type: application/zip\r\n\r\n"
        ).getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(issueAssetUri(issueIndex, fileName))
                .header("Accept", "application/json")
                .header("Authorization", "token " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofFile(archive),
                        HttpRequest.BodyPublishers.ofByteArray(suffix)
                ))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new IOException("Gitea attachment upload failed: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    private Path createArchive(Path logFile) throws IOException {
        Path archive = Files.createTempFile("meshapp-crash-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry entry = new ZipEntry(logFile.getFileName().toString());
            zip.putNextEntry(entry);
            Files.copy(logFile, zip);
            zip.closeEntry();
        }
        return archive;
    }

    private String buildIssueTitle(CrashContext context, ReportType reportType) {
        return "%s: MeshApp %s on %s (%s)".formatted(
                reportType.titlePrefix(),
                context.applicationVersion(),
                context.osName(),
                TITLE_TIME_FORMAT.format(Instant.now(clock))
        );
    }

    private String buildIssueBody(String comment, CrashContext context, ReportType reportType) {
        String normalizedComment = comment == null || comment.isBlank()
                ? "Комментарий не указан."
                : comment.trim();

        return String.join("\n", List.of(
                reportType.bodyLead(),
                "",
                "Версия приложения: " + context.applicationVersion(),
                "Код сборки: " + context.versionCode(),
                "ОС: " + context.osName() + " " + context.osVersion() + " (" + context.osArch() + ")",
                "Время отправки: " + TITLE_TIME_FORMAT.format(Instant.now(clock)),
                "",
                "Комментарий пользователя:",
                normalizedComment,
                "",
                reportType.attachmentNote()
        ));
    }

    private URI issueCollectionUri() {
        return URI.create(apiBase.toString() + "/repos/" + owner + "/" + repo + "/issues");
    }

    private URI issueAssetUri(long issueIndex, String attachmentName) {
        String encodedName = URLEncoder.encode(attachmentName, StandardCharsets.UTF_8);
        return URI.create(
                apiBase.toString()
                        + "/repos/" + owner + "/" + repo + "/issues/" + issueIndex + "/assets?name=" + encodedName
        );
    }

    private URI issuePageUri(long issueIndex) {
        String base = apiBase.toString().replaceFirst("/api/v\\d+/?$", "");
        return URI.create(base + "/" + owner + "/" + repo + "/issues/" + issueIndex);
    }

    private static URI normalizeApiBase(URI apiBase) {
        String normalized = apiBase.toString().replaceAll("/+$", "");
        return URI.create(normalized);
    }

    private static String escapeQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    public record CrashContext(String applicationVersion,
                               int versionCode,
                               String osName,
                               String osVersion,
                               String osArch) {

        public CrashContext {
            applicationVersion = requireText(applicationVersion, "applicationVersion");
            osName = requireText(osName, "osName");
            osVersion = requireText(osVersion, "osVersion");
            osArch = requireText(osArch, "osArch");
        }
    }

    public record SubmissionResult(long issueIndex, URI issueUrl) {}

    private enum ReportType {
        CRASH(
                "Crash report",
                "Автоматически созданный отчёт о сбое MeshApp.",
                "ZIP-архив session-лога приложен во вложениях issue."
        ),
        PROBLEM(
                "Problem report",
                "Автоматически созданный отчёт о проблеме MeshApp.",
                "ZIP-архив session-лога текущей сессии приложен во вложениях issue."
        );

        private final String titlePrefix;
        private final String bodyLead;
        private final String attachmentNote;

        ReportType(String titlePrefix, String bodyLead, String attachmentNote) {
            this.titlePrefix = titlePrefix;
            this.bodyLead = bodyLead;
            this.attachmentNote = attachmentNote;
        }

        private String titlePrefix() {
            return titlePrefix;
        }

        private String bodyLead() {
            return bodyLead;
        }

        private String attachmentNote() {
            return attachmentNote;
        }
    }
}
