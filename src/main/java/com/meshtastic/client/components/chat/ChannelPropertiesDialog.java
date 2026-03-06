package com.meshtastic.client.components.chat;

import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.MessageService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.meshtastic.proto.ChannelProtos;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Модальная панель свойств канала с возможностью редактирования.
 *
 * <p>Вызывается статическим методом {@link #show(DeviceState, ProtocolHandler, int, Runnable)}.
 */
public final class ChannelPropertiesDialog {

    private ChannelPropertiesDialog() {}

    /**
     * Показать панель свойств канала.
     *
     * @param state           текущее состояние устройства
     * @param protocolHandler обработчик протокола для отправки команд
     * @param channelIndex    индекс канала (0 = PRIMARY, 1–7 = SECONDARY)
     * @param onSaved         колбэк, вызываемый после успешного сохранения
     */
    public static void show(DeviceState state,
                            ProtocolHandler protocolHandler,
                            int channelIndex,
                            Runnable onSaved) {
        if (state == null || protocolHandler == null) {
            Toast.show(Toast.Type.WARNING, "Нет подключения к радио");
            return;
        }

        // Найти канал по индексу
        ChannelProtos.Channel currentChannel = findChannel(state, channelIndex);
        if (currentChannel == null) {
            Toast.show(Toast.Type.WARNING, "Канал не найден");
            return;
        }

        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            return;
        }

        ChannelProtos.ChannelSettings currentSettings = currentChannel.getSettings();

        // === Форма ===
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(340);
        panel.setMaxWidth(340);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        Label title = new Label("Свойства канала");
        title.setFont(Font.font("Roboto", FontWeight.BOLD, 15));

        Separator sep = new Separator();

        // Имя канала
        Label nameLabel = new Label("Имя канала:");
        TextField nameField = new TextField(currentSettings.getName());
        nameField.setPromptText("До 11 символов");
        nameField.setMaxWidth(280);
        nameField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null
                    && newVal.getBytes(StandardCharsets.UTF_8).length > 11) {
                nameField.setText(oldVal);
            }
        });

        // Ключ шифрования (PSK)
        Label encLabel = new Label("Ключ шифрования (PSK):");
        String currentPskBase64 = currentSettings.getPsk().isEmpty()
                ? ""
                : Base64.getEncoder().encodeToString(currentSettings.getPsk().toByteArray());
        TextField pskField = new TextField(currentPskBase64);
        pskField.setPromptText("Base64-ключ или пусто (без шифрования)");
        pskField.setMaxWidth(280);

        Button generateKeyBtn = new Button("Сгенерировать");
        generateKeyBtn.setOnAction(ev -> {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            pskField.setText(Base64.getEncoder().encodeToString(key));
        });

        HBox pskRow = new HBox(8, pskField, generateKeyBtn);
        pskRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pskField, Priority.ALWAYS);

        // Uplink / Downlink / Position
        CheckBox uplinkCheck = new CheckBox("Uplink");
        uplinkCheck.setSelected(currentSettings.getUplinkEnabled());

        CheckBox downlinkCheck = new CheckBox("Downlink");
        downlinkCheck.setSelected(currentSettings.getDownlinkEnabled());

        CheckBox positionCheck = new CheckBox("Позиция");
        positionCheck.setSelected(currentSettings.hasModuleSettings()
                && currentSettings.getModuleSettings().getPositionPrecision() > 0);

        // Статус
        Label statusLabel = new Label("");
        statusLabel.setStyle("-fx-opacity: 0.7;");
        statusLabel.setWrapText(true);

        // Кнопки
        Button saveBtn = new Button("Сохранить");
        saveBtn.getStyleClass().add("accent");

        Button cancelBtn = new Button("Отмена");
        cancelBtn.setOnAction(e -> modalPane.hide());

        HBox btnRow = new HBox(10, cancelBtn, saveBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));

        panel.getChildren().addAll(title, sep, nameLabel, nameField, encLabel, pskRow,
                uplinkCheck, downlinkCheck, positionCheck, statusLabel, btnRow);

        // === Обработка «Сохранить» ===
        saveBtn.setOnAction(e -> {
            String channelName = nameField.getText() != null
                    ? nameField.getText().trim() : "";

            // PSK из текстового поля (base64)
            com.google.protobuf.ByteString psk;
            String pskText = pskField.getText() != null
                    ? pskField.getText().trim() : "";
            if (pskText.isEmpty()) {
                psk = com.google.protobuf.ByteString.EMPTY;
            } else {
                try {
                    byte[] decoded = Base64.getDecoder().decode(pskText);
                    psk = com.google.protobuf.ByteString.copyFrom(decoded);
                } catch (IllegalArgumentException ex) {
                    statusLabel.setText("Некорректный Base64-ключ");
                    return;
                }
            }

            // Собрать Channel protobuf (сохраняем id и role из текущего канала)
            ChannelProtos.ChannelSettings.Builder settingsBuilder =
                    ChannelProtos.ChannelSettings.newBuilder()
                            .setName(channelName)
                            .setPsk(psk)
                            .setId(currentSettings.getId())
                            .setUplinkEnabled(uplinkCheck.isSelected())
                            .setDownlinkEnabled(downlinkCheck.isSelected());

            if (positionCheck.isSelected()) {
                int currentPrecision = currentSettings.hasModuleSettings()
                        ? currentSettings.getModuleSettings().getPositionPrecision() : 0;
                settingsBuilder.setModuleSettings(
                        ChannelProtos.ModuleSettings.newBuilder()
                                .setPositionPrecision(currentPrecision > 0 ? currentPrecision : 32)
                                .build());
            } else {
                settingsBuilder.setModuleSettings(
                        ChannelProtos.ModuleSettings.newBuilder()
                                .setPositionPrecision(0)
                                .build());
            }

            ChannelProtos.Channel channel = ChannelProtos.Channel.newBuilder()
                    .setIndex(channelIndex)
                    .setSettings(settingsBuilder.build())
                    .setRole(currentChannel.getRole())
                    .build();

            saveBtn.setDisable(true);
            statusLabel.setText("Запрос session key...");

            // Паттерн session_passkey: запрос → ожидание → отправка
            Runnable[] listenerHolder = new Runnable[1];
            listenerHolder[0] = () -> Platform.runLater(() -> {
                state.removeOwnerInfoListener(listenerHolder[0]);
                statusLabel.setText("Отправка...");

                MessageService.setChannel(
                        protocolHandler, state, channel, state.getSessionPasskey());
                state.updateChannel(channel);

                Toast.show(Toast.Type.SUCCESS,
                        "Канал \"" + (channelName.isEmpty() ? "Ch " + channelIndex : channelName) + "\" обновлён");
                modalPane.hide();
                onSaved.run();
            });
            state.addOwnerInfoListener(listenerHolder[0]);

            // Таймаут 5 сек — отправить без passkey (для локальных устройств)
            Thread timeout = new Thread(() -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    return;
                }
                Platform.runLater(() -> {
                    state.removeOwnerInfoListener(listenerHolder[0]);
                    if (saveBtn.isDisable()) {
                        MessageService.setChannel(
                                protocolHandler, state, channel, null);
                        state.updateChannel(channel);

                        Toast.show(Toast.Type.SUCCESS,
                                "Канал \"" + (channelName.isEmpty() ? "Ch " + channelIndex : channelName) + "\" обновлён");
                        modalPane.hide();
                        onSaved.run();
                    }
                });
            }, "channel-properties-timeout");
            timeout.setDaemon(true);
            timeout.start();

            MessageService.requestOwnerInfo(protocolHandler, state);
        });

        modalPane.show(panel);
        Platform.runLater(nameField::requestFocus);
    }

    /**
     * Найти канал по индексу в текущем состоянии устройства.
     */
    private static ChannelProtos.Channel findChannel(DeviceState state, int channelIndex) {
        List<ChannelProtos.Channel> channels = state.getChannels();
        for (ChannelProtos.Channel ch : channels) {
            if (ch.getIndex() == channelIndex) {
                return ch;
            }
        }
        return null;
    }
}
