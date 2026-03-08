package com.meshtastic.client.forms;

import com.meshtastic.client.components.NodeDetailContent;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.FavoriteNodeService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Comparator;
import java.util.Locale;
import java.util.function.IntConsumer;

@SystemForm(name = "Ноды", description = "Список нод в сети", tags = {"ноды", "nodes", "устройства", "mesh"})
public class FormNodes extends Form {

    private ListView<NodeData> nodeListView;
    private final ObservableList<NodeData> nodeData = FXCollections.observableArrayList();
    private FilteredList<NodeData> filteredNodes;
    private TextField searchField;
    private Label countBadge;

    private VBox detailPane;
    private Label detailPlaceholder;
    private NodeDetailContent currentDetailContent;
    private int currentDetailNodeNum;

    private boolean suppressSelectionListener;
    private boolean showFavoritesOnly;
    private Button favFilterBtn;
    private SortedList<NodeData> sortedNodes;

    private DeviceState state;
    private ProtocolHandler protocolHandler;
    private String connectionId;

    private final IntConsumer nodeUpdateListener = num -> Platform.runLater(() -> {
        if (state == null) { return; }
        NodeData node = state.getNodeDb().get(num);

        // Нода удалена из nodeDb — убрать из списка и очистить детали
        if (node == null) {
            nodeData.removeIf(n -> n.getNodeNum() == num);
            if (num == currentDetailNodeNum) {
                showDetail(null);
            }
            return;
        }

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
    });

    private final Runnable connectionListener = () -> Platform.runLater(this::rebindState);

    private final Runnable favoritesListener = () -> Platform.runLater(() -> {
        if (showFavoritesOnly) {
            updateFilterPredicate();
        }
        nodeListView.refresh();
    });

    public FormNodes() {
        initComponents();
    }

    @Override
    public void formInit() {
        ConnectionManager.getInstance().addListener(connectionListener);
        FavoriteNodeService.getInstance().addListener(favoritesListener);
        rebindState();
    }

    @Override
    public void formOpen() {
        rebindState();
    }

    @Override
    public void formRefresh() {
        reloadList();
    }

    // ==================== UI ====================

    private void initComponents() {
        getStyleClass().add("node-form");

        // --- Левая панель: поиск + список ---
        VBox leftPane = new VBox();
        leftPane.getStyleClass().add("node-list-pane");

        searchField = new TextField();
        searchField.setPromptText("\uD83D\uDD0D Поиск");
        searchField.getStyleClass().add("node-search-field");

        // Кнопка фильтра «Только избранные»
        SVGPath favFilterIcon = SvgIconLoader.load("/icons/favorite.svg", 16);
        favFilterBtn = new Button();
        favFilterBtn.setGraphic(favFilterIcon);
        favFilterBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        favFilterBtn.getStyleClass().add("chat-new-btn");
        favFilterBtn.setTooltip(new Tooltip("Только избранные"));
        favFilterBtn.setOnAction(e -> {
            showFavoritesOnly = !showFavoritesOnly;
            if (showFavoritesOnly) {
                favFilterBtn.getStyleClass().add("favorite-btn-active");
                favFilterBtn.getTooltip().setText("Показать все");
            } else {
                favFilterBtn.getStyleClass().remove("favorite-btn-active");
                favFilterBtn.getTooltip().setText("Только избранные");
            }
            updateFilterPredicate();
        });

        // Кнопка сортировки
        SVGPath sortIcon = SvgIconLoader.load("/icons/sort.svg", 16);
        Button sortBtn = new Button();
        sortBtn.setGraphic(sortIcon);
        sortBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        sortBtn.getStyleClass().add("chat-new-btn");
        sortBtn.setTooltip(new Tooltip("Сортировка"));

        ToggleGroup sortGroup = new ToggleGroup();
        RadioMenuItem sortByTime = new RadioMenuItem("По времени в сети");
        RadioMenuItem sortByNameAsc = new RadioMenuItem("По алфавиту (А→Я)");
        RadioMenuItem sortByNameDesc = new RadioMenuItem("По алфавиту (Я→А)");
        RadioMenuItem sortByAdded = new RadioMenuItem("По порядку добавления");
        sortByTime.setToggleGroup(sortGroup);
        sortByNameAsc.setToggleGroup(sortGroup);
        sortByNameDesc.setToggleGroup(sortGroup);
        sortByAdded.setToggleGroup(sortGroup);
        sortByTime.setSelected(true);

        ContextMenu sortMenu = new ContextMenu(sortByTime, sortByNameAsc, sortByNameDesc, sortByAdded);
        sortBtn.setOnAction(e -> sortMenu.show(sortBtn, javafx.geometry.Side.BOTTOM, 0, 0));

        Comparator<NodeData> defaultSort = Comparator.comparingInt(NodeData::getLastHeard).reversed()
                .thenComparing(n -> n.getLongName() != null ? n.getLongName() : "");

        sortByTime.setOnAction(e -> sortedNodes.setComparator(defaultSort));
        sortByNameAsc.setOnAction(e -> sortedNodes.setComparator(
                Comparator.comparing(n -> n.getLongName() != null ? n.getLongName().toLowerCase(Locale.ROOT) : "\uffff")));
        sortByNameDesc.setOnAction(e -> sortedNodes.setComparator(
                Comparator.comparing((NodeData n) -> n.getLongName() != null ? n.getLongName().toLowerCase(Locale.ROOT) : "").reversed()));
        sortByAdded.setOnAction(e -> sortedNodes.setComparator(null));

        HBox searchBox = new HBox(8, searchField, favFilterBtn, sortBtn);
        searchBox.setPadding(new Insets(8));
        searchBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        filteredNodes = new FilteredList<>(nodeData, n -> true);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> updateFilterPredicate());

