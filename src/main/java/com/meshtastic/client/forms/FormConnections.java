package com.meshtastic.client.forms;

import com.meshtastic.client.connection.ConnectionException;
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

        Label title = new Label("Подключения");
        title.getStyleClass().add("form-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("connection-toolbar");
        actionToolbar.getItems().add(createToolbarButton(
                "Добавить",
                "Создать новое подключение",
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

        String statusText = connected ? "\u2713 Подключено" : reconnecting ? "\u21BB Переподключение..." : "";
        String statusColor = connected ? "#1EA97C" : "#F59E0B";
        Label lblStatus = new Label(statusText);
        lblStatus.setStyle("-fx-text-fill: " + statusColor + "; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar actionToolbar = createConnectionActionToolbar(entry, connected, reconnecting);

        topRow.getChildren().addAll(indicator, lblName, lblStatus, spacer, actionToolbar);

        String addressText;
        if (entry.getEffectiveType() == ConnectionType.BLE) {
            String devName = entry.getBleDeviceName() != null ? entry.getBleDeviceName() : "";
            addressText = "BLE: " + devName + " (" + entry.getBleAddress() + ")";
        } else if (entry.getEffectiveType() == ConnectionType.SERIAL) {
            addressText = "Serial: " + entry.getPortName() + " (" + entry.getBaudRate() + " бод"
                    + ", " + formatSerialModemLineMode(entry.getEffectiveSerialModemLineMode()) + ")";
        } else {
            addressText = "TCP: " + entry.getHost() + ":" + entry.getPort();
        }
        ProtocolType protocolType = ConnectionManager.getInstance().getActiveProtocolType(entry.getId());
        addressText += " · Протокол: " + formatProtocol(protocolType);
        addressText += " · Автоподключение: " + (entry.isAutoconnect() ? "вкл" : "выкл");

        Label lblAddress = new Label(addressText);
        lblAddress.setStyle("-fx-opacity: 0.6;");

        card.getChildren().addAll(topRow, lblAddress);
        return card;
    }

    private ToolBar createConnectionActionToolbar(ConnectionEntry entry, boolean connected, boolean reconnecting) {
        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("connection-toolbar");

        Button connectButton = createToolbarButton(
                connected || reconnecting ? "Отключить" : "Подключить",
                connected || reconnecting ? "Отключить текущее соединение" : "Подключиться к устройству",
                connected || reconnecting ? "/icons/disconnect.svg" : "/icons/connect.svg",
                () -> {
                    if (entry.isConnected() || entry.isReconnecting()) {
                        doDisconnect(entry);
                    } else {
                        doConnect(entry);
                    }
                });

        Button editButton = createToolbarButton(
                "Изменить",
                "Изменить параметры подключения",
                "/drawer/icon/setting.svg",
                () -> showEditDialog(entry));
        editButton.setDisable(connected || reconnecting);

        Button deleteButton = createToolbarButton(
                "Удалить",
                "Удалить подключение",
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
                        Toast.show(Toast.Type.SUCCESS, "Подключено: " + entry.getName()));

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
                        Toast.show(Toast.Type.ERROR, "Ошибка: " + ex.getMessage()));
            }
        }, "connect-" + entry.getId()).start();
    }

    private void doDisconnect(ConnectionEntry entry) {
        Thread worker = new Thread(() -> {
            try {
                ConnectionManager.getInstance().disconnect(entry.getId());
                Platform.runLater(() -> {
                    MyDrawerBuilder.updateHeader("?", "?", "?");
                    Toast.show(Toast.Type.SUCCESS, "Отключено: " + entry.getName());
                });
            } catch (RuntimeException ex) {
                Platform.runLater(() ->
                        Toast.show(Toast.Type.ERROR, "Ошибка отключения: " + ex.getMessage()));
            }
        }, "disconnect-" + entry.getId());
        worker.setDaemon(true);
        worker.start();
    }

    private void doDelete(ConnectionEntry entry) {
        ModalPane.showConfirm(
                "Подтверждение",
                "Удалить подключение \"" + entry.getName() + "\"?",
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
                                    Toast.show(Toast.Type.SUCCESS, "Удалено: " + entry.getName());
                                });
                            } catch (RuntimeException ex) {
                                Platform.runLater(() ->
                                        Toast.show(Toast.Type.ERROR, "Ошибка удаления: " + ex.getMessage()));
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
            Toast.show(Toast.Type.SUCCESS, "Добавлено: " + entry.getName());
        });

        modalPane.show(form);
        form.formOpen();
    }

    private void showEditDialog(ConnectionEntry entry) {
        if (entry.isConnected() || entry.isReconnecting()) {
            Toast.show(Toast.Type.WARNING, "Отключите подключение перед изменением параметров");
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
                Toast.show(Toast.Type.SUCCESS, "Сохранено: " + updated.getName());
            } catch (RuntimeException ex) {
                Toast.show(Toast.Type.ERROR, "Ошибка сохранения: " + ex.getMessage());
            }
        });

        modalPane.show(form);
        form.formOpen();
    }

    private static String formatProtocol(ProtocolType protocolType) {
        if (protocolType == null) {
            return "?";
        }
        return switch (protocolType) {
            case MESHTASTIC -> "Meshtastic";
            case MESHCORE_KISS -> "MeshCore KISS";
            case MESHCORE_COMPANION -> "MeshCore Companion";
        };
    }

    private static String formatSerialModemLineMode(SerialModemLineMode mode) {
        if (mode == null) {
            return "Auto";
        }
        return switch (mode) {
            case AUTO -> "Auto";
            case DTR_OFF_RTS_OFF -> "DTR off, RTS off";
            case DTR_OFF_RTS_ON -> "DTR off, RTS on";
            case DTR_ON_RTS_OFF -> "DTR on, RTS off";
            case DTR_ON_RTS_ON -> "DTR on, RTS on";
        };
    }
}
