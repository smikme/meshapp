package com.meshtastic.client.forms;

import com.meshtastic.client.components.NodeDetailContent;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.FavoriteNodeService;
import com.meshtastic.client.service.IgnoredNodeService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.SystemForm;
import com.meshtastic.client.utils.UnicodeTextUtils;
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

import java.util.*;
import java.util.function.IntConsumer;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@SystemForm(name = "Ноды", description = "Список нод в сети", tags = {"ноды", "nodes", "устройства", "mesh"})
public class FormNodes extends Form {

    private static final String WINDOWS_HIT_TEST_BACKGROUND = "-fx-background-color: rgba(0,0,0,0.004);";

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
    private boolean showDetails;
    private boolean hideOffline;
    private boolean includeUnknownNames;
    private boolean showDirectOnly;
    private boolean showIgnoredOnly;
    private Button favFilterBtn;
    private CheckMenuItem filterFavorites;
    private CheckMenuItem filterIgnored;
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
                // Обновлять правую панель только для реально выбранной ноды.
                // Иначе частые апдейты чужих нод дёргают detail-table во время layout/render.
                if (num == currentDetailNodeNum) {
                    for (NodeData n : nodeListView.getItems()) {
                        if (n.getNodeNum() == num) {
                            nodeListView.getSelectionModel().select(n);
                            break;
                        }
                    }
                    refreshDetail();
                }
                return;
            }
        }
        nodeData.add(node);
    });

    private final Runnable connectionListener = () -> Platform.runLater(this::rebindState);

    private final Runnable favoritesListener = () -> Platform.runLater(() -> {
        if (showFavoritesOnly) {
            injectOfflineFavorites();
            updateFilterPredicate();
        }
        nodeListView.refresh();
    });

    private final Runnable ignoredListener = () -> Platform.runLater(() -> {
        if (showIgnoredOnly) {
            injectOfflineIgnored();
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
        IgnoredNodeService.getInstance().addListener(ignoredListener);
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
        if (OsDetect.isWindows() && !AppPreferences.isDisableEffectsEffective()) {
            leftPane.setStyle(WINDOWS_HIT_TEST_BACKGROUND);
        }

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
            syncFavoritesState();
            AppPreferences.setNodesFilterFavorites(showFavoritesOnly);
            if (filterFavorites != null) { filterFavorites.setSelected(showFavoritesOnly); }
        });

        // Кнопка сортировки и фильтров
        SVGPath sortIcon = SvgIconLoader.load("/icons/sort.svg", 16);
        Button sortBtn = new Button();
        sortBtn.setGraphic(sortIcon);
        sortBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        sortBtn.getStyleClass().add("chat-new-btn");
        sortBtn.setTooltip(new Tooltip("Сортировка и фильтры"));

        // --- Сортировки ---
        ToggleGroup sortGroup = new ToggleGroup();
        RadioMenuItem sortLastHeardNew = new RadioMenuItem("Последний отклик (новые)");
        RadioMenuItem sortLastHeardOld = new RadioMenuItem("Последний отклик (старые)");
        RadioMenuItem sortDistance = new RadioMenuItem("Расстояние");
        RadioMenuItem sortSignal = new RadioMenuItem("Сигнал (SNR)");
        RadioMenuItem sortHops = new RadioMenuItem("Количество хопов");
        RadioMenuItem sortChannel = new RadioMenuItem("Канал");
        sortLastHeardNew.setToggleGroup(sortGroup);
        sortLastHeardOld.setToggleGroup(sortGroup);
        sortDistance.setToggleGroup(sortGroup);
        sortSignal.setToggleGroup(sortGroup);
        sortHops.setToggleGroup(sortGroup);
        sortChannel.setToggleGroup(sortGroup);

        // --- Фильтры ---
        CheckMenuItem filterUnknown = new CheckMenuItem("Показывать неизвестные ноды");
        CheckMenuItem filterDetails = new CheckMenuItem("Показать детали");
        CheckMenuItem filterHideOffline = new CheckMenuItem("Скрыть офлайн-ноды");
        filterFavorites = new CheckMenuItem("Только избранные");
        CheckMenuItem filterDirect = new CheckMenuItem("Только прямые (0 хопов)");
        filterIgnored = new CheckMenuItem("Игнорируемые");

        ContextMenu sortMenu = new ContextMenu(
                sortLastHeardNew, sortLastHeardOld, sortDistance, sortSignal, sortHops, sortChannel,
                new SeparatorMenuItem(),
                filterUnknown, filterDetails, filterHideOffline, filterFavorites, filterDirect, filterIgnored
        );
        // Не закрывать меню при клике на чекбоксы — переоткрыть
        for (MenuItem item : sortMenu.getItems()) {
            if (item instanceof CheckMenuItem) {
                item.addEventHandler(javafx.event.ActionEvent.ACTION, ev -> {
                    Platform.runLater(() -> {
                        if (!sortMenu.isShowing()) {
                            sortMenu.show(sortBtn, javafx.geometry.Side.BOTTOM, 0, 0);
                        }
                    });
                });
            }
        }
        sortBtn.setOnAction(e -> {
            if (sortMenu.isShowing()) {
                sortMenu.hide();
            } else {
                sortMenu.show(sortBtn, javafx.geometry.Side.BOTTOM, 0, 0);
            }
        });

        // Компараторы сортировки
        Comparator<NodeData> defaultSort = Comparator.comparingInt(NodeData::getLastHeard).reversed()
                .thenComparing(n -> n.getLongName() != null ? n.getLongName() : "");

        // Map sort keys → RadioMenuItems и Comparators
        Map<String, RadioMenuItem> sortKeyToItem = new LinkedHashMap<>();
        sortKeyToItem.put("LAST_HEARD_NEW", sortLastHeardNew);
        sortKeyToItem.put("LAST_HEARD_OLD", sortLastHeardOld);
        sortKeyToItem.put("DISTANCE", sortDistance);
        sortKeyToItem.put("SIGNAL", sortSignal);
        sortKeyToItem.put("HOPS", sortHops);
        sortKeyToItem.put("CHANNEL", sortChannel);

        sortLastHeardNew.setOnAction(e -> { sortedNodes.setComparator(defaultSort); AppPreferences.setNodesSort("LAST_HEARD_NEW"); });
        sortLastHeardOld.setOnAction(e -> {
            sortedNodes.setComparator(Comparator.comparingInt(NodeData::getLastHeard)
                    .thenComparing(n -> n.getLongName() != null ? n.getLongName() : ""));
            AppPreferences.setNodesSort("LAST_HEARD_OLD");
        });
        sortDistance.setOnAction(e -> { sortedNodes.setComparator(buildDistanceComparator()); AppPreferences.setNodesSort("DISTANCE"); });
        sortSignal.setOnAction(e -> { sortedNodes.setComparator(Comparator.comparingDouble(NodeData::getSnr).reversed()); AppPreferences.setNodesSort("SIGNAL"); });
        sortHops.setOnAction(e -> {
            sortedNodes.setComparator(buildHopsComparator());
            AppPreferences.setNodesSort("HOPS");
        });
        sortChannel.setOnAction(e -> {
            sortedNodes.setComparator(Comparator.comparingInt(NodeData::getChannel)
                    .thenComparing(Comparator.comparingInt(NodeData::getLastHeard).reversed()));
            AppPreferences.setNodesSort("CHANNEL");
        });

        // Обработчики фильтров
        filterUnknown.setOnAction(e -> {
            includeUnknownNames = filterUnknown.isSelected();
            AppPreferences.setNodesFilterUnknown(includeUnknownNames);
            updateFilterPredicate();
        });
        filterDetails.setOnAction(e -> {
            showDetails = filterDetails.isSelected();
            AppPreferences.setNodesFilterDetails(showDetails);
            nodeListView.refresh();
        });
        filterHideOffline.setOnAction(e -> {
            hideOffline = filterHideOffline.isSelected();
            AppPreferences.setNodesFilterHideOffline(hideOffline);
            updateFilterPredicate();
        });
        filterFavorites.setOnAction(e -> {
            showFavoritesOnly = filterFavorites.isSelected();
            AppPreferences.setNodesFilterFavorites(showFavoritesOnly);
            syncFavoritesState();
        });
        filterDirect.setOnAction(e -> {
            showDirectOnly = filterDirect.isSelected();
            AppPreferences.setNodesFilterDirect(showDirectOnly);
            updateFilterPredicate();
        });
        filterIgnored.setOnAction(e -> {
            showIgnoredOnly = filterIgnored.isSelected();
            AppPreferences.setNodesFilterIgnored(showIgnoredOnly);
            syncIgnoredState();
        });

        // --- Восстановить настройки из Preferences ---
        includeUnknownNames = AppPreferences.isNodesFilterUnknown();
        showDetails = AppPreferences.isNodesFilterDetails();
        hideOffline = AppPreferences.isNodesFilterHideOffline();
        showFavoritesOnly = AppPreferences.isNodesFilterFavorites();
        showDirectOnly = AppPreferences.isNodesFilterDirect();
        showIgnoredOnly = AppPreferences.isNodesFilterIgnored();

        filterUnknown.setSelected(includeUnknownNames);
        filterDetails.setSelected(showDetails);
        filterHideOffline.setSelected(hideOffline);
        filterFavorites.setSelected(showFavoritesOnly);
        filterDirect.setSelected(showDirectOnly);
        filterIgnored.setSelected(showIgnoredOnly);

        // Синхронизировать favFilterBtn
        if (showFavoritesOnly) {
            favFilterBtn.getStyleClass().add("favorite-btn-active");
            favFilterBtn.getTooltip().setText("Показать все");
        }

        // Восстановить сортировку
        String savedSort = AppPreferences.getNodesSort();
        RadioMenuItem savedSortItem = sortKeyToItem.getOrDefault(savedSort, sortLastHeardNew);
        savedSortItem.setSelected(true);

        HBox searchBox = new HBox(8, searchField, favFilterBtn, sortBtn);
        searchBox.setPadding(new Insets(8));
        searchBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        filteredNodes = new FilteredList<>(nodeData, n -> true);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> updateFilterPredicate());

        // Инициализировать компаратор по сохранённой сортировке
        Comparator<NodeData> initialComparator = resolveComparator(savedSort, defaultSort);
        sortedNodes = new SortedList<>(filteredNodes, initialComparator);

        nodeListView = new ListView<>(sortedNodes);
        nodeListView.getStyleClass().add("node-list-view");
        if (OsDetect.isWindows() && !AppPreferences.isDisableEffectsEffective()) {
            nodeListView.setStyle(WINDOWS_HIT_TEST_BACKGROUND);
        }
        nodeListView.setCellFactory(lv -> new NodeListCell());
        nodeListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldNode, newNode) -> {
                    if (!suppressSelectionListener) { showDetail(newNode); }
                });

        // Бейдж с количеством нод
        countBadge = new Label("0");
        countBadge.getStyleClass().add("node-count-badge");
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
        detailPlaceholder.getStyleClass().add("form-placeholder-label");
        detailPlaceholder.setMaxWidth(Double.MAX_VALUE);
        detailPlaceholder.setAlignment(Pos.CENTER);

        detailPane.getChildren().add(detailPlaceholder);

        // --- SplitPane ---
        SplitPane splitPane = new SplitPane(leftPane, detailPane);
        splitPane.setDividerPositions(AppPreferences.getNodesDividerPos());
        splitPane.getStyleClass().add("node-split-pane");
        SplitPane.setResizableWithParent(leftPane, false);
        splitPane.getDividers().get(0).positionProperty().addListener((obs, oldVal, newVal) ->
                AppPreferences.setNodesDividerPos(newVal.doubleValue()));

        getChildren().add(splitPane);

        // Растянуть SplitPane на всю форму
        splitPane.prefWidthProperty().bind(widthProperty());
        splitPane.prefHeightProperty().bind(heightProperty());

        // Применить фильтры при старте
        updateFilterPredicate();
    }

    /** Возвращает компаратор по строковому ключу сортировки. */
    private Comparator<NodeData> resolveComparator(String sortKey, Comparator<NodeData> defaultSort) {
        return switch (sortKey) {
            case "LAST_HEARD_OLD" -> Comparator.comparingInt(NodeData::getLastHeard)
                    .thenComparing(n -> n.getLongName() != null ? n.getLongName() : "");
            case "DISTANCE" -> buildDistanceComparator();
            case "SIGNAL" -> Comparator.comparingDouble(NodeData::getSnr).reversed();
            case "HOPS" -> buildHopsComparator();
            case "CHANNEL" -> Comparator.comparingInt(NodeData::getChannel)
                    .thenComparing(Comparator.comparingInt(NodeData::getLastHeard).reversed());
            default -> defaultSort;
        };
    }

    private Comparator<NodeData> buildHopsComparator() {
        return Comparator.comparingInt((NodeData node) -> node.hasHopsAway() ? node.getHopsAway() : Integer.MAX_VALUE)
                .thenComparing(Comparator.comparingInt(NodeData::getLastHeard).reversed());
    }

    /** Синхронизирует визуальное состояние кнопки favFilterBtn и применяет фильтр. */
    private void syncFavoritesState() {
        if (showFavoritesOnly) {
            favFilterBtn.getStyleClass().add("favorite-btn-active");
            favFilterBtn.getTooltip().setText("Показать все");
            injectOfflineFavorites();
        } else {
            favFilterBtn.getStyleClass().remove("favorite-btn-active");
            favFilterBtn.getTooltip().setText("Только избранные");
            removeOfflineNodes();
        }
        updateFilterPredicate();
    }

    /** Синхронизирует состояние фильтра игнорируемых и применяет фильтр. */
    private void syncIgnoredState() {
        if (showIgnoredOnly) {
            injectOfflineIgnored();
        } else {
            removeOfflineNodes();
        }
        updateFilterPredicate();
    }

    // ==================== Distance ====================

    private static final double EARTH_RADIUS_KM = 6371.0;

    /** Comparator по расстоянию от своей ноды. Ноды без GPS — в конец. */
    private Comparator<NodeData> buildDistanceComparator() {
        double myLat = 0, myLon = 0;
        if (state != null) {
            NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
            if (myNode != null) {
                myLat = myNode.getLatitude();
                myLon = myNode.getLongitude();
            }
        }
        final double lat1 = myLat;
        final double lon1 = myLon;
        boolean hasMyPos = lat1 != 0 || lon1 != 0;

        if (!hasMyPos) {
            // Нет своей позиции — fallback на lastHeard DESC
            return Comparator.comparingInt(NodeData::getLastHeard).reversed()
                    .thenComparing(n -> n.getLongName() != null ? n.getLongName() : "");
        }

        return Comparator.comparingDouble((NodeData n) -> {
            if (n.getLatitude() == 0 && n.getLongitude() == 0) { return Double.MAX_VALUE; }
            return haversine(lat1, lon1, n.getLatitude(), n.getLongitude());
        }).thenComparing(Comparator.comparingInt(NodeData::getLastHeard).reversed());
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ==================== Filter ====================

    /** Подгрузить избранные ноды из БД, которых нет в текущем живом списке. */
    private void injectOfflineFavorites() {
        List<NodeData> dbFavorites = NodeCacheService.getInstance().loadFavoriteNodes();
        Set<Integer> existingNums = new HashSet<>();
        for (NodeData n : nodeData) { existingNums.add(n.getNodeNum()); }
        for (NodeData dbNode : dbFavorites) {
            if (!existingNums.contains(dbNode.getNodeNum())) {
                nodeData.add(dbNode);
            }
        }
    }

    /** Подгрузить игнорируемые ноды из БД, которых нет в текущем живом списке. */
    private void injectOfflineIgnored() {
        List<NodeData> dbIgnored = NodeCacheService.getInstance().loadIgnoredNodes();
        Set<Integer> existingNums = new HashSet<>();
        for (NodeData n : nodeData) { existingNums.add(n.getNodeNum()); }
        for (NodeData dbNode : dbIgnored) {
            if (!existingNums.contains(dbNode.getNodeNum())) {
                nodeData.add(dbNode);
            }
        }
    }

    /** Убрать ноды, которых нет в DeviceState (были подгружены из БД). */
    private void removeOfflineNodes() {
        if (state == null) { return; }
        Set<Integer> liveNums = state.getNodeDb().keySet();
        nodeData.removeIf(n -> !liveNums.contains(n.getNodeNum()));
    }

    private void updateFilterPredicate() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        boolean hasQuery = !query.isEmpty();
        FavoriteNodeService favService = FavoriteNodeService.getInstance();
        IgnoredNodeService ignService = IgnoredNodeService.getInstance();
        long now = System.currentTimeMillis() / 1000;
        filteredNodes.setPredicate(node -> {
            // Текстовый поиск применяем первым, чтобы во время поиска
            // можно было находить ноды даже без имени.
            if (!matchesSearchQuery(node, query)) {
                return false;
            }
            // Фильтр неизвестных нод (без имени)
            if (!includeUnknownNames && !node.hasName() && !hasQuery) {
                return false;
            }
            // Фильтр офлайн-нод (не слышны более 2 часов)
            if (hideOffline && node.getLastHeard() > 0 && (now - node.getLastHeard()) > 7200) {
                return false;
            }
            // Фильтр только избранные
            if (showFavoritesOnly && !favService.isFavorite(node.getNodeId())) {
                return false;
            }
            // Фильтр только прямые (0 хопов)
            if (showDirectOnly && !node.isDirectNeighbor()) {
                return false;
            }
            // Фильтр только игнорируемые
            if (showIgnoredOnly && !ignService.isIgnored(node.getNodeId())) {
                return false;
            }
            return true;
        });
        countBadge.setText(String.valueOf(filteredNodes.size()));
    }

    static boolean matchesSearchQuery(NodeData node, String query) {
        if (node == null) { return false; }
        if (query == null || query.isBlank()) { return true; }

        String normalized = query.trim().toLowerCase(Locale.ROOT);
        String legacyNodeId = String.format(Locale.ROOT, "!%08x", node.getNodeNum());
        String hexNodeNum = String.format(Locale.ROOT, "%08x", node.getNodeNum());
        String decimalNodeNum = String.valueOf(node.getNodeNum());

        return containsIgnoreCase(node.getLongName(), normalized)
                || containsIgnoreCase(node.getShortName(), normalized)
                || containsIgnoreCase(node.getNodeId(), normalized)
                || containsIgnoreCase(legacyNodeId, normalized)
                || containsIgnoreCase(hexNodeNum, normalized)
                || containsIgnoreCase(decimalNodeNum, normalized)
                || containsIgnoreCase(node.getRole(), normalized)
                || containsIgnoreCase(node.getHwModel(), normalized);
    }

    // ==================== Node list cell ====================

    private class NodeListCell extends ListCell<NodeData> {
        private final HBox root = new HBox(10);
        private final StackPane avatarPane = new StackPane();
        private final Label avatarLabel = new Label();
        private final VBox textBox = new VBox(2);
        private final Label nameLabel = new Label();
        private final Label subtitleLabel = new Label();
        private final Label detailsLabel = new Label();
        private final StackPane starPane = new StackPane();

        NodeListCell() {
            root.setAlignment(Pos.CENTER_LEFT);
            root.setPadding(new Insets(6, 10, 6, 10));
            root.getStyleClass().add("node-list-cell-root");

            // Круглый аватар
            avatarPane.setMinSize(40, 40);
            avatarPane.setMaxSize(40, 40);
            avatarPane.getStyleClass().add("node-avatar");
            avatarLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 0;");
            avatarPane.getChildren().add(avatarLabel);

            nameLabel.getStyleClass().add("node-name-label");

            subtitleLabel.getStyleClass().add("node-subtitle-label");

            detailsLabel.getStyleClass().addAll("node-subtitle-label", "node-detail-meta-label");

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
            MenuItem addIgnItem = new MenuItem("Добавить в игнорируемые");
            MenuItem removeIgnItem = new MenuItem("Убрать из игнорируемых");
            ContextMenu ctxMenu = new ContextMenu(addFavItem, removeFavItem,
                    new SeparatorMenuItem(), addIgnItem, removeIgnItem);
            setContextMenu(ctxMenu);

            ctxMenu.setOnShowing(ev -> {
                NodeData nd = getItem();
                boolean fav = nd != null && FavoriteNodeService.getInstance().isFavorite(nd.getNodeId());
                addFavItem.setVisible(!fav);
                removeFavItem.setVisible(fav);
                boolean ign = nd != null && IgnoredNodeService.getInstance().isIgnored(nd.getNodeId());
                addIgnItem.setVisible(!ign);
                removeIgnItem.setVisible(ign);
            });

            addFavItem.setOnAction(ev -> {
                NodeData nd = getItem();
                if (nd != null) { FavoriteNodeService.getInstance().addFavorite(nd.getNodeId()); }
            });

            removeFavItem.setOnAction(ev -> {
                NodeData nd = getItem();
                if (nd != null) { FavoriteNodeService.getInstance().removeFavorite(nd.getNodeId()); }
            });

            addIgnItem.setOnAction(ev -> {
                NodeData nd = getItem();
                if (nd != null) { IgnoredNodeService.getInstance().addIgnored(nd.getNodeId()); }
            });

            removeIgnItem.setOnAction(ev -> {
                NodeData nd = getItem();
                if (nd != null) { IgnoredNodeService.getInstance().removeIgnored(nd.getNodeId()); }
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
            displayName = UnicodeTextUtils.sanitizeForJavaFxDisplay(displayName);
            nameLabel.setText(displayName);

            // Аватар: shortName целиком или первые 4 символа имени
            String avatarText;
            if (node.getShortName() != null && !node.getShortName().isEmpty()) {
                avatarText = UnicodeTextUtils.sanitize(node.getShortName()).toUpperCase(Locale.ROOT);
            } else {
                avatarText = UnicodeTextUtils.prefixByCodePoints(displayName, 4).toUpperCase(Locale.ROOT);
            }
            String safeAvatarText = UnicodeTextUtils.sanitizeForJavaFxDisplay(avatarText);
            avatarLabel.setText(safeAvatarText);
            avatarLabel.setFont(Font.font("Roboto", FontWeight.BOLD,
                    NodeUtils.avatarFontSize(safeAvatarText, 40)));

            String color = NodeUtils.roleColor(node.getRole());
            avatarPane.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 20;");

            // Подстрока
            String subtitle = formatLastHeardRelative(node.getLastHeard());
            if (node.hasHopsAway() && node.getHopsAway() > 0) {
                subtitle += " · " + node.getHopsAway() + " хоп";
            }
            subtitleLabel.setText(subtitle);

            // Расширенные детали
            if (showDetails) {
                StringBuilder sb = new StringBuilder();
                if (node.getSnr() != 0) { sb.append("SNR: ").append(String.format("%.1f", node.getSnr())).append(" дБ"); }
                if (node.hasHopsAway() && node.getHopsAway() > 0) {
                    if (!sb.isEmpty()) { sb.append(" · "); }
                    sb.append(node.getHopsAway()).append(" хоп");
                }
                if (node.getBatteryLevel() > 0 && node.getBatteryLevel() <= 100) {
                    if (!sb.isEmpty()) { sb.append(" · "); }
                    sb.append("Бат: ").append(node.getBatteryLevel()).append("%");
                } else if (node.getBatteryLevel() == 101) {
                    if (!sb.isEmpty()) { sb.append(" · "); }
                    sb.append("Бат: USB");
                }
                if (node.getVoltage() > 0) {
                    if (!sb.isEmpty()) { sb.append(" · "); }
                    sb.append(String.format("%.1fV", node.getVoltage()));
                }
                if (node.getChannel() > 0) {
                    if (!sb.isEmpty()) { sb.append(" · "); }
                    sb.append("Кан: ").append(node.getChannel());
                }
                detailsLabel.setText(sb.toString());
                if (!textBox.getChildren().contains(detailsLabel)) {
                    textBox.getChildren().add(detailsLabel);
                }
                detailsLabel.setVisible(true);
                detailsLabel.setManaged(true);
            } else {
                textBox.getChildren().remove(detailsLabel);
                detailsLabel.setVisible(false);
                detailsLabel.setManaged(false);
            }

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

        ConnectionEntry entry = mgr.getSelectedConnectionEntry();
        if (entry != null && entry.isConnected()) {
            newState = mgr.getDeviceState(entry.getId());
            newHandler = mgr.getProtocolHandler(entry.getId());
            newConnId = entry.getId();
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

    private static boolean containsIgnoreCase(String text, String query) {
        return text != null && query != null && text.toLowerCase(Locale.ROOT).contains(query);
    }
}
