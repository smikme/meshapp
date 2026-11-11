package com.meshtastic.client.forms.settings;

import com.meshtastic.client.components.chat.MeshCoreChannelDialog;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.protocol.meshcore.*;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/** Editable Companion settings and contact management, independent of Meshtastic Admin. */
public final class MeshCoreSettingsPane extends VBox implements AutoCloseable {
    private final MeshCoreCompanionProtocolRuntime runtime;
    private final Label status = new Label();
    private final Label phase = new Label();
    private final Runnable listener =
            () ->
                    Platform.runLater(() -> {
                        if (!this.closed) {
                            updatePhase();
                            refreshLists();
                        }
                    });
    private final ListView<MeshCoreChannel> channels = new ListView<>();
    private final ListView<MeshCoreContact> contacts = new ListView<>();
    private boolean closed;

    public MeshCoreSettingsPane(MeshCoreCompanionProtocolRuntime runtime) {
        super(10);
        this.runtime = runtime;
        setPadding(new Insets(10));
        status.setWrapText(true);
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs()
                .addAll(
                        tab("meshcore.device", device()),
                        tab("meshcore.channels", channels()),
                        tab("meshcore.contacts", contacts()),
                        tab("meshcore.tools", tools()),
                        tab("meshcore.advanced", advanced()));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().addAll(phase, tabs, status);
        runtime.getState().addListener(listener);
        updatePhase();
        refreshLists();
    }

    private Tab tab(String key, Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        return new Tab(I18n.t(key), scroll);
    }

    private TextField field(Object value) {
        return new TextField(value == null ? "" : String.valueOf(value));
    }

    private VBox row(String key, Node input) {
        return new VBox(4, new Label(I18n.t(key)), input);
    }

    private Button action(String key, Supplier<? extends CompletableFuture<?>> work) {
        Button button = new Button(I18n.t(key));
        button.setOnAction(_ -> {
            try {
                button.setDisable(true);
                status.setText(I18n.t("meshcore.working"));
                work.get()
                        .whenComplete((_, error) ->
                                Platform.runLater(() -> {
                                    if (closed) return;
                                    button.setDisable(false);
                                    status.setText(
                                            error == null
                                                    ? I18n.t("meshcore.saved")
                                                    : MeshCoreChannelDialog.message(error));
                                    refreshLists();
                                }));
            } catch (RuntimeException ex) {
                button.setDisable(false);
                status.setText(MeshCoreChannelDialog.message(ex));
            }
        });
        return button;
    }

