package com.meshtastic.client.components.chat;

import com.meshtastic.client.i18n.I18n;
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
import java.util.List;

/**
 * Modal editor for an existing channel and its radio-side settings.
 *
 * <p>Use {@link #show(DeviceState, ProtocolHandler, int, Runnable)} to open
 * the panel for a channel index.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ChannelPropertiesDialog {

    private static final int[] PRECISION_BITS = {10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 32};
    private static final String[] PRECISION_KEYS = {
            "chat.channel.precision.23km", "chat.channel.precision.12km",
            "chat.channel.precision.5_8km", "chat.channel.precision.2_9km",
            "chat.channel.precision.1_5km", "chat.channel.precision.700m",
            "chat.channel.precision.350m", "chat.channel.precision.200m",
            "chat.channel.precision.90m", "chat.channel.precision.50m",
            "chat.channel.precision.exact"
    };

    private ChannelPropertiesDialog() {}

    /**
     * Opens the editable channel properties panel.
     *
     * @param state current device state
     * @param protocolHandler protocol handler used to send radio commands
     * @param channelIndex channel index, where 0 is PRIMARY and 1-7 are SECONDARY
     * @param onSaved callback invoked after a successful save
     */
    public static void show(DeviceState state,
                            ProtocolHandler protocolHandler,
                            int channelIndex,
                            Runnable onSaved) {
        if (state == null || protocolHandler == null) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.radioNotConnected"));
            return;
        }

        // Resolve the channel before constructing the editor.
        ChannelProtos.Channel currentChannel = findChannel(state, channelIndex);
        if (currentChannel == null) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.channelNotFound"));
            return;
        }

        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            return;
        }

        ChannelProtos.ChannelSettings currentSettings = currentChannel.getSettings();

        // Build the form.
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(340);
        panel.setMaxWidth(340);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        Label title = new Label(I18n.t("chat.channel.properties.title"));
        title.getStyleClass().add("dialog-title");

        Separator sep = new Separator();

        // Channel name.
        Label nameLabel = new Label(I18n.t("chat.channel.name"));
        TextField nameField = new TextField(currentSettings.getName());
        nameField.setPromptText(I18n.t("chat.channel.namePrompt"));
        nameField.setMaxWidth(280);
        nameField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null
                    && newVal.getBytes(StandardCharsets.UTF_8).length > 11) {
                nameField.setText(oldVal);
            }
        });

        // Encryption key (PSK).
        Label encLabel = new Label(I18n.t("chat.channel.psk"));
        String currentPskBase64 = currentSettings.getPsk().isEmpty()
                ? ""
                : Base64.getEncoder().encodeToString(currentSettings.getPsk().toByteArray());
        TextField pskField = new TextField(currentPskBase64);
        pskField.setPromptText(I18n.t("chat.channel.pskPrompt"));
        pskField.setMaxWidth(280);

        Button generateKeyBtn = new Button(I18n.t("common.generate"));
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

        CheckBox positionCheck = new CheckBox(I18n.t("chat.channel.position"));
        int currentPrecisionVal = currentSettings.hasModuleSettings()
                ? currentSettings.getModuleSettings().getPositionPrecision() : 0;
        positionCheck.setSelected(currentPrecisionVal > 0);

        // Position precision slider.
        int initIndex = 4; // Default: 14 bits, about 1.5 km.
        if (currentPrecisionVal > 0) {
            for (int i = 0; i < PRECISION_BITS.length; i++) {
                if (PRECISION_BITS[i] >= currentPrecisionVal) {
                    initIndex = i;
                    break;
                }
            }
        }

        Label precisionLabel = new Label(precisionLabel(initIndex));
        precisionLabel.getStyleClass().add("muted-small-label");

        Slider precisionSlider = new Slider(0, PRECISION_BITS.length - 1, initIndex);
        precisionSlider.setMajorTickUnit(1);
        precisionSlider.setMinorTickCount(0);
        precisionSlider.setSnapToTicks(true);
        precisionSlider.setMaxWidth(280);
        precisionSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                precisionLabel.setText(precisionLabel(newVal.intValue())));

        VBox precisionBox = new VBox(4, precisionSlider, precisionLabel);
        precisionBox.setPadding(new Insets(0, 0, 0, 24));
        precisionBox.setVisible(positionCheck.isSelected());
        precisionBox.setManaged(positionCheck.isSelected());
        positionCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            precisionBox.setVisible(newVal);
            precisionBox.setManaged(newVal);
        });

        // Status message.
        Label statusLabel = new Label("");
        statusLabel.getStyleClass().add("muted-small-label");
        statusLabel.setWrapText(true);

        // Action buttons.
        Button saveBtn = new Button(I18n.t("common.save"));
        saveBtn.getStyleClass().add("accent");

        Button cancelBtn = new Button(I18n.t("common.cancel"));
        cancelBtn.setOnAction(e -> modalPane.hide());

        HBox btnRow = new HBox(10, cancelBtn, saveBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));

        panel.getChildren().addAll(title, sep, nameLabel, nameField, encLabel, pskRow,
                uplinkCheck, downlinkCheck, positionCheck, precisionBox, statusLabel, btnRow);

        // Save handling.
        saveBtn.setOnAction(e -> {
            String channelName = nameField.getText() != null
                    ? nameField.getText().trim() : "";

            // Decode the PSK from the Base64 text field.
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
                    statusLabel.setText(I18n.t("chat.channel.invalidPsk"));
                    return;
                }
            }

            // Build the Channel protobuf while preserving the current id and role.
            ChannelProtos.ChannelSettings.Builder settingsBuilder =
                    ChannelProtos.ChannelSettings.newBuilder()
                            .setName(channelName)
                            .setPsk(psk)
                            .setId(currentSettings.getId())
                            .setUplinkEnabled(uplinkCheck.isSelected())
                            .setDownlinkEnabled(downlinkCheck.isSelected());

            if (positionCheck.isSelected()) {
                int bits = PRECISION_BITS[(int) precisionSlider.getValue()];
                settingsBuilder.setModuleSettings(
                        ChannelProtos.ModuleSettings.newBuilder()
                                .setPositionPrecision(bits)
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
            statusLabel.setText(I18n.t("chat.channel.requestSessionKey"));

            // session_passkey flow: request it, wait for it, then send the update.
            Runnable[] listenerHolder = new Runnable[1];
            listenerHolder[0] = () -> Platform.runLater(() -> {
                state.removeOwnerInfoListener(listenerHolder[0]);
                statusLabel.setText(I18n.t("chat.channel.sending"));

                MessageService.setChannel(
                        protocolHandler, state, channel, state.getSessionPasskey());
                state.updateChannel(channel);

                Toast.show(Toast.Type.SUCCESS,
                        I18n.t("chat.channel.updated", displayChannelName(channelName, channelIndex)));
                modalPane.hide();
                onSaved.run();
            });
            state.addOwnerInfoListener(listenerHolder[0]);

            // After 5 seconds, fall back to sending without a passkey for local devices.
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
                                I18n.t("chat.channel.updated", displayChannelName(channelName, channelIndex)));
                        modalPane.hide();
                        onSaved.run();
                    }
                });
            }, "channel-properties-timeout");
            timeout.setDaemon(true);
            timeout.start();

            MessageService.requestSessionPasskey(protocolHandler, state);
        });

        modalPane.show(panel);
        Platform.runLater(nameField::requestFocus);
    }

    /**
     * Finds a channel by index in the current device state.
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

    private static String displayChannelName(String channelName, int channelIndex) {
        return channelName.isEmpty()
                ? I18n.t("chat.channel.defaultName", channelIndex)
                : channelName;
    }

    private static String precisionLabel(int index) {
        return I18n.t(PRECISION_KEYS[index]);
    }
}
