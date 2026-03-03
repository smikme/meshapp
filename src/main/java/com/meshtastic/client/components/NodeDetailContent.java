package com.meshtastic.client.components;

import com.meshtastic.client.forms.FormChat;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.AllForms;
import com.meshtastic.client.system.FormManager;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.SvgIconLoader;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Единый компонент детальной информации о ноде.
 * Содержит: вертикальный тулбар слева (действия) + контент справа (заголовок, таблица, график).
 * <p>
 * Используется в двух контекстах:
 * <ul>
 *   <li>{@link NodeDetailPanel} — модальная панель (выезжает справа, с кнопкой «Назад»)</li>
 *   <li>{@code FormNodes} — встроенная панель в SplitPane (без кнопки «Назад»)</li>
 * </ul>
 */
public class NodeDetailContent extends HBox {

    private final TelemetryChartPanel chartPanel;
    private final ObservableList<String[]> tableData;
    private final int nodeNum;     // для протокольных операций (requestNodeInfo, removeNode)
    private final String nodeId;   // для идентификации (openDirectChat, deleteNode)
    private final ProtocolHandler protocolHandler;
    private final DeviceState state;

    /**
     * @param state             состояние устройства (для телеметрии), может быть {@code null}
     * @param node              нода для отображения
     * @param handler           протокол-обработчик для отправки радио-запросов, может быть {@code null}
     * @param onBeforeNavigate  вызывается перед навигацией в приватный чат (напр. закрыть модалку),
     *                          может быть {@code null}
     */
    public NodeDetailContent(DeviceState state, NodeData node, ProtocolHandler handler, Runnable onBeforeNavigate) {
        this.nodeNum = node.getNodeNum();
        this.nodeId = node.getNodeId();
        this.protocolHandler = handler;
        this.state = state;

        String displayName = node.getLongName() != null && !node.getLongName().isEmpty()
                ? node.getLongName() : node.getNodeId() != null ? node.getNodeId() : "?";

        // === Вертикальный тулбар слева (56px, структура как DrawerPane) ===
        StackPane toolbarPane = new StackPane();
        toolbarPane.setPrefWidth(56);
        toolbarPane.setMinWidth(56);
        toolbarPane.setMaxWidth(56);

        ToolBar actionToolbar = new ToolBar();
        actionToolbar.setOrientation(Orientation.VERTICAL);
        actionToolbar.getStyleClass().add("drawer-toolbar");

        SVGPath privateChatIcon = SvgIconLoader.load("/drawer/icon/chat-private.svg", 22);
        Button privateChatBtn = new Button();
        privateChatBtn.setGraphic(privateChatIcon);
        privateChatBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        privateChatBtn.getStyleClass().add("drawer-toolbar-button");
        privateChatBtn.setTooltip(new Tooltip("Приватный чат"));

        boolean hasPublicKey = node.getPublicKey() != null && node.getPublicKey().length > 0;
        privateChatBtn.setDisable(!hasPublicKey);

        privateChatBtn.setOnAction(e -> {
            if (onBeforeNavigate != null) {
                onBeforeNavigate.run();
            }
            FormChat formChat = (FormChat) AllForms.getForm(FormChat.class);
            FormManager.showForm(formChat);
            formChat.openDirectChat(node.getNodeId(), node);
        });

        // Кнопка «Обновить ноду» — запрос информации по радио
        SVGPath refreshIcon = SvgIconLoader.load("/drawer/icon/refresh-node.svg", 22);
        Button refreshBtn = new Button();
        refreshBtn.setGraphic(refreshIcon);
        refreshBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        refreshBtn.getStyleClass().add("drawer-toolbar-button");
        refreshBtn.setTooltip(new Tooltip("Обновить информацию о ноде"));
        refreshBtn.setDisable(handler == null || state == null);
        refreshBtn.setOnAction(e -> {
            if (protocolHandler != null && this.state != null) {
                ModalPane.showConfirm(
                        "Обновить информацию?",
                        "Запросить обновление информации о ноде \"" + displayName + "\" по радио?",
                        confirmed -> {
                            if (confirmed) {
                                MessageService.requestNodeInfo(protocolHandler, this.state, nodeNum);
                            }
                        }
                );
            }
        });

        // Кнопка «Удалить ноду»
        SVGPath deleteIcon = SvgIconLoader.load("/drawer/icon/delete-node.svg", 22);
        Button deleteBtn = new Button();
        deleteBtn.setGraphic(deleteIcon);
        deleteBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        deleteBtn.getStyleClass().add("drawer-toolbar-button");
        deleteBtn.setTooltip(new Tooltip("Удалить ноду"));
        deleteBtn.setDisable(state == null);
        deleteBtn.setOnAction(e -> {
            if (this.state != null) {
                ModalPane.showConfirm(
                        "Удалить ноду?",
                        "Удалить ноду \"" + displayName + "\" из списка? Данные телеметрии также будут удалены.",
                        confirmed -> {
                            if (confirmed) {
                                this.state.removeNode(nodeNum);
                                NodeCacheService.getInstance().deleteNode(nodeId);
                                if (onBeforeNavigate != null) {
                                    onBeforeNavigate.run();
                                }
                            }
                        }
                );
            }
        });

        actionToolbar.getItems().addAll(privateChatBtn, refreshBtn, deleteBtn);

        VBox toolbarContainer = new VBox(actionToolbar);
        toolbarContainer.setAlignment(Pos.TOP_CENTER);
        toolbarContainer.setPadding(new Insets(0, 0, 8, 0));

        toolbarPane.getChildren().add(toolbarContainer);

        // === Заголовок: большой аватар + имя + nodeId ===
        String avatarText;
        if (node.getShortName() != null && !node.getShortName().isEmpty()) {
            avatarText = node.getShortName().toUpperCase(java.util.Locale.ROOT);
        } else {
            avatarText = displayName.length() > 4
                    ? displayName.substring(0, 4).toUpperCase(java.util.Locale.ROOT)
                    : displayName.toUpperCase(java.util.Locale.ROOT);
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

        // === Таблица деталей ===
        tableData = FXCollections.observableArrayList();
        NodeUtils.fillDetailRows(tableData, node);
        TableView<String[]> table = NodeUtils.createDetailTable(tableData);

        // === График телеметрии ===
        chartPanel = new TelemetryChartPanel();
        VBox.setVgrow(chartPanel, Priority.ALWAYS);
        if (state != null) {
            chartPanel.bind(state, node.getNodeId());
        }

        VBox contentPane = new VBox(10, header, sep, table, chartPanel);
        contentPane.setPadding(new Insets(4, 8, 8, 0));
        HBox.setHgrow(contentPane, Priority.ALWAYS);

        getChildren().addAll(toolbarPane, contentPane);
    }

    /** Конструктор без callback навигации (для inline-использования в FormNodes). */
    public NodeDetailContent(DeviceState state, NodeData node, ProtocolHandler handler) {
        this(state, node, handler, null);
    }

    /** Обновить только данные таблицы (без пересоздания UI). */
    public void updateTableData(NodeData node) {
        tableData.clear();
        NodeUtils.fillDetailRows(tableData, node);
    }

    public TelemetryChartPanel getChartPanel() {
        return chartPanel;
    }

    public int getNodeNum() {
        return nodeNum;
    }
}
