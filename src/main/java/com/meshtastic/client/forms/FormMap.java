package com.meshtastic.client.forms;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.components.NodeDetailPanel;
import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.components.map.MapMarker;
import com.meshtastic.client.components.map.TileMapView;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.FavoriteNodeService;
import com.meshtastic.client.service.IgnoredNodeService;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import org.meshtastic.proto.MeshProtos;

import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps form: displays an OSM map, positioned nodes, filters, search, distance
 * measurement, area selection, offline tiles, and traces.
 * <p>
 * The form connects the application UI to the low-level {@link TileMapView}:
 * it collects nodes from the current {@link DeviceState} and cache, applies
 * filters, parses saved traceroute results, and passes ready marker/segment
 * data to the map.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@SystemForm(name = "Карты", description = "OSM-карта с онлайн/оффлайн тайлами", tags = {"карта", "maps", "osm"})
public class FormMap extends Form {

    private static final int RECENT_TRACE_LIMIT = 20;
    private static final int TRACE_NODE_CACHE_LIMIT = 2_000;
    private static final double DOWNLOAD_PROGRESS_WIDTH = 180;
    private static final double STATUS_LEGEND_WIDTH = 260;
    private static final Pattern TRACE_SEGMENT_PATTERN = Pattern.compile(" →(-?\\d+[.,]\\d+)dB→ | → ");
    private static final Pattern TRACE_NODE_ID_PATTERN = Pattern.compile("!([0-9a-fA-F]{1,8})");
    private static final Pattern TRACE_SECTION_LABEL_PATTERN =
            Pattern.compile("(?iu)^\\s*(?:прямой|обратный|forward|reverse)\\s*:\\s*");

    private final TileMapView mapView = new TileMapView();
    private final Label statusLabel = new Label();
    private final Label pointerLabel = new Label();
    private final Label measureLabel = new Label();
    private final Label areaLabel = new Label();
    private final Label tileDirectoryLabel = new Label();
    private final ProgressBar downloadProgressBar = new ProgressBar(0);
    private final Button downloadPauseButton = new Button();
    private final Button downloadCancelButton = new Button();
    private final TextField searchField = new TextField();
    private final ContextMenu searchSuggestionMenu = new ContextMenu();
    private final Button favoriteFilterButton = new Button();
    private final ToggleButton offlineButton = new ToggleButton(I18n.t("map.offline"));
    private final ToggleButton nightModeButton = new ToggleButton();
    private final ToggleButton measureButton = new ToggleButton();
    private final ToggleButton areaButton = new ToggleButton();
    private final Button myNodeButton = new Button();
    private final Button fitNodesButton = new Button();
    private final Button tracesButton = new Button();
    private final ContextMenu tracesMenu = new ContextMenu();
    private final Button downloadButton = new Button(I18n.t("map.downloadArea"));

    private DeviceState state;
    private int localNodeNum;
    private boolean autoFitPending = !AppPreferences.hasMapView();
    private boolean includeUnknownNames;
    private boolean hideOffline;
    private boolean showFavoritesOnly;
    private boolean showDirectOnly;
    private boolean showIgnoredOnly;
    private CheckMenuItem filterFavorites;
    private CheckMenuItem filterIgnored;
    private List<NodeData> currentSearchMatches = List.of();
    private List<String> currentSearchInsertTexts = List.of();
    private List<CustomMenuItem> currentSearchSuggestionItems = List.of();
    private int selectedSearchSuggestionIndex = -1;
    private TileMapView.DownloadHandle activeDownload;
    private boolean downloadPaused;
    private final Map<Long, ParsedTrace> selectedTraces = new LinkedHashMap<>();
    private List<ParsedTrace> recentTraces = List.of();
    private Map<String, NodeData> currentMarkerNodes = Map.of();
    private boolean suppressSearchSuggestions;

    private final Runnable connectionListener = () -> Platform.runLater(this::rebindState);
    private final IntConsumer nodeUpdateListener = ignored -> Platform.runLater(this::reloadMarkers);
    private final Runnable favoritesListener = () -> Platform.runLater(this::reloadMarkers);
    private final Runnable ignoredListener = () -> Platform.runLater(this::reloadMarkers);

    /**
     * Creates the form and builds the map panel UI immediately.
     */
    public FormMap() {
        initComponents();
    }

    /**
     * Subscribes the form to connection, favorite-node, and ignored-node changes.
     */
    @Override
    public void formInit() {
        ConnectionManager.getInstance().addListener(connectionListener);
        FavoriteNodeService.getInstance().addListener(favoritesListener);
        IgnoredNodeService.getInstance().addListener(ignoredListener);
        rebindState();
    }

    /**
     * Rebinds the active device state when the form is opened.
     */
    @Override
    public void formOpen() {
        rebindState();
    }

    /**
     * Saves the map center and zoom level when the form is closed.
     */
    @Override
    public void formClose() {
        AppPreferences.saveMapView(mapView.getCenterLatitude(), mapView.getCenterLongitude(), mapView.getZoom());
    }

    /**
     * Refreshes markers when the form is manually refreshed.
     */
    @Override
    public void formRefresh() {
        reloadMarkers();
    }

