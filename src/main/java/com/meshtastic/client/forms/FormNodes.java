package com.meshtastic.client.forms;

import com.meshtastic.client.components.NodeDetailContent;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
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
import com.meshtastic.client.utils.BatteryLevelEstimator;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.SystemForm;
import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
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
    private HBox bulkActionBar;
    private Label bulkSelectionLabel;
    private Button bulkDeleteBtn;

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

    private final IntConsumer nodeUpdateListener = num -> Platform.runLater(() -> {
        if (state == null) { return; }
        NodeData node = state.getNodeDb().get(num);

            // The node was removed from nodeDb; remove it from the list and clear details.
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
            // Update the right panel only for the actually selected node.
            // Frequent updates for other nodes would otherwise disturb the detail table during layout/render.
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

        // --- Left Panel: Search and List ---
        VBox leftPane = new VBox();
        leftPane.getStyleClass().add("node-list-pane");
        if (OsDetect.isWindows() && !AppPreferences.isDisableEffectsEffective()) {
            leftPane.setStyle(WINDOWS_HIT_TEST_BACKGROUND);
        }

        searchField = new TextField();
        searchField.setPromptText(I18n.t("node.search.placeholder"));
        searchField.getStyleClass().add("node-search-field");

        // Favorites-only filter button.
        SVGPath favFilterIcon = SvgIconLoader.load("/icons/favorite.svg", 16);
        favFilterBtn = new Button();
        favFilterBtn.setGraphic(favFilterIcon);
        favFilterBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        favFilterBtn.getStyleClass().add("chat-new-btn");
        favFilterBtn.setTooltip(new Tooltip(I18n.t("node.filter.favoriteOnly")));
        favFilterBtn.setOnAction(e -> {
            showFavoritesOnly = !showFavoritesOnly;
            syncFavoritesState();
            AppPreferences.setNodesFilterFavorites(showFavoritesOnly);
            if (filterFavorites != null) { filterFavorites.setSelected(showFavoritesOnly); }
        });

        // Sort and filter button.
        SVGPath sortIcon = SvgIconLoader.load("/icons/sort.svg", 16);
        Button sortBtn = new Button();
        sortBtn.setGraphic(sortIcon);
        sortBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        sortBtn.getStyleClass().add("chat-new-btn");
        sortBtn.setTooltip(new Tooltip(I18n.t("node.sortAndFilters")));

        // --- Sorting ---
        ToggleGroup sortGroup = new ToggleGroup();
        RadioMenuItem sortLastHeardNew = new RadioMenuItem(I18n.t("node.sort.lastHeardNew"));
        RadioMenuItem sortLastHeardOld = new RadioMenuItem(I18n.t("node.sort.lastHeardOld"));
        RadioMenuItem sortDistance = new RadioMenuItem(I18n.t("node.sort.distance"));
        RadioMenuItem sortSignal = new RadioMenuItem(I18n.t("node.sort.signal"));
        RadioMenuItem sortHops = new RadioMenuItem(I18n.t("node.sort.hops"));
        RadioMenuItem sortChannel = new RadioMenuItem(I18n.t("node.sort.channel"));
        sortLastHeardNew.setToggleGroup(sortGroup);
        sortLastHeardOld.setToggleGroup(sortGroup);
        sortDistance.setToggleGroup(sortGroup);
        sortSignal.setToggleGroup(sortGroup);
        sortHops.setToggleGroup(sortGroup);
        sortChannel.setToggleGroup(sortGroup);

        // --- Filters ---
        CheckMenuItem filterUnknown = new CheckMenuItem(I18n.t("node.filter.showUnknown"));
        CheckMenuItem filterDetails = new CheckMenuItem(I18n.t("node.filter.showDetails"));
        CheckMenuItem filterHideOffline = new CheckMenuItem(I18n.t("node.filter.hideOffline"));
        filterFavorites = new CheckMenuItem(I18n.t("node.filter.favoriteOnly"));
        CheckMenuItem filterDirect = new CheckMenuItem(I18n.t("node.filter.directOnly"));
        filterIgnored = new CheckMenuItem(I18n.t("node.filter.ignored"));

        ContextMenu sortMenu = new ContextMenu(
                sortLastHeardNew, sortLastHeardOld, sortDistance, sortSignal, sortHops, sortChannel,
                new SeparatorMenuItem(),
                filterUnknown, filterDetails, filterHideOffline, filterFavorites, filterDirect, filterIgnored
        );
                // Keep the menu open when checkboxes are clicked by reopening it.
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

        // Sort comparators.
        Comparator<NodeData> defaultSort = Comparator.comparingInt(NodeData::getLastHeard).reversed()
                .thenComparing(n -> n.getLongName() != null ? n.getLongName() : "");

        // Map sort keys to RadioMenuItems and Comparators.
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

        // Filter handlers.
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

        // --- Restore Settings from Preferences ---
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

        // Synchronize favFilterBtn.
        if (showFavoritesOnly) {
            favFilterBtn.getStyleClass().add("favorite-btn-active");
            favFilterBtn.getTooltip().setText(I18n.t("node.filter.showAll"));
        }

        // Restore sorting.
        String savedSort = AppPreferences.getNodesSort();
        RadioMenuItem savedSortItem = sortKeyToItem.getOrDefault(savedSort, sortLastHeardNew);
        savedSortItem.setSelected(true);

        HBox searchBox = new HBox(8, searchField, favFilterBtn, sortBtn);
        searchBox.setPadding(new Insets(8));
        searchBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        bulkActionBar = createBulkActionBar();

        filteredNodes = new FilteredList<>(nodeData, n -> true);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> updateFilterPredicate());

        // Initialize comparator from the saved sort mode.
        Comparator<NodeData> initialComparator = resolveComparator(savedSort, defaultSort);
        sortedNodes = new SortedList<>(filteredNodes, initialComparator);

        nodeListView = new ListView<>(sortedNodes);
        nodeListView.getStyleClass().add("node-list-view");
        if (OsDetect.isWindows() && !AppPreferences.isDisableEffectsEffective()) {
            nodeListView.setStyle(WINDOWS_HIT_TEST_BACKGROUND);
        }
        nodeListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        nodeListView.setCellFactory(lv -> new NodeListCell());
        nodeListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldNode, newNode) -> {
                    if (!suppressSelectionListener) { showDetail(newNode); }
                });
        nodeListView.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<NodeData>) change -> updateBulkActionBarState());

        // Node-count badge.
        countBadge = new Label("0");
        countBadge.getStyleClass().add("node-count-badge");
        countBadge.setMouseTransparent(true);
        StackPane.setAlignment(countBadge, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(countBadge, new Insets(0, 12, 12, 0));

        StackPane listWrapper = new StackPane(nodeListView, countBadge);
        VBox.setVgrow(listWrapper, Priority.ALWAYS);

        filteredNodes.addListener((javafx.collections.ListChangeListener<NodeData>) change ->
                countBadge.setText(String.valueOf(filteredNodes.size())));

        leftPane.getChildren().addAll(searchBox, bulkActionBar, listWrapper);

        // --- Right Panel: Details ---
        detailPane = new VBox();
        detailPane.setPadding(Insets.EMPTY);
        detailPane.getStyleClass().add("node-detail-pane");

        detailPlaceholder = new Label(I18n.t("node.placeholder.select"));
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

        // Stretch the SplitPane to the whole form.
        splitPane.prefWidthProperty().bind(widthProperty());
        splitPane.prefHeightProperty().bind(heightProperty());

        // Apply filters on startup.
        updateFilterPredicate();
        updateBulkActionBarState();
    }

    private HBox createBulkActionBar() {
        bulkSelectionLabel = new Label();
        bulkSelectionLabel.getStyleClass().add("node-bulk-selection-label");
        HBox.setHgrow(bulkSelectionLabel, Priority.ALWAYS);

        Button addFavoriteBtn = createBulkActionButton(
                "/icons/favorite.svg",
                I18n.t("node.bulk.addFavorite")
        );
        addFavoriteBtn.getStyleClass().add("favorite-btn-active");
        addFavoriteBtn.setOnAction(e -> addFavorites(selectedNodes()));

        Button addIgnoredBtn = createBulkActionButton(
                "/icons/eye-off.svg",
                I18n.t("node.bulk.addIgnored")
        );
        addIgnoredBtn.getStyleClass().add("ignored-btn-active");
        addIgnoredBtn.setOnAction(e -> addIgnored(selectedNodes()));

        bulkDeleteBtn = createBulkActionButton(
                "/drawer/icon/delete-node.svg",
                I18n.t("node.bulk.delete")
        );
        bulkDeleteBtn.getStyleClass().add("node-bulk-delete-btn");
        bulkDeleteBtn.setOnAction(e -> deleteNodesWithConfirmation(selectedNodes()));

        Button clearSelectionBtn = createBulkActionButton(
                "/icons/close.svg",
                I18n.t("node.bulk.clearSelection")
        );
        clearSelectionBtn.setOnAction(e -> nodeListView.getSelectionModel().clearSelection());

        HBox bar = new HBox(8, bulkSelectionLabel, addFavoriteBtn, addIgnoredBtn, bulkDeleteBtn, clearSelectionBtn);
        bar.setPadding(new Insets(0, 8, 8, 8));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("node-bulk-action-bar");
        bar.setVisible(false);
        bar.setManaged(false);
        return bar;
    }

    private Button createBulkActionButton(String iconResource, String tooltipText) {
        SVGPath icon = SvgIconLoader.load(iconResource, 16);
        Button button = new Button();
        button.setGraphic(icon);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().add("chat-new-btn");
        button.setTooltip(new Tooltip(tooltipText));
        return button;
    }

    /** Returns the comparator for a string sort key. */
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

    /** Synchronizes favFilterBtn visual state and applies the filter. */
    private void syncFavoritesState() {
        if (showFavoritesOnly) {
            favFilterBtn.getStyleClass().add("favorite-btn-active");
            favFilterBtn.getTooltip().setText(I18n.t("node.filter.showAll"));
            injectOfflineFavorites();
        } else {
            favFilterBtn.getStyleClass().remove("favorite-btn-active");
            favFilterBtn.getTooltip().setText(I18n.t("node.filter.favoriteOnly"));
            removeOfflineNodes();
        }
        updateFilterPredicate();
    }

    /** Synchronizes ignored-filter state and applies the filter. */
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

    /** Comparator by distance from the local node. Nodes without GPS are placed last. */
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
        // No local position: fall back to lastHeard DESC.
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

    /** Loads favorite nodes from the database when they are absent from the live list. */
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

    /** Loads ignored nodes from the database when they are absent from the live list. */
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

    /** Removes nodes absent from DeviceState, typically nodes loaded from the database. */
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
        // Apply text search first so unnamed nodes can still be found during search.
            if (!matchesSearchQuery(node, query)) {
                return false;
            }
        // Unknown-node filter for nodes without names.
            if (!includeUnknownNames && !node.hasName() && !hasQuery) {
                return false;
            }
        // Offline-node filter for nodes not heard for more than two hours.
            if (hideOffline && node.getLastHeard() > 0 && (now - node.getLastHeard()) > 7200) {
                return false;
            }
        // Favorites-only filter.
            if (showFavoritesOnly && !favService.isFavorite(node.getNodeId())) {
                return false;
            }
        // Direct-only filter for 0-hop nodes.
            if (showDirectOnly && !node.isDirectNeighbor()) {
                return false;
            }
        // Ignored-only filter.
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

    // ==================== Bulk actions ====================

    private void updateBulkActionBarState() {
        if (bulkActionBar == null || bulkSelectionLabel == null) { return; }

        int selectedCount = selectedNodes().size();
        boolean showBulkActions = selectedCount > 1;
        bulkActionBar.setVisible(showBulkActions);
        bulkActionBar.setManaged(showBulkActions);
        bulkSelectionLabel.setText(selectionCountText(selectedCount));
        if (bulkDeleteBtn != null) {
            bulkDeleteBtn.setDisable(state == null || selectedCount == 0);
        }
    }

    private List<NodeData> selectedNodes() {
        if (nodeListView == null) { return List.of(); }
        return normalizedNodes(nodeListView.getSelectionModel().getSelectedItems());
    }

    private List<NodeData> contextActionNodes(NodeData contextNode) {
        if (contextNode == null) { return List.of(); }

        List<NodeData> selected = selectedNodes();
        if (selected.size() > 1 && containsNode(selected, contextNode)) {
            return selected;
        }
        return List.of(contextNode);
    }

    private static List<NodeData> normalizedNodes(Collection<NodeData> nodes) {
        if (nodes == null || nodes.isEmpty()) { return List.of(); }

        Map<Integer, NodeData> byNodeNum = new LinkedHashMap<>();
        for (NodeData node : nodes) {
            if (node != null) {
                byNodeNum.putIfAbsent(node.getNodeNum(), node);
            }
        }
        return List.copyOf(byNodeNum.values());
    }

    private static boolean containsNode(Collection<NodeData> nodes, NodeData target) {
        if (nodes == null || target == null) { return false; }
        for (NodeData node : nodes) {
            if (node != null && node.getNodeNum() == target.getNodeNum()) {
                return true;
            }
        }
        return false;
    }

    private void addFavorites(Collection<NodeData> nodes) {
        FavoriteNodeService service = FavoriteNodeService.getInstance();
        for (NodeData node : normalizedNodes(nodes)) {
            service.addFavorite(node.getNodeId());
        }
        nodeListView.refresh();
    }

    private void addIgnored(Collection<NodeData> nodes) {
        IgnoredNodeService service = IgnoredNodeService.getInstance();
        for (NodeData node : normalizedNodes(nodes)) {
            service.addIgnored(node.getNodeId());
        }
        nodeListView.refresh();
    }

    private void deleteNodesWithConfirmation(Collection<NodeData> nodes) {
        List<NodeData> targets = normalizedNodes(nodes);
        if (targets.isEmpty() || state == null) { return; }

        String title;
        String message;
        if (targets.size() == 1) {
            title = I18n.t("node.confirm.delete.title");
            message = I18n.t("node.confirm.delete.message", displayName(targets.getFirst()));
        } else {
            title = I18n.t("node.confirm.deleteMany.title");
            message = I18n.t("node.confirm.deleteMany.message", targets.size());
        }

        ModalPane.showConfirm(title, message, confirmed -> {
            if (confirmed) {
                deleteNodes(targets);
            }
        });
    }

    private void deleteNodes(Collection<NodeData> targets) {
        List<NodeData> normalizedTargets = normalizedNodes(targets);
        if (normalizedTargets.isEmpty()) { return; }

        Set<Integer> deletedNodeNums = new HashSet<>();
        Set<Integer> remainingSelection = selectedNodeNums();
        for (NodeData node : normalizedTargets) {
            int nodeNum = node.getNodeNum();
            deletedNodeNums.add(nodeNum);
            remainingSelection.remove(nodeNum);
            if (state != null) {
                state.removeNode(nodeNum);
            }
            NodeCacheService.getInstance().deleteNode(node.getNodeId());
        }

        boolean removedCurrentDetail = deletedNodeNums.contains(currentDetailNodeNum);
        nodeData.removeIf(node -> deletedNodeNums.contains(node.getNodeNum()));
        restoreSelection(remainingSelection);
        if (removedCurrentDetail) {
            showDetail(nodeListView.getSelectionModel().getSelectedItem());
        }
        updateFilterPredicate();
        updateBulkActionBarState();
    }

    private Set<Integer> selectedNodeNums() {
        Set<Integer> nums = new LinkedHashSet<>();
        for (NodeData node : selectedNodes()) {
            nums.add(node.getNodeNum());
        }
        return nums;
    }

    private void restoreSelection(Set<Integer> nodeNums) {
        suppressSelectionListener = true;
        try {
            nodeListView.getSelectionModel().clearSelection();
            if (nodeNums != null && !nodeNums.isEmpty()) {
                for (NodeData node : nodeListView.getItems()) {
                    if (nodeNums.contains(node.getNodeNum())) {
                        nodeListView.getSelectionModel().select(node);
                    }
                }
            }
        } finally {
            suppressSelectionListener = false;
        }
    }

    private static String selectionCountText(int count) {
        return I18n.t("node.bulk.selected." + I18n.pluralCategory(count), count);
    }

    private static String displayName(NodeData node) {
        if (node == null) { return "?"; }
        String value = node.getLongName() != null && !node.getLongName().isEmpty()
                ? node.getLongName()
                : node.getNodeId() != null ? node.getNodeId() : "?";
        return UnicodeTextUtils.sanitizeForJavaFxDisplay(value);
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

        // Circular avatar.
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

        // Favorite star.
            SVGPath starIcon = SvgIconLoader.load("/icons/favorite.svg", 12);
            if (starIcon != null) {
                starPane.getChildren().add(starIcon);
                starPane.getStyleClass().add("node-favorite-star");
            }
            starPane.setMinSize(16, 16);
            starPane.setMaxSize(16, 16);
            starPane.setAlignment(Pos.CENTER);

            root.getChildren().addAll(avatarPane, textBox, starPane);

        // Context menu.
            MenuItem addFavItem = new MenuItem(I18n.t("node.menu.addFavorite"));
            MenuItem removeFavItem = new MenuItem(I18n.t("node.menu.removeFavorite"));
            MenuItem addIgnItem = new MenuItem(I18n.t("node.menu.addIgnored"));
            MenuItem removeIgnItem = new MenuItem(I18n.t("node.menu.removeIgnored"));
            MenuItem deleteItem = new MenuItem(I18n.t("node.action.delete"));
            SeparatorMenuItem favoriteSeparator = new SeparatorMenuItem();
            SeparatorMenuItem deleteSeparator = new SeparatorMenuItem();
            ContextMenu ctxMenu = new ContextMenu(addFavItem, removeFavItem,
                    favoriteSeparator, addIgnItem, removeIgnItem, deleteSeparator, deleteItem);
            setContextMenu(ctxMenu);

            ctxMenu.setOnShowing(ev -> {
                NodeData nd = getItem();
                List<NodeData> targets = contextActionNodes(nd);
                boolean bulk = targets.size() > 1;
                boolean fav = nd != null && FavoriteNodeService.getInstance().isFavorite(nd.getNodeId());
                boolean ign = nd != null && IgnoredNodeService.getInstance().isIgnored(nd.getNodeId());

                addFavItem.setText(I18n.t(bulk ? "node.menu.addSelectedFavorite" : "node.menu.addFavorite"));
                addIgnItem.setText(I18n.t(bulk ? "node.menu.addSelectedIgnored" : "node.menu.addIgnored"));
                deleteItem.setText(I18n.t(bulk ? "node.menu.deleteSelected" : "node.action.delete"));

                addFavItem.setVisible(bulk || !fav);
                removeFavItem.setVisible(!bulk && fav);
                addIgnItem.setVisible(bulk || !ign);
                removeIgnItem.setVisible(!bulk && ign);
                deleteItem.setVisible(!targets.isEmpty() && state != null);
                favoriteSeparator.setVisible(addIgnItem.isVisible() || removeIgnItem.isVisible());
                deleteSeparator.setVisible(deleteItem.isVisible());
            });

            addFavItem.setOnAction(ev -> {
                NodeData nd = getItem();
                addFavorites(contextActionNodes(nd));
            });

            removeFavItem.setOnAction(ev -> {
                NodeData nd = getItem();
                if (nd != null) { FavoriteNodeService.getInstance().removeFavorite(nd.getNodeId()); }
            });

            addIgnItem.setOnAction(ev -> {
                NodeData nd = getItem();
                addIgnored(contextActionNodes(nd));
            });

            removeIgnItem.setOnAction(ev -> {
                NodeData nd = getItem();
                if (nd != null) { IgnoredNodeService.getInstance().removeIgnored(nd.getNodeId()); }
            });

            deleteItem.setOnAction(ev -> {
                NodeData nd = getItem();
                deleteNodesWithConfirmation(contextActionNodes(nd));
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

        // Avatar text: full shortName or the first four characters of the name.
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

            // Substring.
            String subtitle = formatLastHeardRelative(node.getLastHeard());
            if (node.hasHopsAway() && node.getHopsAway() > 0) {
                subtitle += " · " + hopText(node.getHopsAway());
            }
            subtitleLabel.setText(subtitle);

        // Expanded details.
            if (showDetails) {
                StringBuilder sb = new StringBuilder();
                if (node.getSnr() != 0) {
                    sb.append(I18n.t("node.list.snr",
                            String.format(I18n.locale(), "%.1f", node.getSnr()),
                            I18n.t("node.unit.db")));
                }
                if (node.hasHopsAway() && node.getHopsAway() > 0) {
                    if (!sb.isEmpty()) { sb.append(" · "); }
                    sb.append(hopText(node.getHopsAway()));
                }
                if (BatteryLevelEstimator.hasBatteryPercent(node.getBatteryLevel(), node.getVoltage())) {
                    if (!sb.isEmpty()) { sb.append(" · "); }
                    sb.append(I18n.t("node.list.battery",
                            BatteryLevelEstimator.effectivePercent(node.getBatteryLevel(), node.getVoltage())));
                }
                if (node.getVoltage() > 0) {
                    if (!sb.isEmpty()) { sb.append(" · "); }
                    sb.append(String.format(I18n.locale(), "%.1f%s",
                            node.getVoltage(), I18n.t("node.unit.volt")));
                }
                if (node.getChannel() > 0) {
                    if (!sb.isEmpty()) { sb.append(" · "); }
                    sb.append(I18n.t("node.list.channel", node.getChannel()));
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

        // Favorite star.
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

            // Enrich a bare node with cached data before showing it.
        if (!node.hasName()) {
            NodeCacheService.getInstance().enrichFromCache(node);
        }

                // Same node: update only table data without recreating the UI.
        if (node.getNodeNum() == currentDetailNodeNum && currentDetailContent != null) {
            currentDetailContent.updateTableData(node);
            return;
        }

            // Detach the previous component.
        if (currentDetailContent != null) {
            currentDetailContent.getChartPanel().unbind();
        }

        currentDetailNodeNum = node.getNodeNum();
        currentDetailContent = new NodeDetailContent(state, node, protocolHandler);
        VBox.setVgrow(currentDetailContent, Priority.ALWAYS);

        detailPane.getChildren().clear();
        detailPane.getChildren().add(currentDetailContent);
    }

    /** Updates details when the selected node changes, preserving UI and chart filter state. */
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

        ConnectionEntry entry = mgr.getSelectedConnectionEntry();
        if (entry != null && entry.isConnected()) {
            newState = mgr.getDeviceState(entry.getId());
            newHandler = mgr.getProtocolHandler(entry.getId());
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

        if (this.state != null) {
            this.state.addNodeUpdateListener(nodeUpdateListener);
        }

        reloadList();
    }

    private void reloadList() {
        Set<Integer> selectedNodeNums = selectedNodeNums();

        // Suppress the listener so setAll() -> null selection does not close details.
        suppressSelectionListener = true;
        try {
            if (state != null) {
                nodeData.setAll(state.getNodeDb().values());
            } else {
                nodeData.clear();
            }

            nodeListView.getSelectionModel().clearSelection();
            if (!selectedNodeNums.isEmpty()) {
                for (NodeData node : nodeListView.getItems()) {
                    if (selectedNodeNums.contains(node.getNodeNum())) {
                        nodeListView.getSelectionModel().select(node);
                    }
                }
            }
        } finally {
            suppressSelectionListener = false;
            updateBulkActionBarState();
        }
    }

    // ==================== Helpers ====================

    /**
     * Formats relative time: online, minutes ago, hours ago, or days ago.
     */
    private static String formatLastHeardRelative(int epochSeconds) {
        if (epochSeconds <= 0) { return I18n.t("node.status.noData"); }
        long now = System.currentTimeMillis() / 1000;
        long diff = now - epochSeconds;
        if (diff < 60) { return I18n.t("node.status.online"); }
        if (diff < 3600) {
            long min = diff / 60;
            return I18n.t("node.status.lastHeard", min, pluralNodeUnit("minute", min));
        }
        if (diff < 86400) {
            long hours = diff / 3600;
            return I18n.t("node.status.lastHeard", hours, pluralNodeUnit("hour", hours));
        }
        long days = diff / 86400;
        return I18n.t("node.status.lastHeard", days, pluralNodeUnit("day", days));
    }

    private static String hopText(long hops) {
        return hops + " " + pluralNodeUnit("hop", hops);
    }

    private static String pluralNodeUnit(String unit, long value) {
        return I18n.t("node.unit." + unit + "." + pluralSuffix(value));
    }

    private static String pluralSuffix(long n) {
        if (!I18n.LANGUAGE_RU.equals(I18n.locale().getLanguage())) {
            return Math.abs(n) == 1 ? "one" : "many";
        }
        n = Math.abs(n) % 100;
        long n1 = n % 10;
        if (n > 10 && n < 20) { return "many"; }
        if (n1 == 1) { return "one"; }
        if (n1 >= 2 && n1 <= 4) { return "few"; }
        return "many";
    }

    private static boolean containsIgnoreCase(String text, String query) {
        return text != null && query != null && text.toLowerCase(Locale.ROOT).contains(query);
    }
}
