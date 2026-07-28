package com.meshtastic.client.components.map;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent HTTP freshness and validation data stored beside a cached map tile.
 * Metadata enables cache expiration checks and conditional requests using
 * {@code ETag} and {@code Last-Modified} validators.
 *
 * @param expiresAt   instant after which the cached tile must be revalidated
 * @param etag        server-provided entity tag, or an empty string
 * @param lastModified server-provided HTTP modification date, or an empty string
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record TileCacheMetadata(Instant expiresAt, String etag, String lastModified) {
    /** Minimum fallback lifetime used when a response has no usable cache headers. */
    static final Duration FALLBACK_TTL = Duration.ofDays(7);
    private static final Pattern MAX_AGE = Pattern.compile("(?:^|,)\\s*max-age=(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * Returns the sidecar metadata path for a tile.
     *
     * @param tile cached tile path
     * @return metadata path beside the tile
     */
    static Path pathFor(Path tile) {
        return tile.resolveSibling(tile.getFileName() + ".http-cache");
    }

    /**
     * Reads cached metadata, treating absent, malformed, or unreadable data as a cache miss.
     *
     * @param tile cached tile path
     * @return parsed metadata, or an empty value
     */
    static Optional<TileCacheMetadata> load(Path tile) {
        Path metadata = pathFor(tile);
        if (!Files.isRegularFile(metadata)) {
            return Optional.empty();
        }
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(metadata)) {
            values.load(input);
            return Optional.of(new TileCacheMetadata(
                    Instant.parse(values.getProperty("expiresAt")),
                    values.getProperty("etag", ""),
                    values.getProperty("lastModified", "")
            ));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Tests whether a tile can be reused without a network request.
     * Files created before sidecar metadata was introduced receive the fallback lifetime.
     *
     * @param tile cached tile path
     * @param now  current instant
     * @return {@code true} when the tile is still fresh
     */
    static boolean isFresh(Path tile, Instant now) {
        Optional<TileCacheMetadata> metadata = load(tile);
        if (metadata.isPresent()) {
            return now.isBefore(metadata.get().expiresAt());
        }
        try {
            return now.isBefore(Files.getLastModifiedTime(tile).toInstant().plus(FALLBACK_TTL));
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * Builds metadata from HTTP response headers, retaining validators omitted by a
     * {@code 304 Not Modified} response.
     *
     * @param headers  response headers
     * @param now      response time
     * @param previous prior metadata, or {@code null}
     * @return updated metadata
     */
    static TileCacheMetadata fromHeaders(HttpHeaders headers, Instant now, TileCacheMetadata previous) {
        Instant expires = cacheControlExpiry(headers, now)
                .or(() -> expiresHeader(headers))
                .orElse(now.plus(FALLBACK_TTL));
        String etag = headers.firstValue("ETag")
                .orElse(previous == null ? "" : previous.etag());
        String lastModified = headers.firstValue("Last-Modified")
                .orElse(previous == null ? "" : previous.lastModified());
        return new TileCacheMetadata(expires, etag, lastModified);
    }

    /**
     * Adds available HTTP cache validators to a request.
     *
     * @param request request builder to update
     */
    void addValidators(HttpRequest.Builder request) {
        if (!etag.isBlank()) {
            request.header("If-None-Match", etag);
        }
        if (!lastModified.isBlank()) {
            request.header("If-Modified-Since", lastModified);
        }
    }

    /**
     * Writes this metadata beside a cached tile.
     *
     * @param tile cached tile path
     * @throws IOException if the sidecar cannot be written
     */
    void save(Path tile) throws IOException {
        Properties values = new Properties();
        values.setProperty("expiresAt", expiresAt.toString());
        values.setProperty("etag", etag);
        values.setProperty("lastModified", lastModified);
        Path metadata = pathFor(tile);
        try (OutputStream output = Files.newOutputStream(metadata)) {
            values.store(output, "MeshApp tile HTTP cache metadata");
        }
    }

    private static Optional<Instant> cacheControlExpiry(HttpHeaders headers, Instant now) {
        for (String value : headers.allValues("Cache-Control")) {
            String normalized = value.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("no-cache") || normalized.contains("no-store")) {
                return Optional.of(now);
            }
            Matcher matcher = MAX_AGE.matcher(value);
            if (matcher.find()) {
                try {
                    return Optional.of(now.plusSeconds(Long.parseLong(matcher.group(1))));
                } catch (NumberFormatException ignored) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Instant> expiresHeader(HttpHeaders headers) {
        return headers.firstValue("Expires").flatMap(value -> {
            try {
                return Optional.of(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        });
    }
}
