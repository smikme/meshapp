package com.meshtastic.client.service;

import com.meshtastic.client.model.UpdateInfo;
import com.meshtastic.client.platform.OsDetect.OsType;
import com.meshtastic.client.platform.OsDetect.PackageFormat;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sun.net.httpserver.HttpServer;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class UpdateCheckServiceTest {

    @Test
    void requestCarriesPlatformAttributes() {
        HttpRequest request = UpdateCheckService.buildRequest(
                URI.create("https://example.invalid/meshapp.json"),
                OsType.LINUX,
                PackageFormat.FLATPAK,
                "x86_64",
                "1.2.3",
                123
        );

        assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
        assertEquals("linux", request.headers().firstValue("X-MeshApp-OS").orElseThrow());
        assertEquals("flatpak", request.headers().firstValue("X-MeshApp-Package").orElseThrow());
        assertEquals("x86_64", request.headers().firstValue("X-MeshApp-Arch").orElseThrow());
        assertEquals("1.2.3", request.headers().firstValue("X-MeshApp-Version").orElseThrow());
        assertEquals("123", request.headers().firstValue("X-MeshApp-Version-Code").orElseThrow());
    }

    @Test
    void availableUpdateReturnsInfoForNewerManifest() {
        Optional<UpdateInfo> update = UpdateCheckService.availableUpdate(
                "{\"version\":\"1.2.4\",\"versionCode\":124}",
                123
        );

        assertTrue(update.isPresent());
        assertEquals("1.2.4", update.orElseThrow().getVersion());
    }

    @Test
    void availableUpdateReturnsEmptyForCurrentManifest() {
        Optional<UpdateInfo> update = UpdateCheckService.availableUpdate(
                "{\"version\":\"1.2.3\",\"versionCode\":123}",
                123
        );

        assertTrue(update.isEmpty());
    }

    @Test
    void sameReleaseUpdatesByBuildAndRejectsOlderOrRepeatedBuilds() {
        String manifest = "{\"version\":\"v2.4.0+build.126\",\"versionCode\":2004000,\"buildNumber\":126,\"channel\":\"stable\"}";
        assertTrue(UpdateCheckService.availableUpdate(manifest, "v2.4.0+build.125", 2004000, "stable").isPresent());
        assertTrue(UpdateCheckService.availableUpdate(manifest, "v2.4.0+build.126", 2004000, "stable").isEmpty());
        assertTrue(UpdateCheckService.availableUpdate(manifest, "v2.4.0+build.127", 2004000, "stable").isEmpty());
        assertTrue(UpdateCheckService.availableUpdate(manifest, "v2.4.0", 2004000, "stable").isPresent());
        assertThrows(IllegalArgumentException.class, () -> UpdateCheckService.availableUpdate(
                manifest.replace("\"buildNumber\":126", "\"buildNumber\":999"), "v2.4.0+build.125", 2004000, "stable"));
        assertThrows(IllegalArgumentException.class, () -> UpdateCheckService.availableUpdate(
                manifest, "v2.4.0-unstable.125", 2003999, "unstable"));
    }

    @Test
    void fetchesOnlyAllowedChannelsAndPromotesUnstableToStable() throws Exception {
        var stable = new AtomicReference<>("{\"version\":\"v2.4.0+build.128\",\"versionCode\":2004000}");
        var unstable = new AtomicReference<>("{\"version\":\"v2.4.0-unstable.127\",\"versionCode\":2003999}");
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/updates/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(path);
            String body = path.equals("/updates/stable/meshapp.json") ? stable.get() : unstable.get();
            byte[] bytes = (body == null ? "missing" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/updates/");
            var result = UpdateCheckService.checkForUpdateAsync(client, root, "v2.4.0+build.126", 2004000)
                    .get(5, TimeUnit.SECONDS).orElseThrow();
            assertEquals("v2.4.0+build.128", result.getVersion());
            assertEquals(java.util.List.of("/updates/stable/meshapp.json"), requests);
            requests.clear();
            result = UpdateCheckService.checkForUpdateAsync(client, root, "v2.4.0-unstable.126", 2003999)
                    .get(5, TimeUnit.SECONDS).orElseThrow();
            assertEquals("v2.4.0+build.128", result.getVersion());
            assertEquals(2, requests.size());
            stable.set(null);
            result = UpdateCheckService.checkForUpdateAsync(client, root, "v2.4.0-unstable.126", 2003999)
                    .get(5, TimeUnit.SECONDS).orElseThrow();
            assertEquals(127, result.getBuildNumber());
            requests.clear();
            assertTrue(UpdateCheckService.checkForUpdateAsync(client, root, "dev+local.900", 0)
                    .get(5, TimeUnit.SECONDS).isEmpty());
            assertTrue(requests.isEmpty());
        } finally {
            server.stop(0);
        }
    }
}
