package com.meshtastic.client.components.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileCacheMetadataTest {

    @TempDir
    Path tempDir;

    @Test
    void cacheControlAndValidatorsArePersistedAndReused() throws Exception {
        Path tile = tempDir.resolve("tile.png");
        Files.write(tile, new byte[]{1});
        Instant now = Instant.parse("2026-07-29T12:00:00Z");
        HttpHeaders headers = HttpHeaders.of(Map.of(
                "Cache-Control", List.of("public, max-age=3600"),
                "ETag", List.of("\"tile-v2\""),
                "Last-Modified", List.of("Wed, 29 Jul 2026 10:00:00 GMT")
        ), (name, value) -> true);

        TileCacheMetadata metadata = TileCacheMetadata.fromHeaders(headers, now, null);
        metadata.save(tile);

        assertEquals(now.plusSeconds(3600), metadata.expiresAt());
        assertTrue(TileCacheMetadata.isFresh(tile, now.plusSeconds(3599)));
        assertFalse(TileCacheMetadata.isFresh(tile, now.plusSeconds(3601)));
        assertEquals(metadata, TileCacheMetadata.load(tile).orElseThrow());

        HttpRequest.Builder request = HttpRequest.newBuilder(java.net.URI.create("https://example.test/tile.png"));
        metadata.addValidators(request);
        HttpRequest built = request.build();
        assertEquals("\"tile-v2\"", built.headers().firstValue("If-None-Match").orElseThrow());
        assertEquals("Wed, 29 Jul 2026 10:00:00 GMT",
                built.headers().firstValue("If-Modified-Since").orElseThrow());
    }

    @Test
    void responseWithoutCachingHeadersUsesSevenDayFallback() {
        Instant now = Instant.parse("2026-07-29T12:00:00Z");
        HttpHeaders headers = HttpHeaders.of(Map.of(), (name, value) -> true);

        TileCacheMetadata metadata = TileCacheMetadata.fromHeaders(headers, now, null);

        assertEquals(now.plus(TileCacheMetadata.FALLBACK_TTL), metadata.expiresAt());
    }
}