        sortedNodes = new SortedList<>(filteredNodes, defaultSort);

        nodeListView = new ListView<>(sortedNodes);
        nodeListView.getStyleClass().add("node-list-view");
        nodeListView.setCellFactory(lv -> new NodeListCell());
        nodeListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldNode, newNode) -> {
                    if (!suppressSelectionListener) { showDetail(newNode); }
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
        detailPane = new VBox();
        detailPane.setPadding(Insets.EMPTY);
        detailPane.getStyleClass().add("node-detail-pane");

        detailPlaceholder = new Label("Выберите ноду из списка");
        detailPlaceholder.setStyle("-fx-opacity: 0.5; -fx-font-size: 14px;");
        detailPlaceholder.setMaxWidth(Double.MAX_VALUE);
        detailPlaceholder.setAlignment(Pos.CENTER);

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

    // ==================== Filter ====================

    private void updateFilterPredicate() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        FavoriteNodeService favService = FavoriteNodeService.getInstance();
        filteredNodes.setPredicate(node -> {
            if (showFavoritesOnly && !favService.isFavorite(node.getNodeId())) {
                return false;
            }
            if (query.isEmpty()) { return true; }
            if (node.getLongName() != null && node.getLongName().toLowerCase(Locale.ROOT).contains(query)) { return true; }
            if (node.getShortName() != null && node.getShortName().toLowerCase(Locale.ROOT).contains(query)) { return true; }
            if (node.getNodeId() != null && node.getNodeId().toLowerCase(Locale.ROOT).contains(query)) { return true; }
            return String.valueOf(node.getNodeNum()).contains(query);
        });
    }

    // ==================== Node list cell ====================

    private class NodeListCell extends ListCell<NodeData> {
        private final HBox root = new HBox(10);
        private final StackPane avatarPane = new StackPane();
        private final Label avatarLabel = new Label();
        private final VBox textBox = new VBox(2);
        private final Label nameLabel = new Label();
        private final Label subtitleLabel = new Label();
        private final StackPane starPane = new StackPane();

        NodeListCell() {
            root.setAlignment(Pos.CENTER_LEFT);
            root.setPadding(new Insets(6, 10, 6, 10));
            root.getStyleClass().add("node-list-cell-root");

            // Круглый аватар
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

            // Звёздочка избранного
            SVGPath starIcon = SvgIconLoader.load("/icons/favorite.svg", 12);
            if (starIcon != null) {
                starPane.getChildren().add(starIcon);
                starPane.getStyleClass().add("node-favorite-star");
            }
            starPane.setMinSize(16, 16);
            starPane.setMaxSize(16, 16);
            starPane.setAlignment(Pos.CENTER);

            root.getChildren().addAll(avatarPane, textBox, starPane);

            // Контекстное меню
            MenuItem addFavItem = new MenuItem("Добавить в избранное");
            MenuItem removeFavItem = new MenuItem("Убрать из избранного");
            ContextMenu ctxMenu = new ContextMenu(addFavItem, removeFavItem);
            setContextMenu(ctxMenu);

            ctxMenu.setOnShowing(ev -> {
                NodeData nd = getItem();
                boolean fav = nd != null && FavoriteNodeService.getInstance().isFavorite(nd.getNodeId());
                addFavItem.setVisible(!fav);
                removeFavItem.setVisible(fav);
            });

            addFavItem.setOnAction(ev -> {
                NodeData nd = getItem();
                if (nd != null) { FavoriteNodeService.getInstance().addFavorite(nd.getNodeId()); }
            });

            removeFavItem.setOnAction(ev -> {
                NodeData nd = getItem();
                if (nd != null) { FavoriteNodeService.getInstance().removeFavorite(nd.getNodeId()); }
            });
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
                    : node.getNodeId() != null ? node.getNodeId() : "?";
            nameLabel.setText(displayName);

            // Аватар: shortName целиком или первые 4 символа имени
            String avatarText;
            if (node.getShortName() != null && !node.getShortName().isEmpty()) {
                avatarText = node.getShortName().toUpperCase(Locale.ROOT);
            } else {
                avatarText = displayName.length() > 4
                        ? displayName.substring(0, 4).toUpperCase(Locale.ROOT)
                        : displayName.toUpperCase(Locale.ROOT);
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

            // Звёздочка избранного
            boolean isFav = FavoriteNodeService.getInstance().isFavorite(node.getNodeId());
            starPane.setVisible(isFav);
            starPane.setManaged(isFav);

            setGraphic(root);
            setText(null);
        }
    }

    // ==================== Detail panel ====================

    private void showDetail(NodeData node) {
        if (node == null) {
            if (currentDetailContent != null) {
                currentDetailContent.getChartPanel().unbind();
                currentDetailContent = null;
            }
            currentDetailNodeNum = 0;
            detailPane.getChildren().clear();
            detailPane.getChildren().add(detailPlaceholder);
            return;
        }

        // Обогатить bare-ноду данными из кэша перед показом
        if (!node.hasName()) {
            NodeCacheService.getInstance().enrichFromCache(node);
        }

        // Если та же нода — обновляем только данные таблицы, не пересоздаём UI
        if (node.getNodeNum() == currentDetailNodeNum && currentDetailContent != null) {
            currentDetailContent.updateTableData(node);
            return;
        }

        // Отвязать предыдущий компонент
        if (currentDetailContent != null) {
            currentDetailContent.getChartPanel().unbind();
        }

        currentDetailNodeNum = node.getNodeNum();
        currentDetailContent = new NodeDetailContent(state, node, protocolHandler);
        VBox.setVgrow(currentDetailContent, Priority.ALWAYS);

        detailPane.getChildren().clear();
        detailPane.getChildren().add(currentDetailContent);
    }

    /** Обновить детали если выбранная нода обновилась (не пересоздаёт UI, сохраняет фильтр графика) */
    private void refreshDetail() {
        NodeData selected = nodeListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            showDetail(selected);
        }
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
                if (newState != null) { break; }
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

    // ==================== Helpers ====================

    /**
     * Относительное время: «в сети», «X минут назад», «X часов назад», «X дней назад».
     */
    private static String formatLastHeardRelative(int epochSeconds) {
        if (epochSeconds <= 0) { return "нет данных"; }
        long now = System.currentTimeMillis() / 1000;
        long diff = now - epochSeconds;
        if (diff < 60) { return "в сети"; }
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
        if (n > 10 && n < 20) { return "минут"; }
        if (n1 == 1) { return "минуту"; }
        if (n1 >= 2 && n1 <= 4) { return "минуты"; }
        return "минут";
    }

    private static String hourWord(long n) {
        n = Math.abs(n) % 100;
        long n1 = n % 10;
        if (n > 10 && n < 20) { return "часов"; }
        if (n1 == 1) { return "час"; }
        if (n1 >= 2 && n1 <= 4) { return "часа"; }
        return "часов";
    }

    private static String dayWord(long n) {
        n = Math.abs(n) % 100;
        long n1 = n % 10;
        if (n > 10 && n < 20) { return "дней"; }
        if (n1 == 1) { return "день"; }
        if (n1 >= 2 && n1 <= 4) { return "дня"; }
        return "дней";
    }
}
