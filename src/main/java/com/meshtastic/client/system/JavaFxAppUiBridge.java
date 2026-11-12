package com.meshtastic.client.system;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.components.PasskeyDialog;
import com.meshtastic.client.menu.MyDrawerBuilder;
import com.meshtastic.client.modal.Toast;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.stage.Stage;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * JavaFX presentation bridge for desktop mode.
 * <p>
 * For notification suppression, the primary window is considered active only
 * while it is showing, not minimized, and focused. Tracking all three properties
 * prevents a stale focus flag from suppressing notifications after hiding to tray.
 */
public final class JavaFxAppUiBridge implements AppUi.Bridge {

    @Override
    public void runLater(Runnable action) {
        if (action == null) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    @Override
    public void showStatus(AppUi.StatusType type, String message) {
        runLater(() -> Toast.show(toToastType(type), message != null ? message : ""));
    }

    @Override
    public void setChatUnreadDot(boolean show) {
        runLater(() -> DrawerManager.setChatUnreadDot(show));
    }

    @Override
    public void updateHeader(String shortName, String longName, String nodeId) {
        runLater(() -> MyDrawerBuilder.updateHeader(shortName, longName, nodeId));
    }

    @Override
    public void requestBlePasskey(long requestId,
                                  String deviceAddress,
                                  IntConsumer onSubmit,
                                  Runnable onCancel) {
        runLater(() -> PasskeyDialog.show(
                requestId,
                deviceAddress,
                passkey -> {
                    if (onSubmit != null) {
                        onSubmit.accept(passkey);
                    }
                },
                () -> {
                    if (onCancel != null) {
                        onCancel.run();
                    }
                }));
    }

    @Override
    public void dismissBlePasskey(long requestId) {
        runLater(() -> PasskeyDialog.dismiss(requestId));
    }

    /**
     * Reports whether the primary window is visible, not minimized, and focused.
     *
     * @return {@code false} if the primary stage is absent, hidden, minimized,
     *         or unfocused
     */
    @Override
    public boolean isPrimaryWindowFocused() {
        return isWindowActive(MeshApp.getPrimaryStage());
    }

    /**
     * Observes the primary window's activity for notification suppression.
     * <p>
     * Registration and callbacks run on the JavaFX application thread. If a
     * primary stage exists at registration time, the listener receives its current
     * activity immediately and is called again when focus, visibility, or
     * minimization changes. A hidden or minimized window reports {@code false}
     * even if its JavaFX focus flag remains set.
     *
     * @param listener consumer of the current activity state; {@code null} is ignored
     */
    @Override
    public void addPrimaryWindowFocusListener(Consumer<Boolean> listener) {
        if (listener == null) {
            return;
        }
        runLater(() -> {
            Stage stage = MeshApp.getPrimaryStage();
            if (stage != null) {
                ChangeListener<Boolean> update = (_, _, _) -> listener.accept(isWindowActive(stage));
                stage.focusedProperty().addListener(update);
                stage.showingProperty().addListener(update);
                stage.iconifiedProperty().addListener(update);
                listener.accept(isWindowActive(stage));
            }
        });
    }

    /**
     * Combines window visibility, minimization, and focus into the activity state
     * shared by the initial query and subsequent listener updates.
     *
     * @param stage window to inspect, or {@code null} if no primary window exists
     * @return whether the stage is showing, not minimized, and focused
     */
    private static boolean isWindowActive(Stage stage) {
        return stage != null && stage.isShowing() && !stage.isIconified() && stage.isFocused();
    }

    private static Toast.Type toToastType(AppUi.StatusType type) {
        return switch (type != null ? type : AppUi.StatusType.INFO) {
            case INFO -> Toast.Type.INFO;
            case SUCCESS -> Toast.Type.SUCCESS;
            case WARNING -> Toast.Type.WARNING;
            case ERROR -> Toast.Type.ERROR;
        };
    }
}
