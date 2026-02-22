package com.meshtastic.client.components;

import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.utils.NodeUtils;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Универсальный компонент детальной информации о ноде.
 * Показывается в ModalPane (выезжает справа) с кнопкой «Назад».
 * Содержит: заголовок (аватар + имя), таблицу параметров, график телеметрии.
 * <p>
 * Использование: {@code NodeDetailPanel.showForNode(state, node);}
 */
public class NodeDetailPanel extends VBox {

    private static final double PANEL_WIDTH = 420;

    private final TelemetryChartPanel chartPanel;

    public NodeDetailPanel(DeviceState state, NodeData node) {
        setSpacing(10);
        setPadding(new Insets(15, 20, 15, 20));
        setPrefWidth(PANEL_WIDTH);
        setMaxWidth(PANEL_WIDTH);
        setMaxHeight(Double.MAX_VALUE);
        getStyleClass().add("modal-side-panel");

        // === Кнопка «Назад» ===
        Button backBtn = new Button("\u2190 Назад");
        backBtn.getStyleClass().add("node-detail-back-btn");
        backBtn.setOnAction(e -> close());

        HBox backRow = new HBox(backBtn);
        backRow.setAlignment(Pos.CENTER_LEFT);

        // === Заголовок: большой аватар + имя + nodeId ===
        String displayName = node.getLongName() != null && !node.getLongName().isEmpty()
                ? node.getLongName() : (node.getNodeId() != null ? node.getNodeId() : "?");

        String avatarText;
        if (node.getShortName() != null && !node.getShortName().isEmpty()) {
            avatarText = node.getShortName().toUpperCase();
        } else {
            avatarText = displayName.length() > 4
                    ? displayName.substring(0, 4).toUpperCase()
                    : displayName.toUpperCase();
        }
        String color = NodeUtils.roleColor(node.getRole());

        StackPane bigAvatar = new StackPane();
        bigAvatar.setMinSize(56, 56);
        bigAvatar.setMaxSize(56, 56);
        bigAvatar.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 28;");
        Label avatarLabel = new Label(avatarText);
        avatarLabel.setFont(Font.font("Roboto", FontWeight.BOLD,
                NodeUtils.avatarFontSize(avatarText.length(), 56)));
        avatarLabel.setStyle("-fx-text-fill: white;");
        bigAvatar.getChildren().add(avatarLabel);

        Label nameLabel = new Label(displayName);
        nameLabel.setFont(Font.font("Roboto", FontWeight.BOLD, 18));

        Label nodeIdLabel = new Label(node.getNodeId() != null ? node.getNodeId() : "");
        nodeIdLabel.setStyle("-fx-opacity: 0.6;");

        VBox headerText = new VBox(2, nameLabel, nodeIdLabel);
        headerText.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(12, bigAvatar, headerText);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 10, 0));

        Separator sep = new Separator();

        // === Таблица деталей (13 строк) ===
        ObservableList<String[]> tableData = FXCollections.observableArrayList();
        NodeUtils.fillDetailRows(tableData, node);
        TableView<String[]> table = NodeUtils.createDetailTable(tableData);

        // === График телеметрии ===
        chartPanel = new TelemetryChartPanel();
        VBox.setVgrow(chartPanel, Priority.ALWAYS);
        if (state != null) {
            chartPanel.bind(state, node.getNodeNum());
        }

        // === Скроллируемый контент (всё кроме кнопки «Назад») ===
        VBox content = new VBox(10, header, sep, table, chartPanel);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(backRow, scrollPane);
    }

    /** Закрыть панель: отвязать телеметрию и скрыть модалку */
    public void close() {
        chartPanel.unbind();
        ModalPane modal = ModalPane.getInstance();
        if (modal != null) {
            modal.hide();
        }
    }

    /**
     * Показать детальную информацию о ноде в ModalPane.
     * Универсальный метод — можно вызывать из любого места приложения.
     */
    public static void showForNode(DeviceState state, NodeData node) {
        if (node == null) return;
        ModalPane modal = ModalPane.getInstance();
        if (modal == null) return;

        NodeDetailPanel panel = new NodeDetailPanel(state, node);
        modal.setOnHidden(panel.chartPanel::unbind);
        modal.show(panel);
    }
}
