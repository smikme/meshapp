package com.meshtastic.client.service;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.meshtastic.client.components.chat.MeshFilesImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Uploads chat images to MeshFiles and returns public original/preview URLs.
 *
 * <p>The service sends the selected file as a raw request body, because the
 * MeshFiles API does not accept multipart form uploads.
 */
public final class MeshFilesUploadService {

    private static final Logger log = LoggerFactory.getLogger(MeshFilesUploadService.class);
    private static final Gson JSON = new Gson();

    private static final URI UPLOAD_URI = URI.create("https://d.privatepractice.app/api/files");
    private static final String API_KEY = "17386E82-EC3E-4635-9BBA-B049699413F2";
    private static final String USER_AGENT = "MeshApp MeshFiles Upload";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);
    private static final int SIGNATURE_BYTES = 12;
    private static final MeshFilesUploadService INSTANCE = new MeshFilesUploadService();

    private final HttpClient httpClient;

    public static MeshFilesUploadService getInstance() {
        return INSTANCE;
    }

    /**
     * Creates a service using the default shared {@link HttpClient} settings.
     */
    public MeshFilesUploadService() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /**
     * Creates a service with an injected client for tests or alternate runtime wiring.
     *
     * @param httpClient HTTP client used for upload requests
     */
    MeshFilesUploadService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Uploads an image file to MeshFiles.
     *
     * <p>The returned future completes with the public original and preview URLs.
     * It completes exceptionally when the file is missing, empty, unsupported, or
     * when MeshFiles returns a non-success response.
     *
     * @param imagePath path to a local supported image file
     * @return asynchronous upload result
     */
    public CompletableFuture<MeshFilesImage> upload(Path imagePath) {
        try {
            Path normalizedPath = validateImagePath(imagePath);
            String contentType = detectSupportedImageContentType(normalizedPath)
                    .orElseThrow(() -> new IllegalArgumentException("Unsupported image format"));
            HttpRequest request = HttpRequest.newBuilder(UPLOAD_URI)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", contentType)
                    .header("X-API-Key", API_KEY)
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofFile(normalizedPath))
                    .build();

            return httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(this::parseUploadResponse);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static Path validateImagePath(Path imagePath) throws IOException {
        if (imagePath == null) {
            throw new IllegalArgumentException("Image path is empty");
        }
        Path normalized = imagePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Selected file is not a regular file");
        }
        if (Files.size(normalized) <= 0) {
            throw new IllegalArgumentException("Selected image is empty");
        }
        return normalized;
    }

    private MeshFilesImage parseUploadResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            log.warn("MeshFiles upload failed: HTTP {} {}", status, response.body());
            throw new IllegalStateException("MeshFiles upload failed: HTTP " + status);
        }

        try {
            UploadResponse parsed = JSON.fromJson(response.body(), UploadResponse.class);
            if (parsed == null
                    || isBlank(parsed.id())
                    || isBlank(parsed.url())
                    || isBlank(parsed.previewUrl())) {
                throw new IllegalStateException("MeshFiles returned incomplete upload response");
            }
            return new MeshFilesImage(parsed.id(), parsed.url(), parsed.previewUrl());
        } catch (JsonParseException e) {
            throw new IllegalStateException("MeshFiles returned invalid JSON", e);
        }
    }

    private static Optional<String> detectSupportedImageContentType(Path path) throws IOException {
        byte[] signature = readSignature(path);
        if (startsWith(signature, 0xff, 0xd8, 0xff)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(signature, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) {
            return Optional.of("image/png");
        }
        if (startsWith(signature, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
                || startsWith(signature, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61)) {
            return Optional.of("image/gif");
        }
        if (startsWith(signature, 0x42, 0x4d)) {
            return Optional.of("image/bmp");
        }
        if (startsWith(signature, 0x49, 0x49, 0x2a, 0x00)
                || startsWith(signature, 0x4d, 0x4d, 0x00, 0x2a)) {
            return Optional.of("image/tiff");
        }
        return Optional.empty();
    }

    private static byte[] readSignature(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(SIGNATURE_BYTES);
        }
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[i] & 0xff) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record UploadResponse(String id, String url, String previewUrl, String expiresAt) {}
}
