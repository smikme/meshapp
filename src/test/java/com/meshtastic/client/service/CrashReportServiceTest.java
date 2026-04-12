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
import java.util.concurrent.atomic.AtomicReference;

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
}