    /**
     * Shows one saved traceroute result on the map.
     *
     * <p>This method is used by external forms that already know the
     * {@code traceroute_results} row id. The previous trace selection is cleared
     * so the map focuses only on the requested route.
     *
     * @param tracerouteResultId id of the {@code traceroute_results} row
     */
    public void showTracerouteResult(long tracerouteResultId) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showTracerouteResult(tracerouteResultId));
            return;
        }

        MessageDbService.getInstance()
                .loadTracerouteResult(tracerouteResultId, currentOwnerNodeId())
                .flatMap(this::parseTraceRecord)
                .ifPresentOrElse(
                        trace -> {
                            selectedTraces.clear();
                            selectedTraces.put(trace.dbId(), trace);
                            syncTracesButton();
                            refreshSelectedTraceOverlay(true);
                            if (tracesMenu.isShowing()) {
                                refreshTracesMenu();
                            }
                        },
                        () -> Toast.show(Toast.Type.WARNING, I18n.t("map.trace.notFound")));
    }

    /**
     * Builds the toolbar, map, and status line, then restores saved map settings:
     * center, zoom, offline mode, night mode, and tile directory.
     */
    private void initComponents() {
        getStyleClass().add("map-form");

        mapView.setView(AppPreferences.getMapCenterLatitude(), AppPreferences.getMapCenterLongitude(), AppPreferences.getMapZoom());
        mapView.setOfflineOnly(AppPreferences.isMapOfflineMode());
        mapView.setNightMode(AppPreferences.isMapNightMode());
        String tileDirectory = AppPreferences.getMapTileDirectory();
        if (tileDirectory != null && !tileDirectory.isBlank()) {
            restoreTileDirectory(tileDirectory);
        }

        mapView.setStatusListener(statusLabel::setText);
        mapView.setPointerListener(point -> pointerLabel.setText(TileMapView.formatCoordinate(point.latitude(), point.longitude())));
        mapView.setMeasureListener(measureLabel::setText);
        mapView.setAreaSelectionListener(areaLabel::setText);
        mapView.setMarkerClickListener(this::showMarkerDetails);

        configureSearchControls();

        offlineButton.setSelected(mapView.isOfflineOnly());
        offlineButton.setTooltip(new Tooltip(I18n.t("map.tooltip.offline")));
        offlineButton.setOnAction(event -> {
            boolean offline = offlineButton.isSelected();
            mapView.setOfflineOnly(offline);
            AppPreferences.setMapOfflineMode(offline);
        });

        configureIconToggleButton(nightModeButton, "/icons/dark.svg", I18n.t("map.tooltip.nightMode"));
        nightModeButton.setSelected(mapView.isNightMode());
        nightModeButton.setOnAction(event -> {
            boolean nightMode = nightModeButton.isSelected();
            mapView.setNightMode(nightMode);
            AppPreferences.setMapNightMode(nightMode);
        });

        configureIconButton(myNodeButton, "/icons/map-my-node.svg", I18n.t("map.tooltip.myNode"));
        myNodeButton.setOnAction(event -> centerOnMyNode());

        configureIconButton(fitNodesButton, "/drawer/icon/nodes.svg", I18n.t("map.tooltip.fitNodes"));
        fitNodesButton.setOnAction(event -> {
            if (!mapView.fitMarkers()) {
                statusLabel.setText(I18n.t("map.status.noNodesWithCoordinates"));
            }
        });

        configureTraceButton();

        configureIconToggleButton(measureButton, "/icons/map-ruler.svg", I18n.t("map.tooltip.measure"));
        measureButton.setTooltip(new Tooltip(I18n.t("map.tooltip.measure")));
        measureButton.setOnAction(event -> {
            boolean measuring = measureButton.isSelected();
            if (measuring) {
                areaButton.setSelected(false);
                mapView.setAreaSelectionMode(false);
            }
            mapView.setMeasuring(measuring);
        });

        configureIconToggleButton(areaButton, "/icons/map-select-area.svg", I18n.t("map.tooltip.area"));
        areaButton.setTooltip(new Tooltip(I18n.t("map.tooltip.area")));
        areaButton.setOnAction(event -> {
            boolean selectingArea = areaButton.isSelected();
            if (selectingArea) {
                measureButton.setSelected(false);
                mapView.setMeasuring(false);
            }
            mapView.setAreaSelectionMode(selectingArea);
        });

        Button clearMeasureButton = new Button(I18n.t("common.reset"));
        clearMeasureButton.setTooltip(new Tooltip(I18n.t("map.tooltip.clearTools")));
        clearMeasureButton.setOnAction(event -> {
            mapView.clearMeasure();
            mapView.clearSelectedArea();
            selectedTraces.clear();
            syncTracesButton();
            refreshSelectedTraceOverlay(false);
            if (tracesMenu.isShowing()) {
                refreshTracesMenu();
            }
            statusLabel.setText(I18n.t("map.status.toolsCleared"));
        });

        Button zoomInButton = iconButton("+", I18n.t("map.zoom.in"));
        zoomInButton.setOnAction(event -> mapView.zoomIn());

        Button zoomOutButton = iconButton("−", I18n.t("map.zoom.out"));
        zoomOutButton.setOnAction(event -> mapView.zoomOut());

        downloadButton.setTooltip(new Tooltip(I18n.t("map.tooltip.downloadArea")));
        downloadButton.setOnAction(event -> downloadSelectedAreaTiles());

        Button tileDirectoryButton = new Button(I18n.t("map.tileDirectory.button"));
        tileDirectoryButton.setTooltip(new Tooltip(I18n.t("map.tooltip.tileDirectory")));
        tileDirectoryButton.setOnAction(event -> chooseTileDirectory());

        Button filterButton = createFilterButton();

        ToolBar toolbar = new ToolBar(
                searchField,
                favoriteFilterButton,
                filterButton,
                zoomInButton,
                zoomOutButton,
                myNodeButton,
                fitNodesButton,
                tracesButton,
                measureButton,
                areaButton,
                clearMeasureButton,
                nightModeButton,
                offlineButton,
                downloadButton,
                tileDirectoryButton
        );
        toolbar.getStyleClass().add("map-toolbar");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        pointerLabel.getStyleClass().add("map-status-label");
        statusLabel.getStyleClass().add("map-status-label");
        statusLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        statusLabel.setMinWidth(STATUS_LEGEND_WIDTH);
        statusLabel.setPrefWidth(STATUS_LEGEND_WIDTH);
        statusLabel.setMaxWidth(STATUS_LEGEND_WIDTH);
        measureLabel.getStyleClass().add("map-status-label");
        areaLabel.getStyleClass().add("map-status-label");
        tileDirectoryLabel.getStyleClass().add("map-status-label");
        tileDirectoryLabel.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        tileDirectoryLabel.setMaxWidth(Double.MAX_VALUE);
        downloadProgressBar.setMinWidth(DOWNLOAD_PROGRESS_WIDTH);
        downloadProgressBar.setPrefWidth(DOWNLOAD_PROGRESS_WIDTH);
        downloadProgressBar.setMaxWidth(DOWNLOAD_PROGRESS_WIDTH);
        configureDownloadControlButton(downloadPauseButton, "/icons/pause.svg", I18n.t("map.tooltip.downloadPause"), "||");
        configureDownloadControlButton(downloadCancelButton, "/icons/close.svg", I18n.t("map.tooltip.downloadCancel"), "x");
        downloadPauseButton.setOnAction(event -> toggleDownloadPause());
        downloadCancelButton.setOnAction(event -> cancelDownload());
        hideDownloadProgress();
        updateTileDirectoryLabel();

        HBox downloadProgressBox = new HBox(4, downloadProgressBar, downloadPauseButton, downloadCancelButton);
        downloadProgressBox.setAlignment(Pos.CENTER_LEFT);

        HBox statusBar = new HBox(
                16,
                downloadProgressBox,
                statusLabel,
                pointerLabel,
                measureLabel,
                areaLabel,
                tileDirectoryLabel
        );
        statusBar.getStyleClass().add("map-status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(tileDirectoryLabel, Priority.ALWAYS);

        VBox content = new VBox(8, toolbar, mapView, statusBar);
        content.setPadding(new Insets(10));
        VBox.setVgrow(mapView, Priority.ALWAYS);
        getChildren().setAll(content);
    }

    /**
     * Creates a compact text button using the map panel style.
     */
    private Button iconButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().add("map-icon-button");
        button.setContentDisplay(ContentDisplay.TEXT_ONLY);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    /**
     * Configures predictive node search on the map and the quick favorites filter.
     */
    private void configureSearchControls() {
        searchField.setPromptText(I18n.t("map.search.placeholder"));
        searchField.getStyleClass().add("map-search-field");
        searchField.setPrefWidth(180);
        searchField.setMaxWidth(260);
        searchSuggestionMenu.getStyleClass().add("map-search-suggestions-menu");
        searchField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!suppressSearchSuggestions) {
                refreshSearchSuggestions();
            }
        });
        searchField.focusedProperty().addListener((obs, oldValue, focused) -> {
            if (!focused) {
                hideSearchSuggestions();
            }
        });
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleSearchKeyPressed);

        configureIconButton(favoriteFilterButton, "/icons/favorite.svg", I18n.t("node.filter.favoriteOnly"));
        favoriteFilterButton.setOnAction(event -> {
            showFavoritesOnly = !showFavoritesOnly;
            AppPreferences.setMapFilterFavorites(showFavoritesOnly);
            if (filterFavorites != null) {
                filterFavorites.setSelected(showFavoritesOnly);
            }
            syncFavoriteFilterButton();
            reloadMarkers();
        });

        includeUnknownNames = AppPreferences.isMapFilterUnknown();
        hideOffline = AppPreferences.isMapFilterHideOffline();
        showFavoritesOnly = AppPreferences.isMapFilterFavorites();
        showDirectOnly = AppPreferences.isMapFilterDirect();
        showIgnoredOnly = AppPreferences.isMapFilterIgnored();
        syncFavoriteFilterButton();
    }

    /**
     * Configures the recent-traces menu button.
     */
    private void configureTraceButton() {
        configureIconButton(tracesButton, "/icons/map-traces.svg", I18n.t("map.traces.recent"));
        tracesMenu.getStyleClass().add("map-traces-menu");
        tracesButton.setOnAction(event -> {
            if (tracesMenu.isShowing()) {
                tracesMenu.hide();
            } else {
                refreshTracesMenu();
                tracesMenu.show(tracesButton, javafx.geometry.Side.BOTTOM, 0, 0);
            }
        });
        syncTracesButton();
    }

    /**
     * Creates the map-specific node-filter menu.
     */
    private Button createFilterButton() {
        Button filterButton = new Button();
        configureIconButton(filterButton, "/icons/sort.svg", I18n.t("map.filter.button"));

        CheckMenuItem filterUnknown = new CheckMenuItem(I18n.t("node.filter.showUnknown"));
        CheckMenuItem filterHideOffline = new CheckMenuItem(I18n.t("node.filter.hideOffline"));
        filterFavorites = new CheckMenuItem(I18n.t("node.filter.favoriteOnly"));
        CheckMenuItem filterDirect = new CheckMenuItem(I18n.t("node.filter.directOnly"));
        filterIgnored = new CheckMenuItem(I18n.t("node.filter.ignored"));

        Map<CheckMenuItem, Runnable> actions = new LinkedHashMap<>();
        actions.put(filterUnknown, () -> {
            includeUnknownNames = filterUnknown.isSelected();
            AppPreferences.setMapFilterUnknown(includeUnknownNames);
        });
        actions.put(filterHideOffline, () -> {
            hideOffline = filterHideOffline.isSelected();
            AppPreferences.setMapFilterHideOffline(hideOffline);
        });
        actions.put(filterFavorites, () -> {
            showFavoritesOnly = filterFavorites.isSelected();
            AppPreferences.setMapFilterFavorites(showFavoritesOnly);
            syncFavoriteFilterButton();
        });
        actions.put(filterDirect, () -> {
            showDirectOnly = filterDirect.isSelected();
            AppPreferences.setMapFilterDirect(showDirectOnly);
        });
        actions.put(filterIgnored, () -> {
            showIgnoredOnly = filterIgnored.isSelected();
            AppPreferences.setMapFilterIgnored(showIgnoredOnly);
        });

        filterUnknown.setSelected(includeUnknownNames);
        filterHideOffline.setSelected(hideOffline);
        filterFavorites.setSelected(showFavoritesOnly);
        filterDirect.setSelected(showDirectOnly);
        filterIgnored.setSelected(showIgnoredOnly);

        ContextMenu filterMenu = new ContextMenu(
                filterUnknown,
                filterHideOffline,
                filterFavorites,
                filterDirect,
                new SeparatorMenuItem(),
                filterIgnored
        );

        for (MenuItem item : filterMenu.getItems()) {
            if (item instanceof CheckMenuItem checkItem) {
                checkItem.setOnAction(event -> {
                    Runnable action = actions.get(checkItem);
                    if (action != null) {
                        action.run();
                    }
                    reloadMarkers();
                    Platform.runLater(() -> {
                        if (!filterMenu.isShowing()) {
                            filterMenu.show(filterButton, javafx.geometry.Side.BOTTOM, 0, 0);
                        }
                    });
                });
            }
        }

        filterButton.setOnAction(event -> {
            if (filterMenu.isShowing()) {
                filterMenu.hide();
            } else {
                filterMenu.show(filterButton, javafx.geometry.Side.BOTTOM, 0, 0);
            }
        });

        return filterButton;
    }

    /**
     * Synchronizes the visual state of the Favorites Only button.
     */
    private void syncFavoriteFilterButton() {
        if (showFavoritesOnly) {
            if (!favoriteFilterButton.getStyleClass().contains("favorite-btn-active")) {
                favoriteFilterButton.getStyleClass().add("favorite-btn-active");
            }
            favoriteFilterButton.getTooltip().setText(I18n.t("map.filter.showAllNodes"));
        } else {
            favoriteFilterButton.getStyleClass().remove("favorite-btn-active");
            favoriteFilterButton.getTooltip().setText(I18n.t("node.filter.favoriteOnly"));
        }
    }

    /**
     * Reloads recent saved traceroute results and builds the selection menu.
     * Multiple traces can be selected, and selected entries stay checked.
     */
    private void refreshTracesMenu() {
        recentTraces = MessageDbService.getInstance()
                .loadRecentTracerouteResults(RECENT_TRACE_LIMIT, currentOwnerNodeId())
                .stream()
                .map(this::parseTraceRecord)
                .flatMap(Optional::stream)
                .toList();

        tracesMenu.getItems().clear();
        if (recentTraces.isEmpty()) {
            MenuItem emptyItem = new MenuItem(I18n.t("map.traces.empty"));
            emptyItem.setDisable(true);
            tracesMenu.getItems().add(emptyItem);
            return;
        }

        for (ParsedTrace trace : recentTraces) {
            CheckMenuItem item = new CheckMenuItem(traceMenuTitle(trace));
            item.setSelected(selectedTraces.containsKey(trace.dbId()));
            item.setOnAction(event -> {
                if (item.isSelected()) {
                    selectedTraces.put(trace.dbId(), trace);
                } else {
                    selectedTraces.remove(trace.dbId());
                }
                syncTracesButton();
                refreshSelectedTraceOverlay(true);
                Platform.runLater(() -> {
                    if (!tracesMenu.isShowing()) {
                        tracesMenu.show(tracesButton, javafx.geometry.Side.BOTTOM, 0, 0);
                    }
                });
            });
            tracesMenu.getItems().add(item);
        }

        tracesMenu.getItems().add(new SeparatorMenuItem());
        MenuItem clearItem = new MenuItem(I18n.t("map.traces.clearSelected"));
        clearItem.setDisable(selectedTraces.isEmpty());
        clearItem.setOnAction(event -> {
            selectedTraces.clear();
            syncTracesButton();
            refreshSelectedTraceOverlay(true);
        });
        tracesMenu.getItems().add(clearItem);
    }

    /**
     * Updates the trace button tooltip and active style from the selected-route count.
     */
    private void syncTracesButton() {
        if (selectedTraces.isEmpty()) {
            tracesButton.getStyleClass().remove("map-traces-button-active");
            tracesButton.getTooltip().setText(I18n.t("map.traces.recent"));
        } else {
            if (!tracesButton.getStyleClass().contains("map-traces-button-active")) {
                tracesButton.getStyleClass().add("map-traces-button-active");
            }
            tracesButton.getTooltip().setText(I18n.t("map.traces.selected", selectedTraces.size()));
        }
    }

    /**
     * Builds a compact trace menu title: time, target, and link count.
     */
    private String traceMenuTitle(ParsedTrace trace) {
        int linkCount = trace.paths().stream()
                .mapToInt(path -> Math.max(0, path.names().size() - 1))
                .sum();
        String suffix = linkCount + " " + pluralUnit("map.trace.link", linkCount);
        return NodeData.formatTime(trace.timestamp()) + " · " + trace.targetName() + " · " + suffix;
    }

    /**
     * Updates search suggestions using the same rules as the chat bot.
     */
    private void refreshSearchSuggestions() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim();
        if (query.isBlank() || !searchField.isFocused()) {
            hideSearchSuggestions();
            return;
        }

        List<NodeData> candidates = collectSearchNodes();
        List<ChatBotCommandHelper.NodeSuggestion> suggestions = ChatBotCommandHelper.suggestNodes(candidates, query, 8);
        if (suggestions.isEmpty()) {
            hideSearchSuggestions();
            return;
        }

        List<CustomMenuItem> items = new ArrayList<>();
        List<NodeData> matches = new ArrayList<>();
        List<String> insertTexts = new ArrayList<>();
        for (ChatBotCommandHelper.NodeSuggestion suggestion : suggestions) {
            ChatBotCommandHelper.NodeResolution resolution = ChatBotCommandHelper.resolveTarget(suggestion.insertText(), candidates);
            if (resolution.status() != ChatBotCommandHelper.NodeResolutionStatus.FOUND || resolution.node() == null) {
                continue;
            }
            NodeData node = resolution.node();
            matches.add(node);
            insertTexts.add(suggestion.insertText());
            CustomMenuItem item = buildSearchSuggestionItem(suggestion, node);
            int index = items.size();
            item.getContent().setOnMouseEntered(event -> {
                selectedSearchSuggestionIndex = index;
                updateSearchSuggestionSelection();
            });
            items.add(item);
        }

        if (items.isEmpty()) {
            hideSearchSuggestions();
            return;
        }

        currentSearchMatches = List.copyOf(matches);
        currentSearchInsertTexts = List.copyOf(insertTexts);
        currentSearchSuggestionItems = List.copyOf(items);
        selectedSearchSuggestionIndex = 0;
        searchSuggestionMenu.getItems().setAll(items);
        updateSearchSuggestionSelection();
        if (!searchSuggestionMenu.isShowing()) {
            searchSuggestionMenu.show(searchField, javafx.geometry.Side.BOTTOM, 0, 0);
        }
    }

    /**
     * Creates one row in the search suggestion popup.
     */
    private CustomMenuItem buildSearchSuggestionItem(ChatBotCommandHelper.NodeSuggestion suggestion, NodeData node) {
        Label primary = new Label(suggestion.primaryText());
        primary.getStyleClass().add("chat-command-suggestion-primary");

        Label secondary = new Label(suggestion.secondaryText());
        secondary.getStyleClass().add("chat-command-suggestion-secondary");
        boolean hasSecondary = suggestion.secondaryText() != null && !suggestion.secondaryText().isBlank();
        secondary.setVisible(hasSecondary);
        secondary.setManaged(hasSecondary);

        VBox labels = new VBox(2, primary, secondary);
        labels.setAlignment(Pos.CENTER_LEFT);
        labels.getStyleClass().add("map-search-suggestion-row");
        labels.setPrefWidth(Math.max(220, searchField.getWidth()));

        CustomMenuItem item = new CustomMenuItem(labels, true);
        item.setOnAction(event -> centerOnSearchNode(node, suggestion.insertText()));
        return item;
    }

    /**
     * Handles keyboard navigation through search results.
     */
    private void handleSearchKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            hideSearchSuggestions();
            searchField.clear();
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.DOWN) {
            ensureSearchSuggestions();
            moveSearchSelection(1);
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.UP) {
            ensureSearchSuggestions();
            moveSearchSelection(-1);
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.ENTER) {
            centerOnSelectedSearchMatch();
            event.consume();
        }
    }

    /**
     * Ensures the suggestions popup is open before keyboard navigation.
     */
    private void ensureSearchSuggestions() {
        if (currentSearchMatches.isEmpty()) {
            refreshSearchSuggestions();
        } else if (!searchSuggestionMenu.isShowing()) {
            searchSuggestionMenu.show(searchField, javafx.geometry.Side.BOTTOM, 0, 0);
        }
    }

    /**
     * Moves the highlighted search suggestion, wrapping around the list edges.
     */
    private void moveSearchSelection(int delta) {
        if (currentSearchMatches.isEmpty()) {
            return;
        }
        if (selectedSearchSuggestionIndex < 0 || selectedSearchSuggestionIndex >= currentSearchMatches.size()) {
            selectedSearchSuggestionIndex = delta > 0 ? 0 : currentSearchMatches.size() - 1;
        } else {
            selectedSearchSuggestionIndex = Math.floorMod(selectedSearchSuggestionIndex + delta, currentSearchMatches.size());
        }
        updateSearchSuggestionSelection();
    }

    /**
     * Applies the selected-row CSS class to the current search suggestion.
     */
    private void updateSearchSuggestionSelection() {
        for (int i = 0; i < currentSearchSuggestionItems.size(); i++) {
            javafx.scene.Node content = currentSearchSuggestionItems.get(i).getContent();
            content.getStyleClass().remove("map-search-suggestion-row-selected");
            if (i == selectedSearchSuggestionIndex) {
                content.getStyleClass().add("map-search-suggestion-row-selected");
            }
        }
    }

    /**
     * Closes the suggestions popup and resets transient search state.
     */
    private void hideSearchSuggestions() {
        currentSearchMatches = List.of();
        currentSearchInsertTexts = List.of();
        currentSearchSuggestionItems = List.of();
        selectedSearchSuggestionIndex = -1;
        searchSuggestionMenu.hide();
    }

    /**
     * Parses a saved traceroute row into the map representation.
     * New rows are read from protobuf RouteDiscovery; legacy rows fall back to
     * the old text format.
     *
     * @return parsed trace, or empty when the row is corrupted
     */
    private Optional<ParsedTrace> parseTraceRecord(MessageDbService.TracerouteResultRecord record) {
        if (record == null) {
            return Optional.empty();
        }
        byte[] routeData = record.routeData();
        if (routeData != null && routeData.length > 0) {
            try {
                return Optional.of(parseTraceRoute(record, MeshProtos.RouteDiscovery.parseFrom(routeData)));
            } catch (Exception ignored) {
        // Try to recover old or corrupted rows from formatted_text below.
            }
        }
        return Optional.ofNullable(parseTraceText(
                record.id(),
                record.timestamp(),
                record.formattedText(),
                record.targetName()));
    }

    private ParsedTrace parseTraceRoute(MessageDbService.TracerouteResultRecord record,
                                        MeshProtos.RouteDiscovery route) {
        String targetName = firstNonBlank(record.targetName(), record.targetNodeId(), I18n.t("map.trace.targetFallback"));
        List<TracePath> paths = new ArrayList<>();
        paths.add(new TracePath(
                false,
                routeNodeNames(traceSelfName(), route.getRouteList(), targetName),
                snrValues(route.getSnrTowardsList())));
        if (route.getRouteBackCount() > 0 || route.getSnrBackCount() > 0) {
            paths.add(new TracePath(
                    true,
                    routeNodeNames(targetName, route.getRouteBackList(), traceSelfName()),
                    snrValues(route.getSnrBackList())));
        }
        return new ParsedTrace(record.id(), targetName, record.timestamp(), List.copyOf(paths));
    }

    /**
     * Parses legacy traceroute text from old system messages.
     */
    private ParsedTrace parseTraceText(long id, long timestamp, String text, String targetFallback) {
        if (text == null || !text.startsWith(TracerouteView.TRACEROUTE_PREFIX)) {
            return null;
        }

        String[] lines = text.split("\\R");
        if (lines.length < 2) {
            return null;
        }

        String header = lines[0];
        String targetName = header.substring(TracerouteView.TRACEROUTE_PREFIX.length()).trim();
        if (targetName.isBlank()) {
            targetName = firstNonBlank(targetFallback, I18n.t("map.trace.targetFallback"));
        }

        List<TracePath> paths = new ArrayList<>();
        int forwardLineIndex = -1;
        ParsedTraceLine forward = null;
        for (int i = 1; i < lines.length; i++) {
            forward = parseTraceLine(stripTraceSectionLabel(lines[i]));
            if (forward != null) {
                forwardLineIndex = i;
                break;
            }
        }
        if (forward == null) {
            return null;
        }
        paths.add(new TracePath(false, forward.names(), forward.snrValues()));

        for (int i = forwardLineIndex + 1; i < lines.length; i++) {
            ParsedTraceLine back = parseTraceLine(stripTraceSectionLabel(lines[i]));
            if (back != null) {
                paths.add(new TracePath(true, back.names(), back.snrValues()));
                break;
            }
        }

        return new ParsedTrace(
                id,
                targetName,
                timestamp,
                List.copyOf(paths)
        );
    }

    private List<String> routeNodeNames(String firstName, List<Integer> intermediateNodeNums, String lastName) {
        List<String> names = new ArrayList<>();
        names.add(firstName);
        for (Integer nodeNum : intermediateNodeNums) {
            if (nodeNum != null) {
                names.add(traceNodeName(nodeNum));
            }
        }
        names.add(lastName);
        return List.copyOf(names);
    }

    private List<Double> snrValues(List<Integer> rawValues) {
        List<Double> values = new ArrayList<>();
        for (Integer rawValue : rawValues) {
            if (rawValue != null) {
                values.add(rawValue / 4.0);
            }
        }
        return List.copyOf(values);
    }

    private String traceNodeName(int nodeNum) {
        NodeData node = state != null ? state.getNodeDb().get(nodeNum) : null;
        if (node == null) {
            node = NodeCacheService.getInstance().get(nodeIdFromNum(nodeNum));
        }
        return node != null ? nodeTitle(node) : nodeIdFromNum(nodeNum);
    }

    private static String nodeIdFromNum(int nodeNum) {
        return String.format(Locale.ROOT, "!%08x", nodeNum);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String traceSelfName() {
        return I18n.t("chat.self.avatar");
    }

    private static String pluralUnit(String keyPrefix, long value) {
        return I18n.t(keyPrefix + "." + I18n.pluralCategory(value));
    }

    /**
     * Removes optional Forward/Reverse section labels from a trace line.
     */
    private String stripTraceSectionLabel(String line) {
        return line == null ? "" : TRACE_SECTION_LABEL_PATTERN.matcher(line).replaceFirst("").trim();
    }

    /**
     * Parses a route line such as {@code A ->1.2dB-> B -> C} into hop names and SNR values.
     */
    private ParsedTraceLine parseTraceLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        List<String> names = new ArrayList<>();
        List<Double> snrs = new ArrayList<>();
        Matcher matcher = TRACE_SEGMENT_PATTERN.matcher(line);
        int lastEnd = 0;
        while (matcher.find()) {
            names.add(line.substring(lastEnd, matcher.start()).trim());
            String snrText = matcher.group(1);
            if (snrText != null) {
                try {
                    snrs.add(Double.parseDouble(snrText.replace(',', '.')));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            } else {
                snrs.add(Double.NaN);
            }
            lastEnd = matcher.end();
        }
        if (lastEnd < line.length()) {
            names.add(line.substring(lastEnd).trim());
        }

        names = names.stream()
                .filter(name -> name != null && !name.isBlank())
                .toList();
        if (names.size() < 2) {
            return null;
        }
        return new ParsedTraceLine(List.copyOf(names), List.copyOf(snrs));
    }

    /**
     * Rebuilds the selected traces overlay and fits the map to it when requested.
     *
     * @param fitAndReport {@code true} to fit the map and report status to the user
     */
    private void refreshSelectedTraceOverlay(boolean fitAndReport) {
        if (selectedTraces.isEmpty()) {
            mapView.clearTraceSegments();
            if (fitAndReport) {
                statusLabel.setText(I18n.t("map.trace.hidden"));
            }
            return;
        }

        TraceOverlayBuild build = buildTraceOverlay();
        mapView.setTraceSegments(build.segments());
        if (!fitAndReport) {
            return;
        }

        if (build.segments().isEmpty()) {
            statusLabel.setText(I18n.t("map.trace.noCoordinates"));
            return;
        }
        mapView.fitTraceSegments();
        String skipped = build.skippedPoints() > 0
                ? I18n.t("map.trace.skippedPoints", build.skippedPoints())
                : "";
        statusLabel.setText(I18n.t("map.trace.shown",
                selectedTraces.size(),
                build.totalHops(),
                build.segments().size(),
                skipped));
    }

    /**
     * Builds all visual segments for selected traces.
     * If no explicit reverse trace exists, a synthetic reverse direction without
     * SNR is added so both arrows are visible on the map.
     */
    private TraceOverlayBuild buildTraceOverlay() {
        TraceNodeIndex index = buildTraceNodeIndex();
        List<TileMapView.TraceSegment> segments = new ArrayList<>();
        int skippedPoints = 0;
        int totalHops = 0;
        int traceIndex = 0;

        for (ParsedTrace trace : selectedTraces.values()) {
            totalHops += traceHopCount(trace);
            boolean hasExplicitReverse = false;
            for (TracePath path : trace.paths()) {
                hasExplicitReverse = hasExplicitReverse || path.reverse();
                skippedPoints += appendTracePathSegments(index, segments, trace, path, traceIndex);
            }
            if (!hasExplicitReverse) {
                for (TracePath path : trace.paths()) {
                    if (!path.reverse()) {
                        skippedPoints += appendTracePathSegments(index, segments, trace, mirroredTracePath(path), traceIndex);
                    }
                }
            }
            traceIndex++;
        }

        return new TraceOverlayBuild(List.copyOf(segments), skippedPoints, totalHops);
    }

    /**
     * Counts hops from the original trace paths.
     * Skipped coordinate-less points and visual line stitching do not affect this count.
     */
    private int traceHopCount(ParsedTrace trace) {
        return trace.paths().stream()
                .mapToInt(path -> Math.max(0, path.names().size() - 1))
                .sum();
    }

    /**
     * Converts a trace path into segments between nodes with known coordinates.
     * Nodes without coordinates are skipped, while original indexes are preserved
     * for SNR calculation.
     *
     * @return number of route points that could not be shown because coordinates were missing
     */
    private int appendTracePathSegments(TraceNodeIndex index,
                                        List<TileMapView.TraceSegment> segments,
                                        ParsedTrace trace,
                                        TracePath path,
                                        int traceIndex) {
        int skippedPoints = 0;
        List<TracePoint> points = new ArrayList<>();
        for (int i = 0; i < path.names().size(); i++) {
            NodeData node = resolveTraceNode(path.names().get(i), index);
            if (hasCoordinate(node)) {
                points.add(new TracePoint(node, i));
            } else {
                skippedPoints++;
            }
        }

        for (int i = 1; i < points.size(); i++) {
            TracePoint from = points.get(i - 1);
            TracePoint to = points.get(i);
            TraceSignal signal = traceSignalBetween(
                    path.snrValues(),
                    from.sourceIndex(),
                    to.sourceIndex(),
                    path.reverse(),
                    Math.max(0, path.names().size() - 1)
            );
            segments.add(new TileMapView.TraceSegment(
                    new TileMapView.GeoPoint(from.node().getLatitude(), from.node().getLongitude()),
                    new TileMapView.GeoPoint(to.node().getLatitude(), to.node().getLongitude()),
                    nodeTitle(from.node()),
                    nodeTitle(to.node()),
                    trace.targetName(),
                    signal.text(),
                    signal.snr(),
                    path.reverse(),
                    traceIndex
            ));
        }
        return skippedPoints;
    }

    /**
     * Calculates the SNR label for a visual segment.
     * If the segment spans skipped coordinate-less points, SNR is averaged over
     * the original hops between the visible points.
     */
    private TraceSignal traceSignalBetween(List<Double> snrs,
                                           int fromIndex,
                                           int toIndex,
                                           boolean reverse,
                                           int totalHops) {
        double sum = 0;
        int count = 0;
        for (int i = fromIndex; i < toIndex && i < snrs.size(); i++) {
            double snr = snrs.get(i);
            if (!Double.isNaN(snr)) {
                sum += snr;
                count++;
            }
        }
        double snr = count > 0 ? sum / count : Double.NaN;
        return new TraceSignal(formatTraceSignal(snr, reverse, totalHops), snr);
    }

    /**
     * Creates a synthetic reverse path from the forward route.
     * SNR for this path is unknown and is marked as {@link Double#NaN}.
     */
    private TracePath mirroredTracePath(TracePath path) {
        List<String> names = new ArrayList<>();
        for (int i = path.names().size() - 1; i >= 0; i--) {
            names.add(path.names().get(i));
        }
        List<Double> snrs = new ArrayList<>();
        for (int i = 0; i < Math.max(0, names.size() - 1); i++) {
            snrs.add(Double.NaN);
        }
        return new TracePath(true, List.copyOf(names), List.copyOf(snrs));
    }

    /**
     * Builds a node index for resolving names found in traces.
     * Uses the current DeviceState, the node cache, and the self alias for the local node.
     */
    private TraceNodeIndex buildTraceNodeIndex() {
        Map<Integer, NodeData> nodesByNum = new LinkedHashMap<>();
        if (state != null) {
            state.getNodeDb().values().forEach(node -> nodesByNum.put(node.getNodeNum(), node));
        }
        NodeCacheService cache = NodeCacheService.getInstance();
        for (NodeData node : cache.getAll()) {
            nodesByNum.putIfAbsent(node.getNodeNum(), node);
        }
        for (NodeData node : cache.loadPage(0, TRACE_NODE_CACHE_LIMIT)) {
            nodesByNum.putIfAbsent(node.getNodeNum(), node);
        }

        List<NodeData> nodes = new ArrayList<>(nodesByNum.values());
        Map<String, NodeData> byToken = new LinkedHashMap<>();
        for (NodeData node : nodes) {
            putTraceLookupToken(byToken, node.getNodeId(), node);
            putTraceLookupToken(byToken, node.getLongName(), node);
            putTraceLookupToken(byToken, node.getShortName(), node);
            putTraceLookupToken(byToken, nodeTitle(node), node);
        }

        NodeData localNode = state != null ? state.getNodeDb().get(localNodeNum) : null;
        if (localNode != null) {
            putTraceLookupToken(byToken, "Я", localNode);
            putTraceLookupToken(byToken, "Me", localNode);
            putTraceLookupToken(byToken, traceSelfName(), localNode);
        }
        return new TraceNodeIndex(List.copyOf(nodes), byToken, localNode);
    }

    /**
     * Adds a normalized node search token to the trace index.
     */
    private void putTraceLookupToken(Map<String, NodeData> byToken, String token, NodeData node) {
        String normalized = normalizeTraceToken(token);
        if (!normalized.isBlank() && node != null) {
            byToken.putIfAbsent(normalized, node);
        }
    }

    /**
     * Finds a node by a trace name: nodeId, longName, shortName, title, or chat-bot suggestion.
     */
    private NodeData resolveTraceNode(String name, TraceNodeIndex index) {
        String normalized = normalizeTraceToken(name);
        if (normalized.isBlank()) {
            return null;
        }
        if ("я".equals(normalized)
                || "me".equals(normalized)
                || normalizeTraceToken(traceSelfName()).equals(normalized)) {
            return index.localNode();
        }

        String nodeId = extractTraceNodeId(name);
        if (nodeId != null) {
            NodeData node = index.byToken().get(normalizeTraceToken(nodeId));
            if (node != null) {
                return node;
            }
            return NodeCacheService.getInstance().get(nodeId);
        }

        NodeData exact = index.byToken().get(normalized);
        if (exact != null) {
            return exact;
        }

        ChatBotCommandHelper.NodeResolution resolution = ChatBotCommandHelper.resolveTarget(name, index.nodes());
        return resolution.status() == ChatBotCommandHelper.NodeResolutionStatus.FOUND
                ? resolution.node()
                : null;
    }

    /**
     * Extracts a nodeId from arbitrary text and pads short hex forms to eight characters.
     */
    private String extractTraceNodeId(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = TRACE_NODE_ID_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String hex = matcher.group(1).toLowerCase(Locale.ROOT);
        return "!" + "0".repeat(Math.max(0, 8 - hex.length())) + hex;
    }

    /**
     * Normalizes a string for comparing node names in traces.
     */
    private String normalizeTraceToken(String token) {
        return token == null
                ? ""
                : token.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Builds a trace-line label with direction, original hop count, and SNR.
     */
    private String formatTraceSignal(double snr, boolean reverse, int totalHops) {
        String direction = I18n.t(reverse ? "map.trace.direction.reverse" : "map.trace.direction.forward");
        String hops = totalHops > 0 ? " · " + totalHops + " " + pluralUnit("node.unit.hop", totalHops) : "";
        return Double.isNaN(snr)
                ? I18n.t("map.trace.signalNoSnr", direction, hops, I18n.t("map.trace.snrUnavailable"))
                : I18n.t("map.trace.signalWithSnr",
                direction,
                hops,
                String.format(I18n.locale(), "%.1f", snr),
                I18n.t("node.unit.db"));
    }

    /**
     * Returns nodes available for map search: nodes with coordinates that pass filters.
     */
    private List<NodeData> collectSearchNodes() {
        List<NodeData> nodes = new ArrayList<>();
        for (NodeData node : collectMapNodes()) {
            if (hasCoordinate(node) && isVisibleByMapFilters(node)) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    /**
     * Centers the map on the currently selected search result.
     */
    private void centerOnSelectedSearchMatch() {
        ensureSearchSuggestions();
        if (currentSearchMatches.isEmpty()) {
            statusLabel.setText(I18n.t("map.search.nodeNotFound"));
            return;
        }
        int index = selectedSearchSuggestionIndex >= 0 && selectedSearchSuggestionIndex < currentSearchMatches.size()
                ? selectedSearchSuggestionIndex
                : 0;
        NodeData node = currentSearchMatches.get(index);
        String searchText = index < currentSearchInsertTexts.size()
                ? currentSearchInsertTexts.get(index)
                : ChatBotCommandHelper.formatNodeToken(node);
        centerOnSearchNode(node, searchText);
    }

    /**
     * Centers the map on the found node while keeping search results and markers visible.
     */
    private void centerOnSearchNode(NodeData node, String searchText) {
        if (!hasCoordinate(node)) {
            statusLabel.setText(I18n.t("map.search.nodeNoCoordinates"));
            return;
        }
        suppressSearchSuggestions = true;
        try {
            hideSearchSuggestions();
            searchField.setText(searchText);
            searchField.positionCaret(searchField.getText().length());
        } finally {
            suppressSearchSuggestions = false;
        }
        mapView.setView(node.getLatitude(), node.getLongitude(), Math.max(mapView.getZoom(), 13));
        statusLabel.setText(I18n.t("map.search.foundNode", nodeTitle(node)));
    }

    /**
     * Configures a map-panel button as an icon with a tooltip.
     */
    private void configureIconButton(Button button, String iconPath, String tooltip) {
        SVGPath icon = SvgIconLoader.load(iconPath, 18);
        if (icon != null) {
            button.setGraphic(icon);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            button.setText("?");
        }
        button.getStyleClass().add("map-icon-button");
        button.setTooltip(new Tooltip(tooltip));
    }

    /**
     * Configures a compact download-control button next to the progress bar.
     */
    private void configureDownloadControlButton(Button button, String iconPath, String tooltip, String fallbackText) {
        button.getStyleClass().add("map-progress-icon-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        setButtonIcon(button, iconPath, fallbackText, 14);
    }

    /**
     * Updates a button icon while keeping text fallback for a missing SVG.
     */
    private void setButtonIcon(Button button, String iconPath, String fallbackText, double size) {
        SVGPath icon = SvgIconLoader.load(iconPath, size);
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

    /**
     * Configures a toggle map-panel button as an icon with a tooltip.
     */
    private void configureIconToggleButton(ToggleButton button, String iconPath, String tooltip) {
        SVGPath icon = SvgIconLoader.load(iconPath, 18);
        if (icon != null) {
            button.setGraphic(icon);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            button.setText("?");
        }
        button.getStyleClass().add("map-icon-button");
        button.setTooltip(new Tooltip(tooltip));
    }

    /**
     * Restores the saved offline tile directory if the path still exists.
     */
    private void restoreTileDirectory(String tileDirectory) {
        try {
            Path root = Path.of(tileDirectory);
            if (Files.isDirectory(root)) {
                mapView.setExternalTileRoot(root);
            }
        } catch (InvalidPathException ignored) {
            AppPreferences.setMapTileDirectory("");
        }
    }

    /**
     * Binds the form to the selected active connection and reattaches the node-update listener.
     */
    private void rebindState() {
        DeviceState oldState = state;
        DeviceState newState = null;
        int newLocalNodeNum = 0;

        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = manager.getSelectedConnectionEntry();
        if (entry != null && entry.isConnected()) {
            DeviceState candidate = manager.getDeviceState(entry.getId());
            if (candidate != null) {
                newState = candidate;
                newLocalNodeNum = candidate.getMyNodeNum();
            }
        }

        if (oldState != null && oldState != newState) {
            oldState.removeNodeUpdateListener(nodeUpdateListener);
        }
        if (newState != null && oldState != newState) {
            newState.addNodeUpdateListener(nodeUpdateListener);
        }

        state = newState;
        localNodeNum = newLocalNodeNum;
        reloadMarkers();
    }

    /**
     * Rebuilds map markers from current state and cache.
     * The local node remains on the map whenever it has coordinates, even if
     * filters would otherwise hide it.
     */
    private void reloadMarkers() {
        List<MapMarker> markers = new ArrayList<>();
        Map<String, NodeData> markerNodes = new LinkedHashMap<>();
        for (NodeData node : collectMapNodes()) {
            if (!hasCoordinate(node)) {
                continue;
            }
            boolean isLocalNode = node.getNodeNum() == localNodeNum;
            if (!isLocalNode && !passesFilters(node)) {
                continue;
            }
            String title = nodeTitle(node);
            markers.add(new MapMarker(
                    node.getNodeId(),
                    title,
                    shortMarkerTitle(node),
                    node.getLatitude(),
                    node.getLongitude(),
                    isLocalNode
            ));
            if (node.getNodeId() != null && !node.getNodeId().isBlank()) {
                markerNodes.put(node.getNodeId(), node);
            }
        }

        markers.sort(Comparator.comparing(MapMarker::local).reversed().thenComparing(MapMarker::title, String.CASE_INSENSITIVE_ORDER));
        currentMarkerNodes = Map.copyOf(markerNodes);
        mapView.setMarkers(markers);
        refreshSelectedTraceOverlay(false);
        fitNodesButton.setDisable(markers.isEmpty());
        NodeData myNode = state != null ? state.getNodeDb().get(localNodeNum) : null;
        myNodeButton.setDisable(!hasCoordinate(myNode));
        if (autoFitPending && !markers.isEmpty()) {
            autoFitPending = false;
            Platform.runLater(mapView::fitMarkers);
        }
    }

    /**
     * Opens the reusable node-detail panel when a map marker is clicked.
     */
    private void showMarkerDetails(MapMarker marker) {
        if (marker == null || marker.id() == null || marker.id().isBlank()) {
            statusLabel.setText(I18n.t("map.marker.unresolved"));
            return;
        }

        NodeData node = resolveMarkerNode(marker.id());
        if (node == null) {
            statusLabel.setText(I18n.t("map.marker.notFound", marker.title()));
            return;
        }

        NodeCacheService.getInstance().enrichFromCache(node);
        NodeDetailPanel.showForNode(state, node);
        statusLabel.setText(I18n.t("map.marker.opened", nodeTitle(node)));
    }

    /**
     * Finds NodeData for a marker among current markers, active DeviceState, and cache.
     */
    private NodeData resolveMarkerNode(String nodeId) {
        NodeData node = currentMarkerNodes.get(nodeId);
        if (node != null) {
            return node;
        }
        if (state != null) {
            for (NodeData candidate : state.getNodeDb().values()) {
                if (nodeId.equals(candidate.getNodeId())) {
                    return candidate;
                }
            }
        }
        return NodeCacheService.getInstance().get(nodeId);
    }

    /**
     * Builds the source node set for the map from the current DeviceState and,
     * when needed, from favorite or ignored node caches.
     */
    private List<NodeData> collectMapNodes() {
        if (state == null && !showFavoritesOnly && !showIgnoredOnly) {
            return List.of();
        }

        Map<Integer, NodeData> nodes = new LinkedHashMap<>();
        if (state != null) {
            state.getNodeDb().values().forEach(node -> nodes.put(node.getNodeNum(), node));
        }
        String ownerNodeId = currentOwnerNodeId();
        if (showFavoritesOnly) {
            if (!ownerNodeId.isBlank()) {
                for (NodeData node : NodeCacheService.getInstance().loadFavoriteNodes(ownerNodeId)) {
                    nodes.putIfAbsent(node.getNodeNum(), node);
                }
            }
        }
        if (showIgnoredOnly) {
            if (!ownerNodeId.isBlank()) {
                for (NodeData node : NodeCacheService.getInstance().loadIgnoredNodes(ownerNodeId)) {
                    nodes.putIfAbsent(node.getNodeNum(), node);
                }
            }
        }
        return new ArrayList<>(nodes.values());
    }

    /**
     * Checks whether a node should be shown on the map under current filters.
     */
    private boolean passesFilters(NodeData node) {
        return isVisibleByMapFilters(node);
    }

    /**
     * Implements map filters: unknown names, offline nodes, favorites, direct neighbors, and ignored nodes.
     */
    private boolean isVisibleByMapFilters(NodeData node) {
        if (!includeUnknownNames && !node.hasName()) {
            return false;
        }
        long now = System.currentTimeMillis() / 1000;
        if (hideOffline && node.getLastHeard() > 0 && (now - node.getLastHeard()) > 7200) {
            return false;
        }
        String ownerNodeId = currentOwnerNodeId();
        if (showFavoritesOnly
                && (ownerNodeId.isBlank() || !FavoriteNodeService.getInstance().isFavorite(node.getNodeId(), ownerNodeId))) {
            return false;
        }
        if (showDirectOnly && !node.isDirectNeighbor()) {
            return false;
        }
        return !showIgnoredOnly
                || (!ownerNodeId.isBlank() && IgnoredNodeService.getInstance().isIgnored(node.getNodeId(), ownerNodeId));
    }

    /**
     * Centers the map on the local node coordinates.
     */
    private void centerOnMyNode() {
        if (state == null) {
            statusLabel.setText(I18n.t("map.myNode.noConnection"));
            return;
        }

        NodeData myNode = state.getNodeDb().get(localNodeNum);
        if (!hasCoordinate(myNode)) {
            statusLabel.setText(I18n.t("map.myNode.noCoordinates"));
            return;
        }

        mapView.setView(myNode.getLatitude(), myNode.getLongitude(), Math.max(mapView.getZoom(), 13));
        statusLabel.setText(I18n.t("map.myNode.centered"));
    }

    /**
     * Checks whether the node has valid coordinates.
     * The {@code 0,0} value is treated as missing coordinates.
     */
    private boolean hasCoordinate(NodeData node) {
        return node != null
                && (node.getLatitude() != 0 || node.getLongitude() != 0)
                && node.getLatitude() >= -90
                && node.getLatitude() <= 90
                && node.getLongitude() >= -180
                && node.getLongitude() <= 180;
    }

    /**
     * Returns the best available node name for tooltips and labels.
     */
    private String nodeTitle(NodeData node) {
        if (node.getLongName() != null && !node.getLongName().isBlank()) {
            return node.getLongName();
        }
        if (node.getShortName() != null && !node.getShortName().isBlank()) {
            return node.getShortName();
        }
        if (node.getNodeId() != null && !node.getNodeId().isBlank()) {
            return node.getNodeId();
        }
        return String.format(Locale.ROOT, "!%08x", node.getNodeNum());
    }

    /**
     * Returns a compact marker label that should fit inside the circle.
     */
    private String shortMarkerTitle(NodeData node) {
        if (node.getShortName() != null && !node.getShortName().isBlank()) {
            return node.getShortName();
        }
        String title = nodeTitle(node);
        if (title.startsWith("!") && title.length() >= 5) {
            return title.substring(title.length() - 4);
        }
        return title;
    }

    /**
     * Returns the owner nodeId of the current connection for message-history isolation.
     */
    private String currentOwnerNodeId() {
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

    /**
     * Opens the external tile-directory picker and applies the selected directory to the map.
     */
    private void chooseTileDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("map.tileDirectory.title"));
        Path current = mapView.getExternalTileRoot();
        if (current != null && Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        }

        var stage = MeshApp.getPrimaryStage();
        var selected = chooser.showDialog(stage);
        if (selected == null) {
            return;
        }

        Path root = selected.toPath();
        mapView.setExternalTileRoot(root);
        AppPreferences.setMapTileDirectory(root.toString());
        updateTileDirectoryLabel();
    }

    /**
     * Updates the status label for the current local tile source.
     */
    private void updateTileDirectoryLabel() {
        Path root = mapView.getExternalTileRoot();
        if (root == null) {
            tileDirectoryLabel.setText(I18n.t("map.tileDirectory.cache", mapView.cacheRoot()));
        } else {
            tileDirectoryLabel.setText(I18n.t("map.tileDirectory.external", root));
        }
    }

    /**
     * Starts downloading tiles into the local cache and displays progress.
     */
    private void downloadSelectedAreaTiles() {
        if (!mapView.hasSelectedArea()) {
            String message = I18n.t("map.download.selectAreaFirst");
            statusLabel.setText(message);
            hideDownloadProgress();
            Toast.show(Toast.Type.WARNING, message);
            return;
        }

        downloadButton.setDisable(true);
        long count = mapView.downloadTileCount();
        if (count <= 0) {
            downloadButton.setDisable(false);
            String message = I18n.t("map.download.noTiles");
            statusLabel.setText(message);
            Toast.show(Toast.Type.WARNING, message);
            return;
        }

        downloadPaused = false;
        updateDownloadPauseButton();
        statusLabel.setText(I18n.t("map.download.starting", count, pluralUnit("map.status.tile", count)));
        showDownloadProgress(0);

        TileMapView.DownloadHandle[] handleRef = new TileMapView.DownloadHandle[1];
        TileMapView.DownloadHandle handle = mapView.downloadSelectedAreaTiles(progress -> {
            if (activeDownload != handleRef[0]) {
                return;
            }
            handleDownloadProgress(progress);
        });
        handleRef[0] = handle;
        activeDownload = handle;
    }

    private void handleDownloadProgress(TileMapView.DownloadProgress progress) {
        if (progress.total() == 0) {
            finishDownload(progress.message());
            return;
        }

        showDownloadProgress((double) progress.completed() / progress.total());
        if (progress.state() == TileMapView.DownloadState.CANCELLED) {
            finishDownload(progress.message());
            return;
        }
        if (progress.state() == TileMapView.DownloadState.COMPLETED || progress.completed() >= progress.total()) {
            finishDownload(I18n.t("map.download.available",
                    progress.available(),
                    progress.total(),
                    pluralUnit("map.status.tile", progress.total())));
            return;
        }

        statusLabel.setText(downloadPaused
                ? I18n.t("map.download.pausedProgress", progress.completed(), progress.total())
                : progress.message());
    }

    private void toggleDownloadPause() {
        if (activeDownload == null) {
            return;
        }
        if (downloadPaused) {
            activeDownload.resume();
            downloadPaused = false;
            statusLabel.setText(I18n.t("map.download.resumed"));
        } else {
            activeDownload.pause();
            downloadPaused = true;
            statusLabel.setText(I18n.t("map.download.paused"));
        }
        updateDownloadPauseButton();
    }

    private void updateDownloadPauseButton() {
        if (downloadPaused) {
            setButtonIcon(downloadPauseButton, "/icons/play.svg", ">", 14);
            downloadPauseButton.setTooltip(new Tooltip(I18n.t("map.tooltip.downloadResume")));
        } else {
            setButtonIcon(downloadPauseButton, "/icons/pause.svg", "||", 14);
            downloadPauseButton.setTooltip(new Tooltip(I18n.t("map.tooltip.downloadPause")));
        }
    }

    private void cancelDownload() {
        if (activeDownload == null) {
            return;
        }
        activeDownload.cancel();
        finishDownload(I18n.t("map.download.cancelled"));
    }

    private void finishDownload(String message) {
        activeDownload = null;
        downloadPaused = false;
        downloadButton.setDisable(false);
        statusLabel.setText(message);
        updateDownloadPauseButton();
        hideDownloadProgress();
    }

    private void showDownloadProgress(double progress) {
        setDownloadProgressVisible(true);
        downloadProgressBar.setProgress(Math.max(0, Math.min(1, progress)));
    }

    private void hideDownloadProgress() {
        downloadProgressBar.setProgress(0);
        setDownloadProgressVisible(false);
    }

    private void setDownloadProgressVisible(boolean visible) {
        downloadProgressBar.setVisible(visible);
        downloadProgressBar.setManaged(visible);
        downloadPauseButton.setVisible(visible);
        downloadPauseButton.setManaged(visible);
        downloadCancelButton.setVisible(visible);
        downloadCancelButton.setManaged(visible);
    }

    /**
     * Saved trace read from the dedicated results table.
     *
     * @param dbId       id of the traceroute_results row
     * @param targetName target node name
     * @param timestamp  result timestamp
     * @param paths      forward path and, when present, reverse path
     */
    private record ParsedTrace(long dbId, String targetName, long timestamp, List<TracePath> paths) {
    }

    /**
     * One trace path.
     *
     * @param reverse   {@code true} for the reverse path
     * @param names     node names in route order
     * @param snrValues SNR between adjacent nodes; {@link Double#NaN} when absent
     */
    private record TracePath(boolean reverse, List<String> names, List<Double> snrValues) {
    }

    /**
     * Trace node with known coordinates and its original route index.
     */
    private record TracePoint(NodeData node, int sourceIndex) {
    }

    /**
     * Prepared label and numeric SNR for a visual trace segment.
     */
    private record TraceSignal(String text, double snr) {
    }

    /**
     * Parse result for one textual traceroute line.
     */
    private record ParsedTraceLine(List<String> names, List<Double> snrValues) {
    }

    /**
     * Index for fast node lookup by different name variants found in traces.
     */
    private record TraceNodeIndex(List<NodeData> nodes, Map<String, NodeData> byToken, NodeData localNode) {
    }

    /**
     * Result of building the trace overlay for the map.
     *
     * @param segments      prepared visual segments
     * @param skippedPoints number of points without coordinates
     * @param totalHops     original hop count, before visual stitching
     */
    private record TraceOverlayBuild(List<TileMapView.TraceSegment> segments, int skippedPoints, int totalHops) {
    }
}
