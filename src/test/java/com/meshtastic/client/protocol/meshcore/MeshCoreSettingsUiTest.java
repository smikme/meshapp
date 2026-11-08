package com.meshtastic.client.protocol.meshcore;

import static org.junit.jupiter.api.Assertions.*;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.forms.settings.MeshCoreSettingsPane;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.concurrent.*;

class MeshCoreSettingsUiTest {
    @TempDir Path home;

    @BeforeAll
    static void toolkit() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void settingsTabsRenderAndSaveNameUsesTheCompanionCommand() throws Exception {
        TestEnvironmentSupport.setUserHome(home);
        TestEnvironmentSupport.resetSingletons();
        var radio = new MeshCoreCompanionProtocolRuntimeTest.Radio();
        var runtime =
                new MeshCoreCompanionProtocolRuntime(
                        new ProtocolRuntimeContext(
                                "ui", new ConnectionEntry("test", "x", 1), radio, "test"));
        try (runtime) {
            runtime.start().get(2, TimeUnit.SECONDS);
            CompletableFuture<Void> rendered = new CompletableFuture<>();
            Platform.runLater(() -> {
                try (var pane = new MeshCoreSettingsPane(runtime)) {
                    new Scene(pane, 1000, 760);
                    pane.applyCss();
                    pane.layout();
                    TabPane tabs = (TabPane) pane.getChildren().get(1);
                    assertEquals(5, tabs.getTabs().size());
                    int index = 0;
                    for (Tab tab : tabs.getTabs()) {
                        tabs.getSelectionModel().select(tab);
                        pane.applyCss();
                        pane.layout();
                        var image = pane.snapshot(null, null);
                        var pixels = image.getPixelReader();
                        var buffered =
                                new java.awt.image.BufferedImage(
                                        (int) image.getWidth(),
                                        (int) image.getHeight(),
                                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < buffered.getHeight(); y++)
                            for (int x = 0; x < buffered.getWidth(); x++)
                                buffered.setRGB(x, y, pixels.getArgb(x, y));
                        Path path = Path.of("build/reports/meshcore-ui/tab-" + (index++) + ".png");
                        Files.createDirectories(path.getParent());
                        javax.imageio.ImageIO.write(buffered, "png", path.toFile());
                    }
                    tabs.getSelectionModel().selectFirst();
                    pane.applyCss();
                    pane.layout();
                    var scroll = (ScrollPane) tabs.getTabs().getFirst().getContent();
                    var device = (javafx.scene.layout.VBox) scroll.getContent();
                    ((Button) device.getChildren().get(1)).fire();
                    assertTrue(radio.sent.stream().anyMatch(p -> p[0] == 8));
                    rendered.complete(null);
                } catch (Throwable error) {
                    rendered.completeExceptionally(error);
                }
            });
            rendered.get(15, TimeUnit.SECONDS);
        } finally {
            TestEnvironmentSupport.resetSingletons();
        }
    }
}
