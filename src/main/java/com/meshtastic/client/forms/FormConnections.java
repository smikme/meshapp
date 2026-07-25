package com.meshtastic.client.forms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.menu.MyDrawerBuilder;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.model.RemoteRpcConnectionMode;
import com.meshtastic.client.model.SerialModemLineMode;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.SerialPortDiscoveryService;
import com.meshtastic.client.simple.SimpleConnectionForm;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@SystemForm(name = "Подключения", description = "Менеджер соединений", tags = {"connections", "options"})
public class FormConnections extends Form {

    private static final Duration REMOTE_RPC_TIMEOUT = Duration.ofSeconds(15);

    private VBox cardsBox;
    private final Map<String, ConnectionCard> cardsByConnectionId = new HashMap<>();
    private final Map<String, RemoteConnectionSnapshot> remoteConnectionSnapshots = new ConcurrentHashMap<>();
    private final Set<String> remoteRefreshInProgress = ConcurrentHashMap.newKeySet();
    private final Set<String> remoteActionInProgress = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean rebuildCardsQueued = new AtomicBoolean(false);
    private final Runnable changeListener = this::queueRebuildCards;

    private record ConnectionCard(VBox root,
                                  Label indicator,
                                  Label name,
                                  Label status,
                                  Label address,
                                  ToolBar actionToolbar,
                                  VBox remoteContent) {}

    public FormConnections() {
        init();
    }

    private void init() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(I18n.t("connection.form.name"));
        title.getStyleClass().add("form-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("connection-toolbar");
        actionToolbar.getItems().add(createToolbarButton(
                I18n.t("connection.action.add"),
                I18n.t("connection.action.add.tooltip"),
                "/icons/add.svg",
                this::showAddDialog));

        titleRow.getChildren().addAll(title, spacer, actionToolbar);

        cardsBox = new VBox(10);

        ScrollPane scrollPane = new ScrollPane(cardsBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        content.getChildren().addAll(titleRow, scrollPane);
        getChildren().add(content);
    }

    @Override
    public void formInit() {
        ConnectionManager.getInstance().addListener(changeListener);
        SerialPortDiscoveryService.getInstance().startScanning();
        rebuildCards();
    }

    @Override
    public void formRefresh() {
        rebuildCards();
    }

    private void rebuildCards() {
        List<ConnectionEntry> entries = ConnectionManager.getInstance().getEntries();
        Set<String> activeIds = entries.stream()
                .map(ConnectionEntry::getId)
                .collect(java.util.stream.Collectors.toSet());

        cardsByConnectionId.keySet().removeIf(id -> !activeIds.contains(id));
        cardsBox.getChildren().removeIf(node ->
                !(node.getUserData() instanceof String id) || !activeIds.contains(id));

        for (int index = 0; index < entries.size(); index++) {
            ConnectionEntry entry = entries.get(index);
            ConnectionCard card = cardsByConnectionId.computeIfAbsent(
                    entry.getId(),
                    ignored -> createConnectionCard(entry));
            updateConnectionCard(card, entry);

            int currentIndex = cardsBox.getChildren().indexOf(card.root());
            if (currentIndex < 0) {
                cardsBox.getChildren().add(index, card.root());
            } else if (currentIndex != index) {
                cardsBox.getChildren().remove(currentIndex);
                cardsBox.getChildren().add(index, card.root());
            }
        }
    }

    private void queueRebuildCards() {
        if (!rebuildCardsQueued.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            rebuildCardsQueued.set(false);
            rebuildCards();
        });
    }

    private ConnectionCard createConnectionCard(ConnectionEntry entry) {
        VBox card = new VBox(5);
        card.setUserData(entry.getId());
        card.setPadding(new Insets(15));
        card.getStyleClass().add("connection-card");

        HBox topRow = new HBox(10);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label indicator = new Label("\u25CF");

        Label lblName = new Label(entry.getName());
        lblName.getStyleClass().add("connection-card-name");

        Label lblStatus = new Label();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("connection-toolbar");

        topRow.getChildren().addAll(indicator, lblName, lblStatus, spacer, actionToolbar);

        Label lblAddress = new Label();
        lblAddress.setStyle("-fx-opacity: 0.6;");

        VBox remoteContent = new VBox();
        remoteContent.setVisible(false);
        remoteContent.setManaged(false);

        card.getChildren().addAll(topRow, lblAddress, remoteContent);
        return new ConnectionCard(
                card,
                indicator,
                lblName,
                lblStatus,
                lblAddress,
                actionToolbar,
                remoteContent);
    }

