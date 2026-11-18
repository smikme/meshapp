package com.meshtastic.client.forms.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.utils.AppPreferences;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RpcConnectionQrDialogTest {
    private static final String KEY = "mra1_" + "A".repeat(43);

    @BeforeAll
    static void toolkit() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void cloudDisplaysOnlyTheCloudEndpointAndRendersWithInvalidDirectSettings() throws Exception {
        onFx(() -> {
            var dialog = RpcConnectionQrDialog.create(null, true, "0.0.0.0", "invalid", KEY);
            var endpoint = (TextField) dialog.getDialogPane().lookup("#rpc-qr-address");
            assertEquals(ConnectionEntry.CLOUD_RPC_ROUTER_SERVER, endpoint.getText());
            assertFalse(endpoint.isEditable());
            assertFalse(dialog.getDialogPane().lookup("#rpc-qr-port").isVisible());
            assertQrAvailable(dialog, true);
            snapshot(dialog, "cloud");
        });
    }

    @Test
    void directAddressCanBeCorrectedWithoutSharingAnUnusableWildcardQr() throws Exception {
        onFx(() -> {
            var dialog = RpcConnectionQrDialog.create(null, false, "0.0.0.0", "44030", KEY);
            var endpoint = (TextField) dialog.getDialogPane().lookup("#rpc-qr-address");
            assertTrue(endpoint.isEditable());
            assertQrAvailable(dialog, false);
            endpoint.setText("192.168.1.25");
            assertQrAvailable(dialog, true);
            snapshot(dialog, "direct");
            endpoint.setText("");
            assertQrAvailable(dialog, false);
        });
    }

    @Test
    void invalidKeyCannotBeCopiedAsAConnectionQr() throws Exception {
        onFx(() -> {
            var dialog = RpcConnectionQrDialog.create(null, true, "", "", "invalid");
            assertQrAvailable(dialog, false);
        });
    }

    @Test
    void settingsButtonUsesTheCurrentCloudCheckboxRatherThanSavedLocalSettings(@TempDir Path home) throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousRoot = System.getProperty(AppPreferences.PROP_PREFERENCES_ROOT_PATH);
        TestEnvironmentSupport.setUserHome(home);
        try {
            AppPreferences.setRemoteRpcServerEnabled(true);
            AppPreferences.setRemoteRpcServerBindAddress("192.168.1.25");
            AppPreferences.setRemoteRpcServerPort(44030);
            AppPreferences.setRemoteRpcAccessKey(KEY);
            AppPreferences.setRemoteRpcRouterEnabled(false);
            onFx(() -> {
                var panel = ApplicationSettingsPanelFactory.createRemoteRpcSettingsPanel();
                Scene scene = new Scene(panel);
                var cloud = (CheckBox) panel.lookup("#remote-rpc-router-enabled");
                var button = (Button) panel.lookup("#remote-rpc-qr");
                try {
                    for (boolean enabled : new boolean[] {true, false}) {
                        cloud.setSelected(enabled);
                        button.fire();
                        Window window = Window.getWindows().stream()
                                .filter(w -> w.getScene().getRoot() instanceof DialogPane)
                                .findFirst().orElseThrow();
                        try {
                            var pane = (DialogPane) window.getScene().getRoot();
                            assertEquals(enabled ? ConnectionEntry.CLOUD_RPC_ROUTER_SERVER : "192.168.1.25",
                                    ((TextField) pane.lookup("#rpc-qr-address")).getText());
                            assertEquals(!enabled, pane.lookup("#rpc-qr-port").isVisible());
                            assertFalse(pane.lookup("#rpc-qr-error").isVisible());
                        } finally {
                            window.hide();
                        }
                    }
                } finally {
                    // Detaching the panel also removes its RPC status listener.
                    scene.setRoot(new VBox());
                }
            });
            assertFalse(AppPreferences.isRemoteRpcRouterEnabled(), "Sharing must not apply settings or start RPC");
        } finally {
            TestEnvironmentSupport.resetSingletons();
            System.setProperty("user.home", previousHome);
            if (previousRoot == null) System.clearProperty(AppPreferences.PROP_PREFERENCES_ROOT_PATH);
            else System.setProperty(AppPreferences.PROP_PREFERENCES_ROOT_PATH, previousRoot);
        }
    }

    private static void assertQrAvailable(Dialog<Void> dialog, boolean available) {
        var pane = dialog.getDialogPane();
        var holder = (VBox) pane.lookup("#rpc-qr-image");
        assertEquals(available ? 1 : 0, holder.getChildren().size());
        assertEquals(!available, pane.lookup("#rpc-qr-error").isVisible());
        for (var type : pane.getButtonTypes().subList(0, 2)) {
            assertEquals(!available, pane.lookupButton(type).isDisabled());
        }
    }

    private static void snapshot(Dialog<Void> dialog, String name) throws Exception {
        var pane = dialog.getDialogPane();
        // Render the dialog's own scene without showing a window.
        pane.applyCss();
        pane.autosize();
        pane.layout();
        for (var target : new javafx.scene.Node[] {pane, pane.lookup("#rpc-qr-image")}) {
            var image = target.snapshot(null, null);
            var buffered = new java.awt.image.BufferedImage((int) image.getWidth(),
                    (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < buffered.getHeight(); y++) {
                for (int x = 0; x < buffered.getWidth(); x++) {
                    buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                }
            }
            Path path = Path.of("build/reports/rpc-qr", name + (target == pane ? "-dialog" : "-qr") + ".png");
            Files.createDirectories(path.getParent());
            javax.imageio.ImageIO.write(buffered, "png", path.toFile());
        }
    }

    private static void onFx(CheckedRunnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            String previous = I18n.getLanguageTag();
            try {
                I18n.setLanguageTagForTests(I18n.LANGUAGE_RU);
                action.run();
            } finally {
                I18n.setLanguageTagForTests(previous);
            }
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
