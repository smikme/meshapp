package com.meshtastic.client.forms;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.menu.MyDrawerBuilder;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.SerialPortDiscoveryService;
import com.meshtastic.client.simple.SimpleConnectionForm;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
        title.setFont(Font.font("Roboto", FontWeight.BOLD, 16));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnAdd = new Button("Добавить");
        btnAdd.setOnAction(e -> showAddDialog());

        titleRow.getChildren().addAll(title, spacer, btnAdd);

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
        lblName.setFont(Font.font("Roboto", FontWeight.BOLD, 14));

        String statusText = connected ? "\u2713 Подключено" : reconnecting ? "\u21BB Переподключение..." : "";
        String statusColor = connected ? "#1EA97C" : "#F59E0B";
        Label lblStatus = new Label(statusText);
        lblStatus.setStyle("-fx-text-fill: " + statusColor + "; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnConnect = new Button(connected || reconnecting ? "Отключить" : "Подключить");
        btnConnect.setOnAction(e -> {
            if (entry.isConnected() || entry.isReconnecting()) {
                doDisconnect(entry);
            } else {
                doConnect(entry);
            }
        });

        Button btnDelete = new Button("Удалить");
        btnDelete.setOnAction(e -> doDelete(entry));

        topRow.getChildren().addAll(indicator, lblName, lblStatus, spacer, btnConnect, btnDelete);

        String addressText;
        if (entry.getEffectiveType() == ConnectionType.BLE) {
            String devName = entry.getBleDeviceName() != null ? entry.getBleDeviceName() : "";
            addressText = "BLE: " + devName + " (" + entry.getBleAddress() + ")";
        } else if (entry.getEffectiveType() == ConnectionType.SERIAL) {
            addressText = "Serial: " + entry.getPortName() + " (" + entry.getBaudRate() + " бод)";
        } else {
            addressText = "TCP: " + entry.getHost() + ":" + entry.getPort();
        }
        Label lblAddress = new Label(addressText);
        lblAddress.setStyle("-fx-opacity: 0.6;");

        card.getChildren().addAll(topRow, lblAddress);
        return card;
    }

    private void doConnect(ConnectionEntry entry) {
        new Thread(() -> {
            try {
                ConnectionManager mgr = ConnectionManager.getInstance();
                mgr.connect(entry.getId());
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
        ConnectionManager.getInstance().disconnect(entry.getId());
        MyDrawerBuilder.updateHeader("?", "?", "?");
        Toast.show(Toast.Type.SUCCESS, "Отключено: " + entry.getName());
    }

    private void doDelete(ConnectionEntry entry) {
        ModalPane.showConfirm(
                "Подтверждение",
                "Удалить подключение \"" + entry.getName() + "\"?",
                confirmed -> {
                    if (confirmed) {
                        ConnectionManager.getInstance().removeEntry(entry.getId());
                        Toast.show(Toast.Type.SUCCESS, "Удалено: " + entry.getName());
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
}