    private void updateConnectionCard(ConnectionCard card, ConnectionEntry entry) {
        boolean connected = entry.isConnected();
        boolean reconnecting = entry.isReconnecting();
        String stateColor = connected ? "#1EA97C" : reconnecting ? "#F59E0B" : "#9CA3AF";

        card.root().setStyle(connected || reconnecting
                ? "-fx-border-color: " + stateColor
                + "; -fx-border-width: 0 0 0 4; -fx-background-radius: 20; -fx-border-radius: 20;"
                : "");
        card.indicator().setStyle(
                "-fx-text-fill: " + stateColor + "; -fx-font-weight: bold;");
        card.name().setText(entry.getName());
        card.status().setText(connected
                ? I18n.t("connection.status.connected")
                : reconnecting ? I18n.t("connection.status.reconnecting") : "");
        card.status().setStyle(
                "-fx-text-fill: " + stateColor + "; -fx-font-weight: bold;");

        ProtocolType protocolType = ConnectionManager.getInstance()
                .getActiveProtocolType(entry.getId());
        card.address().setText(String.join(" · ",
                formatTransportAddress(entry),
                I18n.t("connection.card.protocol", formatProtocol(protocolType)),
                I18n.t("connection.card.autoconnect", I18n.t(entry.isAutoconnect()
                        ? "connection.state.on"
                        : "connection.state.off"))));
        updateConnectionActionToolbar(
                card.actionToolbar(),
                entry,
                connected,
                reconnecting);

        boolean showRemoteContent = entry.getEffectiveType() == ConnectionType.REMOTE_RPC
                && connected;
        card.remoteContent().setVisible(showRemoteContent);
        card.remoteContent().setManaged(showRemoteContent);
        if (showRemoteContent) {
            card.remoteContent().getChildren().setAll(createRemoteHostConnectionsSection(entry));
        } else {
            card.remoteContent().getChildren().clear();
            if (entry.getEffectiveType() == ConnectionType.REMOTE_RPC) {
                remoteConnectionSnapshots.remove(entry.getId());
            }
        }
    }

    private VBox createRemoteHostConnectionsSection(ConnectionEntry rpcEntry) {
        VBox section = new VBox(6);
        section.setPadding(new Insets(8, 0, 0, 22));

        HBox headerRow = new HBox(8);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(I18n.t("connection.remote.title"));
        title.getStyleClass().add("item-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button refreshButton = createToolbarButton(
                I18n.t("connection.remote.action.refresh"),
                I18n.t("connection.remote.action.refresh.tooltip"),
                "/icons/refresh.svg",
                () -> refreshRemoteConnections(rpcEntry, true));
        refreshButton.setDisable(remoteRefreshInProgress.contains(rpcEntry.getId()));
        headerRow.getChildren().addAll(title, spacer, refreshButton);

        section.getChildren().addAll(new Separator(), headerRow);

        RemoteRpcState state = remoteRpcState(rpcEntry);
        if (state == null) {
            Label waitingLabel = new Label(I18n.t("connection.remote.notReady"));
            waitingLabel.setStyle("-fx-opacity: 0.65;");
            section.getChildren().add(waitingLabel);
            return section;
        }

        RemoteConnectionSnapshot snapshot = remoteConnectionSnapshots.get(rpcEntry.getId());
        if (snapshot == null) {
            refreshRemoteConnections(rpcEntry, false);
            Label loadingLabel = new Label(I18n.t("connection.remote.loading"));
            loadingLabel.setStyle("-fx-opacity: 0.65;");
            section.getChildren().add(loadingLabel);
            return section;
        }
        if (snapshot.error() != null && !snapshot.error().isBlank()) {
            Label errorLabel = new Label(I18n.t("connection.remote.error", snapshot.error()));
            errorLabel.setWrapText(true);
            errorLabel.setStyle("-fx-text-fill: #B91C1C;");
            section.getChildren().add(errorLabel);
            return section;
        }
        if (snapshot.items().isEmpty()) {
            Label emptyLabel = new Label(I18n.t("connection.remote.empty"));
            emptyLabel.setStyle("-fx-opacity: 0.65;");
            section.getChildren().add(emptyLabel);
            return section;
        }

        for (RemoteConnectionItem item : snapshot.items()) {
            section.getChildren().add(createRemoteConnectionRow(rpcEntry, item));
        }
        return section;
    }

