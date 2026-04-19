package com.meshtastic.client.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashReportServiceTest {

    private HttpServer server;

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void submitCrashReportCreatesIssueAndUploadsArchive() throws Exception {
        AtomicReference<String> issueAuth = new AtomicReference<>();
        AtomicReference<String> issueBody = new AtomicReference<>();
        AtomicReference<String> uploadAuth = new AtomicReference<>();
        AtomicReference<String> uploadContentType = new AtomicReference<>();
        AtomicReference<String> uploadBody = new AtomicReference<>();

        server.createContext("/api/v1/repos/covox/meshapp/issues", exchange -> {
            issueAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            issueBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"index\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        server.createContext("/api/v1/repos/covox/meshapp/issues/42/assets", exchange -> {
            uploadAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            uploadContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            uploadBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        Path logFile = Files.createTempFile("meshapp-session-", ".log");
        Files.writeString(logFile, "fatal: boom");

        CrashReportService service = new CrashReportService(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"),
                "covox",
                "meshapp",
                "test-token",
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-04-10T10:15:30Z"), ZoneOffset.UTC)
        );

        CrashReportService.SubmissionResult result = service.submitCrashReport(
                logFile,
                "Упал при открытии настроек",
                new CrashReportService.CrashContext("1.2.3", 77, "Linux", "6.8", "x86_64")
        );

        assertEquals(42L, result.issueIndex());
        assertEquals("token test-token", issueAuth.get());
        assertEquals("token test-token", uploadAuth.get());
        assertTrue(issueBody.get().contains("Упал при открытии настроек"));
        assertTrue(issueBody.get().contains("\"title\""));
        assertTrue(uploadContentType.get().startsWith("multipart/form-data; boundary="));
        assertTrue(uploadBody.get().contains("name=\"attachment\""));
        assertTrue(uploadBody.get().contains("meshapp-session-"));
    }

    @Test
    void submitProblemReportUsesProblemSpecificTitleAndBody() throws Exception {
        AtomicReference<String> issueBody = new AtomicReference<>();

        server.createContext("/api/v1/repos/covox/meshapp/issues", exchange -> {
            issueBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"index\":7}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        server.createContext("/api/v1/repos/covox/meshapp/issues/7/assets", exchange -> {
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        Path logFile = Files.createTempFile("meshapp-session-", ".log");
        Files.writeString(logFile, "ui glitch");

        CrashReportService service = new CrashReportService(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"),
                "covox",
                "meshapp",
                "test-token",
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-04-10T10:15:30Z"), ZoneOffset.UTC)
        );

        service.submitProblemReport(
                logFile,
                "Не открывается окно помощи",
                new CrashReportService.CrashContext("1.2.3", 77, "Linux", "6.8", "x86_64")
        );

        assertTrue(issueBody.get().contains("Problem report"));
        assertTrue(issueBody.get().contains("Автоматически созданный отчёт о проблеме MeshApp."));
        assertTrue(issueBody.get().contains("Не открывается окно помощи"));
        assertTrue(issueBody.get().contains("текущей сессии"));
    }

    @Test
    void submitCrashReportArchivesDirectoryPayload() throws Exception {
        AtomicReference<byte[]> uploadBody = new AtomicReference<>();
        AtomicReference<String> uploadContentType = new AtomicReference<>();

        server.createContext("/api/v1/repos/covox/meshapp/issues", exchange -> {
            byte[] response = "{\"index\":99}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        server.createContext("/api/v1/repos/covox/meshapp/issues/99/assets", exchange -> {
            uploadContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            uploadBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        Path bundleDir = Files.createTempDirectory("meshapp-bundle-");
        Files.writeString(bundleDir.resolve("meshapp-session.log"), "fatal: boom");
        Files.writeString(bundleDir.resolve("meta.json"), "{\"session\":\"abc\"}");

        CrashReportService service = new CrashReportService(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"),
                "covox",
                "meshapp",
                "test-token",
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-04-10T10:15:30Z"), ZoneOffset.UTC)
        );

        service.submitCrashReport(
                bundleDir,
                "Падает без стектрейса",
                new CrashReportService.CrashContext("1.2.3", 77, "Windows", "10", "x86_64")
        );

        byte[] zipBytes = extractZipPayload(uploadBody.get(), uploadContentType.get());
        List<String> entryNames = readZipEntryNames(zipBytes);
        assertTrue(entryNames.stream().anyMatch(name -> name.endsWith("/meshapp-session.log")));
        assertTrue(entryNames.stream().anyMatch(name -> name.endsWith("/meta.json")));
    }

    private static byte[] extractZipPayload(byte[] multipartBody, String contentType) {
        String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        byte[] headerDelimiter = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] trailerDelimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.UTF_8);

        int partStart = indexOf(multipartBody, boundaryBytes, 0);
        int payloadStart = indexOf(multipartBody, headerDelimiter, partStart) + headerDelimiter.length;
        int payloadEnd = indexOf(multipartBody, trailerDelimiter, payloadStart);

        byte[] zip = new byte[payloadEnd - payloadStart];
        System.arraycopy(multipartBody, payloadStart, zip, 0, zip.length);
        return zip;
    }

    private static List<String> readZipEntryNames(byte[] zipBytes) throws Exception {
        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryNames.add(entry.getName());
            }
        }
        return entryNames;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int fromIndex) {
        outer:
        for (int i = Math.max(0, fromIndex); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new IllegalArgumentException("Needle not found in multipart payload");
    }
}
