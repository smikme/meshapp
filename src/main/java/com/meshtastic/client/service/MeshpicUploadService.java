package com.meshtastic.client.service;

import com.google.gson.Gson;
import com.meshtastic.client.components.chat.ChatImage;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * Meshpic's session/CSRF protected web upload protocol, verified 2026-11-11.
 * This is not a versioned public API. See docs/meshpic-integration.md.
 * Each upload gets its own anonymous session; cookies and tokens stay in memory.
 */
public final class MeshpicUploadService {
    public static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    public static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".gif", ".webp");
    private static final Gson JSON = new Gson();
    private static final Pattern META = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w-]+)\\s*=\\s*([\"'])(.*?)\\2");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9]{1,64}");
    private static final MeshpicUploadService INSTANCE = new MeshpicUploadService();
    private final HttpClient client;
    private final URI origin;
    private final Duration timeout;

    public static MeshpicUploadService getInstance() { return INSTANCE; }

    public MeshpicUploadService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://meshpic.org/"), Duration.ofSeconds(90));
    }

    MeshpicUploadService(HttpClient client, URI origin, Duration timeout) {
        this.client = client;
        this.origin = origin;
        this.timeout = timeout;
    }

    /** File I/O and validation run off the FX thread, before any HTTP request. */
    public CompletableFuture<ChatImage> upload(Path path) {
        return CompletableFuture.supplyAsync(() -> readImage(path))
                .thenCompose(file -> client.sendAsync(request(origin).GET().build(),
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                        .thenCompose(response -> upload(file, session(response))));
    }

    private CompletableFuture<ChatImage> upload(ImageFile file, Session session) {
        String boundary = "MeshApp-" + UUID.randomUUID();
        String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image."
                + file.extension() + "\"\r\nContent-Type: " + file.contentType() + "\r\n\r\n";
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(prefix, StandardCharsets.UTF_8),
                HttpRequest.BodyPublishers.ofByteArray(file.bytes()),
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
        HttpRequest request = request(origin.resolve("upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", session.cookie()).header("X-CSRF-Token", session.csrf())
                .header("X-Meshpic-UI", "1")
                .header("Origin", origin.resolve("/").toString().replaceAll("/$", ""))
                .header("Referer", origin.toString())
                .header("Sec-Fetch-Site", "same-origin").header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty").POST(body).build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::parseResponse);
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", "application/json, text/html;q=0.9")
                .header("User-Agent", "MeshApp Meshpic Upload");
    }

    private static Session session(HttpResponse<String> response) {
        checkStatus(response.statusCode());
        String csrf = null;
        var tags = META.matcher(response.body());
        while (tags.find()) {
            String name = null, content = null;
            var attrs = ATTRIBUTE.matcher(tags.group());
            while (attrs.find()) {
                if ("name".equalsIgnoreCase(attrs.group(1))) name = attrs.group(3);
                if ("content".equalsIgnoreCase(attrs.group(1))) content = attrs.group(3);
            }
            if ("csrf-token".equals(name)) csrf = content;
        }
        String cookie = null;
        for (String header : response.headers().allValues("Set-Cookie")) {
            try {
                for (HttpCookie parsed : HttpCookie.parse(header)) {
                    if ("meshpic_session".equals(parsed.getName())
                            && parsed.getValue().matches("[A-Za-z0-9._-]+")) {
                        cookie = parsed.getName() + "=" + parsed.getValue();
                    }
                }
            } catch (IllegalArgumentException ignored) { /* Fail closed below. */ }
        }
        if (csrf == null || !csrf.matches("[A-Za-z0-9_-]+") || cookie == null) {
            throw failure("chat.image.serviceChanged");
        }
        return new Session(csrf, cookie);
    }

    private ChatImage parseResponse(HttpResponse<String> response) {
        checkStatus(response.statusCode());
        try {
            UploadResponse result = JSON.fromJson(response.body(), UploadResponse.class);
            if (result == null || !validId(result.short_id()) || result.images() == null
                    || result.images().size() != 1) throw new IllegalArgumentException();
            UploadedImage image = result.images().getFirst();
            if (image == null || !validId(image.id())
                    || !("/image/" + result.short_id() + "/" + image.id()).equals(image.url())) {
                throw new IllegalArgumentException();
            }
            Instant expiry = result.expires_at() == null ? null : Instant.parse(result.expires_at());
            String full = origin.resolve(image.url()).toString();
            return new ChatImage(result.short_id(), origin.resolve(result.short_id()).toString(),
                    full + "?s=t", full, expiry);
        } catch (RuntimeException e) {
            throw failure("chat.image.serviceChanged");
        }
    }

    private static boolean validId(String value) { return value != null && ID.matcher(value).matches(); }

    private static void checkStatus(int status) {
        if (status >= 200 && status < 300) return;
        throw failure(switch (status) {
            case 400, 415 -> "chat.image.unsupported";
            case 401, 403 -> "chat.image.sessionFailed";
            case 413 -> "chat.image.tooLarge";
            case 429 -> "chat.image.rateLimited";
            default -> "chat.image.uploadFailed";
        });
    }

    private static ImageFile readImage(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) throw failure("chat.image.invalidFile");
            long size = Files.size(path);
            if (size > MAX_IMAGE_BYTES) throw failure("chat.image.tooLarge");
            if (size == 0) throw failure("chat.image.invalidFile");
            byte[] bytes;
            try (var stream = Files.newInputStream(path)) { bytes = stream.readNBytes(MAX_IMAGE_BYTES + 1); }
            if (bytes.length > MAX_IMAGE_BYTES) throw failure("chat.image.tooLarge");
            if (startsWith(bytes, 0, 0xff, 0xd8, 0xff)) return new ImageFile(bytes, "image/jpeg", "jpg");
            if (startsWith(bytes, 0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
                return new ImageFile(bytes, "image/png", "png");
            if (startsWith(bytes, 0, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
                    || startsWith(bytes, 0, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61))
                return new ImageFile(bytes, "image/gif", "gif");
            if (startsWith(bytes, 0, 0x52, 0x49, 0x46, 0x46) && startsWith(bytes, 8, 0x57, 0x45, 0x42, 0x50))
                return new ImageFile(bytes, "image/webp", "webp");
            throw failure("chat.image.unsupported");
        } catch (IOException e) { throw failure("chat.image.invalidFile"); }
    }

    private static boolean startsWith(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) if ((bytes[offset + i] & 0xff) != expected[i]) return false;
        return true;
    }

    public static String errorKey(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof UploadException upload) return upload.getMessage();
        if (error instanceof HttpTimeoutException) return "chat.image.timeout";
        if (error instanceof IOException) return "chat.image.networkFailed";
        return "chat.image.uploadFailed";
    }

    private static UploadException failure(String key) { return new UploadException(key); }
    private static final class UploadException extends RuntimeException {
        private UploadException(String key) { super(key); }
    }
    private record ImageFile(byte[] bytes, String contentType, String extension) {}
    private record Session(String csrf, String cookie) {}
    private record UploadedImage(String id, String url) {}
    private record UploadResponse(String short_id, String expires_at, List<UploadedImage> images) {}
}