    private HBox createRemoteConnectionRow(ConnectionEntry rpcEntry, RemoteConnectionItem item) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        String indicatorColor = item.connected()
                ? "#1EA97C"
                : item.reconnecting() ? "#F59E0B" : "#9CA3AF";
        Label indicator = new Label("\u25CF");
        indicator.setStyle("-fx-text-fill: " + indicatorColor + "; -fx-font-weight: bold;");

        VBox textBox = new VBox(2);
        Label name = new Label(item.name());
        name.getStyleClass().add("connection-card-name");
        String details = String.join(" · ",
                item.type() + " " + item.address(),
                I18n.t("connection.card.protocol", item.protocol()),
                item.selected() ? I18n.t("connection.remote.selected") : "");
        Label detail = new Label(details.replaceAll("( · )+$", ""));
        detail.setStyle("-fx-opacity: 0.6;");
        textBox.getChildren().addAll(name, detail);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        ToolBar toolbar = createRemoteConnectionActionToolbar(rpcEntry, item);
        row.getChildren().addAll(indicator, textBox, toolbar);
        return row;
    }

    private ToolBar createRemoteConnectionActionToolbar(ConnectionEntry rpcEntry, RemoteConnectionItem item) {
        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("connection-toolbar");

        boolean busy = isRemoteActionBusy(rpcEntry.getId(), item.id());
        Button connectButton = createToolbarButton(
                item.connected() || item.reconnecting()
                        ? I18n.t("connection.action.disconnect")
                        : I18n.t("connection.action.connect"),
                item.connected() || item.reconnecting()
                        ? I18n.t("connection.remote.action.disconnect.tooltip")
                        : I18n.t("connection.remote.action.connect.tooltip"),
                item.connected() || item.reconnecting() ? "/icons/disconnect.svg" : "/icons/connect.svg",
                () -> doRemoteConnectionAction(
                        rpcEntry,
                        item,
                        item.connected() || item.reconnecting()
                                ? "connection.disconnect"
                                : "connection.connect"));
        connectButton.setDisable(busy);

        Button selectButton = createToolbarButton(
                I18n.t("connection.remote.action.select"),
                I18n.t("connection.remote.action.select.tooltip"),
                "/icons/eye.svg",
                () -> doRemoteConnectionAction(rpcEntry, item, "connection.select"));
        selectButton.setDisable(busy || item.selected() || (!item.connected() && !item.reconnecting()));

        actionToolbar.getItems().addAll(connectButton, new Separator(Orientation.VERTICAL), selectButton);
        return actionToolbar;
    }

    private void updateConnectionActionToolbar(ToolBar actionToolbar,
                                               ConnectionEntry entry,
                                               boolean connected,
                                               boolean reconnecting) {
        Button connectButton = createToolbarButton(
                connected || reconnecting ? I18n.t("connection.action.disconnect") : I18n.t("connection.action.connect"),
                connected || reconnecting
                        ? I18n.t("connection.action.disconnect.tooltip")
                        : I18n.t("connection.action.connect.tooltip"),
                connected || reconnecting ? "/icons/disconnect.svg" : "/icons/connect.svg",
                () -> {
                    if (entry.isConnected() || entry.isReconnecting()) {
                        doDisconnect(entry);
                    } else {
                        doConnect(entry);
                    }
                });

        Button editButton = createToolbarButton(
                I18n.t("common.edit"),
                I18n.t("connection.action.edit.tooltip"),
                "/drawer/icon/setting.svg",
                () -> showEditDialog(entry));
        editButton.setDisable(connected || reconnecting);

        Button deleteButton = createToolbarButton(
                I18n.t("common.delete"),
                I18n.t("connection.action.delete.tooltip"),
                "/drawer/icon/delete-node.svg",
                () -> doDelete(entry));

        actionToolbar.getItems().setAll(
                connectButton,
                new Separator(Orientation.VERTICAL),
                editButton,
                deleteButton
        );
    }

    private Button createToolbarButton(String title, String description, String iconPath, Runnable action) {
        Button button = new Button();
        button.getStyleClass().add("connection-toolbar-button");
        button.setMinSize(34, 34);
        button.setPrefSize(34, 34);
        button.setMaxSize(34, 34);
        button.setFocusTraversable(false);
        button.setAccessibleText(title);
        setToolbarButtonGraphic(button, iconPath, title);
        button.setTooltip(new Tooltip(title + "\n" + description));
        button.setOnAction(event -> action.run());
        return button;
    }

    private void setToolbarButtonGraphic(Button button, String iconPath, String fallbackText) {
        SVGPath icon = SvgIconLoader.load(iconPath, 18);
        if (icon != null) {
            button.setGraphic(icon);
            button.setText(null);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            button.setGraphic(null);
            button.setText(fallbackText);
            button.setContentDisplay(ContentDisplay.TEXT_ONLY);
        }
    }

    private void doConnect(ConnectionEntry entry) {
        Thread worker = new Thread(() -> {
            try {
                ConnectionManager mgr = ConnectionManager.getInstance();
                mgr.connect(entry.getId());
                mgr.setSelectedConnectionId(entry.getId());
                Platform.runLater(() ->
                        Toast.show(Toast.Type.SUCCESS, I18n.t("connection.toast.connected", entry.getName())));

                CompletableFuture<DeviceState> future = mgr.getConfigFuture(entry.getId());
                if (future != null) {
                    try {
                        DeviceState state = future.get(30, TimeUnit.SECONDS);
                        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
                        if (myNode != null) {
                            String shortName = myNode.getShortName() != null ? myNode.getShortName() : "?";
                            String longName = myNode.getLongName() != null ? myNode.getLongName() : "?";
                            String nodeId = myNode.getNodeId() != null ? myNode.getNodeId() : "?";
                            Platform.runLater(() ->
                                MyDrawerBuilder.updateHeader(shortName, longName, nodeId)
                            );
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (ConnectionException ex) {
                Platform.runLater(() ->
                        Toast.show(Toast.Type.ERROR, I18n.t("connection.toast.error", ex.getMessage())));
            }
        }, "connect-" + entry.getId());
        worker.setDaemon(true);
        worker.start();
    }

    private void doDisconnect(ConnectionEntry entry) {
        Thread worker = new Thread(() -> {
            try {
                ConnectionManager.getInstance().disconnect(entry.getId());
                Platform.runLater(() -> {
                    MyDrawerBuilder.updateHeader("?", "?", "?");
                    Toast.show(Toast.Type.SUCCESS, I18n.t("connection.toast.disconnected", entry.getName()));
                });
            } catch (RuntimeException ex) {
                Platform.runLater(() ->
                        Toast.show(Toast.Type.ERROR, I18n.t("connection.toast.disconnectError", ex.getMessage())));
            }
        }, "disconnect-" + entry.getId());
        worker.setDaemon(true);
        worker.start();
    }

    private void refreshRemoteConnections(ConnectionEntry rpcEntry, boolean force) {
        RemoteRpcState state = remoteRpcState(rpcEntry);
        if (state == null) {
            return;
        }
        String rpcId = rpcEntry.getId();
        if (!force && remoteConnectionSnapshots.containsKey(rpcId)) {
            return;
        }
        if (!remoteRefreshInProgress.add(rpcId)) {
            return;
        }

        state.client()
                .call("connection.list", new JsonObject(), REMOTE_RPC_TIMEOUT)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        remoteConnectionSnapshots.put(
                                rpcId,
                                new RemoteConnectionSnapshot(List.of(), errorMessage(error)));
                    } else {
                        remoteConnectionSnapshots.put(rpcId, remoteSnapshot(result));
                    }
                    remoteRefreshInProgress.remove(rpcId);
                    Platform.runLater(this::rebuildCards);
                });
    }

    private void doRemoteConnectionAction(ConnectionEntry rpcEntry,
                                          RemoteConnectionItem item,
                                          String method) {
        RemoteRpcState state = remoteRpcState(rpcEntry);
        if (state == null) {
            Toast.show(Toast.Type.ERROR, I18n.t("connection.remote.notReady"));
            return;
        }
        String actionKey = remoteActionKey(rpcEntry.getId(), item.id());
        if (!remoteActionInProgress.add(actionKey)) {
            return;
        }

        JsonObject params = new JsonObject();
        params.addProperty("id", item.id());
        state.client()
                .call(method, params, REMOTE_RPC_TIMEOUT)
                .whenComplete((result, error) -> {
                    if (error == null) {
                        remoteConnectionSnapshots.put(rpcEntry.getId(), remoteSnapshot(result));
                    }
                    remoteActionInProgress.remove(actionKey);
                    Platform.runLater(() -> {
                        if (error != null) {
                            Toast.show(Toast.Type.ERROR,
                                    I18n.t("connection.remote.toast.actionError", errorMessage(error)));
                        } else {
                            Toast.show(Toast.Type.SUCCESS,
                                    I18n.t("connection.remote.toast.actionSent", item.name()));
                        }
                        rebuildCards();
                    });
                });
    }

    private RemoteRpcState remoteRpcState(ConnectionEntry entry) {
        ProtocolRuntime<?> runtime = ConnectionManager.getInstance().getProtocolRuntime(entry.getId());
        return runtime != null && runtime.getState() instanceof RemoteRpcState state ? state : null;
    }

    private boolean isRemoteActionBusy(String rpcId, String remoteId) {
        return remoteActionInProgress.contains(remoteActionKey(rpcId, remoteId));
    }

    private static String remoteActionKey(String rpcId, String remoteId) {
        return rpcId + ":" + remoteId;
    }

    private static RemoteConnectionSnapshot remoteSnapshot(JsonElement result) {
        JsonObject object = result != null && result.isJsonObject()
                ? result.getAsJsonObject()
                : new JsonObject();
        JsonArray array = object.has("items") && object.get("items").isJsonArray()
                ? object.getAsJsonArray("items")
                : new JsonArray();
        List<RemoteConnectionItem> items = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String id = stringField(item, "id");
            if (id.isBlank()) {
                continue;
            }
            items.add(new RemoteConnectionItem(
                    id,
                    firstText(stringField(item, "name"), id),
                    stringField(item, "type"),
                    stringField(item, "protocol"),
                    stringField(item, "address"),
                    booleanField(item, "connected"),
                    booleanField(item, "reconnecting"),
                    booleanField(item, "selected"),
                    stringField(item, "nodeId")
            ));
        }
        return new RemoteConnectionSnapshot(List.copyOf(items), null);
    }

    private static String stringField(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString();
    }

    private static boolean booleanField(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String errorMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }

    private void doDelete(ConnectionEntry entry) {
        ModalPane.showConfirm(
                I18n.t("connection.confirm.delete.title"),
                I18n.t("connection.confirm.delete.message", entry.getName()),
                confirmed -> {
                    if (confirmed) {
                        boolean resetHeader = entry.isConnected() || entry.isReconnecting();
                        Thread worker = new Thread(() -> {
                            try {
                                ConnectionManager.getInstance().removeEntry(entry.getId());
                                Platform.runLater(() -> {
                                    if (resetHeader) {
                                        MyDrawerBuilder.updateHeader("?", "?", "?");
                                    }
                                    Toast.show(Toast.Type.SUCCESS, I18n.t("connection.toast.deleted", entry.getName()));
                                });
                            } catch (RuntimeException ex) {
                                Platform.runLater(() ->
                                        Toast.show(Toast.Type.ERROR, I18n.t("connection.toast.deleteError", ex.getMessage())));
                            }
                        }, "delete-connection-" + entry.getId());
                        worker.setDaemon(true);
                        worker.start();
                    }
                });
    }

    private void showAddDialog() {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) { return; }

        SimpleConnectionForm form = new SimpleConnectionForm();
        form.setOnSave(entry -> {
            form.cleanup();
            modalPane.hide();
            ConnectionManager.getInstance().addEntry(entry);
            Toast.show(Toast.Type.SUCCESS, I18n.t("connection.toast.added", entry.getName()));
        });

        modalPane.show(form);
        form.formOpen();
    }

    private void showEditDialog(ConnectionEntry entry) {
        if (entry.isConnected() || entry.isReconnecting()) {
            Toast.show(Toast.Type.WARNING, I18n.t("connection.toast.disconnectBeforeEdit"));
            return;
        }

        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) { return; }

        SimpleConnectionForm form = new SimpleConnectionForm(entry);
        form.setOnSave(updated -> {
            try {
                ConnectionManager.getInstance().updateEntry(updated);
                form.cleanup();
                modalPane.hide();
                Toast.show(Toast.Type.SUCCESS, I18n.t("connection.toast.saved", updated.getName()));
            } catch (RuntimeException ex) {
                Toast.show(Toast.Type.ERROR, I18n.t("connection.toast.saveError", ex.getMessage()));
            }
        });

        modalPane.show(form);
        form.formOpen();
    }

    private record RemoteConnectionSnapshot(List<RemoteConnectionItem> items, String error) {
    }

    private record RemoteConnectionItem(String id,
                                        String name,
                                        String type,
                                        String protocol,
                                        String address,
                                        boolean connected,
                                        boolean reconnecting,
                                        boolean selected,
                                        String nodeId) {
    }

    private static String formatTransportAddress(ConnectionEntry entry) {
        return switch (entry.getEffectiveType()) {
            case BLE -> I18n.t("connection.card.address.ble",
                    entry.getBleDeviceName() != null ? entry.getBleDeviceName() : "",
                    entry.getBleAddress());
            case SERIAL -> I18n.t("connection.card.address.serial",
                    entry.getPortName(),
                    entry.getBaudRate(),
                    formatSerialModemLineMode(entry.getEffectiveSerialModemLineMode()));
            case TCP -> I18n.t("connection.card.address.tcp", entry.getHost(), entry.getPort());
            case REMOTE_RPC -> entry.getEffectiveRemoteRpcMode() == RemoteRpcConnectionMode.ROUTER
                    ? I18n.t("connection.card.address.remoteRpcRouter",
                            ConnectionEntry.CLOUD_RPC_ROUTER_DISPLAY_HOST,
                            ConnectionEntry.CLOUD_RPC_ROUTER_PORT)
                    : I18n.t("connection.card.address.remoteRpc", entry.getHost(), entry.getPort());
        };
    }

    private static String formatProtocol(ProtocolType protocolType) {
        if (protocolType == null) {
            return "?";
        }
        return switch (protocolType) {
            case MESHTASTIC -> I18n.t("connection.protocol.meshtastic");
            case MESHCORE_KISS -> I18n.t("connection.protocol.meshcoreKiss");
            case MESHCORE_COMPANION -> I18n.t("connection.protocol.meshcoreCompanion");
            case REMOTE_RPC -> I18n.t("connection.protocol.remoteRpc");
        };
    }

    private static String formatSerialModemLineMode(SerialModemLineMode mode) {
        if (mode == null) {
            return I18n.t("connection.serialLine.auto");
        }
        return switch (mode) {
            case AUTO -> I18n.t("connection.serialLine.auto");
            case DTR_OFF_RTS_OFF -> I18n.t("connection.serialLine.dtrOffRtsOff");
            case DTR_OFF_RTS_ON -> I18n.t("connection.serialLine.dtrOffRtsOn");
            case DTR_ON_RTS_OFF -> I18n.t("connection.serialLine.dtrOnRtsOff");
            case DTR_ON_RTS_ON -> I18n.t("connection.serialLine.dtrOnRtsOn");
        };
    }
}