    private VBox device() {
        var s = runtime.getState();
        TextField name = field(s.getDeviceName()),
                frequency = field(s.getRadioFrequencyMhz()),
                bandwidth = field(s.getRadioBandwidthKhz());
        TextField sf = field(s.getRadioSpreadingFactor()),
                cr = field(s.getRadioCodingRate()),
                power = field(s.getTxPowerDbm());
        TextField lat = field(s.getAdvertisementLatitude()),
                lon = field(s.getAdvertisementLongitude());
        PasswordField pin = new PasswordField();
        pin.setPromptText("000000");
        CheckBox repeat = new CheckBox(I18n.t("meshcore.repeat"));
        repeat.setSelected(s.isRepeatEnabled());
        VBox box =
                new VBox(
                        12,
                        row("meshcore.name", name),
                        action("meshcore.saveName", () -> runtime.setName(name.getText())),
                        row("meshcore.frequency", frequency),
                        row("meshcore.bandwidth", bandwidth),
                        new HBox(10, row("meshcore.sf", sf), row("meshcore.cr", cr)),
                        repeat,
                        action(
                                "meshcore.saveRadio",
                                () ->
                                        runtime.setRadio(
                                                Double.parseDouble(frequency.getText()),
                                                Double.parseDouble(bandwidth.getText()),
                                                Integer.parseInt(sf.getText()),
                                                Integer.parseInt(cr.getText()),
                                                repeat.isSelected())),
                        row("meshcore.txPower", power),
                        action(
                                "meshcore.savePower",
                                () -> runtime.setTxPower(Integer.parseInt(power.getText()))),
                        new HBox(10, row("meshcore.latitude", lat), row("meshcore.longitude", lon)),
                        action(
                                "meshcore.savePosition",
                                () ->
                                        runtime.setPosition(
                                                Double.parseDouble(lat.getText()),
                                                Double.parseDouble(lon.getText()))),
                        action("meshcore.syncTime", runtime::synchronizeTime),
                        action("meshcore.advert", () -> runtime.sendAdvertisement(true)),
                        row("meshcore.pin", pin),
                        action(
                                "meshcore.savePin",
                                () -> runtime.setPin(Integer.parseInt(pin.getText()))));
        CheckBox manual = new CheckBox(I18n.t("meshcore.manualContacts"));
        manual.setSelected(Boolean.TRUE.equals(s.getManualAddContacts()));
        ComboBox<Integer> base = mode(s.getTelemetryModeBase()),
                location = mode(s.getTelemetryModeLocation()),
                environment = mode(s.getTelemetryModeEnvironment());
        ComboBox<Integer> policy = new ComboBox<>();
        policy.getItems().addAll(0, 1);
        if (s.getAdvertisementLocationPolicy() != null
                && !policy.getItems().contains(s.getAdvertisementLocationPolicy()))
            policy.getItems().add(s.getAdvertisementLocationPolicy());
        policy.setValue(
                s.getAdvertisementLocationPolicy() == null
                        ? 0
                        : s.getAdvertisementLocationPolicy());
        policy.setConverter(
                labels(value ->
                        switch (value) {
                            case 0 -> I18n.t("meshcore.location.0");
                            case 1 -> I18n.t("meshcore.location.1");
                            case 2 -> I18n.t("meshcore.location.2");
                            default -> Integer.toString(value);
                        }));
        Spinner<Integer> multiAcks = new Spinner<>(0, 255, s.getMultiAckCount());
        box.getChildren()
                .addAll(
                        manual,
                        row("meshcore.telemetryBase", base),
                        row("meshcore.telemetryLocation", location),
                        row("meshcore.telemetryEnvironment", environment),
                        row("meshcore.locationPolicy", policy),
                        row("meshcore.multiAcks", multiAcks),
                        action(
                                "meshcore.savePolicies",
                                () ->
                                        runtime.setOtherParameters(
                                                manual.isSelected(),
                                                base.getValue()
                                                        | (location.getValue() << 2)
                                                        | (environment.getValue() << 4),
                                                policy.getValue(),
                                                multiAcks.getValue())));
        Button reboot =
                action(
                        "meshcore.reboot",
                        () -> {
                            if (confirmationDeclined("meshcore.rebootConfirm"))
                                return CompletableFuture.failedFuture(
                                        new java.util.concurrent.CancellationException());
                            return runtime.reboot();
                        });
        box.getChildren().add(reboot);
        return box;
    }

