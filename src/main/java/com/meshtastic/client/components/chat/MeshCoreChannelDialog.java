package com.meshtastic.client.components.chat;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.protocol.meshcore.*;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.Base64;

/** Companion channel editor; successful writes are verified by GET_CHANNEL. */
public final class MeshCoreChannelDialog {
    private MeshCoreChannelDialog() {}

    public static void show(
            MeshCoreCompanionProtocolRuntime runtime, Integer selected, Runnable changed) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("meshcore.channels"));
        ComboBox<Integer> slot = new ComboBox<>();
        for (int i = 0; i < runtime.maxChannels(); i++) slot.getItems().add(i);
        int free = 0;
        while (free < runtime.maxChannels()
                && runtime.getState().getDeviceState().hasEnabledChannel(free)) free++;
        slot.setValue(selected != null ? selected : Math.min(free, runtime.maxChannels() - 1));
        TextField name = new TextField();
        PasswordField secret = new PasswordField();
        secret.setPromptText(I18n.t("meshcore.keyHint"));
        Label status = new Label();
        status.setWrapText(true);
        Button generate = new Button(I18n.t("common.generate"));
        generate.setOnAction(e ->
                secret.setText(
                        Base64.getEncoder().encodeToString(MeshCoreCommands.channelKey("", null))));
        Runnable load =
                () -> {
                    var c = runtime.getState().getMeshCoreChannels().get(slot.getValue());
                    name.setText(c == null || c.name() == null ? "" : c.name());
                    secret.setText(c == null ? "" : Base64.getEncoder().encodeToString(c.secret()));
                };
        load.run();
        slot.valueProperty().addListener((o, a, b) -> load.run());
        TextField link = new TextField();
        link.setPromptText("meshcore://channel/add?…");
        Button importLink = new Button(I18n.t("meshcore.importLink"));
        importLink.setOnAction(e -> {
            try {
                var c = MeshCoreSharing.channelFromLink(slot.getValue(), link.getText());
                name.setText(c.name());
                secret.setText(Base64.getEncoder().encodeToString(c.secret()));
            } catch (RuntimeException ex) {
                status.setText(message(ex));
            }
        });
        Button shareLink = new Button(I18n.t("meshcore.shareLink"));
        shareLink.setOnAction(e -> {
            try {
                byte[] key = MeshCoreCommands.channelKey(name.getText(), secret.getText());
                secret.setText(Base64.getEncoder().encodeToString(key));
                MeshCoreShareDialog.show(
                        MeshCoreSharing.channelLink(
                                new MeshCoreChannel(slot.getValue(), name.getText(), key)));
            } catch (RuntimeException ex) {
                status.setText(message(ex));
            }
        });
        Button save = new Button(I18n.t("common.save")),
                clear = new Button(I18n.t("meshcore.clearChannel"));
        save.setOnAction(e -> {
            try {
                byte[] key = MeshCoreCommands.channelKey(name.getText(), secret.getText());
                save.setDisable(true);
                runtime.setChannel(slot.getValue(), name.getText(), key)
                        .whenComplete((v, error) ->
                                Platform.runLater(() -> {
                                    save.setDisable(false);
                                    if (error == null) {
                                        changed.run();
                                        dialog.close();
                                    } else status.setText(message(error));
                                }));
            } catch (RuntimeException ex) {
                status.setText(message(ex));
            }
        });
        clear.setOnAction(e -> {
            Alert confirm =
                    new Alert(
                            Alert.AlertType.CONFIRMATION,
                            I18n.t("meshcore.clearChannelConfirm"),
                            ButtonType.OK,
                            ButtonType.CANCEL);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            clear.setDisable(true);
            runtime.setChannel(slot.getValue(), "", new byte[16])
                    .whenComplete((v, error) ->
                            Platform.runLater(() -> {
                                clear.setDisable(false);
                                if (error == null) {
                                    changed.run();
                                    dialog.close();
                                } else status.setText(message(error));
                            }));
        });
        VBox content =
                new VBox(
                        8,
                        new Label(I18n.t("meshcore.slot")),
                        slot,
                        new Label(I18n.t("chat.channel.name")),
                        name,
                        new Label(I18n.t("chat.channel.psk")),
                        secret,
                        generate,
                        link,
                        new HBox(8, importLink, shareLink),
                        new HBox(8, save, clear),
                        status);
        content.setPrefWidth(420);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }

    public static String message(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
