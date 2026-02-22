package com.meshtastic.client.forms;

import com.meshtastic.client.components.TelemetryChartPanel;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.SystemForm;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.Comparator;
import java.util.function.IntConsumer;

@SystemForm(name = "Ноды", description = "Список нод в сети", tags = {"ноды", "nodes", "устройства", "mesh"})
public class FormNodes extends Form {

    private static final int REFRESH_INTERVAL_MS = 60_000;


    private ListView<NodeData> nodeListView;
    private final ObservableList<NodeData> nodeData = FXCollections.observableArrayList();
    private FilteredList<NodeData> filteredNodes;
    private TextField searchField;
    private Label countBadge;

    private VBox detailPane;
    private Label detailPlaceholder;
    private GridPane detailGrid;
    private TelemetryChartPanel currentChartPanel;
    private int currentDetailNodeNum;
    private ObservableList<String[]> detailTableData;

    private boolean suppressSelectionListener;

    private DeviceState state;
    private ProtocolHandler protocolHandler;
    private String connectionId;

    private final IntConsumer nodeUpdateListener = num -> Platform.runLater(() -> {
        if (state == null) return;
        NodeData node = state.getNodeDb().get(num);
        if (node != null) {
            for (int i = 0; i < nodeData.size(); i++) {
                if (nodeData.get(i).getNodeNum() == num) {
                    suppressSelectionListener = true;
                    try {
                        nodeData.set(i, node);
                    } finally {
                        suppressSelectionListener = false;
                    }
                    // Восстановить выделение если обновлённая нода была выбрана
                    if (num == currentDetailNodeNum) {
                        for (NodeData n : nodeListView.getItems()) {
                            if (n.getNodeNum() == num) {
                                nodeListView.getSelectionModel().select(n);
                                break;
                            }
                        }
                    }
                    refreshDetail();
                    return;
                }
            }
            nodeData.add(node);
        }
    });

    private final Runnable connectionListener = () -> Platform.runLater(this::rebindState);

    private Timeline refreshTimer;

    public FormNodes() {
        initComponents();
    }

    @Override
    public void formInit() {
        ConnectionManager.getInstance().addListener(connectionListener);
        rebindState();
        startRefreshTimer();
    }

    @Override
    public void formOpen() {
        rebindState();
    }

    @Override
    public void formRefresh() {
        reloadList();
        requestMissingNodeInfo();
    }

    // ==================== UI ====================

