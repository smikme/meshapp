package com.meshtastic.client.system;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.components.PasskeyDialog;
import com.meshtastic.client.menu.MyDrawerBuilder;
import com.meshtastic.client.modal.Toast;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * JavaFX presentation bridge for desktop mode.
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

    @Override
    public boolean isPrimaryWindowFocused() {
        Stage stage = MeshApp.getPrimaryStage();
        return stage != null && stage.isFocused();
    }

    @Override
    public void addPrimaryWindowFocusListener(Consumer<Boolean> listener) {
        if (listener == null) {
            return;
        }
        runLater(() -> {
            Stage stage = MeshApp.getPrimaryStage();
            if (stage != null) {
                listener.accept(stage.isFocused());
                stage.focusedProperty().addListener(
                        (obs, wasFocused, isFocused) -> listener.accept(isFocused));
            }
        });
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
