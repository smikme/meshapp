package com.meshtastic.client.tray;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.system.RootPane;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class AppTrayManagerTest {

    @TempDir
    Path tempHome;

    private AppTrayManager manager;
    private Stage stage;
    private Button minimizeButton;
    private FakeTrayService tray;
    private AtomicInteger shutdowns;

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        AppPreferences.setMinimizeToTray(true);
        AppPreferences.setDisableEffects(true);
        tray = new FakeTrayService();
        shutdowns = new AtomicInteger();
        manager = new AppTrayManager(() -> tray, shutdowns::incrementAndGet);
    }

    @AfterEach
    void tearDown() throws Exception {
        onFxThread(() -> {
            manager.dispose();
            // Keep the shared test toolkit alive after disposing the manager.
            Platform.setImplicitExit(false);
            if (stage != null) {
                stage.hide();
            }
        });
    }

    @Test
    void installedTrayKeepsRuntimeAliveBeforeFirstMinimize() throws Exception {
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            assertTrue(manager.isAvailable());
            assertFalse(Platform.isImplicitExit());
        });
    }

    @ParameterizedTest
    @EnumSource(value = StageStyle.class, names = {"DECORATED", "UNDECORATED", "TRANSPARENT"})
    void minimizeButtonHidesWindowAfterItsActionReturns(StageStyle style) throws Exception {
        AtomicBoolean handlingAction = new AtomicBoolean();
        AtomicBoolean hiddenDuringAction = new AtomicBoolean();
        onFxThread(() -> {
            showWindow(style);
            stage.setOnHiding(event -> hiddenDuringAction.set(handlingAction.get()));
            minimizeButton.setOnAction(event -> {
                handlingAction.set(true);
                try {
                    manager.requestMinimize();
                } finally {
                    handlingAction.set(false);
                }
            });
            minimizeButton.fire();
        });

        onFxThread(() -> {
            assertFalse(hiddenDuringAction.get(), "Do not destroy the native window during its input event");
            assertFalse(stage.isShowing());
            assertFalse(Platform.isImplicitExit());
            assertTrue(manager.isAvailable());
        });
    }

    @Test
    void trayActivationRestoresWindowAndAllowsRepeatedMinimization() throws Exception {
        onFxThread(() -> showWindow(StageStyle.DECORATED));
        for (int i = 0; i < 3; i++) {
            onFxThread(minimizeButton::fire);
            onFxThread(() -> {
                assertFalse(stage.isShowing());
                assertFalse(Platform.isImplicitExit());
            });

            // AWT and native tray callbacks arrive outside the JavaFX thread.
            tray.onActivate.run();
            onFxThread(() -> {
                assertTrue(stage.isShowing());
                assertFalse(stage.isIconified());
                assertFalse(Platform.isImplicitExit());
            });
        }
    }

    @Test
    void restoringWindowCancelsQueuedMinimization() throws Exception {
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            manager.requestMinimize();
            manager.restoreWindow();
        });
        onFxThread(() -> assertTrue(stage.isShowing()));
    }

    @Test
    void disablingTrayPreferenceCancelsQueuedMinimization() throws Exception {
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            manager.requestMinimize();
            AppPreferences.setMinimizeToTray(false);
        });
        onFxThread(() -> assertTrue(stage.isShowing()));
    }

    @Test
    void unavailableTrayFallsBackToNormalMinimization() throws Exception {
        tray.available = false;
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            assertFalse(manager.isAvailable());
            minimizeButton.fire();
        });
        onFxThread(() -> {
            assertTrue(stage.isShowing());
            assertTrue(stage.isIconified());
        });
    }

    @Test
    void disabledTrayPreferenceUsesNormalMinimization() throws Exception {
        AppPreferences.setMinimizeToTray(false);
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            minimizeButton.fire();
        });
        onFxThread(() -> {
            assertTrue(stage.isShowing());
            assertTrue(stage.isIconified());
        });
    }

    @Test
    void nativeMinimizationAlsoHidesToTray() throws Exception {
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            stage.setIconified(true);
        });
        // Native iconification and the deferred hide each use an FX queue turn.
        onFxThread(() -> {});
        onFxThread(() -> {
            assertFalse(stage.isShowing());
            assertFalse(Platform.isImplicitExit());
        });
    }

    @ParameterizedTest
    @EnumSource(value = StageStyle.class, names = {"DECORATED", "UNDECORATED", "TRANSPARENT"})
    void closeRequestHidesToTrayWithoutShuttingDown(StageStyle style) throws Exception {
        onFxThread(() -> {
            showWindow(style);
            fireCloseRequest();
            assertTrue(stage.isShowing(), "Close must wait until the current event returns");
        });
        onFxThread(() -> {
            assertFalse(stage.isShowing());
            assertFalse(Platform.isImplicitExit());
            assertEquals(0, shutdowns.get());
        });
        tray.onActivate.run();
        onFxThread(() -> assertTrue(stage.isShowing()));
    }

    @Test
    void closeWithTrayDisabledExitsEvenIfOtherWindowsAreOpen() throws Exception {
        AppPreferences.setMinimizeToTray(false);
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            Stage auxiliary = new Stage();
            auxiliary.setScene(new Scene(new StackPane(), 200, 100));
            auxiliary.show();
            try {
                fireCloseRequest();
                assertEquals(1, shutdowns.get());
            } finally {
                auxiliary.hide();
            }
        });
    }

    @Test
    void closeWithUnavailableTrayExitsInsteadOfLeavingUnreachableWindow() throws Exception {
        tray.available = false;
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            fireCloseRequest();
            assertEquals(1, shutdowns.get());
        });
    }

    @Test
    void explicitTrayExitStillExitsWithTrayPreferenceEnabled() throws Exception {
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            fireCloseRequest();
        });
        onFxThread(() -> assertFalse(stage.isShowing()));
        tray.onExit.run();
        tray.onActivate.run();
        onFxThread(() -> {
            assertEquals(1, shutdowns.get());
            assertFalse(stage.isShowing());
            manager.exitApplication();
            assertEquals(1, shutdowns.get(), "Repeated exit requests must not run shutdown twice");
        });
    }

    @Test
    void explicitExitCancelsPendingCloseToTray() throws Exception {
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            fireCloseRequest();
            manager.exitApplication();
        });
        onFxThread(() -> {
            assertTrue(stage.isShowing(), "No deferred tray transition may run after shutdown starts");
            assertEquals(1, shutdowns.get());
        });
    }

    @Test
    void togglingTrayPreferenceChangesCloseBehaviorWithoutRestart() throws Exception {
        AppPreferences.setMinimizeToTray(false);
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            AppPreferences.setMinimizeToTray(true);
            fireCloseRequest();
        });
        onFxThread(() -> {
            assertFalse(stage.isShowing());
            assertEquals(0, shutdowns.get());
        });
        tray.onActivate.run();
        onFxThread(() -> {
            assertTrue(stage.isShowing());
            AppPreferences.setMinimizeToTray(false);
            fireCloseRequest();
            assertEquals(1, shutdowns.get());
        });
    }

    @Test
    void closeAndRestorePreserveSceneAndUnsavedInput() throws Exception {
        TextField[] input = new TextField[1];
        Scene[] scene = new Scene[1];
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            input[0] = new TextField("Unsent message");
            scene[0] = stage.getScene();
            ((StackPane) scene[0].getRoot()).getChildren().setAll(input[0]);
            input[0].selectRange(2, 7);
            fireCloseRequest();
        });
        onFxThread(() -> assertFalse(stage.isShowing()));
        tray.onActivate.run();
        onFxThread(() -> {
            assertTrue(stage.isShowing());
            assertSame(scene[0], stage.getScene());
            assertEquals("Unsent message", input[0].getText());
            assertEquals(2, input[0].getSelection().getStart());
            assertEquals(7, input[0].getSelection().getEnd());
        });
    }

    @Test
    void closingAuxiliaryWindowDoesNotMinimizeMainWindow() throws Exception {
        onFxThread(() -> {
            showWindow(StageStyle.DECORATED);
            Stage auxiliary = new Stage();
            auxiliary.initOwner(stage);
            auxiliary.setScene(new Scene(new StackPane(), 200, 100));
            auxiliary.show();
            try {
                auxiliary.fireEvent(new WindowEvent(auxiliary, WindowEvent.WINDOW_CLOSE_REQUEST));
                assertFalse(auxiliary.isShowing());
                assertTrue(stage.isShowing());
                assertEquals(0, shutdowns.get());
            } finally {
                auxiliary.hide();
            }
        });
    }

    @Test
    void customCloseButtonUsesTheSameCloseHandlerAsSystemFrame() throws Exception {
        AppPreferences.setDisableEffects(false);
        assumeFalse(AppPreferences.isDisableEffectsEffective(), "Custom frame is unavailable on this OS");
        TestEnvironmentSupport.resetSingletons();
        AtomicInteger closeRequests = new AtomicInteger();
        try {
            onFxThread(() -> {
                RootPane root = new RootPane();
                // Exercise the real custom button, using portable hide/show for this test.
                AppPreferences.setDisableEffects(true);
                showWindow(StageStyle.DECORATED);
                stage.getScene().setRoot(root);
                stage.setOnCloseRequest(event -> {
                    closeRequests.incrementAndGet();
                    manager.handleCloseRequest(event);
                });
                ((Button) root.lookup(".window-btn-close")).fire();
            });
            onFxThread(() -> {
                assertEquals(1, closeRequests.get());
                assertEquals(0, shutdowns.get());
                assertFalse(stage.isShowing());
            });
            tray.onActivate.run();
            onFxThread(() -> {
                assertTrue(stage.isShowing());
                AppPreferences.setMinimizeToTray(false);
                ((Button) stage.getScene().getRoot().lookup(".window-btn-close")).fire();
                assertEquals(2, closeRequests.get());
                assertEquals(1, shutdowns.get());
            });
        } finally {
            onFxThread(() -> {
                ModalPane.install(null);
                Toast.setOverlay(null);
            });
            TestEnvironmentSupport.resetSingletons();
        }
    }

    private void fireCloseRequest() {
        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    private void showWindow(StageStyle style) {
        stage = new Stage(style);
        minimizeButton = new Button("Minimize");
        minimizeButton.setOnAction(event -> manager.requestMinimize());
        stage.setScene(new Scene(new StackPane(minimizeButton), 320, 200));
        stage.setOnCloseRequest(manager::handleCloseRequest);
        stage.show();
        Platform.setImplicitExit(true);
        manager.initialize(stage);
    }

    private static void onFxThread(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(5, TimeUnit.SECONDS);
    }

    private static final class FakeTrayService implements AppTrayService {
        private boolean available = true;
        private Runnable onActivate;
        private Runnable onExit;

        @Override
        public boolean install(Runnable onActivate, Runnable onExit) {
            this.onActivate = onActivate;
            this.onExit = onExit;
            return available;
        }
    }
}
