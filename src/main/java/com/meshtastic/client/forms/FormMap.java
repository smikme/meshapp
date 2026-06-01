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
 * Форма «Карты»: отображает OSM-карту, ноды с координатами, фильтры,
 * поиск, измерение расстояний, выделение области, оффлайн-тайлы и трейсы.
 * <p>
 * Форма связывает UI приложения с низкоуровневым компонентом {@link TileMapView}:
 * собирает ноды из текущего {@link DeviceState} и кэша, применяет фильтры,
 * парсит сохранённые результаты traceroute и передаёт готовые маркеры/сегменты
 * в карту.
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
     * Создаёт форму и сразу собирает визуальные элементы панели карт.
     */
    public FormMap() {
        initComponents();
    }

    /**
     * Подписывает форму на изменения подключения, избранных и игнорируемых нод.
     */
    @Override
    public void formInit() {
        ConnectionManager.getInstance().addListener(connectionListener);
        FavoriteNodeService.getInstance().addListener(favoritesListener);
        IgnoredNodeService.getInstance().addListener(ignoredListener);
        rebindState();
    }

    /**
     * При открытии формы заново привязывает активное состояние устройства.
     */
    @Override
    public void formOpen() {
        rebindState();
    }

    /**
     * Сохраняет центр и масштаб карты при закрытии формы.
     */
    @Override
    public void formClose() {
        AppPreferences.saveMapView(mapView.getCenterLatitude(), mapView.getCenterLongitude(), mapView.getZoom());
    }

    /**
     * Обновляет маркеры при ручном обновлении формы.
     */
    @Override
    public void formRefresh() {
        reloadMarkers();
    }

    /**
     * Показывает на карте один сохранённый traceroute-результат.
     *
     * <p>Метод используется внешними формами, которые уже знают id записи
     * {@code traceroute_results}. Предыдущий выбор трейсов очищается, чтобы
     * карта сфокусировалась только на указанном маршруте.
     *
     * @param tracerouteResultId id записи {@code traceroute_results}
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
     * Собирает тулбар, карту и статусную строку, затем восстанавливает сохранённые
     * настройки карты: центр, масштаб, оффлайн-режим, ночной режим и каталог тайлов.
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
     * Создаёт компактную текстовую кнопку в стиле панели карт.
     */
    private Button iconButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().add("map-icon-button");
        button.setContentDisplay(ContentDisplay.TEXT_ONLY);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    /**
     * Настраивает предиктивный поиск нод на карте и быстрый фильтр избранного.
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
            AppPreferences.setNodesFilterFavorites(showFavoritesOnly);
            if (filterFavorites != null) {
                filterFavorites.setSelected(showFavoritesOnly);
            }
            syncFavoriteFilterButton();
            reloadMarkers();
        });

        includeUnknownNames = AppPreferences.isNodesFilterUnknown();
        hideOffline = AppPreferences.isNodesFilterHideOffline();
        showFavoritesOnly = AppPreferences.isNodesFilterFavorites();
        showDirectOnly = AppPreferences.isNodesFilterDirect();
        showIgnoredOnly = AppPreferences.isNodesFilterIgnored();
        syncFavoriteFilterButton();
    }

    /**
     * Настраивает кнопку меню последних трейсов.
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
     * Создаёт меню фильтров нод, синхронизированное с фильтрами формы «Ноды».
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
            AppPreferences.setNodesFilterUnknown(includeUnknownNames);
        });
        actions.put(filterHideOffline, () -> {
            hideOffline = filterHideOffline.isSelected();
            AppPreferences.setNodesFilterHideOffline(hideOffline);
        });
        actions.put(filterFavorites, () -> {
            showFavoritesOnly = filterFavorites.isSelected();
            AppPreferences.setNodesFilterFavorites(showFavoritesOnly);
            syncFavoriteFilterButton();
        });
        actions.put(filterDirect, () -> {
            showDirectOnly = filterDirect.isSelected();
            AppPreferences.setNodesFilterDirect(showDirectOnly);
        });
        actions.put(filterIgnored, () -> {
            showIgnoredOnly = filterIgnored.isSelected();
            AppPreferences.setNodesFilterIgnored(showIgnoredOnly);
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
     * Синхронизирует визуальное состояние кнопки «Только избранные».
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
     * Перечитывает последние сохранённые результаты traceroute и строит меню выбора.
     * Можно выбрать несколько трейсов, выбранные элементы остаются отмеченными.
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
     * Обновляет подсказку и активный стиль кнопки трейсов по числу выбранных маршрутов.
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
     * Формирует короткий заголовок трейса для меню: время, цель и число связей.
     */
    private String traceMenuTitle(ParsedTrace trace) {
        int linkCount = trace.paths().stream()
                .mapToInt(path -> Math.max(0, path.names().size() - 1))
                .sum();
        String suffix = linkCount + " " + pluralUnit("map.trace.link", linkCount);
        return NodeData.formatTime(trace.timestamp()) + " · " + trace.targetName() + " · " + suffix;
    }

    /**
     * Обновляет список подсказок поиска по тем же правилам, что используются в чат-боте.
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
     * Создаёт одну строку выпадающей подсказки поиска.
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
     * Обрабатывает клавиатурную навигацию по результатам поиска.
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
     * Гарантирует наличие открытого меню подсказок перед навигацией с клавиатуры.
     */
    private void ensureSearchSuggestions() {
        if (currentSearchMatches.isEmpty()) {
            refreshSearchSuggestions();
        } else if (!searchSuggestionMenu.isShowing()) {
            searchSuggestionMenu.show(searchField, javafx.geometry.Side.BOTTOM, 0, 0);
        }
    }

    /**
     * Сдвигает выделение в списке подсказок поиска с циклическим переходом через край.
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
     * Применяет CSS-класс выбранной строки к текущей подсказке поиска.
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
     * Закрывает меню подсказок и сбрасывает временное состояние поиска.
     */
    private void hideSearchSuggestions() {
        currentSearchMatches = List.of();
        currentSearchInsertTexts = List.of();
        currentSearchSuggestionItems = List.of();
        selectedSearchSuggestionIndex = -1;
        searchSuggestionMenu.hide();
    }

    /**
     * Парсит сохранённую запись traceroute в структуру для карты.
     * Новые записи читаются из protobuf RouteDiscovery, legacy-записи — из старого текстового формата.
     *
     * @return разобранный трейс или пустое значение, если запись повреждена
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
                // Старые или повреждённые записи пробуем восстановить из formatted_text ниже.
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
     * Парсит legacy-текст traceroute из старых системных сообщений.
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
     * Удаляет необязательные подписи секций «Прямой:» и «Обратный:» из строки трейса.
     */
    private String stripTraceSectionLabel(String line) {
        return line == null ? "" : TRACE_SECTION_LABEL_PATTERN.matcher(line).replaceFirst("").trim();
    }

    /**
     * Разбирает строку маршрута вида {@code A →1.2dB→ B → C} в имена хопов и SNR.
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
     * Пересобирает оверлей выбранных трейсов и при необходимости масштабирует карту к ним.
     *
     * @param fitAndReport {@code true}, чтобы приблизить карту и вывести статус пользователю
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
     * Строит все визуальные сегменты выбранных трейсов.
     * Если явного обратного трейса нет, добавляет синтетическое обратное направление
     * без SNR, чтобы на карте были видны обе стрелки.
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
     * Считает количество хопов по исходным путям трейса.
     * Пропущенные точки без координат и визуальная «склейка» линий на это число не влияют.
     */
    private int traceHopCount(ParsedTrace trace) {
        return trace.paths().stream()
                .mapToInt(path -> Math.max(0, path.names().size() - 1))
                .sum();
    }

    /**
     * Превращает путь трейса в сегменты между нодами с известными координатами.
     * Ноды без координат пропускаются, но исходные индексы сохраняются для расчёта SNR.
     *
     * @return количество точек маршрута, которые не удалось показать из-за отсутствия координат
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
     * Рассчитывает подпись SNR для визуального сегмента.
     * Если сегмент получился после пропуска промежуточных точек без координат,
     * SNR усредняется по исходным хопам между видимыми точками.
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
     * Создаёт синтетический обратный путь на основе прямого маршрута.
     * SNR для такого пути неизвестен и помечается как {@link Double#NaN}.
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
     * Собирает индекс нод для разрешения имён из трейсов.
     * Используются текущий DeviceState, кэш нод и псевдоним «Я» для собственной ноды.
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
     * Добавляет нормализованный поисковый токен ноды в индекс трейсов.
     */
    private void putTraceLookupToken(Map<String, NodeData> byToken, String token, NodeData node) {
        String normalized = normalizeTraceToken(token);
        if (!normalized.isBlank() && node != null) {
            byToken.putIfAbsent(normalized, node);
        }
    }

    /**
     * Находит ноду по имени из трейса: nodeId, longName, shortName, заголовок или подсказка чат-бота.
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
     * Извлекает nodeId из произвольного текста и дополняет короткую hex-форму до восьми символов.
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
     * Нормализует строку для сравнения имён нод в трейсе.
     */
    private String normalizeTraceToken(String token) {
        return token == null
                ? ""
                : token.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Формирует подпись линии трейса с направлением, исходным числом хопов и SNR.
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
     * Возвращает ноды, доступные для поиска на карте: с координатами и проходящие фильтры.
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
     * Центрирует карту по текущему выбранному результату поиска.
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
     * Центрирует карту по найденной ноде и оставляет результаты/маркеры на карте.
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
     * Настраивает кнопку панели карт как иконку с подсказкой.
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
     * Настраивает компактную кнопку управления загрузкой рядом с progress bar.
     */
    private void configureDownloadControlButton(Button button, String iconPath, String tooltip, String fallbackText) {
        button.getStyleClass().add("map-progress-icon-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        setButtonIcon(button, iconPath, fallbackText, 14);
    }

    /**
     * Обновляет иконку кнопки, оставляя текстовый fallback для отсутствующего SVG.
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
     * Настраивает переключаемую кнопку панели карт как иконку с подсказкой.
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
     * Восстанавливает сохранённый каталог оффлайн-тайлов, если путь ещё существует.
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
     * Привязывает форму к выбранному активному подключению и перевешивает listener обновления нод.
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
     * Пересобирает маркеры карты из текущего состояния и кэша.
     * Собственная нода всегда остаётся на карте при наличии координат, даже если фильтры её скрыли бы.
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
     * Открывает переиспользуемую панель информации о ноде по клику на маркер карты.
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
     * Находит NodeData для маркера среди текущих маркеров, активного DeviceState и кэша.
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
     * Собирает исходный набор нод для карты из текущего DeviceState и, при необходимости,
     * из кэша избранных или игнорируемых нод.
     */
    private List<NodeData> collectMapNodes() {
        if (state == null && !showFavoritesOnly && !showIgnoredOnly) {
            return List.of();
        }

        Map<Integer, NodeData> nodes = new LinkedHashMap<>();
        if (state != null) {
            state.getNodeDb().values().forEach(node -> nodes.put(node.getNodeNum(), node));
        }
        if (showFavoritesOnly) {
            for (NodeData node : NodeCacheService.getInstance().loadFavoriteNodes()) {
                nodes.putIfAbsent(node.getNodeNum(), node);
            }
        }
        if (showIgnoredOnly) {
            for (NodeData node : NodeCacheService.getInstance().loadIgnoredNodes()) {
                nodes.putIfAbsent(node.getNodeNum(), node);
            }
        }
        return new ArrayList<>(nodes.values());
    }

    /**
     * Проверяет, должна ли нода отображаться на карте с учётом текущих фильтров.
     */
    private boolean passesFilters(NodeData node) {
        return isVisibleByMapFilters(node);
    }

    /**
     * Реализация фильтров карты: неизвестные имена, оффлайн, избранные,
     * прямые соседи и игнорируемые.
     */
    private boolean isVisibleByMapFilters(NodeData node) {
        if (!includeUnknownNames && !node.hasName()) {
            return false;
        }
        long now = System.currentTimeMillis() / 1000;
        if (hideOffline && node.getLastHeard() > 0 && (now - node.getLastHeard()) > 7200) {
            return false;
        }
        if (showFavoritesOnly && !FavoriteNodeService.getInstance().isFavorite(node.getNodeId())) {
            return false;
        }
        if (showDirectOnly && !node.isDirectNeighbor()) {
            return false;
        }
        return !showIgnoredOnly || IgnoredNodeService.getInstance().isIgnored(node.getNodeId());
    }

    /**
     * Центрирует карту по координатам собственной ноды.
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
     * Проверяет, что у ноды есть валидные координаты.
     * Значение {@code 0,0} трактуется как отсутствие координат.
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
     * Возвращает лучшее доступное имя ноды для подсказок и подписей.
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
     * Возвращает короткую подпись маркера, которая должна помещаться в круг.
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
     * Возвращает nodeId владельца текущего подключения для изоляции истории сообщений.
     */
    private String currentOwnerNodeId() {
        return state != null && state.getOwnerNodeId() != null ? state.getOwnerNodeId() : "";
    }

    /**
     * Открывает выбор внешнего каталога тайлов и применяет его к карте.
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
     * Обновляет статусную подпись текущего источника локальных тайлов.
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
     * Запускает загрузку тайлов в локальный кэш и отображает прогресс.
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
     * Сохранённый трейс, извлечённый из отдельной таблицы результатов.
     *
     * @param dbId       id записи traceroute_results
     * @param targetName имя целевой ноды
     * @param timestamp  время результата
     * @param paths      прямой и, при наличии, обратный путь
     */
    private record ParsedTrace(long dbId, String targetName, long timestamp, List<TracePath> paths) {
    }

    /**
     * Один путь трейса.
     *
     * @param reverse   {@code true}, если путь обратный
     * @param names     имена нод в порядке прохождения маршрута
     * @param snrValues SNR между соседними нодами; {@link Double#NaN}, если значения нет
     */
    private record TracePath(boolean reverse, List<String> names, List<Double> snrValues) {
    }

    /**
     * Нода трейса с известными координатами и её исходный индекс в маршруте.
     */
    private record TracePoint(NodeData node, int sourceIndex) {
    }

    /**
     * Подготовленная подпись и числовой SNR для визуального сегмента трейса.
     */
    private record TraceSignal(String text, double snr) {
    }

    /**
     * Результат разбора одной текстовой строки traceroute.
     */
    private record ParsedTraceLine(List<String> names, List<Double> snrValues) {
    }

    /**
     * Индекс для быстрого поиска нод по разным вариантам имени из трейса.
     */
    private record TraceNodeIndex(List<NodeData> nodes, Map<String, NodeData> byToken, NodeData localNode) {
    }

    /**
     * Результат построения оверлея трейсов для карты.
     *
     * @param segments      готовые визуальные сегменты
     * @param skippedPoints число точек без координат
     * @param totalHops     исходное количество хопов без учёта визуальной склейки
     */
    private record TraceOverlayBuild(List<TileMapView.TraceSegment> segments, int skippedPoints, int totalHops) {
    }
}
