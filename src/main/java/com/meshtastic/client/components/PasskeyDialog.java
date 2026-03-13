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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.function.IntConsumer;

/**
 * Модальный диалог ввода PIN-кода (passkey) для BLE pairing.
 * Вызывается из нативного BlueZ agent callback при сопряжении с устройством.
 */
public final class PasskeyDialog {

    private PasskeyDialog() {}

    /**
     * Показать диалог ввода passkey.
     *
     * @param deviceAddress MAC-адрес устройства, запрашивающего pairing
     * @param onPasskey     callback с введённым PIN (int)
     * @param onCancel      callback при отмене
     */
    public static void show(String deviceAddress,
                            IntConsumer onPasskey,
                            Runnable onCancel) {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            return;
        }

        VBox panel = new VBox(12);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(340);
        panel.setMaxWidth(340);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        Label title = new Label("Сопряжение BLE");
        title.setFont(Font.font("Roboto", FontWeight.BOLD, 15));

        Separator sep = new Separator();

        Label infoLabel = new Label("Устройство " + deviceAddress + " запрашивает PIN-код для сопряжения.");
        infoLabel.setWrapText(true);

        Label pinLabel = new Label("PIN-код:");
        TextField pinField = new TextField();
        pinField.setPromptText("123456");
        pinField.setMaxWidth(280);

        // Только цифры, максимум 6
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
            modalPane.hide();
            onPasskey.accept(passkey);
        });

        // Enter = submit
        pinField.setOnAction(e -> connectBtn.fire());

        modalPane.show(panel);
        Platform.runLater(pinField::requestFocus);
    }
}
