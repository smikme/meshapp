package com.meshtastic.client.update;

import com.google.gson.Gson;
import com.meshtastic.client.model.SelfUpdateArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfUpdateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void removesPartialDownloadWhenChecksumFails() throws Exception {
        Path source = tempDir.resolve("update.zip");
        Files.writeString(source, "not an update");
        Path staging = tempDir.resolve("staging");
        SelfUpdateArtifact artifact = new Gson().fromJson("""
                {
                  "url": "%s",
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                  "size": %d
                }
                """.formatted(source.toUri(), Files.size(source)), SelfUpdateArtifact.class);
        Properties properties = new Properties();
        properties.setProperty(SelfUpdateEnvironment.PROP_STAGING_DIR, staging.toString());
        SelfUpdateEnvironment environment = SelfUpdateEnvironment.from(
                Map.of(
                        SelfUpdateEnvironment.ENV_ROOT, tempDir.resolve("MeshApp").toString(),
                        SelfUpdateEnvironment.ENV_VERSION, "v2.1.19"
                ),
                properties
        ).orElseThrow();
        SelfUpdateService service = new SelfUpdateService(
                HttpClient.newHttpClient(),
                UpdateSignatureVerifier.current()
        );
        List<SelfUpdateService.UpdateProgress> progress = new ArrayList<>();

        assertThrows(
                IOException.class,
                () -> service.download(
                        new SelfUpdateService.UpdatePlan(null, artifact, environment, "v2.1.20"),
                        progress::add
                )
        );

        assertFalse(Files.exists(staging.resolve("v2.1.20-update.zip.part")));
        assertTrue(progress.stream().anyMatch(event ->
                event.phase() == SelfUpdateService.ProgressPhase.DOWNLOAD
                        && event.completedBytes() > 0));
    }
}
