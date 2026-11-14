package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.UpdateInfo;
import com.meshtastic.client.platform.OsDetect.OsType;
import com.meshtastic.client.platform.OsDetect.PackageFormat;
import com.meshtastic.client.update.ReleaseVersion;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.AppPreferences.UpdateChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sun.net.httpserver.HttpServer;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class UpdateCheckServiceTest {

    @TempDir
    Path home;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(home);
    }

    @AfterEach
    void tearDown() throws Exception {
        AppPreferences.getState().removeNode();
        TestEnvironmentSupport.setUserHome(home);
    }

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
                null, 123, "stable", UpdateChannel.STABLE
        );

        assertTrue(update.isPresent());
        assertEquals("1.2.4", update.orElseThrow().getVersion());
    }

    @Test
    void availableUpdateReturnsEmptyForCurrentManifest() {
        Optional<UpdateInfo> update = UpdateCheckService.availableUpdate(
                "{\"version\":\"1.2.3\",\"versionCode\":123}",
                null, 123, "stable", UpdateChannel.STABLE
        );

        assertTrue(update.isEmpty());
    }

    @Test
    void sameReleaseUpdatesByBuildAndRejectsOlderOrRepeatedBuilds() {
        String manifest = "{\"version\":\"v2.4.0+build.126\",\"versionCode\":2004000,\"buildNumber\":126,\"channel\":\"stable\"}";
        assertTrue(UpdateCheckService.availableUpdate(manifest, "v2.4.0+build.125", 2004000, "stable", UpdateChannel.STABLE).isPresent());
        assertTrue(UpdateCheckService.availableUpdate(manifest, "v2.4.0+build.126", 2004000, "stable", UpdateChannel.STABLE).isEmpty());
        assertTrue(UpdateCheckService.availableUpdate(manifest, "v2.4.0+build.127", 2004000, "stable", UpdateChannel.STABLE).isEmpty());
        assertTrue(UpdateCheckService.availableUpdate(manifest, "v2.4.0", 2004000, "stable", UpdateChannel.STABLE).isPresent());
        assertThrows(IllegalArgumentException.class, () -> UpdateCheckService.availableUpdate(
                manifest.replace("\"buildNumber\":126", "\"buildNumber\":999"), "v2.4.0+build.125", 2004000, "stable", UpdateChannel.STABLE));
        assertThrows(IllegalArgumentException.class, () -> UpdateCheckService.availableUpdate(
                manifest, "v2.4.0-unstable.125", 2003999, "unstable", UpdateChannel.UNSTABLE));
        assertThrows(IllegalArgumentException.class, () -> UpdateCheckService.availableUpdate(
                "{\"version\":\"v2.5.0-unstable.129\",\"versionCode\":2004999}",
                "v2.4.0+build.125", 2004000, "stable", UpdateChannel.STABLE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"v2.4.0+build.126", "v2.4.0-unstable.126", "v2.5.0-unstable.129"})
    void defaultsToStableRegardlessOfInstalledBuild(String localVersion) throws Exception {
        try (var feeds = new UpdateFeeds()) {
            var result = feeds.check(localVersion).orElseThrow();
            assertEquals("v2.4.0+build.128", result.getVersion());
            assertEquals(List.of("/updates/stable/meshapp.json"), feeds.requests);
        }
    }

    @Test
    void stableDoesNotFallBackToUnstableWhenStableIsUnpublished() throws Exception {
        try (var feeds = new UpdateFeeds()) {
            feeds.stableManifest = null;
            assertTrue(feeds.check("v2.4.0-unstable.126").isEmpty());
            assertEquals(List.of("/updates/stable/meshapp.json"), feeds.requests);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"v2.4.0+build.126", "v2.4.0-unstable.126"})
    void optingIntoUnstableAllowsNewerTestBuilds(String localVersion) throws Exception {
        AppPreferences.setUpdateChannel(UpdateChannel.UNSTABLE);
        try (var feeds = new UpdateFeeds()) {
            assertEquals("v2.5.0-unstable.129", feeds.check(localVersion).orElseThrow().getVersion());
            assertEquals(Set.of("/updates/stable/meshapp.json", "/updates/unstable/meshapp.json"),
                    Set.copyOf(feeds.requests));
        }
    }

    @Test
    void unstableChannelStillPromotesToStableRelease() throws Exception {
        AppPreferences.setUpdateChannel(UpdateChannel.UNSTABLE);
        try (var feeds = new UpdateFeeds()) {
            feeds.unstableManifest = "{\"version\":\"v2.4.0-unstable.127\",\"versionCode\":2003999}";
            var result = feeds.check("v2.4.0-unstable.126").orElseThrow();
            assertEquals("v2.4.0+build.128", result.getVersion());
        }
    }

    @ParameterizedTest
    @EnumSource(UpdateChannel.class)
    void unstableChannelToleratesEitherFeedBeingUnpublished(UpdateChannel missing) throws Exception {
        AppPreferences.setUpdateChannel(UpdateChannel.UNSTABLE);
        try (var feeds = new UpdateFeeds()) {
            if (missing == UpdateChannel.STABLE) feeds.stableManifest = null;
            else feeds.unstableManifest = null;
            assertEquals(missing == UpdateChannel.STABLE ? "v2.5.0-unstable.129" : "v2.4.0+build.128",
                    feeds.check("v2.4.0+build.126").orElseThrow().getVersion());
        }
    }

    @Test
    void switchingToStableOffersLatestStableEvenWhenItIsOlder() throws Exception {
        try (var feeds = new UpdateFeeds()) {
            AppPreferences.setUpdateChannel(UpdateChannel.UNSTABLE);
            assertEquals("v2.5.0-unstable.129", feeds.check("v2.5.0-unstable.128").orElseThrow().getVersion());

            AppPreferences.setUpdateChannel(UpdateChannel.STABLE);
            feeds.requests.clear();
            assertEquals("v2.4.0+build.128", feeds.check("v2.5.0-unstable.128").orElseThrow().getVersion());
            assertEquals(List.of("/updates/stable/meshapp.json"), feeds.requests);

            // Once the stable release is installed it must not be offered again.
            assertTrue(feeds.check("v2.4.0+build.128").isEmpty());

            AppPreferences.setUpdateChannel(UpdateChannel.UNSTABLE);
            assertEquals("v2.5.0-unstable.129", feeds.check("v2.5.0-unstable.128").orElseThrow().getVersion());
        }
    }

    @ParameterizedTest
    @EnumSource(UpdateChannel.class)
    void stableInstallationsNeverDowngradeToAnOlderStableBuild(UpdateChannel channel) throws Exception {
        AppPreferences.setUpdateChannel(channel);
        try (var feeds = new UpdateFeeds()) {
            feeds.unstableManifest = null;
            assertTrue(feeds.check("v2.4.0+build.129").isEmpty());
            assertTrue(feeds.check("v2.5.0+build.130").isEmpty());
        }
    }

    @Test
    void unstableChannelDoesNotFallBackToAnOlderStableRelease() throws Exception {
        AppPreferences.setUpdateChannel(UpdateChannel.UNSTABLE);
        try (var feeds = new UpdateFeeds()) {
            assertTrue(feeds.check("v2.5.0-unstable.129").isEmpty());
            feeds.unstableManifest = null;
            assertTrue(feeds.check("v2.5.0-unstable.129").isEmpty());
        }
    }

    @Test
    void stableTransitionStillRejectsInvalidManifestMetadata() throws Exception {
        try (var feeds = new UpdateFeeds()) {
            feeds.stableManifest = "{\"version\":\"v2.4.0+build.128\",\"versionCode\":2004000,\"channel\":\"unstable\"}";
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> feeds.check("v2.5.0-unstable.129"));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
        }
    }

    @ParameterizedTest
    @EnumSource(UpdateChannel.class)
    void devBuildSkipsAllFeeds(UpdateChannel channel) throws Exception {
        AppPreferences.setUpdateChannel(channel);
        try (var feeds = new UpdateFeeds()) {
            assertTrue(feeds.check("dev+local.900").isEmpty());
            assertTrue(feeds.requests.isEmpty());
        }
    }

    private static final class UpdateFeeds implements AutoCloseable {
        volatile String stableManifest = "{\"version\":\"v2.4.0+build.128\",\"versionCode\":2004000}";
        volatile String unstableManifest = "{\"version\":\"v2.5.0-unstable.129\",\"versionCode\":2004999}";
        final List<String> requests = new CopyOnWriteArrayList<>();
        private final HttpServer server;
        private final HttpClient client = HttpClient.newHttpClient();
        private final URI root;

        UpdateFeeds() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/updates/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                requests.add(path);
                String body = switch (path) {
                    case "/updates/stable/meshapp.json" -> stableManifest;
                    case "/updates/unstable/meshapp.json" -> unstableManifest;
                    default -> null;
                };
                byte[] bytes = (body != null ? body : "missing").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(body != null ? 200 : 404, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/updates/");
        }

        Optional<UpdateInfo> check(String localVersion) throws Exception {
            int code = ReleaseVersion.parse(localVersion).map(ReleaseVersion::legacyCode).orElse(0);
            return UpdateCheckService.checkForUpdateAsync(client, root, localVersion, code).get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            client.close();
            server.stop(0);
        }
    }
}