    private javafx.util.StringConverter<Integer> labels(IntFunction<String> label) {
        return new javafx.util.StringConverter<>() {
            public String toString(Integer value) {
                return value == null ? "" : label.apply(value);
            }

            public Integer fromString(String value) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static String contactTypeLabel(int type) {
        return switch (type) {
            case 1 -> I18n.t("meshcore.type.1");
            case 2 -> I18n.t("meshcore.type.2");
            case 3 -> I18n.t("meshcore.type.3");
            case 4 -> I18n.t("meshcore.type.4");
            default -> Integer.toString(type);
        };
    }

    private ComboBox<Integer> mode(Integer selected) {
        ComboBox<Integer> box = new ComboBox<>();
        box.getItems().addAll(0, 1, 2, 3);
        box.setValue(selected == null ? 0 : selected);
        box.setConverter(
                labels(value ->
                        switch (value) {
                            case 0 -> I18n.t("meshcore.permission.0");
                            case 1 -> I18n.t("meshcore.permission.1");
                            case 2 -> I18n.t("meshcore.permission.2");
                            case 3 -> I18n.t("meshcore.permission.3");
                            default -> Integer.toString(value);
                        }));
        return box;
    }

    private VBox channels() {
        channels.setPrefHeight(240);
        Button add = new Button(I18n.t("meshcore.editChannel"));
        add.setOnAction(_ ->
                MeshCoreChannelDialog.show(
                        runtime,
                        channels.getSelectionModel().getSelectedItem() == null
                                ? null
                                : channels.getSelectionModel().getSelectedItem().index(),
                        this::refreshLists));
        return new VBox(
                10,
                channels,
                new HBox(10, add, action("meshcore.refresh", runtime::refreshChannels)));
    }

    private MeshCoreContact selected() {
        var c = contacts.getSelectionModel().getSelectedItem();
        if (c == null) throw new IllegalArgumentException(I18n.t("meshcore.selectContact"));
        return c;
    }

    private void editContact(boolean create) {
        MeshCoreContact previous = create ? null : selected();
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("meshcore.editContact"));
        TextField key = field(previous == null ? "" : previous.id()),
                name = field(previous == null ? "" : previous.name()),
                lat = field(previous == null ? 0 : previous.latitude()),
                lon = field(previous == null ? 0 : previous.longitude());
        key.setEditable(create);
        ComboBox<Integer> type = new ComboBox<>();
        type.getItems().addAll(1, 2, 3, 4);
        type.setValue(previous == null ? 1 : previous.type());
        type.setConverter(labels(MeshCoreSettingsPane::contactTypeLabel));
        CheckBox favorite = new CheckBox(I18n.t("meshcore.favorite"));
        favorite.setSelected(previous != null && (previous.flags() & 1) != 0);
        Label result = new Label();
        Button save = new Button(I18n.t("common.save"));
        save.setOnAction(_ -> {
            try {
                MeshCoreContact edited =
                        MeshCoreContact.create(
                                        MeshCoreCommands.key(key.getText()),
                                        name.getText(),
                                        type.getValue(),
                                        previous == null ? 0 : previous.flags(),
                                        Double.parseDouble(lat.getText()),
                                        Double.parseDouble(lon.getText()))
                                .withFavorite(favorite.isSelected());
                if (previous != null) {
                    byte[] packet = edited.packet();
                    System.arraycopy(previous.packet(), 35, packet, 35, 65);
                    MeshCoreCompanionFrames.writeIntLe(packet, 132, previous.lastAdvert());
                    edited = new MeshCoreContact(packet);
                }
                save.setDisable(true);
                runtime.updateContact(edited)
                        .whenComplete((_, error) ->
                                Platform.runLater(() -> {
                                    save.setDisable(false);
                                    if (error == null) {
                                        refreshLists();
                                        dialog.close();
                                    } else result.setText(MeshCoreChannelDialog.message(error));
                                }));
            } catch (RuntimeException error) {
                result.setText(MeshCoreChannelDialog.message(error));
            }
        });
        dialog.getDialogPane()
                .setContent(
                        new VBox(
                                8,
                                row("meshcore.publicKey", key),
                                row("meshcore.name", name),
                                row("meshcore.contactType", type),
                                favorite,
                                row("meshcore.latitude", lat),
                                row("meshcore.longitude", lon),
                                save,
                                result));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }

    private VBox contacts() {
        contacts.setPrefHeight(260);
        contacts.setCellFactory(_ ->
                new ListCell<>() {
                    @Override
                    protected void updateItem(MeshCoreContact c, boolean empty) {
                        super.updateItem(c, empty);
                        setText(
                                empty || c == null
                                        ? null
                                        : (c.name() == null ? "" : c.name())
                                                + " · "
                                                + c.id()
                                                + " · "
                                                + contactTypeLabel(c.type()));
                    }
                });
        TextArea data = new TextArea();
        data.setPrefRowCount(3);
        data.setWrapText(true);
        data.setPromptText(I18n.t("meshcore.contactData"));
        Button export =
                action(
                        "meshcore.exportContact",
                        () ->
                                runtime.exportContact(selected().id())
                                        .thenAccept(b ->
                                                Platform.runLater(() ->
                                                        data.setText(
                                                                HexFormat.of().formatHex(b)))));
        Button importButton =
                action(
                        "meshcore.importContact",
                        () ->
                                data.getText().trim().startsWith("meshcore:")
                                        ? runtime.updateContact(
                                                MeshCoreSharing.contactFromLink(data.getText()))
                                        : runtime.importContact(
                                                HexFormat.of().parseHex(data.getText().trim())));
        Button add = new Button(I18n.t("meshcore.addContact")),
                edit = new Button(I18n.t("meshcore.editContact")),
                qr = new Button(I18n.t("meshcore.shareLink"));
        add.setOnAction(_ -> editContact(true));
        edit.setOnAction(_ -> {
            try {
                editContact(false);
            } catch (RuntimeException ex) {
                status.setText(ex.getMessage());
            }
        });
        qr.setOnAction(_ -> {
            try {
                com.meshtastic.client.components.chat.MeshCoreShareDialog.show(
                        MeshCoreSharing.contactLink(selected()));
            } catch (RuntimeException ex) {
                status.setText(ex.getMessage());
            }
        });
        PasswordField password = new PasswordField();
        TextField command = new TextField();
        TextArea remoteOutput = new TextArea();
        remoteOutput.setEditable(false);
        remoteOutput.setPrefRowCount(4);
        java.util.function.Consumer<Object> display =
                value ->
                        Platform.runLater(() ->
                                remoteOutput.setText(
                                        value instanceof byte[] b
                                                ? HexFormat.ofDelimiter(" ").formatHex(b)
                                                : String.valueOf(value)));
        Button remove =
                action(
                        "meshcore.removeContact",
                        () -> {
                            String id = selected().id();
                            if (confirmationDeclined("meshcore.removeContactConfirm"))
                                return CompletableFuture.failedFuture(
                                        new java.util.concurrent.CancellationException());
                            return runtime.removeContact(id);
                        });
        return new VBox(
                10,
                contacts,
                new FlowPane(
                        8,
                        8,
                        add,
                        edit,
                        qr,
                        action("meshcore.refresh", runtime::refreshContacts),
                        action("meshcore.resetPath", () -> runtime.resetPath(selected().id())),
                        action(
                                "meshcore.shareContact",
                                () -> runtime.shareContact(selected().id())),
                        remove),
                data,
                new HBox(8, export, importButton),
                row("meshcore.remotePassword", password),
                new FlowPane(
                        8,
                        8,
                        action(
                                "meshcore.login",
                                () ->
                                        runtime.login(selected().id(), password.getText())
                                                .thenAccept(display)),
                        action("meshcore.logout", () -> runtime.logout(selected().id())),
                        action(
                                "meshcore.remoteStatus",
                                () -> runtime.remoteStatus(selected().id()).thenAccept(display)),
                        action(
                                "meshcore.telemetry",
                                () ->
                                        runtime.telemetry(selected().id())
                                                .thenAccept(b ->
                                                        display.accept(
                                                                MeshCoreTelemetry.decode(
                                                                        Arrays.copyOfRange(
                                                                                b, 8, b.length))))),
                        action(
                                "meshcore.discoverPath",
                                () -> runtime.discoverPath(selected().id()).thenAccept(display)),
                        action(
                                "meshcore.advertPath",
                                () -> runtime.advertPath(selected().id()).thenAccept(display))),
                row("meshcore.remoteCommand", command),
                action(
                        "meshcore.remoteCli",
                        () ->
                                runtime.remoteCli(selected().id(), command.getText())
                                        .thenAccept(display)),
                remoteOutput);
    }

    private VBox tools() {
        TextField cli = new TextField();
        cli.setPromptText(I18n.t("meshcore.cliHint"));
        TextArea output = new TextArea();
        output.setEditable(false);
        output.setPrefRowCount(12);
        Button execute =
                action(
                        "meshcore.runCli",
                        () ->
                                runtime.runCli(cli.getText())
                                        .thenAccept(text ->
                                                Platform.runLater(() -> output.setText(text))));
        execute.setDisable(
                runtime.getState().getFirmwareProtocolVersion() == null
                        || runtime.getState().getFirmwareProtocolVersion() < 14);
        ComboBox<Integer> stats = new ComboBox<>();
        stats.getItems().addAll(0, 1, 2);
        stats.setValue(0);
        TextField variable = field(""), value = field("");
        return new VBox(
                10,
                new Label(I18n.t("meshcore.cliHint")),
                cli,
                execute,
                output,
                row("meshcore.statsGroup", stats),
                action(
                        "meshcore.statistics",
                        () ->
                                runtime.getStatistics(stats.getValue())
                                        .thenAccept(b ->
                                                Platform.runLater(() ->
                                                        output.setText(
                                                                HexFormat.ofDelimiter(" ")
                                                                        .formatHex(b))))),
                action(
                        "meshcore.customRead",
                        () ->
                                runtime.getCustomVariables()
                                        .thenAccept(b ->
                                                Platform.runLater(() ->
                                                        output.setText(
                                                                new String(
                                                                        b,
                                                                        1,
                                                                        b.length - 1,
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .UTF_8))))),
                new HBox(8, row("meshcore.variable", variable), row("meshcore.value", value)),
                action(
                        "meshcore.customWrite",
                        () -> runtime.setCustomVariable(variable.getText(), value.getText())));
    }

    private VBox advanced() {
        TextArea request = new TextArea("{\n  \"operation\": \"tuning.get\"\n}");
        request.setPrefRowCount(5);
        TextArea output = new TextArea();
        output.setEditable(false);
        output.setPrefRowCount(12);
        Button execute =
                action(
                        "meshcore.execute",
                        () -> {
                            var params =
                                    com.google.gson.JsonParser.parseString(request.getText())
                                            .getAsJsonObject();
                            String op = params.get("operation").getAsString();
                            if (op.startsWith("identity.") || op.equals("device.factoryReset")) {
                                if (confirmationDeclined("meshcore.identityConfirm"))
                                    return CompletableFuture.failedFuture(
                                            new java.util.concurrent.CancellationException());
                                params.addProperty("confirm", true);
                            }
                            return MeshCoreRpc.execute(runtime, params)
                                    .thenAccept(result ->
                                            Platform.runLater(() ->
                                                    output.setText(
                                                            new com.google.gson.GsonBuilder()
                                                                    .setPrettyPrinting()
                                                                    .create()
                                                                    .toJson(result))));
                        });
        return new VBox(
                10,
                new Label(I18n.t("meshcore.apiHint")),
                request,
                execute,
                output,
                action(
                        "meshcore.events",
                        () ->
                                CompletableFuture.runAsync(() -> {
                                    String events =
                                            runtime.recentEvents().stream()
                                                    .map(Object::toString)
                                                    .collect(
                                                            java.util.stream.Collectors.joining(
                                                                    "\n"));
                                    Platform.runLater(() -> output.setText(events));
                                })));
    }

    private boolean confirmationDeclined(String key) {
        return new Alert(
                                Alert.AlertType.CONFIRMATION,
                                I18n.t(key),
                                ButtonType.OK,
                                ButtonType.CANCEL)
                        .showAndWait()
                        .orElse(ButtonType.CANCEL)
                != ButtonType.OK;
    }

    private void updatePhase() {
        if (!closed)
            phase.setText(
                    "MeshCore · "
                            + switch (runtime.getState().getPhase()) {
                                case READY -> I18n.t("meshcore.phase.READY");
                                case HANDSHAKE -> I18n.t("meshcore.phase.HANDSHAKE");
                                case SYNCING -> I18n.t("meshcore.phase.SYNCING");
                                case DISCONNECTED -> I18n.t("meshcore.phase.DISCONNECTED");
                                case ERROR -> I18n.t("meshcore.phase.ERROR");
                            }
                            + " · "
                            + Objects.toString(runtime.getState().getDeviceName(), "")
                            + " · "
                            + Objects.toString(runtime.getState().getLastError(), ""));
    }

    private void refreshLists() {
        Integer channel =
                channels.getSelectionModel().getSelectedItem() == null
                        ? null
                        : channels.getSelectionModel().getSelectedItem().index();
        String contact =
                contacts.getSelectionModel().getSelectedItem() == null
                        ? null
                        : contacts.getSelectionModel().getSelectedItem().id();
        channels.getItems()
                .setAll(
                        runtime.getState().getMeshCoreChannels().values().stream()
                                .sorted(Comparator.comparingInt(MeshCoreChannel::index))
                                .toList());
        contacts.getItems().setAll(runtime.getState().getContacts().values());
        if (channel != null)
            channels.getItems().stream()
                    .filter(c -> c.index() == channel)
                    .findFirst()
                    .ifPresent(c -> channels.getSelectionModel().select(c));
        if (contact != null)
            contacts.getItems().stream()
                    .filter(c -> c.id().equals(contact))
                    .findFirst()
                    .ifPresent(c -> contacts.getSelectionModel().select(c));
    }

    @Override
    public void close() {
        closed = true;
        runtime.getState().removeListener(listener);
    }
}
