package com.meshtastic.client.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MeshpicUploadServiceTest {
    @TempDir Path directory;
    private HttpServer server;
    private ExecutorService executor;
    private URI origin;
    private MeshpicUploadService service;
    private final AtomicInteger gets = new AtomicInteger();
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicReference<com.sun.net.httpserver.Headers> uploadHeaders = new AtomicReference<>();
    private volatile byte[] uploadBody;
    private volatile int uploadStatus = 201;
    private volatile long delayMillis;
    private volatile boolean sessionCookie = true;
    private volatile String home = "<meta content='test-csrf' name='csrf-token'>";
    private volatile String response = """
            {"short_id":"feZ","expires_at":"2026-11-12T18:00:19.170188+00:00",
             "image_count":1,"images":[{"id":"WHm","url":"/image/feZ/WHm"}],"view_count":0}
            """;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/upload")) {
                posts.incrementAndGet();
                uploadHeaders.set(exchange.getRequestHeaders());
                uploadBody = exchange.getRequestBody().readAllBytes();
                if (delayMillis > 0) {
                    try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                reply(exchange, uploadStatus, response);
            } else {
                gets.incrementAndGet();
                if (sessionCookie) exchange.getResponseHeaders().add("Set-Cookie", "meshpic_session=test-session; HttpOnly; Path=/");
                reply(exchange, 200, home);
            }
        });
        server.start();
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        service = new MeshpicUploadService(HttpClient.newHttpClient(), origin, Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void uploadsMultipartWithSessionAndCsrfAndSeparatesUrls() throws Exception {
        Path file = png();
        var image = service.upload(file).join();
        assertEquals(origin.resolve("feZ").toString(), image.shareUrl());
        assertEquals(origin.resolve("image/feZ/WHm").toString(), image.fullUrl());
        assertEquals(image.fullUrl() + "?s=t", image.previewUrl());
        assertEquals(Instant.parse("2026-11-12T18:00:19.170188Z"), image.expiresAt());
        var headers = uploadHeaders.get();
        assertEquals("meshpic_session=test-session", headers.getFirst("Cookie"));
        assertEquals("test-csrf", headers.getFirst("X-CSRF-Token"));
        assertEquals("1", headers.getFirst("X-Meshpic-UI"));
        assertEquals("same-origin", headers.getFirst("Sec-Fetch-Site"));
        assertEquals("cors", headers.getFirst("Sec-Fetch-Mode"));
        assertEquals("empty", headers.getFirst("Sec-Fetch-Dest"));
        assertEquals(origin.toString(), headers.getFirst("Referer"));
        assertNull(headers.getFirst("X-API-Key"));
        String boundary = headers.getFirst("Content-Type").split("boundary=")[1];
        String wire = new String(uploadBody, StandardCharsets.ISO_8859_1);
        assertTrue(wire.startsWith("--" + boundary + "\r\n"));
        assertTrue(wire.contains("name=\"file\"; filename=\"image.png\"\r\nContent-Type: image/png\r\n\r\n"));
        assertTrue(wire.contains(Files.readString(file, StandardCharsets.ISO_8859_1)));
        assertTrue(wire.endsWith("\r\n--" + boundary + "--\r\n"));
    }

    @Test
    void validatesFilesBeforeNetworkAndBoundsReads() throws Exception {
        Path huge = directory.resolve("large.png");
        try (var file = new RandomAccessFile(huge.toFile(), "rw")) {
            file.setLength(MeshpicUploadService.MAX_IMAGE_BYTES + 1L);
        }
        assertError("chat.image.tooLarge", huge);
        assertError("chat.image.invalidFile", Files.createFile(directory.resolve("empty.png")));
        assertError("chat.image.invalidFile", directory.resolve("missing.png"));
        assertError("chat.image.invalidFile", directory);
        assertError("chat.image.unsupported", Files.writeString(directory.resolve("fake.jpg"), "not an image"));
        assertError("chat.image.unsupported", Files.write(directory.resolve("image.bmp"), new byte[]{0x42, 0x4d, 0, 0}));
        assertError("chat.image.unsupported", Files.write(directory.resolve("image.tiff"), new byte[]{0x49, 0x49, 0x2a, 0}));
        assertEquals(0, gets.get());
        assertEquals(0, posts.get());
    }

    @Test
    void acceptsExactSizeLimitAndSniffsWebpInsteadOfExtension() throws Exception {
        Path file = png();
        try (var data = new RandomAccessFile(file.toFile(), "rw")) { data.setLength(MeshpicUploadService.MAX_IMAGE_BYTES); }
        assertNotNull(service.upload(file).join());
        Path webp;
        try (var resource = getClass().getResourceAsStream("/images/meshpic-input.webp")) {
            assertNotNull(resource, "WebP test fixture must be available");
            webp = Files.write(directory.resolve("misnamed.png"), resource.readAllBytes());
        }
        assertNotNull(service.upload(webp).join());
        assertTrue(new String(uploadBody, StandardCharsets.ISO_8859_1).contains("Content-Type: image/webp"));
    }

    @Test
    void rejectsMissingSessionOrCsrfWithoutPosting() throws Exception {
        home = "<html>No CSRF token</html>";
        assertError("chat.image.serviceChanged", png());
        home = "<meta name=\"csrf-token\" content=\"test-csrf\">";
        sessionCookie = false;
        assertError("chat.image.serviceChanged", png());
        assertEquals(0, posts.get());
    }

    @Test
    void rejectsInvalidJsonMissingFieldsAndUntrustedImagePaths() throws Exception {
        for (String invalid : new String[]{"<html>bad</html>", "null", "{}",
                "{\"short_id\":\"feZ\",\"images\":[]}",
                "{\"short_id\":\"feZ\",\"images\":[{\"id\":\"WHm\",\"url\":\"https://elsewhere.test/image\"}]}",
                response.replace("2026-11-12T18:00:19.170188+00:00", "invalid-date")}) {
            response = invalid;
            assertError("chat.image.serviceChanged", png());
        }
    }

    @Test
    void reportsHttpErrorsWithoutRetryingUpload() throws Exception {
        int[] statuses = {400, 401, 403, 413, 415, 429, 500, 302};
        String[] keys = {"unsupported", "sessionFailed", "sessionFailed", "tooLarge", "unsupported", "rateLimited", "uploadFailed", "uploadFailed"};
        for (int i = 0; i < statuses.length; i++) {
            uploadStatus = statuses[i];
            assertError("chat.image." + keys[i], png());
        }
        assertEquals(statuses.length, posts.get());
    }

    @Test
    void reportsTimeoutAndConnectionFailure() throws Exception {
        delayMillis = 700;
        service = new MeshpicUploadService(HttpClient.newHttpClient(), origin, Duration.ofMillis(200));
        assertError("chat.image.timeout", png());
        server.stop(0);
        assertError("chat.image.networkFailed", png());
    }

    private Path png() throws IOException {
        return Files.write(directory.resolve("test.png"), Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII="));
    }

    private void assertError(String key, Path file) {
        var exception = assertThrows(CompletionException.class, () -> service.upload(file).join());
        assertEquals(key, MeshpicUploadService.errorKey(exception));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
