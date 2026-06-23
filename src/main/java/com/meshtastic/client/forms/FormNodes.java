package com.meshtastic.client.forms;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meshtastic.client.components.NodeDetailContent;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.rpc.RemoteNodeJson;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.FavoriteNodeService;
import com.meshtastic.client.service.IgnoredNodeService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.AllForms;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.system.FormManager;
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
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.IntConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@SystemForm(name = "Ноды", description = "Список нод в сети", tags = {"ноды", "nodes", "устройства", "mesh"})
public class FormNodes extends Form {

    private static final String WINDOWS_HIT_TEST_BACKGROUND = "-fx-background-color: rgba(0,0,0,0.004);";
    private static final Duration REMOTE_RPC_TIMEOUT = Duration.ofSeconds(15);

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
    private boolean allowMultipleSelectionChange;
    private Integer selectionAnchorNodeNum;
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
    private RemoteRpcState remoteRpcState;
    private String remoteOwnerNodeId = "";
    private final Map<String, Boolean> remoteFavoriteFlags = new HashMap<>();
    private final Map<String, Boolean> remoteIgnoredFlags = new HashMap<>();

    private final IntConsumer nodeUpdateListener = num -> Platform.runLater(() -> {
        if (state == null) { return; }
        NodeData node = state.getNodeDb().get(num);

        // The node was removed from nodeDb; remove it from the list and clear details.
        if (node == null) {
            Set<Integer> selectedBeforeRemoval = selectedNodeNums();
            selectedBeforeRemoval.remove(num);
            nodeData.removeIf(n -> n.getNodeNum() == num);
            restoreSelection(selectedBeforeRemoval);
            if (num == currentDetailNodeNum) {
                showDetail(null);
            }
            return;
        }

        for (int i = 0; i < nodeData.size(); i++) {
            if (nodeData.get(i).getNodeNum() == num) {
                Set<Integer> selectedBeforeUpdate = selectedNodeNums();
                suppressSelectionListener = true;
                try {
                    nodeData.set(i, node);
                } finally {
                    suppressSelectionListener = false;
                }
                restoreSelection(selectedBeforeUpdate);
                // Update the right panel only for the actually selected node.
                // Frequent updates for other nodes would otherwise disturb the detail table during layout/render.
                if (num == currentDetailNodeNum) {
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
                (ListChangeListener<NodeData>) change -> {
                    enforceExplicitBulkSelection();
                    updateBulkActionBarState();
                });

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
        clearSelectionBtn.setOnAction(e -> clearNodeSelection());

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
        } else {
            favFilterBtn.getStyleClass().remove("favorite-btn-active");
            favFilterBtn.getTooltip().setText(I18n.t("node.filter.favoriteOnly"));
            if (remoteRpcState == null) {
                removeOfflineNodes();
            }
        }
        if (remoteRpcState != null) {
            reloadList();
        } else {
            injectOfflineFilteredNodes();
            updateFilterPredicate();
        }
    }

    /** Synchronizes ignored-filter state and applies the filter. */
    private void syncIgnoredState() {
        if (!showIgnoredOnly && remoteRpcState == null) {
            removeOfflineNodes();
        }
        if (remoteRpcState != null) {
            reloadList();
        } else {
            injectOfflineFilteredNodes();
            updateFilterPredicate();
        }
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
        String ownerNodeId = currentOwnerNodeId();
        if (ownerNodeId.isBlank()) { return; }
        List<NodeData> dbFavorites = NodeCacheService.getInstance().loadFavoriteNodes(ownerNodeId);
        Set<Integer> existingNums = new HashSet<>();
        for (NodeData n : nodeData) { existingNums.add(n.getNodeNum()); }
        for (NodeData dbNode : dbFavorites) {
            if (existingNums.add(dbNode.getNodeNum())) {
                nodeData.add(dbNode);
            }
        }
    }

    /** Loads ignored nodes from the database when they are absent from the live list. */
    private void injectOfflineIgnored() {
        String ownerNodeId = currentOwnerNodeId();
        if (ownerNodeId.isBlank()) { return; }
        List<NodeData> dbIgnored = NodeCacheService.getInstance().loadIgnoredNodes(ownerNodeId);
        Set<Integer> existingNums = new HashSet<>();
        for (NodeData n : nodeData) { existingNums.add(n.getNodeNum()); }
        for (NodeData dbNode : dbIgnored) {
            if (existingNums.add(dbNode.getNodeNum())) {
                nodeData.add(dbNode);
            }
        }
    }

    /** Loads cached nodes needed by filters that can include nodes absent from DeviceState. */
    private void injectOfflineFilteredNodes() {
        if (showFavoritesOnly) {
            injectOfflineFavorites();
        }
        if (showIgnoredOnly) {
            injectOfflineIgnored();
        }
    }

    /** Removes nodes absent from DeviceState, typically nodes loaded from the database. */
    private void removeOfflineNodes() {
        if (state == null) {
            nodeData.clear();
            return;
        }
        Set<Integer> liveNums = state.getNodeDb().keySet();
        nodeData.removeIf(n -> !liveNums.contains(n.getNodeNum()));
    }

    private void updateFilterPredicate() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        boolean hasQuery = !query.isEmpty();
        FavoriteNodeService favService = FavoriteNodeService.getInstance();
        IgnoredNodeService ignService = IgnoredNodeService.getInstance();
        String ownerNodeId = currentOwnerNodeId();
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
            if (showFavoritesOnly && !isFavoriteNode(node, favService, ownerNodeId)) {
                return false;
            }
        // Direct-only filter for 0-hop nodes.
            if (showDirectOnly && !node.isDirectNeighbor()) {
                return false;
            }
        // Ignored-only filter.
            if (showIgnoredOnly && !isIgnoredNode(node, ignService, ownerNodeId)) {
                return false;
            }
            return true;
        });
        countBadge.setText(String.valueOf(filteredNodes.size()));
    }

    private boolean isFavoriteNode(NodeData node, FavoriteNodeService service, String ownerNodeId) {
        if (node == null || node.getNodeId() == null) {
            return false;
        }
        if (remoteRpcState != null) {
            return remoteFavoriteFlags.getOrDefault(node.getNodeId(), false);
        }
        return !ownerNodeId.isBlank() && service.isFavorite(node.getNodeId(), ownerNodeId);
    }

    private boolean isIgnoredNode(NodeData node, IgnoredNodeService service, String ownerNodeId) {
        if (node == null || node.getNodeId() == null) {
            return false;
        }
        if (remoteRpcState != null) {
            return remoteIgnoredFlags.getOrDefault(node.getNodeId(), false);
        }
        return !ownerNodeId.isBlank() && service.isIgnored(node.getNodeId(), ownerNodeId);
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
            bulkDeleteBtn.setDisable((state == null && remoteRpcState == null) || selectedCount == 0);
        }
    }

    private List<NodeData> selectedNodes() {
        if (nodeListView == null) { return List.of(); }
        return normalizedNodes(nodeListView.getSelectionModel().getSelectedItems());
    }

    private void enforceExplicitBulkSelection() {
        if (nodeListView == null) { return; }
        List<NodeData> selected = selectedNodes();
        if (allowMultipleSelectionChange || selected.size() <= 1) {
            return;
        }

        NodeData selectedItem = nodeListView.getSelectionModel().getSelectedItem();
        NodeData nodeToKeep = selectedItem != null ? selectedItem : selected.getLast();
        int nodeIndex = nodeListView.getItems().indexOf(nodeToKeep);

        allowMultipleSelectionChange = true;
        try {
            if (nodeIndex >= 0) {
                selectionAnchorNodeNum = nodeToKeep.getNodeNum();
                nodeListView.getSelectionModel().clearAndSelect(nodeIndex);
                nodeListView.getFocusModel().focus(nodeIndex);
            } else {
                clearNodeSelection();
            }
        } finally {
            allowMultipleSelectionChange = false;
        }
    }

    private void clearNodeSelection() {
        selectionAnchorNodeNum = null;
        nodeListView.getSelectionModel().clearSelection();
    }

    private void handleNodeCellMousePressed(NodeListCell cell, MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) { return; }

        if (cell.isEmpty() || cell.getItem() == null) {
            event.consume();
            return;
        }

        selectNodeFromUserClick(cell.getItem(), event);
        event.consume();
    }

    private void selectNodeFromUserClick(NodeData node, MouseEvent event) {
        int nodeIndex = nodeListView.getItems().indexOf(node);
        if (nodeIndex < 0) { return; }

        if (event.isShiftDown()) {
            selectNodeRange(nodeIndex);
        } else if (isShortcutSelectionClick(event)) {
            toggleNodeSelection(nodeIndex, node);
        } else {
            selectSingleNode(nodeIndex, node);
        }
    }

    private static boolean isShortcutSelectionClick(MouseEvent event) {
        return event.isShortcutDown() || event.isControlDown() || event.isMetaDown();
    }

    private void selectSingleNode(int nodeIndex, NodeData node) {
        selectionAnchorNodeNum = node.getNodeNum();
        nodeListView.getSelectionModel().clearAndSelect(nodeIndex);
        nodeListView.getFocusModel().focus(nodeIndex);
    }

    private void toggleNodeSelection(int nodeIndex, NodeData node) {
        selectionAnchorNodeNum = node.getNodeNum();
        MultipleSelectionModel<NodeData> selectionModel = nodeListView.getSelectionModel();
        allowMultipleSelectionChange = true;
        try {
            if (selectionModel.isSelected(nodeIndex)) {
                selectionModel.clearSelection(nodeIndex);
            } else {
                selectionModel.select(nodeIndex);
                nodeListView.getFocusModel().focus(nodeIndex);
            }
        } finally {
            allowMultipleSelectionChange = false;
        }
    }

    private void selectNodeRange(int clickedIndex) {
        int anchorIndex = selectionAnchorIndex()
                .orElseGet(() -> {
                    int selectedIndex = nodeListView.getSelectionModel().getSelectedIndex();
                    return selectedIndex >= 0 ? selectedIndex : clickedIndex;
                });
        if (selectionAnchorNodeNum == null) {
            selectionAnchorNodeNum = nodeListView.getItems().get(anchorIndex).getNodeNum();
        }

        int from = Math.min(anchorIndex, clickedIndex);
        int to = Math.max(anchorIndex, clickedIndex);
        MultipleSelectionModel<NodeData> selectionModel = nodeListView.getSelectionModel();
        allowMultipleSelectionChange = true;
        suppressSelectionListener = true;
        try {
            selectionModel.clearSelection();
            IntStream.rangeClosed(from, to)
                    .filter(index -> index != clickedIndex)
                    .forEach(selectionModel::select);
            selectionModel.select(clickedIndex);
            nodeListView.getFocusModel().focus(clickedIndex);
        } finally {
            suppressSelectionListener = false;
            allowMultipleSelectionChange = false;
        }
        showDetail(nodeListView.getItems().get(clickedIndex));
    }

    private OptionalInt selectionAnchorIndex() {
        if (selectionAnchorNodeNum == null) { return OptionalInt.empty(); }
        ObservableList<NodeData> items = nodeListView.getItems();
        return IntStream.range(0, items.size())
                .filter(index -> items.get(index).getNodeNum() == selectionAnchorNodeNum)
                .findFirst();
    }

    private List<NodeData> contextActionNodes(NodeData contextNode) {
        return Optional.ofNullable(contextNode)
                .map(node -> {
                    List<NodeData> selected = selectedNodes();
                    return selected.size() > 1 && containsNode(selected, node)
                            ? selected
                            : List.of(node);
                })
                .orElseGet(List::of);
    }

    private static List<NodeData> normalizedNodes(Collection<NodeData> nodes) {
        return Optional.ofNullable(nodes).stream()
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                NodeData::getNodeNum,
                                node -> node,
                                (first, ignored) -> first,
                                LinkedHashMap::new),
                        byNodeNum -> List.copyOf(byNodeNum.values())));
    }

    private static boolean containsNode(Collection<NodeData> nodes, NodeData target) {
        return target != null
                && Optional.ofNullable(nodes).stream()
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .anyMatch(node -> node.getNodeNum() == target.getNodeNum());
    }

    private void addFavorites(Collection<NodeData> nodes) {
        if (remoteRpcState != null) {
            setRemoteFlags("node.favorite", normalizedNodes(nodes), true, ignored -> nodeListView.refresh());
            return;
        }
        String ownerNodeId = currentOwnerNodeId();
        if (ownerNodeId.isBlank()) { return; }
        FavoriteNodeService service = FavoriteNodeService.getInstance();
        normalizedNodes(nodes).stream()
                .map(NodeData::getNodeId)
                .forEach(nodeId -> service.addFavorite(nodeId, ownerNodeId));
        nodeListView.refresh();
    }

    private void addIgnored(Collection<NodeData> nodes) {
        if (remoteRpcState != null) {
            setRemoteFlags("node.ignored", normalizedNodes(nodes), true, ignored -> nodeListView.refresh());
            return;
        }
        String ownerNodeId = currentOwnerNodeId();
        if (ownerNodeId.isBlank()) { return; }
        IgnoredNodeService service = IgnoredNodeService.getInstance();
        normalizedNodes(nodes).stream()
                .map(NodeData::getNodeId)
                .forEach(nodeId -> service.addIgnored(nodeId, ownerNodeId));
        nodeListView.refresh();
    }

    private void deleteNodesWithConfirmation(Collection<NodeData> nodes) {
        List<NodeData> targets = normalizedNodes(nodes);
        if (targets.isEmpty() || (state == null && remoteRpcState == null)) { return; }

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
        if (remoteRpcState != null) {
            deleteRemoteNodes(normalizedTargets);
            return;
        }

        Set<Integer> deletedNodeNums = normalizedTargets.stream()
                .map(NodeData::getNodeNum)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Integer> remainingSelection = selectedNodeNums();
        remainingSelection.removeAll(deletedNodeNums);
        Optional.ofNullable(state).ifPresent(currentState ->
                deletedNodeNums.forEach(currentState::removeNode));
        String ownerNodeId = state != null ? state.getOwnerNodeId() : null;
        NodeCacheService nodeCache = NodeCacheService.getInstance();
        normalizedTargets.stream()
                .map(NodeData::getNodeId)
                .forEach(nodeId -> nodeCache.deleteNode(nodeId, ownerNodeId));

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
        return selectedNodes().stream()
                .map(NodeData::getNodeNum)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void restoreSelection(Set<Integer> nodeNums) {
        allowMultipleSelectionChange = true;
        suppressSelectionListener = true;
        try {
            nodeListView.getSelectionModel().clearSelection();
            Optional.ofNullable(nodeNums)
                    .filter(nums -> !nums.isEmpty())
                    .ifPresent(nums -> nodeListView.getItems().stream()
                            .filter(node -> nums.contains(node.getNodeNum()))
                            .forEach(nodeListView.getSelectionModel()::select));
            normalizeSelectionAnchor(selectedNodeNums());
        } finally {
            suppressSelectionListener = false;
            allowMultipleSelectionChange = false;
        }
    }

    private void normalizeSelectionAnchor(Set<Integer> selectedNodeNums) {
        if (selectedNodeNums == null || selectedNodeNums.isEmpty()) {
            selectionAnchorNodeNum = null;
        } else if (selectionAnchorNodeNum == null || !selectedNodeNums.contains(selectionAnchorNodeNum)) {
            selectionAnchorNodeNum = selectedNodeNums.iterator().next();
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

    private String currentOwnerNodeId() {
        if (remoteRpcState != null && remoteOwnerNodeId != null && !remoteOwnerNodeId.isBlank()) {
            return remoteOwnerNodeId;
        }
        if (state != null && state.getOwnerNodeId() != null) {
            return state.getOwnerNodeId();
        }
        ConnectionEntry entry = ConnectionManager.getInstance().getSelectedConnectionEntry();
        if (entry == null) {
            return "";
        }
        String ownerNodeId = ConnectionManager.getInstance().getOwnerNodeId(entry.getId());
        return ownerNodeId != null && !ownerNodeId.isBlank() && !"?".equals(ownerNodeId) ? ownerNodeId : "";
    }

    private NodeDetailContent.ActionDelegate remoteNodeActionDelegate() {
        return new NodeDetailContent.ActionDelegate() {
            @Override
            public boolean isFavorite(String nodeId) {
                return nodeId != null && remoteFavoriteFlags.getOrDefault(nodeId, false);
            }

            @Override
            public boolean isIgnored(String nodeId) {
                return nodeId != null && remoteIgnoredFlags.getOrDefault(nodeId, false);
            }

            @Override
            public void openDirectChat(NodeData node) {
                openRemoteDirectChat(node);
            }

            @Override
            public void refreshNode(NodeData node) {
                callRemoteNodeAction("node.refresh", RemoteNodeJson.nodeParams(node), ignored -> reloadList());
            }

            @Override
            public void deleteNode(NodeData node) {
                deleteRemoteNodes(List.of(node));
            }

            @Override
            public void setFavorite(NodeData node, boolean favorite, Consumer<Boolean> callback) {
                setRemoteFlag("node.favorite", node, favorite, callback);
            }

            @Override
            public void setIgnored(NodeData node, boolean ignored, Consumer<Boolean> callback) {
                setRemoteFlag("node.ignored", node, ignored, callback);
            }
        };
    }

    private void openRemoteDirectChat(NodeData node) {
        if (node == null || node.getNodeId() == null || node.getNodeId().isBlank()) {
            return;
        }
        FormChat formChat = (FormChat) AllForms.getForm(FormChat.class);
        FormManager.showForm(formChat);
        formChat.openDirectChat(node.getNodeId(), node);
    }

    private void setRemoteFlag(String method, NodeData node, boolean enabled, Consumer<Boolean> callback) {
        if (node == null) {
            return;
        }
        callRemoteNodeAction(method, RemoteNodeJson.flagParams(node, enabled), result -> {
            updateRemoteFlagMap(method, node.getNodeId(), enabled);
            Optional.ofNullable(callback).ifPresent(cb -> cb.accept(enabled));
            reloadList();
        });
    }

    private void setRemoteFlags(String method,
                                Collection<NodeData> nodes,
                                boolean enabled,
                                Consumer<Boolean> callback) {
        List<NodeData> targets = normalizedNodes(nodes);
        if (targets.isEmpty() || remoteRpcState == null) {
            return;
        }
        RemoteRpcState rpcState = remoteRpcState;
        CompletableFuture<?>[] calls = targets.stream()
                .map(node -> rpcState.client().call(method, RemoteNodeJson.flagParams(node, enabled), REMOTE_RPC_TIMEOUT))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(calls).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (rpcState != remoteRpcState) {
                return;
            }
            if (error != null) {
                Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", errorMessage(error)));
                return;
            }
            targets.forEach(node -> updateRemoteFlagMap(method, node.getNodeId(), enabled));
            Optional.ofNullable(callback).ifPresent(cb -> cb.accept(enabled));
            reloadList();
        }));
    }

    private void updateRemoteFlagMap(String method, String nodeId, boolean enabled) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        if ("node.favorite".equals(method)) {
            remoteFavoriteFlags.put(nodeId, enabled);
        } else if ("node.ignored".equals(method)) {
            remoteIgnoredFlags.put(nodeId, enabled);
        }
    }

    private void deleteRemoteNodes(Collection<NodeData> nodes) {
        List<NodeData> targets = normalizedNodes(nodes);
        if (targets.isEmpty() || remoteRpcState == null) {
            return;
        }
        RemoteRpcState rpcState = remoteRpcState;
        CompletableFuture<?>[] calls = targets.stream()
                .map(node -> rpcState.client().call("node.delete", RemoteNodeJson.nodeParams(node), REMOTE_RPC_TIMEOUT))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(calls).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (rpcState != remoteRpcState) {
                return;
            }
            if (error != null) {
                Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", errorMessage(error)));
                return;
            }
            Set<Integer> deletedNodeNums = targets.stream()
                    .map(NodeData::getNodeNum)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            nodeData.removeIf(node -> deletedNodeNums.contains(node.getNodeNum()));
            remoteFavoriteFlags.keySet().removeIf(nodeId -> targets.stream()
                    .anyMatch(node -> Objects.equals(node.getNodeId(), nodeId)));
            remoteIgnoredFlags.keySet().removeIf(nodeId -> targets.stream()
                    .anyMatch(node -> Objects.equals(node.getNodeId(), nodeId)));
            if (deletedNodeNums.contains(currentDetailNodeNum)) {
                showDetail(nodeListView.getSelectionModel().getSelectedItem());
            }
            updateFilterPredicate();
            updateBulkActionBarState();
            reloadList();
        }));
    }

    private void callRemoteNodeAction(String method, JsonObject params, Consumer<JsonElement> onSuccess) {
        RemoteRpcState rpcState = remoteRpcState;
        if (rpcState == null) {
            return;
        }
        rpcState.client().call(method, params, REMOTE_RPC_TIMEOUT)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (rpcState != remoteRpcState) {
                        return;
                    }
                    if (error != null) {
                        Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", errorMessage(error)));
                        return;
                    }
                    if (onSuccess != null) {
                        onSuccess.accept(result);
                    }
                }));
    }

    private static String errorMessage(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            Throwable cause = current.getCause();
            if (cause == null) {
                break;
            }
            current = cause;
        }
        String message = current != null ? current.getMessage() : null;
        return message == null || message.isBlank() ? String.valueOf(error) : message;
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
            addEventFilter(MouseEvent.MOUSE_PRESSED, event -> handleNodeCellMousePressed(this, event));
            addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    event.consume();
                }
            });

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
	                String ownerNodeId = currentOwnerNodeId();
	                boolean fav = isFavoriteNode(nd, FavoriteNodeService.getInstance(), ownerNodeId);
	                boolean ign = isIgnoredNode(nd, IgnoredNodeService.getInstance(), ownerNodeId);

                addFavItem.setText(I18n.t(bulk ? "node.menu.addSelectedFavorite" : "node.menu.addFavorite"));
                addIgnItem.setText(I18n.t(bulk ? "node.menu.addSelectedIgnored" : "node.menu.addIgnored"));
                deleteItem.setText(I18n.t(bulk ? "node.menu.deleteSelected" : "node.action.delete"));

                addFavItem.setVisible(bulk || !fav);
                removeFavItem.setVisible(!bulk && fav);
                addIgnItem.setVisible(bulk || !ign);
                removeIgnItem.setVisible(!bulk && ign);
	                deleteItem.setVisible(!targets.isEmpty() && (state != null || remoteRpcState != null));
                favoriteSeparator.setVisible(addIgnItem.isVisible() || removeIgnItem.isVisible());
                deleteSeparator.setVisible(deleteItem.isVisible());
            });

            addFavItem.setOnAction(ev -> {
                NodeData nd = getItem();
                addFavorites(contextActionNodes(nd));
            });

	            removeFavItem.setOnAction(ev -> {
	                NodeData nd = getItem();
	                if (remoteRpcState != null) {
	                    setRemoteFlags("node.favorite", contextActionNodes(nd), false, ignored -> nodeListView.refresh());
	                } else {
	                    String ownerNodeId = currentOwnerNodeId();
	                    if (nd != null && !ownerNodeId.isBlank()) {
	                        FavoriteNodeService.getInstance().removeFavorite(nd.getNodeId(), ownerNodeId);
	                    }
	                }
	            });

            addIgnItem.setOnAction(ev -> {
                NodeData nd = getItem();
                addIgnored(contextActionNodes(nd));
            });

	            removeIgnItem.setOnAction(ev -> {
	                NodeData nd = getItem();
	                if (remoteRpcState != null) {
	                    setRemoteFlags("node.ignored", contextActionNodes(nd), false, ignored -> nodeListView.refresh());
	                } else {
	                    String ownerNodeId = currentOwnerNodeId();
	                    if (nd != null && !ownerNodeId.isBlank()) {
	                        IgnoredNodeService.getInstance().removeIgnored(nd.getNodeId(), ownerNodeId);
	                    }
	                }
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
            String ownerNodeId = currentOwnerNodeId();
	            boolean isFav = isFavoriteNode(node, FavoriteNodeService.getInstance(), ownerNodeId);
	            starPane.setVisible(isFav);
            starPane.setManaged(isFav);

            setGraphic(root);
            setText(null);
        }
    }

    // ==================== Detail panel ====================

    private void showDetail(NodeData node) {
        Optional.ofNullable(node).ifPresentOrElse(this::showNodeDetail, this::showDetailPlaceholder);
    }

    private void showDetailPlaceholder() {
        detachCurrentDetailContent();
        currentDetailNodeNum = 0;
        detailPane.getChildren().setAll(detailPlaceholder);
    }

    private void showNodeDetail(NodeData node) {
        if (remoteRpcState == null && !node.hasName()) {
            NodeCacheService.getInstance().enrichFromCache(node);
        }

        if (isCurrentDetailNode(node)) {
            currentDetailContent.updateTableData(node);
            return;
        }

        detachCurrentDetailContent();
        currentDetailNodeNum = node.getNodeNum();
        currentDetailContent = new NodeDetailContent(
                state,
                node,
                protocolHandler,
                null,
                remoteRpcState != null ? remoteNodeActionDelegate() : null);
        VBox.setVgrow(currentDetailContent, Priority.ALWAYS);

        detailPane.getChildren().setAll(createDetailScrollPane(currentDetailContent));
    }

    private boolean isCurrentDetailNode(NodeData node) {
        return currentDetailContent != null && node.getNodeNum() == currentDetailNodeNum;
    }

    private ScrollPane createDetailScrollPane(NodeDetailContent content) {
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPannable(true);
        scrollPane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        scrollPane.getStyleClass().addAll("node-detail-scroll-pane", "edge-to-edge");
        if (content.minHeightProperty().isBound()) {
            content.minHeightProperty().unbind();
        }
        content.minHeightProperty().bind(scrollPane.viewportBoundsProperty()
                .map(bounds -> bounds.getHeight()));
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        return scrollPane;
    }

    private void detachCurrentDetailContent() {
        if (currentDetailContent == null) {
            return;
        }
        currentDetailContent.getChartPanel().unbind();
        if (currentDetailContent.minHeightProperty().isBound()) {
            currentDetailContent.minHeightProperty().unbind();
        }
        currentDetailContent = null;
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
        RemoteRpcState newRemoteRpcState = null;

        ConnectionEntry entry = mgr.getSelectedConnectionEntry();
        if (entry != null && entry.isConnected()) {
            ProtocolRuntime<?> runtime = mgr.getProtocolRuntime(entry.getId());
            if (runtime != null && runtime.getState() instanceof RemoteRpcState remoteState) {
                newRemoteRpcState = remoteState;
            } else {
                newState = mgr.getDeviceState(entry.getId());
                newHandler = mgr.getProtocolHandler(entry.getId());
            }
        }

        if (newState == this.state && newRemoteRpcState == this.remoteRpcState) {
            reloadList();
            return;
        }

        if (this.state != null) {
            this.state.removeNodeUpdateListener(nodeUpdateListener);
        }

        this.state = newState;
        this.protocolHandler = newHandler;
        this.remoteRpcState = newRemoteRpcState;
        this.remoteOwnerNodeId = "";
        this.remoteFavoriteFlags.clear();
        this.remoteIgnoredFlags.clear();

        if (this.state != null) {
            this.state.addNodeUpdateListener(nodeUpdateListener);
        }

        reloadList();
    }

    private void reloadList() {
        Set<Integer> selectedNodeNums = selectedNodeNums();
        if (remoteRpcState != null) {
            reloadRemoteList(selectedNodeNums);
            return;
        }

        // Suppress the listener so setAll() -> null selection does not close details.
        suppressSelectionListener = true;
        try {
            if (state != null) {
                nodeData.setAll(state.getNodeDb().values());
            } else {
                nodeData.clear();
            }
            injectOfflineFilteredNodes();
            updateFilterPredicate();

            restoreSelection(selectedNodeNums);
        } finally {
            suppressSelectionListener = false;
            updateBulkActionBarState();
        }
    }

    private void reloadRemoteList(Set<Integer> selectedNodeNums) {
        RemoteRpcState rpcState = remoteRpcState;
        if (rpcState == null) {
            return;
        }
        rpcState.client()
                .call("node.list",
                        RemoteNodeJson.listParams(showFavoritesOnly, showIgnoredOnly),
                        REMOTE_RPC_TIMEOUT)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (rpcState != remoteRpcState) {
                        return;
                    }
                    if (error != null) {
                        Toast.show(Toast.Type.ERROR, I18n.t("chat.remote.error", errorMessage(error)));
                        return;
                    }
                    applyRemoteNodeSnapshot(result, selectedNodeNums);
                }));
    }

    private void applyRemoteNodeSnapshot(JsonElement result, Set<Integer> selectedNodeNums) {
        suppressSelectionListener = true;
        try {
            remoteOwnerNodeId = RemoteNodeJson.ownerNodeId(result);
            remoteFavoriteFlags.clear();
            remoteFavoriteFlags.putAll(RemoteNodeJson.parseFavoriteFlags(result));
            remoteIgnoredFlags.clear();
            remoteIgnoredFlags.putAll(RemoteNodeJson.parseIgnoredFlags(result));
            nodeData.setAll(RemoteNodeJson.parseNodes(result));
            updateFilterPredicate();
            restoreSelection(selectedNodeNums);
            refreshDetail();
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