    private void initComponents() {
        getStyleClass().add("node-form");

        // --- Левая панель: поиск + список ---
        VBox leftPane = new VBox();
        leftPane.getStyleClass().add("node-list-pane");

        searchField = new TextField();
        searchField.setPromptText("\uD83D\uDD0D Поиск (\u2318K)");
        searchField.getStyleClass().add("node-search-field");

        HBox searchBox = new HBox(searchField);
        searchBox.setPadding(new Insets(8));
        HBox.setHgrow(searchField, Priority.ALWAYS);

        filteredNodes = new FilteredList<>(nodeData, n -> true);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            String query = newVal == null ? "" : newVal.trim().toLowerCase();
            filteredNodes.setPredicate(node -> {
                if (query.isEmpty()) return true;
                if (node.getLongName() != null && node.getLongName().toLowerCase().contains(query)) return true;
                if (node.getShortName() != null && node.getShortName().toLowerCase().contains(query)) return true;
                if (node.getNodeId() != null && node.getNodeId().toLowerCase().contains(query)) return true;
                if (String.valueOf(node.getNodeNum()).contains(query)) return true;
                return false;
            });
        });

        SortedList<NodeData> sortedNodes = new SortedList<>(filteredNodes,
                Comparator.comparingInt(NodeData::getHopsAway)
                        .thenComparing(n -> n.getLongName() != null ? n.getLongName() : ""));

        nodeListView = new ListView<>(sortedNodes);
        nodeListView.getStyleClass().add("node-list-view");
        nodeListView.setCellFactory(lv -> new NodeListCell());
        nodeListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldNode, newNode) -> {
                    if (!suppressSelectionListener) showDetail(newNode);
                });

        // Бейдж с количеством нод
        countBadge = new Label("0");
        countBadge.setStyle(
                "-fx-background-color: #5B5B5E;" +
                "-fx-background-radius: 12;" +
                "-fx-padding: 4 10;" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 12px;"
        );
        countBadge.setMouseTransparent(true);
        StackPane.setAlignment(countBadge, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(countBadge, new Insets(0, 12, 12, 0));

        StackPane listWrapper = new StackPane(nodeListView, countBadge);
        VBox.setVgrow(listWrapper, Priority.ALWAYS);

        filteredNodes.addListener((javafx.collections.ListChangeListener<NodeData>) change ->
                countBadge.setText(String.valueOf(filteredNodes.size())));

        leftPane.getChildren().addAll(searchBox, listWrapper);

        // --- Правая панель: детали ---
        detailPane = new VBox(10);
        detailPane.setPadding(new Insets(15));
        detailPane.getStyleClass().add("node-detail-pane");

        detailPlaceholder = new Label("Выберите ноду из списка");
        detailPlaceholder.setStyle("-fx-opacity: 0.5; -fx-font-size: 14px;");
        detailPlaceholder.setMaxWidth(Double.MAX_VALUE);
        detailPlaceholder.setAlignment(Pos.CENTER);

        detailGrid = new GridPane();
        detailGrid.setHgap(12);
        detailGrid.setVgap(6);
        detailGrid.getStyleClass().add("node-detail-grid");
        // Значение растягивается
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(110);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);
        detailGrid.getColumnConstraints().addAll(labelCol, valueCol);

        detailPane.getChildren().add(detailPlaceholder);

        // --- SplitPane ---
        SplitPane splitPane = new SplitPane(leftPane, detailPane);
        splitPane.setDividerPositions(0.38);
        splitPane.getStyleClass().add("node-split-pane");
        SplitPane.setResizableWithParent(leftPane, false);

        getChildren().add(splitPane);

        // Растянуть SplitPane на всю форму
        splitPane.prefWidthProperty().bind(widthProperty());
        splitPane.prefHeightProperty().bind(heightProperty());
    }

    // ==================== Node list cell ====================

    private class NodeListCell extends ListCell<NodeData> {
        private final HBox root = new HBox(10);
        private final StackPane avatarPane = new StackPane();
        private final Label avatarLabel = new Label();
        private final VBox textBox = new VBox(2);
        private final Label nameLabel = new Label();
        private final Label subtitleLabel = new Label();

        NodeListCell() {
            root.setAlignment(Pos.CENTER_LEFT);
            root.setPadding(new Insets(6, 10, 6, 10));
            root.getStyleClass().add("node-list-cell-root");

            // Круглый аватар
            Circle clip = new Circle(20);
            avatarPane.setMinSize(40, 40);
            avatarPane.setMaxSize(40, 40);
            avatarPane.getStyleClass().add("node-avatar");
            avatarLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
            avatarPane.getChildren().add(avatarLabel);

            nameLabel.setFont(Font.font("Roboto", FontWeight.BOLD, 14));
            nameLabel.getStyleClass().add("node-name-label");

            subtitleLabel.getStyleClass().add("node-subtitle-label");

            textBox.getChildren().addAll(nameLabel, subtitleLabel);
            HBox.setHgrow(textBox, Priority.ALWAYS);
            root.getChildren().addAll(avatarPane, textBox);
        }

        @Override
        protected void updateItem(NodeData node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            String displayName = node.getLongName() != null && !node.getLongName().isEmpty()
                    ? node.getLongName()
                    : (node.getNodeId() != null ? node.getNodeId() : "?");
            nameLabel.setText(displayName);

            // Аватар: shortName целиком или первые 4 символа имени
            String avatarText;
            if (node.getShortName() != null && !node.getShortName().isEmpty()) {
                avatarText = node.getShortName().toUpperCase();
            } else {
                avatarText = displayName.length() > 4
                        ? displayName.substring(0, 4).toUpperCase()
                        : displayName.toUpperCase();
            }
            avatarLabel.setText(avatarText);
            avatarLabel.setFont(Font.font("Roboto", FontWeight.BOLD, NodeUtils.avatarFontSize(avatarText.length(), 40)));

            String color = NodeUtils.roleColor(node.getRole());
            avatarPane.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 20;");

            // Подстрока
            String subtitle = formatLastHeardRelative(node.getLastHeard());
            if (node.getHopsAway() > 0) {
                subtitle += " · " + node.getHopsAway() + " хоп";
            }
            subtitleLabel.setText(subtitle);

            setGraphic(root);
            setText(null);
        }
    }

    // ==================== Detail panel ====================

    private void showDetail(NodeData node) {
        if (node == null) {
            // Отвязать предыдущий график телеметрии
            if (currentChartPanel != null) {
                currentChartPanel.unbind();
                currentChartPanel = null;
            }
            currentDetailNodeNum = 0;
            detailPane.getChildren().clear();
            detailPane.getChildren().add(detailPlaceholder);
            return;
        }

        // Если та же нода — обновляем только данные таблицы, не пересоздаём UI
        if (node.getNodeNum() == currentDetailNodeNum) {
            updateDetailTable(node);
            return;
        }

        // Отвязать предыдущий график
        if (currentChartPanel != null) {
            currentChartPanel.unbind();
            currentChartPanel = null;
        }

        currentDetailNodeNum = node.getNodeNum();
        detailPane.getChildren().clear();

        // Заголовок с аватаром
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
        Label bigAvatarLabel = new Label(avatarText);
        bigAvatarLabel.setFont(Font.font("Roboto", FontWeight.BOLD, NodeUtils.avatarFontSize(avatarText.length(), 56)));
        bigAvatarLabel.setStyle("-fx-text-fill: white;");
        bigAvatar.getChildren().add(bigAvatarLabel);

        Label headerName = new Label(displayName);
        headerName.setFont(Font.font("Roboto", FontWeight.BOLD, 18));

        Label headerSub = new Label(node.getNodeId() != null ? node.getNodeId() : "");
        headerSub.setStyle("-fx-opacity: 0.6;");

        VBox headerText = new VBox(2, headerName, headerSub);
        headerText.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(12, bigAvatar, headerText);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 10, 0));

        Separator sep = new Separator();

        // Таблица ключ-значение
        detailTableData = FXCollections.observableArrayList();
        NodeUtils.fillDetailRows(detailTableData, node);

        TableView<String[]> table = NodeUtils.createDetailTable(detailTableData);

        // --- График телеметрии ---
        currentChartPanel = new TelemetryChartPanel();
        VBox.setVgrow(currentChartPanel, Priority.ALWAYS);
        if (state != null) {
            currentChartPanel.bind(state, node.getNodeNum());
        }

        detailPane.getChildren().addAll(header, sep, table, currentChartPanel);
    }

    /** Обновить детали если выбранная нода обновилась (не пересоздаёт UI, сохраняет фильтр графика) */
    private void refreshDetail() {
        NodeData selected = nodeListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            showDetail(selected);
        }
    }

    /** Обновить только данные таблицы деталей без пересоздания UI */
    private void updateDetailTable(NodeData node) {
        if (detailTableData == null) return;
        detailTableData.clear();
        NodeUtils.fillDetailRows(detailTableData, node);
    }

    // ==================== Data ====================

    private void rebindState() {
        var mgr = ConnectionManager.getInstance();
        DeviceState newState = null;
        ProtocolHandler newHandler = null;
        String newConnId = null;

        for (ConnectionEntry entry : mgr.getEntries()) {
            if (entry.isConnected()) {
                newState = mgr.getDeviceState(entry.getId());
                newHandler = mgr.getProtocolHandler(entry.getId());
                newConnId = entry.getId();
                if (newState != null) break;
            }
        }

        if (newState == this.state) {
            reloadList();
            return;
        }

        if (this.state != null) {
            this.state.removeNodeUpdateListener(nodeUpdateListener);
        }

        this.state = newState;
        this.protocolHandler = newHandler;
        this.connectionId = newConnId;

        if (this.state != null) {
            this.state.addNodeUpdateListener(nodeUpdateListener);
        }

        reloadList();
    }

    private void reloadList() {
        // Запомнить выделенную ноду
        NodeData selected = nodeListView.getSelectionModel().getSelectedItem();
        int selectedNodeNum = selected != null ? selected.getNodeNum() : 0;

        // Подавить listener чтобы setAll() → null selection не закрыл детали
        suppressSelectionListener = true;
        try {
            if (state != null) {
                nodeData.setAll(state.getNodeDb().values());
            } else {
                nodeData.clear();
            }

            // Восстановить выделение
            if (selectedNodeNum != 0) {
                for (NodeData node : nodeListView.getItems()) {
                    if (node.getNodeNum() == selectedNodeNum) {
                        nodeListView.getSelectionModel().select(node);
                        break;
                    }
                }
            }
        } finally {
            suppressSelectionListener = false;
        }
    }

    private void startRefreshTimer() {
        if (refreshTimer != null) return;
        refreshTimer = new Timeline(
                new KeyFrame(Duration.millis(REFRESH_INTERVAL_MS), e -> requestMissingNodeInfo())
        );
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        refreshTimer.play();
    }

    private void requestMissingNodeInfo() {
        if (state == null || protocolHandler == null) return;

        int myNodeNum = state.getMyNodeNum();
        for (NodeData node : state.getNodeDb().values()) {
            if (node.getNodeNum() == myNodeNum) continue;
            if (node.getLongName() == null || node.getLongName().isEmpty()) {
                MessageService.requestNodeInfo(protocolHandler, state, node.getNodeNum());
            }
        }
    }

    // ==================== Helpers ====================

    /**
     * Относительное время: «в сети», «X минут назад», «X часов назад», «X дней назад».
     */
    private static String formatLastHeardRelative(int epochSeconds) {
        if (epochSeconds <= 0) return "нет данных";
        long now = System.currentTimeMillis() / 1000;
        long diff = now - epochSeconds;
        if (diff < 60) return "в сети";
        if (diff < 3600) {
            long min = diff / 60;
            return "был(а) " + min + " " + minuteWord(min) + " назад";
        }
        if (diff < 86400) {
            long hours = diff / 3600;
            return "был(а) " + hours + " " + hourWord(hours) + " назад";
        }
        long days = diff / 86400;
        return "был(а) " + days + " " + dayWord(days) + " назад";
    }

    private static String minuteWord(long n) {
        n = Math.abs(n) % 100;
        long n1 = n % 10;
        if (n > 10 && n < 20) return "минут";
        if (n1 == 1) return "минуту";
        if (n1 >= 2 && n1 <= 4) return "минуты";
        return "минут";
    }

    private static String hourWord(long n) {
        n = Math.abs(n) % 100;
        long n1 = n % 10;
        if (n > 10 && n < 20) return "часов";
        if (n1 == 1) return "час";
        if (n1 >= 2 && n1 <= 4) return "часа";
        return "часов";
    }

    private static String dayWord(long n) {
        n = Math.abs(n) % 100;
        long n1 = n % 10;
        if (n > 10 && n < 20) return "дней";
        if (n1 == 1) return "день";
        if (n1 >= 2 && n1 <= 4) return "дня";
        return "дней";
    }
}
