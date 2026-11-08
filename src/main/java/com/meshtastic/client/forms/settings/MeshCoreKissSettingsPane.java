package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.protocol.meshcore.*;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Modem controls; deliberately does not offer Companion chats. */
public final class MeshCoreKissSettingsPane extends VBox implements AutoCloseable {
    private final MeshCoreKissProtocolRuntime runtime;
    private final TextArea output = new TextArea();
    private final Consumer<byte[]> listener =
            b -> Platform.runLater(() -> append("RX " + HexFormat.of().formatHex(b)));

    public MeshCoreKissSettingsPane(MeshCoreKissProtocolRuntime runtime) {
        super(10);
        this.runtime = runtime;
        setPadding(new javafx.geometry.Insets(12));
        output.setEditable(false);
        output.setPrefRowCount(10);
        var radio = runtime.getState().getRadioParameters();
        TextField
                frequency = new TextField(radio == null ? "" : String.valueOf(radio.frequencyHz())),
                bw = new TextField(radio == null ? "" : String.valueOf(radio.bandwidthHz()));
        TextField sf = new TextField(radio == null ? "" : String.valueOf(radio.spreadingFactor())),
                cr = new TextField(radio == null ? "" : String.valueOf(radio.codingRate())),
                power = new TextField(Objects.toString(runtime.getState().getTxPowerDbm(), ""));
        TextArea data = new TextArea();
        data.setPrefRowCount(3);
        data.setPromptText(I18n.t("meshcore.kissData"));
        TextField subcommand = new TextField("13"), payload = new TextField();
        getChildren()
                .addAll(
                        new Label(I18n.t("meshcore.kissHint")),
                        new Label("Frequency (Hz) / BW (Hz) / SF / CR"),
                        new HBox(8, frequency, bw, sf, cr),
                        action(
                                "meshcore.saveRadio",
                                () ->
                                        runtime.setRadio(
                                                Long.parseLong(frequency.getText()),
                                                Long.parseLong(bw.getText()),
                                                Integer.parseInt(sf.getText()),
                                                Integer.parseInt(cr.getText()))),
                        new Label(I18n.t("meshcore.txPower")),
                        power,
                        action(
                                "meshcore.savePower",
                                () -> runtime.setPower(Integer.parseInt(power.getText()))),
                        action(
                                "meshcore.refresh",
                                () ->
                                        runtime.refresh()
                                                .thenRun(() ->
                                                        Platform.runLater(() ->
                                                                append(
                                                                        String.valueOf(
                                                                                runtime.getState()
                                                                                        .getStats()))))),
                        data,
                        action(
                                "meshcore.kissSend",
                                () ->
                                        runtime.sendData(
                                                HexFormat.of()
                                                        .parseHex(
                                                                data.getText()
                                                                        .replaceAll("\\s", "")))),
                        new Label(I18n.t("meshcore.kissHardware")),
                        new HBox(8, subcommand, payload),
                        action(
                                "meshcore.execute",
                                () ->
                                        runtime.hardware(
                                                Integer.parseInt(subcommand.getText(), 16),
                                                HexFormat.of().parseHex(payload.getText()))),
                        output);
        runtime.addDataListener(listener);
    }

    private Button action(String key, Supplier<? extends CompletableFuture<?>> work) {
        Button b = new Button(I18n.t(key));
        b.setOnAction(e -> {
            try {
                b.setDisable(true);
                work.get()
                        .whenComplete((v, error) ->
                                Platform.runLater(() -> {
                                    b.setDisable(false);
                                    append(
                                            error == null
                                                    ? (v instanceof byte[] bytes
                                                            ? HexFormat.of().formatHex(bytes)
                                                            : I18n.t("meshcore.saved"))
                                                    : com.meshtastic.client.components.chat
                                                            .MeshCoreChannelDialog.message(error));
                                }));
            } catch (RuntimeException error) {
                b.setDisable(false);
                append(error.getMessage());
            }
        });
        return b;
    }

    private void append(String line) {
        output.appendText(line + "\n");
        if (output.getLength() > 20000) output.deleteText(0, output.getLength() - 16000);
    }

    @Override
    public void close() {
        runtime.removeDataListener(listener);
    }
}
