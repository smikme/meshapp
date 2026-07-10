package com.meshtastic.client.components;

import com.meshtastic.client.modal.ModalPane;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.IntConsumer;

/**
 * Modal passkey dialog used during BLE pairing.
 * Invoked from the native BlueZ agent callback when a device requests pairing.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class PasskeyDialog {

    private static long activeRequestId;

    private PasskeyDialog() {}

    /**
     * Shows the passkey entry dialog.
     *
     * @param requestId unique pairing request identifier
     * @param deviceAddress MAC address of the device requesting pairing
     * @param onPasskey callback receiving the entered PIN
     * @param onCancel callback invoked when the dialog is cancelled
     */
    public static void show(long requestId,
                            String deviceAddress,
                            IntConsumer onPasskey,
                            Runnable onCancel) {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            onCancel.run();
            return;
        }
        activeRequestId = requestId;

        VBox panel = new VBox(12);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(340);
        panel.setMaxWidth(340);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        Label title = new Label("Сопряжение BLE");
        title.getStyleClass().add("dialog-title");

        Separator sep = new Separator();

        Label infoLabel = new Label("Устройство " + deviceAddress + " запрашивает PIN-код для сопряжения.");
        infoLabel.setWrapText(true);

        Label pinLabel = new Label("PIN-код:");
        TextField pinField = new TextField();
        pinField.setPromptText("123456");
        pinField.setMaxWidth(280);

        // Digits only, up to six characters.
        pinField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String filtered = newVal.replaceAll("[^0-9]", "");
                if (filtered.length() > 6) {
                    filtered = filtered.substring(0, 6);
                }
                if (!filtered.equals(newVal)) {
                    pinField.setText(filtered);
                }
            }
        });

        Label statusLabel = new Label("");
        statusLabel.setStyle("-fx-opacity: 0.7;");
        statusLabel.setWrapText(true);

        Button connectBtn = new Button("Подключить");
        connectBtn.getStyleClass().add("accent");

        Button cancelBtn = new Button("Отмена");
        cancelBtn.setOnAction(e -> {
            if (!claim(requestId)) {
                return;
            }
            modalPane.hide();
            onCancel.run();
        });

        HBox btnRow = new HBox(10, cancelBtn, connectBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));

        panel.getChildren().addAll(title, sep, infoLabel, pinLabel, pinField, statusLabel, btnRow);

        connectBtn.setOnAction(e -> {
            String text = pinField.getText();
            if (text == null || text.isEmpty()) {
                statusLabel.setText("Введите PIN-код");
                return;
            }
            int passkey = Integer.parseInt(text);
            if (!claim(requestId)) {
                return;
            }
            modalPane.hide();
            onPasskey.accept(passkey);
        });

        // Enter = submit
        pinField.setOnAction(e -> connectBtn.fire());

        modalPane.show(panel);
        Platform.runLater(pinField::requestFocus);
    }

    /** Closes the dialog only when it still belongs to the specified pairing request. */
    public static void dismiss(long requestId) {
        if (!claim(requestId)) {
            return;
        }
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane != null) {
            modalPane.hide();
        }
    }

    private static boolean claim(long requestId) {
        if (requestId <= 0 || activeRequestId != requestId) {
            return false;
        }
        activeRequestId = 0;
        return true;
    }
}
