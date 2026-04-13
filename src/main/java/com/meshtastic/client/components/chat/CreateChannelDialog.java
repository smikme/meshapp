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
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.meshtastic.proto.ChannelProtos;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Модальное окно создания SECONDARY канала на устройстве.
 *
 * <p>Вызывается статическим методом {@link #show(DeviceState, ProtocolHandler, Runnable)}.
 */
public final class CreateChannelDialog {

    private static final int[] PRECISION_BITS = {10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 32};
    private static final String[] PRECISION_LABELS = {
            "В пределах 23 км", "В пределах 12 км", "В пределах 5.8 км",
            "В пределах 2.9 км", "В пределах 1.5 км", "В пределах 700 м",
            "В пределах 350 м", "В пределах 200 м", "В пределах 90 м",
            "В пределах 50 м", "Точная позиция"
    };

    private CreateChannelDialog() {}

    /**
     * Показать диалог создания канала.
     *
     * @param state           текущее состояние устройства
     * @param protocolHandler обработчик протокола для отправки команд
     * @param onCreated       колбэк, вызываемый после успешного создания канала
     */
    public static void show(DeviceState state,
                            ProtocolHandler protocolHandler,
                            Runnable onCreated) {
        if (state == null || protocolHandler == null) {
            Toast.show(Toast.Type.WARNING, "Нет подключения к радио");
            return;
        }

        int availableSlot = state.findFirstAvailableChannelSlot();
        if (availableSlot < 0) {
            Toast.show(Toast.Type.WARNING, "Все слоты каналов заняты (макс. 7)");
            return;
        }

        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            return;
        }

        // === Форма ===
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(340);
        panel.setMaxWidth(340);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        Label title = new Label("Создать канал");
        title.getStyleClass().add("dialog-title");

        Separator sep = new Separator();

        // Имя канала
        Label nameLabel = new Label("Имя канала:");
        TextField nameField = new TextField();
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
        TextField pskField = new TextField();
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
        CheckBox downlinkCheck = new CheckBox("Downlink");
        CheckBox positionCheck = new CheckBox("Позиция");

        // Слайдер точности позиции
        Label precisionLabel = new Label(PRECISION_LABELS[4]);
        precisionLabel.getStyleClass().add("muted-small-label");

        Slider precisionSlider = new Slider(0, PRECISION_BITS.length - 1, 4);
        precisionSlider.setMajorTickUnit(1);
        precisionSlider.setMinorTickCount(0);
        precisionSlider.setSnapToTicks(true);
        precisionSlider.setMaxWidth(280);
        precisionSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                precisionLabel.setText(PRECISION_LABELS[newVal.intValue()]));

        VBox precisionBox = new VBox(4, precisionSlider, precisionLabel);
        precisionBox.setPadding(new Insets(0, 0, 0, 24));
        precisionBox.setVisible(false);
        precisionBox.setManaged(false);
        positionCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            precisionBox.setVisible(newVal);
            precisionBox.setManaged(newVal);
        });

        // Статус
        Label statusLabel = new Label("");
        statusLabel.getStyleClass().add("muted-small-label");
        statusLabel.setWrapText(true);

        // Кнопки
        Button createBtn = new Button("Создать");
        createBtn.getStyleClass().add("accent");

        Button cancelBtn = new Button("Отмена");
        cancelBtn.setOnAction(e -> modalPane.hide());

        HBox btnRow = new HBox(10, cancelBtn, createBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));

        panel.getChildren().addAll(title, sep, nameLabel, nameField, encLabel, pskRow,
                uplinkCheck, downlinkCheck, positionCheck, precisionBox, statusLabel, btnRow);

        // === Обработка «Создать» ===
        createBtn.setOnAction(e -> {
            String channelName = nameField.getText() != null
                    ? nameField.getText().trim() : "";
            if (channelName.isEmpty()) {
                statusLabel.setText("Введите имя канала");
                return;
            }

            // PSK из текстового поля (base64)
            com.google.protobuf.ByteString psk;
            String pskText = pskField.getText() != null
                    ? pskField.getText().trim() : "";
            if (pskText.isEmpty()) {
                psk = com.google.protobuf.ByteString.EMPTY;
            } else {
                byte[] decoded = Base64.getDecoder().decode(pskText);
                psk = com.google.protobuf.ByteString.copyFrom(decoded);
            }

            int slot = state.findFirstAvailableChannelSlot();
            if (slot < 0) {
                statusLabel.setText("Нет свободных слотов");
                return;
            }

            // Собрать Channel protobuf
            ChannelProtos.ChannelSettings.Builder settingsBuilder =
                    ChannelProtos.ChannelSettings.newBuilder()
                            .setName(channelName)
                            .setPsk(psk)
                            .setId(ThreadLocalRandom.current().nextInt())
                            .setUplinkEnabled(uplinkCheck.isSelected())
                            .setDownlinkEnabled(downlinkCheck.isSelected());

            if (positionCheck.isSelected()) {
                int bits = PRECISION_BITS[(int) precisionSlider.getValue()];
                settingsBuilder.setModuleSettings(
                        ChannelProtos.ModuleSettings.newBuilder()
                                .setPositionPrecision(bits)
                                .build());
            }

            ChannelProtos.ChannelSettings settings = settingsBuilder.build();

            ChannelProtos.Channel channel = ChannelProtos.Channel.newBuilder()
                    .setIndex(slot)
                    .setSettings(settings)
                    .setRole(ChannelProtos.Channel.Role.SECONDARY)
                    .build();

            createBtn.setDisable(true);
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
                        "Канал \"" + channelName + "\" создан");
                modalPane.hide();
                onCreated.run();
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
                    if (createBtn.isDisable()) {
                        MessageService.setChannel(
                                protocolHandler, state, channel, null);
                        state.updateChannel(channel);

                        Toast.show(Toast.Type.SUCCESS,
                                "Канал \"" + channelName + "\" создан");
                        modalPane.hide();
                        onCreated.run();
                    }
                });
            }, "channel-create-timeout");
            timeout.setDaemon(true);
            timeout.start();

            MessageService.requestSessionPasskey(protocolHandler, state);
        });

        modalPane.show(panel);
        Platform.runLater(nameField::requestFocus);
    }
}
