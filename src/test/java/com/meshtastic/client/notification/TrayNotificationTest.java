package com.meshtastic.client.notification;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.system.AppUi;
import com.meshtastic.client.system.JavaFxAppUiBridge;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for notification suppression across primary-window state
 * changes, using the real JavaFX bridge and a notification service that records
 * delivery without showing operating-system alerts.
 * <p>
 * Tests retain the JavaFX focus flag after hiding or minimizing the window to
 * reproduce platforms that do not deliver a focus-lost event during that transition.
 */
class TrayNotificationTest {

    @TempDir
    Path tempHome;

    private Stage stage;
    private Stage previousPrimaryStage;
    private NotificationManager manager;
    private final List<String> notifications = new ArrayList<>();

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @BeforeEach
    void setUp() throws Exception {
        TestEnvironmentSupport.setUserHome(tempHome);
        AppPreferences.setNotificationsEnabled(true);
        onFxThread(() -> {
            previousPrimaryStage = MeshApp.getPrimaryStage();
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(), 320, 200));
            stage.show();
            setFocused();
            setPrimaryStage(stage);
            AppUi.install(new JavaFxAppUiBridge());
            manager = new NotificationManager(new DeviceState(),
                    (_, body) -> notifications.add(body));
            manager.setActiveChatChecker((_, _) -> true);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onFxThread(() -> {
            if (manager != null) {
                manager.dispose();
            }
            if (stage != null) {
                stage.hide();
            }
            setPrimaryStage(previousPrimaryStage);
            AppUi.reset();
        });
    }

    @ParameterizedTest
    @CsvSource({"channel, 0", "dm, !12345678"})
    void selectedChatNotifiesAfterWindowIsHidden(String chatType, String chatKey) throws Exception {
        onFxThread(() -> {
            setFocused();
            manager.onIncomingMessage(message(), chatType, chatKey);
            assertTrue(notifications.isEmpty(), "The focused selected chat should stay silent");
            stage.hide();
            // Hiding a native window need not deliver a JavaFX focus-lost event.
            setFocused();
            assertFalse(stage.isShowing());
        });

        // Incoming packets are handled outside the FX thread.
        manager.onIncomingMessage(message(), chatType, chatKey);
        onFxThread(() -> assertEquals(List.of("Incoming message"), notifications));
    }

    @Test
    void selectedChannelNotifiesWhenWindowIsMinimized() throws Exception {
        onFxThread(() -> {
            stage.setIconified(true);
            setFocused();
            assertTrue(stage.isIconified());
            manager.onIncomingMessage(message(), "channel", "0");
            assertEquals(List.of("Incoming message"), notifications);
        });
    }

    @Test
    void managerCreatedWhileHiddenDoesNotSuppressSelectedChannel() throws Exception {
        onFxThread(() -> {
            stage.hide();
            setFocused();
            manager.dispose();
            manager = new NotificationManager(new DeviceState(),
                    (_, body) -> notifications.add(body));
            manager.setActiveChatChecker((_, _) -> true);
            manager.onIncomingMessage(message(), "channel", "0");
            assertEquals(List.of("Incoming message"), notifications);
        });
    }

    @Test
    void restoringFocusedWindowSuppressesSelectedChannelAgain() throws Exception {
        onFxThread(() -> {
            stage.hide();
            stage.show();
            setFocused();
            manager.onIncomingMessage(message(), "channel", "0");
            assertTrue(notifications.isEmpty());
        });
    }

    @Test
    void hiddenWindowStillHonorsChannelMute() throws Exception {
        AppPreferences.setChatMuted("", "channel", "0", true);
        onFxThread(() -> {
            stage.hide();
            setFocused();
            manager.onIncomingMessage(message(), "channel", "0");
            assertTrue(notifications.isEmpty());
        });
    }

    private static MeshMessage message() {
        return new MeshMessage("!12345678", "!ffffffff", 0, "Incoming message", 1, false);
    }

    /**
     * Sets the JavaFX focus flag to {@code true} independently of native window-manager events
     * so tests can reproduce a stale focus state. Must run on the FX thread.
     *
     * @throws Exception if reflective access to the JavaFX focus setter fails
     */
    private void setFocused() throws Exception {
        var method = Window.class.getDeclaredMethod("setFocused", boolean.class);
        method.setAccessible(true);
        method.invoke(stage, true);
    }

    private static void setPrimaryStage(Stage value) throws Exception {
        Field field = MeshApp.class.getDeclaredField("primaryStage");
        field.setAccessible(true);
        field.set(null, value);
    }

    /**
     * Runs an action on the FX thread and waits up to five seconds for completion,
     * propagating failures to the test thread.
     *
     * @param action operation to run on the FX thread
     * @throws Exception if the action fails, the wait is interrupted, or the timeout expires
     */
    private static void onFxThread(FxAction action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(5, TimeUnit.SECONDS);
    }

    /** An FX-thread test operation that may throw a checked exception. */
    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }
}
