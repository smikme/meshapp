package com.meshtastic.client.forms;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.components.map.MapMarker;
import com.meshtastic.client.components.map.TileMapView;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
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

import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Форма «Карты»: отображает OSM-карту, ноды с координатами, фильтры,
 * поиск, измерение расстояний, выделение области, оффлайн-тайлы и трейсы.
 * <p>
 * Форма связывает UI приложения с низкоуровневым компонентом {@link TileMapView}:
 * собирает ноды из текущего {@link DeviceState} и кэша, применяет фильтры,
 * парсит сохранённые traceroute-сообщения и передаёт готовые маркеры/сегменты
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
    private static final Pattern TRACE_SECTION_LABEL_PATTERN = Pattern.compile("(?iu)^\\s*(?:прямой|обратный)\\s*:\\s*");

    private final TileMapView mapView = new TileMapView();
    private final Label statusLabel = new Label();
    private final Label pointerLabel = new Label();
    private final Label measureLabel = new Label();
    private final Label areaLabel = new Label();
    private final Label tileDirectoryLabel = new Label();
    private final ProgressBar downloadProgressBar = new ProgressBar(0);
    private final TextField searchField = new TextField();
    private final ContextMenu searchSuggestionMenu = new ContextMenu();
    private final Button favoriteFilterButton = new Button();
    private final ToggleButton offlineButton = new ToggleButton("Оффлайн");
    private final ToggleButton nightModeButton = new ToggleButton();
    private final ToggleButton measureButton = new ToggleButton();
    private final ToggleButton areaButton = new ToggleButton();
    private final Button myNodeButton = new Button();
    private final Button fitNodesButton = new Button();
    private final Button tracesButton = new Button();
    private final ContextMenu tracesMenu = new ContextMenu();
    private final Button downloadButton = new Button("Скачать область");

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
    private final Map<Long, ParsedTrace> selectedTraces = new LinkedHashMap<>();
    private List<ParsedTrace> recentTraces = List.of();
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

        configureSearchControls();

        offlineButton.setSelected(mapView.isOfflineOnly());
        offlineButton.setTooltip(new Tooltip("Использовать только локальные тайлы из кэша и выбранного каталога"));
        offlineButton.setOnAction(event -> {
            boolean offline = offlineButton.isSelected();
            mapView.setOfflineOnly(offline);
            AppPreferences.setMapOfflineMode(offline);
        });

        configureIconToggleButton(nightModeButton, "/icons/dark.svg", "Ночной режим карты");
        nightModeButton.setSelected(mapView.isNightMode());
        nightModeButton.setOnAction(event -> {
            boolean nightMode = nightModeButton.isSelected();
            mapView.setNightMode(nightMode);
            AppPreferences.setMapNightMode(nightMode);
        });

        configureIconButton(myNodeButton, "/icons/map-my-node.svg", "К своей ноде");
        myNodeButton.setOnAction(event -> centerOnMyNode());

        configureIconButton(fitNodesButton, "/drawer/icon/nodes.svg", "Показать ноды с координатами");
        fitNodesButton.setOnAction(event -> {
            if (!mapView.fitMarkers()) {
                statusLabel.setText("Нет нод с координатами");
            }
        });

        configureTraceButton();

        configureIconToggleButton(measureButton, "/icons/map-ruler.svg", "Измерить расстояние между точками на карте");
        measureButton.setTooltip(new Tooltip("Измерить расстояние между точками на карте"));
        measureButton.setOnAction(event -> {
            boolean measuring = measureButton.isSelected();
            if (measuring) {
                areaButton.setSelected(false);
                mapView.setAreaSelectionMode(false);
            }
            mapView.setMeasuring(measuring);
        });

        configureIconToggleButton(areaButton, "/icons/map-select-area.svg", "Выделить прямоугольную область и приблизить карту к ней");
        areaButton.setTooltip(new Tooltip("Выделить прямоугольную область и приблизить карту к ней"));
        areaButton.setOnAction(event -> {
            boolean selectingArea = areaButton.isSelected();
            if (selectingArea) {
                measureButton.setSelected(false);
                mapView.setMeasuring(false);
            }
            mapView.setAreaSelectionMode(selectingArea);
        });

        Button clearMeasureButton = new Button("Сброс");
        clearMeasureButton.setTooltip(new Tooltip("Очистить измерение расстояния, выделенную область и трейсы"));
        clearMeasureButton.setOnAction(event -> {
            mapView.clearMeasure();
            mapView.clearSelectedArea();
            selectedTraces.clear();
            syncTracesButton();
            refreshSelectedTraceOverlay(false);
            if (tracesMenu.isShowing()) {
                refreshTracesMenu();
            }
            statusLabel.setText("Измерение, область и трейсы очищены");
        });

        Button zoomInButton = iconButton("+", "Приблизить");
        zoomInButton.setOnAction(event -> mapView.zoomIn());

        Button zoomOutButton = iconButton("−", "Отдалить");
        zoomOutButton.setOnAction(event -> mapView.zoomOut());

        downloadButton.setTooltip(new Tooltip(
                "Скачать выделенную область на всех масштабах; без выделения — видимые тайлы текущего масштаба"));
        downloadButton.setOnAction(event -> downloadVisibleTiles());

        Button tileDirectoryButton = new Button("Каталог тайлов");
        tileDirectoryButton.setTooltip(new Tooltip("Выбрать локальный каталог формата z/x/y.png|jpg|jpeg"));
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
        downloadProgressBar.setVisible(false);
        updateTileDirectoryLabel();

        HBox statusBar = new HBox(
                16,
                downloadProgressBar,
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
        searchField.setPromptText("Поиск");
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

        configureIconButton(favoriteFilterButton, "/icons/favorite.svg", "Только избранные");
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
        configureIconButton(tracesButton, "/icons/map-traces.svg", "Последние трейсы");
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
        configureIconButton(filterButton, "/icons/sort.svg", "Фильтры нод на карте");

        CheckMenuItem filterUnknown = new CheckMenuItem("Показывать неизвестные ноды");
        CheckMenuItem filterHideOffline = new CheckMenuItem("Скрыть офлайн-ноды");
        filterFavorites = new CheckMenuItem("Только избранные");
        CheckMenuItem filterDirect = new CheckMenuItem("Только прямые (0 хопов)");
        filterIgnored = new CheckMenuItem("Игнорируемые");

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
            favoriteFilterButton.getTooltip().setText("Показать все ноды");
        } else {
            favoriteFilterButton.getStyleClass().remove("favorite-btn-active");
            favoriteFilterButton.getTooltip().setText("Только избранные");
        }
    }

    /**
     * Перечитывает последние системные traceroute-сообщения и строит меню выбора.
     * Можно выбрать несколько трейсов, выбранные элементы остаются отмеченными.
     */
    private void refreshTracesMenu() {
        recentTraces = MessageDbService.getInstance()
                .loadRecentSystemMessagesByPrefix(TracerouteView.TRACEROUTE_PREFIX, RECENT_TRACE_LIMIT, currentOwnerNodeId())
                .stream()
                .map(this::parseTraceMessage)
                .filter(trace -> trace != null)
                .toList();

        tracesMenu.getItems().clear();
        if (recentTraces.isEmpty()) {
            MenuItem emptyItem = new MenuItem("Нет сохранённых трейсов");
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
        MenuItem clearItem = new MenuItem("Очистить выбранные");
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
            tracesButton.getTooltip().setText("Последние трейсы");
        } else {
            if (!tracesButton.getStyleClass().contains("map-traces-button-active")) {
                tracesButton.getStyleClass().add("map-traces-button-active");
            }
            tracesButton.getTooltip().setText("Последние трейсы: выбрано " + selectedTraces.size());
        }
    }

    /**
     * Формирует короткий заголовок трейса для меню: время, цель и число связей.
     */
    private String traceMenuTitle(ParsedTrace trace) {
        int linkCount = trace.paths().stream()
                .mapToInt(path -> Math.max(0, path.names().size() - 1))
                .sum();
        String suffix = linkCount == 1 ? "1 связь" : linkCount + " связ.";
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
     * Парсит сохранённое системное traceroute-сообщение в структуру для карты.
     * Поддерживает прямой маршрут и, если он есть в тексте, явный обратный маршрут.
     *
     * @return разобранный трейс или {@code null}, если сообщение не является traceroute
     */
    private ParsedTrace parseTraceMessage(MeshMessage message) {
        if (message == null || message.getText() == null
                || !message.getText().startsWith(TracerouteView.TRACEROUTE_PREFIX)) {
            return null;
        }

        String[] lines = message.getText().split("\\R");
        if (lines.length < 2) {
            return null;
        }

        String header = lines[0];
        String targetName = header.substring(TracerouteView.TRACEROUTE_PREFIX.length()).trim();
        if (targetName.isBlank()) {
            targetName = "нода";
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
                message.getDbId(),
                targetName,
                message.getTimestamp(),
                List.copyOf(paths)
        );
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
                statusLabel.setText("Трейсы скрыты");
            }
            return;
        }

        TraceOverlayBuild build = buildTraceOverlay();
        mapView.setTraceSegments(build.segments());
        if (!fitAndReport) {
            return;
        }

        if (build.segments().isEmpty()) {
            statusLabel.setText("Не удалось построить трейсы: нет координат выбранных нод");
            return;
        }
        mapView.fitTraceSegments();
        String skipped = build.skippedPoints() > 0
                ? " · точек без координат: " + build.skippedPoints()
                : "";
        statusLabel.setText("Показано трейсов: " + selectedTraces.size()
                + " · хопов по трейсу: " + build.totalHops()
                + " · линий на карте: " + build.segments().size() + skipped);
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
        if ("я".equals(normalized)) {
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
        String direction = reverse ? "обратно" : "туда";
        String hops = totalHops > 0 ? " · " + totalHops + " " + traceHopWord(totalHops) : "";
        return Double.isNaN(snr)
                ? direction + hops + " · SNR н/д"
                : String.format(Locale.ROOT, "%s%s · SNR %.1f dB", direction, hops, snr);
    }

    /**
     * Возвращает правильную русскую форму слова «хоп» для указанного числа.
     */
    private String traceHopWord(int hops) {
        int mod10 = hops % 10;
        int mod100 = hops % 100;
        if (mod10 == 1 && mod100 != 11) {
            return "хоп";
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return "хопа";
        }
        return "хопов";
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
            statusLabel.setText("Нода не найдена");
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
            statusLabel.setText("У найденной ноды нет координат");
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
        statusLabel.setText("Найдена нода: " + nodeTitle(node));
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
        }

        markers.sort(Comparator.comparing(MapMarker::local).reversed().thenComparing(MapMarker::title, String.CASE_INSENSITIVE_ORDER));
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
            statusLabel.setText("Нет подключения к ноде");
            return;
        }

        NodeData myNode = state.getNodeDb().get(localNodeNum);
        if (!hasCoordinate(myNode)) {
            statusLabel.setText("Нет координат своей ноды");
            return;
        }

        mapView.setView(myNode.getLatitude(), myNode.getLongitude(), Math.max(mapView.getZoom(), 13));
        statusLabel.setText("Карта центрирована по своей ноде");
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
        chooser.setTitle("Каталог тайлов OSM");
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
            tileDirectoryLabel.setText("Тайлы: кэш " + mapView.cacheRoot());
        } else {
            tileDirectoryLabel.setText("Тайлы: " + root);
        }
    }

    /**
     * Запускает загрузку тайлов в локальный кэш и отображает прогресс.
     */
    private void downloadVisibleTiles() {
        downloadButton.setDisable(true);
        int count = mapView.downloadTileCount();
        statusLabel.setText("Загрузка " + count + " тайлов...");
        showDownloadProgress(0);
        mapView.downloadVisibleTiles(progress -> {
            statusLabel.setText(progress.message());
            if (progress.total() == 0) {
                downloadButton.setDisable(false);
                hideDownloadProgress();
                return;
            }
            showDownloadProgress((double) progress.completed() / progress.total());
            if (progress.completed() >= progress.total()) {
                downloadButton.setDisable(false);
                String message = "Доступно " + progress.available() + " из " + progress.total() + " тайлов";
                statusLabel.setText(message);
                hideDownloadProgress();
            }
        });
    }

    private void showDownloadProgress(double progress) {
        downloadProgressBar.setVisible(true);
        downloadProgressBar.setProgress(Math.max(0, Math.min(1, progress)));
    }

    private void hideDownloadProgress() {
        downloadProgressBar.setProgress(0);
        downloadProgressBar.setVisible(false);
    }

    /**
     * Сохранённый трейс, извлечённый из системного сообщения.
     *
     * @param dbId       id сообщения в БД
     * @param targetName имя целевой ноды
     * @param timestamp  время сообщения
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
