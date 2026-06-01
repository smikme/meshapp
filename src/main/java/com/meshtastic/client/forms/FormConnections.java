package com.meshtastic.client.forms;

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
import com.meshtastic.client.model.SerialModemLineMode;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@SystemForm(name = "Подключения", description = "Менеджер соединений", tags = {"connections", "options"})
public class FormConnections extends Form {

    private VBox cardsBox;
    private final Runnable changeListener = () -> Platform.runLater(this::rebuildCards);

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
        cardsBox.getChildren().clear();
        for (ConnectionEntry entry : ConnectionManager.getInstance().getEntries()) {
            cardsBox.getChildren().add(createConnectionCard(entry));
        }
    }

    private VBox createConnectionCard(ConnectionEntry entry) {
        boolean connected = entry.isConnected();
        boolean reconnecting = entry.isReconnecting();

        VBox card = new VBox(5);
        card.setPadding(new Insets(15));
        card.getStyleClass().add("connection-card");
        if (connected) {
            card.setStyle("-fx-border-color: #1EA97C; -fx-border-width: 0 0 0 4; -fx-background-radius: 20; -fx-border-radius: 20;");
        } else if (reconnecting) {
            card.setStyle("-fx-border-color: #F59E0B; -fx-border-width: 0 0 0 4; -fx-background-radius: 20; -fx-border-radius: 20;");
        }

        HBox topRow = new HBox(10);
        topRow.setAlignment(Pos.CENTER_LEFT);

        String indicatorColor = connected ? "#1EA97C" : reconnecting ? "#F59E0B" : "#9CA3AF";
        Label indicator = new Label("\u25CF");
        indicator.setStyle("-fx-text-fill: " + indicatorColor + "; -fx-font-weight: bold;");

        Label lblName = new Label(entry.getName());
        lblName.getStyleClass().add("connection-card-name");

        String statusText = connected
                ? I18n.t("connection.status.connected")
                : reconnecting ? I18n.t("connection.status.reconnecting") : "";
        String statusColor = connected ? "#1EA97C" : "#F59E0B";
        Label lblStatus = new Label(statusText);
        lblStatus.setStyle("-fx-text-fill: " + statusColor + "; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar actionToolbar = createConnectionActionToolbar(entry, connected, reconnecting);

        topRow.getChildren().addAll(indicator, lblName, lblStatus, spacer, actionToolbar);

        ProtocolType protocolType = ConnectionManager.getInstance().getActiveProtocolType(entry.getId());
        String addressText = String.join(" · ",
                formatTransportAddress(entry),
                I18n.t("connection.card.protocol", formatProtocol(protocolType)),
                I18n.t("connection.card.autoconnect", I18n.t(entry.isAutoconnect()
                        ? "connection.state.on"
                        : "connection.state.off")));

        Label lblAddress = new Label(addressText);
        lblAddress.setStyle("-fx-opacity: 0.6;");

        card.getChildren().addAll(topRow, lblAddress);
        return card;
    }

    private ToolBar createConnectionActionToolbar(ConnectionEntry entry, boolean connected, boolean reconnecting) {
        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("connection-toolbar");

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

        actionToolbar.getItems().addAll(
                connectButton,
                new Separator(Orientation.VERTICAL),
                editButton,
                deleteButton
        );
        return actionToolbar;
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
        new Thread(() -> {
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
        }, "connect-" + entry.getId()).start();
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
