package com.meshtastic.client.components;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.model.PacketTreeNode;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.PacketMonitorService;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.PacketDebugFormatter;
import com.meshtastic.client.utils.SvgIconLoader;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Отдельное окно мониторинга LoRa mesh-пакетов.
 * Класс инкапсулирует всё состояние UI подсистемы: фильтры, выбранный пакет,
 * HEX/ASCII предпросмотр, дерево разбора, экспорт и сохранение геометрии окна.
 *
 * Основной контракт окна:
 * - новые пакеты появляются вверху таблицы;
 * - если пользователь изучает уже выбранный пакет, приход новых данных не должен
 *   сбрасывать выделение строки, дерева и HEX/ASCII подсветку;
 * - дерево и предпросмотр перестраиваются только при фактической смене выбранного пакета.
 */
public final class PacketMonitorWindow {

    private static final String FILTER_ALL_DIRECTIONS = "Все направления";
    private static final String FILTER_INCOMING = "Входящие";
    private static final String FILTER_OUTGOING = "Исходящие";
    private static final String FILTER_INTERNAL = "Внутренние";
    private static final String FILTER_ALL_TYPES = "Все типы";
    private static final int PAGE_SIZE = 200;
    private static final int PAGE_SHIFT = 80;
    private static final int HEX_PREVIEW_VISIBLE_ROWS = 16;
    private static final int HEX_PREVIEW_ADDRESS_COLUMNS = 6;
    private static final int HEX_PREVIEW_BYTES_COLUMNS = 50;
    private static final int HEX_PREVIEW_ASCII_COLUMNS = 18;
    private static final double TABLE_SCROLL_EDGE_THRESHOLD = 0.02;
    private static final double TABLE_SCROLL_PAGE_UP_THRESHOLD = 0.18;
    private static final double TABLE_SCROLL_PAGE_DOWN_THRESHOLD = 0.82;
    private static final double TABLE_SCROLL_PAGE_REARM_UP_THRESHOLD = 0.30;
    private static final double TABLE_SCROLL_PAGE_REARM_DOWN_THRESHOLD = 0.70;
    private static final double PACKET_TABLE_FIXED_CELL_SIZE = 28;
    private static final double PACKET_TABLE_HEADER_HEIGHT_ESTIMATE = 30;
    private static final double DEFAULT_WINDOW_WIDTH = 1260;
    private static final double DEFAULT_WINDOW_HEIGHT = 860;
    private static final double PACKET_TABLE_TIME_COLUMN_WIDTH = 190;
    private static final double PACKET_TABLE_TYPE_COLUMN_WIDTH = 130;
    private static final double PACKET_TABLE_NODE_COLUMN_WIDTH = 150;
    private static final double PACKET_TABLE_COMPACT_COLUMN_MIN_WIDTH = 72;
    private static final double PACKET_TABLE_PAYLOAD_MIN_WIDTH = 260;
    private static final double PACKET_TABLE_PAYLOAD_RUNTIME_MIN_WIDTH = 140;
    private static final DateTimeFormatter PACKET_EXPORT_FILE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static PacketMonitorWindow instance;

    private final PacketMonitorService packetMonitorService;
    private final ObservableList<PacketLogEntry> packetItems = FXCollections.observableArrayList();
    private final ObservableList<String> packetTypeFilters = FXCollections.observableArrayList(FILTER_ALL_TYPES);
    private final BooleanProperty suppressPacketTableTooltips = new SimpleBooleanProperty(false);
    private final ChangeListener<Number> packetTableScrollListener =
            (obs, oldValue, newValue) -> handlePacketTableScroll(
                    oldValue != null ? oldValue.doubleValue() : 0.0,
                    newValue != null ? newValue.doubleValue() : 0.0
            );

    private final PacketMonitorService.Listener packetListener = new PacketMonitorService.Listener() {
        @Override
        public void onPacketLogged(PacketLogEntry entry) {
            Platform.runLater(() -> handlePacketLogged(entry));
        }

        @Override
        public void onCaptureStateChanged(boolean captureEnabled) {
            Platform.runLater(PacketMonitorWindow.this::updateToolbarState);
        }

        @Override
        public void onCleared() {
            Platform.runLater(PacketMonitorWindow.this::handleCleared);
        }
    };

    private Stage stage;
    private SplitPane contentSplit;
    private TableView<PacketLogEntry> packetTable;
    private TextArea addressPreview;
    private TextArea hexPreview;
    private TextArea asciiPreview;
    private TreeView<PacketTreeNode> packetTree;
    private ComboBox<String> directionFilter;
    private ComboBox<String> typeFilter;
    private DateTimePicker fromDateTimeFilter;
    private DateTimePicker toDateTimeFilter;
    private TextField searchField;
    private Button btnStart;
    private Button btnStop;
    private Button btnClear;
    private Button btnCopyText;
    private Button btnCopyJson;
    private Button btnSaveText;
    private Button btnSaveJson;
    private Label statusLabel;
    private PacketLogEntry viewedPacketEntry;
    private long viewedPacketId = -1L;
    private boolean restoringSelection;
    private boolean restoringPacketTableColumnWidths;
    private boolean suppressFilterReload;
    private boolean pageLoadInProgress;
    private boolean ignoreTableScrollEvents;
    private boolean currentPageHasNewer;
    private boolean currentPageHasOlder;
    private boolean latestFrameDirty;
    private boolean topEdgeLoadArmed = true;
    private boolean bottomEdgeLoadArmed = true;
    private int matchingPacketCount;
    private int totalStoredPacketCount;
    private String rememberedTypeFilterSelection = FILTER_ALL_TYPES;
    private PacketDebugFormatter.HexPreview currentHexPreview = PacketDebugFormatter.formatHexPreview(null);
    private ScrollBar packetTableVerticalScrollBar;
    private double normalWindowX = Double.NaN;
    private double normalWindowY = Double.NaN;
    private double normalWindowWidth = DEFAULT_WINDOW_WIDTH;
    private double normalWindowHeight = DEFAULT_WINDOW_HEIGHT;
    private boolean restoreWindowMaximized;

    private PacketMonitorWindow() {
        this.packetMonitorService = PacketMonitorService.getInstance();
        createStage();
        packetMonitorService.addListener(packetListener);
        reloadCurrentFrame(true, true);
        updateToolbarState();
    }

    /**
     * Показывает singleton-окно мониторинга.
     * Если окно ещё не создано, оно инициализируется вместе со всем внутренним состоянием.
     */
    public static void showWindow() {
        if (Platform.isFxApplicationThread()) {
            showWindowInternal();
        } else {
            Platform.runLater(PacketMonitorWindow::showWindowInternal);
        }
    }

    /**
     * Создаёт singleton-окно при первом вызове и показывает его повторно при последующих.
     * Состояние окна живёт до фактического закрытия stage.
     */
    private static void showWindowInternal() {
        if (instance == null) {
            instance = new PacketMonitorWindow();
        }
        instance.ensureWindowVisible();
        instance.stage.show();
        if (instance.restoreWindowMaximized) {
            instance.captureCurrentWindowBounds();
            instance.stage.setMaximized(true);
            instance.restoreWindowMaximized = false;
        }
        instance.stage.toFront();
        instance.stage.requestFocus();
    }

    /**
     * Создаёт сцену и stage окна мониторинга.
     * Контракт:
     * - окно использует стандартную системную рамку;
     * - геометрия восстанавливается до {@code show()};
     * - геометрия сохраняется на hiding при любом способе закрытия окна.
     */
    private void createStage() {
        VBox root = new VBox(10);
        root.getStyleClass().add("packet-monitor-root");
        root.setPadding(new Insets(12));

        root.getChildren().addAll(createHeader(), createFilterBar(), createContentSplitPane());

        Scene scene = new Scene(root, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
        ThemeManager.applyTheme(scene, AppPreferences.isDarkMode());

        stage = new Stage();
        stage.initStyle(StageStyle.DECORATED);
        stage.setTitle("Мониторинг LoRa-пакетов");
        stage.setResizable(true);
        if (MeshApp.getPrimaryStage() != null && !MeshApp.getPrimaryStage().getIcons().isEmpty()) {
            stage.getIcons().setAll(MeshApp.getPrimaryStage().getIcons());
        }
        stage.setScene(scene);
        restoreWindowState();
        trackWindowBounds();
        stage.setOnHiding(event -> saveWindowState());
        stage.setOnHidden(event -> dispose());
    }

    private HBox createHeader() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Мониторинг LoRa-пакетов");
        title.getStyleClass().add("form-title");

        statusLabel = new Label();
        statusLabel.getStyleClass().add("config-status-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar toolBar = new ToolBar();
        toolBar.getStyleClass().add("logs-toolbar");

        btnStart = createToolbarButton(
                "Начать сбор",
                "Начать сохранение входящих и исходящих LoRa-пакетов",
                "/icons/play.svg",
                packetMonitorService::startCapture
        );
        btnStop = createToolbarButton(
                "Остановить сбор",
                "Остановить сохранение новых LoRa-пакетов",
                "/icons/pause.svg",
                packetMonitorService::stopCapture
        );
        btnClear = createToolbarButton(
                "Очистить данные",
                "Удалить уже собранные данные из таблицы и базы",
                "/icons/clear.svg",
                packetMonitorService::clear
        );

        toolBar.getItems().addAll(
                btnStart,
                btnStop,
                new Separator(Orientation.VERTICAL),
                btnClear
        );

        header.getChildren().addAll(title, statusLabel, spacer, toolBar);
        return header;
    }

    private HBox createFilterBar() {
        HBox filterBar = new HBox(10);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.getStyleClass().add("packet-monitor-filter-bar");

        directionFilter = new ComboBox<>(FXCollections.observableArrayList(
                FILTER_ALL_DIRECTIONS, FILTER_INCOMING, FILTER_OUTGOING, FILTER_INTERNAL));
        directionFilter.setValue(FILTER_ALL_DIRECTIONS);
        directionFilter.valueProperty().addListener((obs, oldValue, newValue) -> onFilterChanged());

        typeFilter = new ComboBox<>(packetTypeFilters);
        typeFilter.setValue(FILTER_ALL_TYPES);
        typeFilter.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!suppressFilterReload) {
                rememberTypeFilterSelection(newValue);
            }
            onFilterChanged();
        });

        fromDateTimeFilter = createDateTimePicker("дд.мм.гггг");
        toDateTimeFilter = createDateTimePicker("дд.мм.гггг");
        fromDateTimeFilter.popupShowingProperty().addListener((obs, oldValue, newValue) -> updatePacketTableTooltipSuppression());
        toDateTimeFilter.popupShowingProperty().addListener((obs, oldValue, newValue) -> updatePacketTableTooltipSuppression());

        searchField = new TextField();
        searchField.setPromptText("Поиск по типу, узлам и payload");
        searchField.getStyleClass().add("packet-monitor-search-field");
        searchField.textProperty().addListener((obs, oldValue, newValue) -> onFilterChanged());

        SVGPath searchIcon = SvgIconLoader.load("/icons/search.svg", 16);
        HBox searchBox = new HBox(8);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.getStyleClass().add("packet-monitor-search-box");
        if (searchIcon != null) {
            searchBox.getChildren().add(searchIcon);
        }
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchBox.getChildren().add(searchField);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        filterBar.getChildren().addAll(
                createFilterLabel("Направление"),
                directionFilter,
                createFilterLabel("Тип"),
                typeFilter,
                createFilterLabel("С"),
                fromDateTimeFilter,
                createFilterLabel("По"),
                toDateTimeFilter,
                spacer,
                searchBox
        );
        HBox.setHgrow(searchBox, Priority.ALWAYS);
        return filterBar;
    }

    /**
     * Собирает основной вертикальный split-контент окна:
     * сверху таблица пакетов, снизу предпросмотр и дерево разбора.
     * Положение разделителя хранится в {@link AppPreferences}.
     */
    private SplitPane createContentSplitPane() {
        packetTable = createPacketTable();

        HBox previewBox = createHexPreviewBox();

        packetTree = new TreeView<>();
        packetTree.setShowRoot(true);
        packetTree.setCellFactory(tree -> new TreeCell<>() {
            @Override
            protected void updateItem(PacketTreeNode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getLabel());
            }
        });
        packetTree.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldValue, newValue) -> highlightHexForSelection(newValue));

        HBox treeContent = new HBox(8, packetTree, createPacketActionsToolbar());
        HBox.setHgrow(packetTree, Priority.ALWAYS);

        VBox tableSection = createSection("Таблица пакетов", packetTable);
        VBox hexSection = createSection("HEX / ASCII предпросмотр", previewBox);
        hexSection.setMinWidth(Region.USE_PREF_SIZE);
        hexSection.setMaxWidth(Region.USE_PREF_SIZE);
        VBox treeSection = createSection("Иерархия пакета", treeContent);
        treeSection.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(treeSection, Priority.ALWAYS);

        HBox bottomContent = new HBox(10, hexSection, treeSection);
        HBox.setHgrow(bottomContent, Priority.ALWAYS);

        contentSplit = new SplitPane(tableSection, bottomContent);
        contentSplit.setOrientation(Orientation.VERTICAL);
        contentSplit.setDividerPositions(AppPreferences.getPacketMonitorDividerPos());
        contentSplit.getDividers().getFirst().positionProperty().addListener((obs, oldValue, newValue) ->
                AppPreferences.setPacketMonitorDividerPos(newValue.doubleValue()));
        VBox.setVgrow(contentSplit, Priority.ALWAYS);
        return contentSplit;
    }

    /**
     * Создаёт фиксированный трёхколоночный HEX/ASCII предпросмотр.
     * Адреса, HEX и ASCII рендерятся отдельно, чтобы подсветка никогда не затрагивала адресную колонку.
     */
    private HBox createHexPreviewBox() {
        addressPreview = createPreviewTextArea("packet-monitor-hex-address");
        hexPreview = createPreviewTextArea("packet-monitor-hex-bytes");
        asciiPreview = createPreviewTextArea("packet-monitor-hex-ascii");

        addressPreview.setPrefColumnCount(HEX_PREVIEW_ADDRESS_COLUMNS);
        addressPreview.setMinWidth(Region.USE_PREF_SIZE);
        addressPreview.setMaxWidth(Region.USE_PREF_SIZE);
        hexPreview.setPrefColumnCount(HEX_PREVIEW_BYTES_COLUMNS);
        hexPreview.setMinWidth(Region.USE_PREF_SIZE);
        hexPreview.setMaxWidth(Region.USE_PREF_SIZE);
        asciiPreview.setPrefColumnCount(HEX_PREVIEW_ASCII_COLUMNS);
        asciiPreview.setMinWidth(Region.USE_PREF_SIZE);
        asciiPreview.setMaxWidth(Region.USE_PREF_SIZE);

        HBox previewBox = new HBox(4, addressPreview, hexPreview, asciiPreview);
        previewBox.getStyleClass().add("packet-monitor-hex-grid");
        previewBox.setMinWidth(Region.USE_PREF_SIZE);
        previewBox.setMaxWidth(Region.USE_PREF_SIZE);
        previewBox.setMinHeight(Region.USE_PREF_SIZE);
        previewBox.setMaxHeight(Region.USE_PREF_SIZE);
        return previewBox;
    }

    /**
     * Создаёт вертикальный toolbar действий над текущим пакетом.
     * Кнопки зависят только от {@link #viewedPacketEntry} и не должны менять selection в таблице.
     */
    private ToolBar createPacketActionsToolbar() {
        btnCopyText = createPacketActionButton(
                "Копировать текст",
                "Скопировать выбранный пакет в текстовом виде",
                "/icons/copy-text.svg",
                this::copyViewedPacketAsText
        );
        btnCopyJson = createPacketActionButton(
                "Копировать JSON",
                "Скопировать выбранный пакет в JSON",
                "/icons/copy-json.svg",
                this::copyViewedPacketAsJson
        );
        btnSaveText = createPacketActionButton(
                "Сохранить текст",
                "Сохранить выбранный пакет в текстовый файл",
                "/icons/save-text.svg",
                this::saveViewedPacketAsText
        );
        btnSaveJson = createPacketActionButton(
                "Сохранить JSON",
                "Сохранить выбранный пакет в JSON файл",
                "/icons/save-json.svg",
                this::saveViewedPacketAsJson
        );

        ToolBar toolBar = new ToolBar(
                btnCopyText,
                btnCopyJson,
                new Separator(Orientation.HORIZONTAL),
                btnSaveText,
                btnSaveJson
        );
        toolBar.setOrientation(Orientation.VERTICAL);
        toolBar.getStyleClass().add("packet-monitor-side-toolbar");
        updatePacketActionButtonsState();
        return toolBar;
    }

    private TextArea createPreviewTextArea(String styleClass) {
        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setPrefRowCount(HEX_PREVIEW_VISIBLE_ROWS);
        textArea.setMinHeight(Region.USE_PREF_SIZE);
        textArea.setPrefHeight(Region.USE_COMPUTED_SIZE);
        textArea.setMaxHeight(Region.USE_PREF_SIZE);
        textArea.getStyleClass().addAll("packet-monitor-hex", styleClass);
        return textArea;
    }

    /**
     * Создаёт таблицу логов пакетов.
     * Selection listener перестраивает детали только если выбран другой пакет,
     * а не если JavaFX переиздал событие на ту же запись.
     * Стартовые ширины колонок задаются явно, но после открытия окна пользователь
     * может свободно менять их вручную.
     */
    private TableView<PacketLogEntry> createPacketTable() {
        TableView<PacketLogEntry> table = new TableView<>(packetItems);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(PACKET_TABLE_FIXED_CELL_SIZE);

        TableColumn<PacketLogEntry, String> colTime = new TableColumn<>("Дата/время");
        colTime.setCellValueFactory(new PropertyValueFactory<>("capturedAtText"));
        configureCompactColumn(colTime, PACKET_TABLE_TIME_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colType = new TableColumn<>("Тип пакета");
        colType.setCellValueFactory(new PropertyValueFactory<>("packetType"));
        configureCompactColumn(colType, PACKET_TABLE_TYPE_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colFrom = new TableColumn<>("От");
        colFrom.setCellValueFactory(cellData -> new ReadOnlyStringWrapper(
                formatPacketFromNode(cellData.getValue())));
        configureCompactColumn(colFrom, PACKET_TABLE_NODE_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colTo = new TableColumn<>("Кому");
        colTo.setCellValueFactory(cellData -> new ReadOnlyStringWrapper(
                formatPacketToNode(cellData.getValue())));
        configureCompactColumn(colTo, PACKET_TABLE_NODE_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colPayload = new TableColumn<>("Payload");
        colPayload.setCellValueFactory(new PropertyValueFactory<>("payloadText"));
        colPayload.setMinWidth(PACKET_TABLE_PAYLOAD_RUNTIME_MIN_WIDTH);
        colPayload.setPrefWidth(PACKET_TABLE_PAYLOAD_MIN_WIDTH);
        colPayload.setMaxWidth(Double.MAX_VALUE);
        colPayload.setResizable(true);
        colPayload.setSortable(false);

        restorePacketTableColumnWidths(colTime, colType, colFrom, colTo, colPayload);
        trackPacketTableColumnWidth(colTime, AppPreferences.KEY_PACKET_MONITOR_COLUMN_TIME_WIDTH);
        trackPacketTableColumnWidth(colType, AppPreferences.KEY_PACKET_MONITOR_COLUMN_TYPE_WIDTH);
        trackPacketTableColumnWidth(colFrom, AppPreferences.KEY_PACKET_MONITOR_COLUMN_FROM_WIDTH);
        trackPacketTableColumnWidth(colTo, AppPreferences.KEY_PACKET_MONITOR_COLUMN_TO_WIDTH);
        trackPacketTableColumnWidth(colPayload, AppPreferences.KEY_PACKET_MONITOR_COLUMN_PAYLOAD_WIDTH);

        table.getColumns().addAll(colTime, colType, colFrom, colTo, colPayload);
        table.setRowFactory(tv -> new TableRow<>() {
            private final Tooltip rowTooltip = new Tooltip();

            @Override
            protected void updateItem(PacketLogEntry item, boolean empty) {
                super.updateItem(item, empty);
                setStyle("");
                tooltipProperty().unbind();
                setTooltip(null);
                if (empty || item == null) {
                    rowTooltip.hide();
                    return;
                }
                switch (item.getDirection()) {
                    case INCOMING -> setStyle("-fx-text-fill: -color-accent-emphasis;");
                    case OUTGOING -> setStyle("-fx-text-fill: -color-success-emphasis;");
                    case INTERNAL -> setStyle("-fx-text-fill: -color-fg-muted;");
                }
                tooltipProperty().bind(Bindings.createObjectBinding(() -> {
                    if (suppressPacketTableTooltips.get()) {
                        rowTooltip.hide();
                        return null;
                    }
                    rowTooltip.setText(item.getDirectionText() + ": " + item.getPayloadText());
                    return rowTooltip;
                }, itemProperty(), suppressPacketTableTooltips));
            }
        });
        table.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldValue, newValue) -> {
                    if (restoringSelection || ignoreTableScrollEvents || pageLoadInProgress || newValue == null) {
                        return;
                    }
                    if (newValue.getId() == viewedPacketId) {
                        return;
                    }
                    updatePacketDetails(newValue);
                });
        table.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                Platform.runLater(this::attachPacketTableScrollListener);
            }
        });
        VBox.setVgrow(table, Priority.ALWAYS);
        return table;
    }

    private Button createPacketActionButton(String title, String tooltipText, String iconPath, Runnable action) {
        Button button = new Button();
        button.getStyleClass().addAll("logs-toolbar-button", "packet-monitor-side-button");
        button.setTooltip(new Tooltip(title + "\n" + tooltipText));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setFocusTraversable(false);

        SVGPath icon = SvgIconLoader.load(iconPath, 18);
        if (icon != null) {
            button.setGraphic(icon);
        } else {
            button.setText(title);
            button.setContentDisplay(ContentDisplay.TEXT_ONLY);
        }

        button.setOnAction(event -> action.run());
        return button;
    }

    private void configureCompactColumn(TableColumn<PacketLogEntry, String> column, double width) {
        column.setMinWidth(Math.min(PACKET_TABLE_COMPACT_COLUMN_MIN_WIDTH, width));
        column.setPrefWidth(width);
        column.setMaxWidth(Double.MAX_VALUE);
        column.setResizable(true);
        column.setSortable(false);
    }

    /**
     * Восстанавливает пользовательские ширины колонок таблицы из конфигурации приложения.
     * Стартовые размеры остаются fallback-значениями на первый запуск либо если настройка отсутствует.
     */
    private void restorePacketTableColumnWidths(TableColumn<PacketLogEntry, String> colTime,
                                                TableColumn<PacketLogEntry, String> colType,
                                                TableColumn<PacketLogEntry, String> colFrom,
                                                TableColumn<PacketLogEntry, String> colTo,
                                                TableColumn<PacketLogEntry, String> colPayload) {
        restoringPacketTableColumnWidths = true;
        applyPacketTableColumnWidth(colTime,
                AppPreferences.KEY_PACKET_MONITOR_COLUMN_TIME_WIDTH,
                PACKET_TABLE_TIME_COLUMN_WIDTH);
        applyPacketTableColumnWidth(colType,
                AppPreferences.KEY_PACKET_MONITOR_COLUMN_TYPE_WIDTH,
                PACKET_TABLE_TYPE_COLUMN_WIDTH);
        applyPacketTableColumnWidth(colFrom,
                AppPreferences.KEY_PACKET_MONITOR_COLUMN_FROM_WIDTH,
                PACKET_TABLE_NODE_COLUMN_WIDTH);
        applyPacketTableColumnWidth(colTo,
                AppPreferences.KEY_PACKET_MONITOR_COLUMN_TO_WIDTH,
                PACKET_TABLE_NODE_COLUMN_WIDTH);
        applyPacketTableColumnWidth(colPayload,
                AppPreferences.KEY_PACKET_MONITOR_COLUMN_PAYLOAD_WIDTH,
                PACKET_TABLE_PAYLOAD_MIN_WIDTH);
        Platform.runLater(() -> restoringPacketTableColumnWidths = false);
    }

    /**
     * Применяет сохранённую ширину к одной колонке с учётом её runtime-ограничений.
     */
    private void applyPacketTableColumnWidth(TableColumn<PacketLogEntry, String> column,
                                             String preferenceKey,
                                             double defaultWidth) {
        double savedWidth = AppPreferences.getPacketMonitorColumnWidth(preferenceKey, defaultWidth);
        double boundedWidth = Math.max(column.getMinWidth(), savedWidth);
        column.setPrefWidth(boundedWidth);
    }

    /**
     * Подписывает колонку на сохранение текущей ширины в preferences.
     * Во время начального восстановления значения игнорируются, чтобы стартовый layout
     * не перетирал уже сохранённые пользовательские настройки.
     */
    private void trackPacketTableColumnWidth(TableColumn<PacketLogEntry, String> column, String preferenceKey) {
        column.widthProperty().addListener((obs, oldValue, newValue) -> {
            if (restoringPacketTableColumnWidths || newValue == null) {
                return;
            }
            double width = newValue.doubleValue();
            if (width > 0) {
                AppPreferences.setPacketMonitorColumnWidth(preferenceKey, width);
            }
        });
    }

    private VBox createSection(String title, javafx.scene.Node content) {
        VBox box = new VBox(8);
        box.getStyleClass().add("packet-monitor-section");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("packet-monitor-section-title");

        VBox.setVgrow(content, Priority.ALWAYS);
        box.getChildren().addAll(titleLabel, content);
        return box;
    }

    private Label createFilterLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("packet-monitor-filter-label");
        return label;
    }

    /**
     * Создаёт фильтр даты/времени на базе DatePicker с кастомным popup-календарём.
     * В нижней части popup доступны выбор времени через слайдеры часов и минут.
     */
    private DateTimePicker createDateTimePicker(String promptText) {
        return new DateTimePicker(promptText, this::onFilterChanged);
    }

    private void updatePacketTableTooltipSuppression() {
        suppressPacketTableTooltips.set(
                (fromDateTimeFilter != null && fromDateTimeFilter.isPopupShowing())
                        || (toDateTimeFilter != null && toDateTimeFilter.isPopupShowing())
        );
    }

    private Button createToolbarButton(String title, String tooltipText, String iconPath, Runnable action) {
        Button button = new Button(title);
        button.getStyleClass().addAll("logs-toolbar-button", "packet-monitor-toolbar-button");
        button.setTooltip(new Tooltip(title + "\n" + tooltipText));
        button.setContentDisplay(ContentDisplay.LEFT);
        setToolbarButtonGraphic(button, iconPath, title);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void setToolbarButtonGraphic(Button button, String iconPath, String fallbackText) {
        SVGPath icon = SvgIconLoader.load(iconPath, 18);
        if (icon != null) {
            button.setGraphic(icon);
            button.setText(fallbackText);
            button.setContentDisplay(ContentDisplay.LEFT);
        } else {
            button.setGraphic(null);
            button.setText(fallbackText);
            button.setContentDisplay(ContentDisplay.TEXT_ONLY);
        }
    }

    /**
     * Срабатывает при изменении любого UI-фильтра.
     * Таблица всегда возвращается к верхнему срезу выборки, потому что клиент
     * больше не держит полный набор записей в памяти.
     */
    private void onFilterChanged() {
        if (suppressFilterReload) {
            return;
        }
        latestFrameDirty = false;
        reloadCurrentFrame(true, true);
    }

    /**
     * Загружает самый новый sliding-window выборки из БД.
     * Используется при старте окна и смене фильтров как базовое состояние таблицы.
     */
    private void reloadCurrentFrame(boolean preserveViewedPacket, boolean clearDetailsIfSelectionLost) {
        pageLoadInProgress = true;
        try {
            long packetIdToKeep = preserveViewedPacket ? viewedPacketId : -1L;
            refreshTypeFilters();
            PacketMonitorService.PacketPage page =
                    packetMonitorService.loadLatestPage(buildCurrentQuery(), PAGE_SIZE);
            applyLoadedPage(page,
                    packetIdToKeep,
                    clearDetailsIfSelectionLost,
                    viewedPacketId <= 0,
                    0);
        } finally {
            pageLoadInProgress = false;
        }
    }

    /**
     * Подгружает следующую страницу старых пакетов, когда пользователь дошёл до низа таблицы.
     * Новый срез полностью заменяет предыдущий, поэтому в памяти не остаётся больше {@value #PAGE_SIZE} строк.
     */
    private void loadOlderPageFromScroll() {
        if (pageLoadInProgress || !currentPageHasOlder || packetItems.isEmpty()) {
            return;
        }

        pageLoadInProgress = true;
        try {
            int anchorIndex = estimateFirstVisibleRow();
            PacketMonitorService.PacketPage page =
                    packetMonitorService.loadOlderPage(
                            buildCurrentQuery(),
                            PacketMonitorService.PageCursor.fromEntry(packetItems.getLast()),
                            PAGE_SHIFT
                    );
            if (page.entries().isEmpty()) {
                currentPageHasOlder = false;
                return;
            }
            appendOlderChunk(page, anchorIndex);
        } finally {
            pageLoadInProgress = false;
        }
    }

    /**
     * Подгружает предыдущий фрейм более новых пакетов, когда пользователь дошёл до верха таблицы.
     * Если текущий фрейм уже является самым новым, но был помечен грязным из-за live-вставок,
     * выполняется переагрузка первого фрейма.
     */
    private void loadNewerPageFromScroll() {
        if (pageLoadInProgress || (!currentPageHasNewer && !latestFrameDirty) || packetItems.isEmpty()) {
            return;
        }

        pageLoadInProgress = true;
        try {
            int anchorIndex = estimateFirstVisibleRow();
            PacketMonitorService.PacketPage page =
                    packetMonitorService.loadNewerPage(
                            buildCurrentQuery(),
                            PacketMonitorService.PageCursor.fromEntry(packetItems.getFirst()),
                            PAGE_SHIFT
                    );
            if (page.entries().isEmpty()) {
                currentPageHasNewer = false;
                latestFrameDirty = false;
                return;
            }
            prependNewerChunk(page, anchorIndex);
        } finally {
            pageLoadInProgress = false;
        }
    }

    /**
     * Применяет к таблице уже загруженную страницу.
     * Контракт:
     * - список таблицы полностью заменяется новым срезом;
     * - если просматриваемый пакет остался в срезе, selection сохраняется;
     * - если пакет исчез из таблицы, детали очищаются только в тех сценариях,
     *   где это инициировал пользователь явно (например сменой фильтра).
     */
    private void applyLoadedPage(PacketMonitorService.PacketPage page,
                                 long packetIdToKeep,
                                 boolean clearDetailsIfSelectionLost,
                                 boolean selectFirstWhenNoViewedPacket,
                                 int scrollToIndex) {
        ignoreTableScrollEvents = true;
        try {
            packetItems.setAll(page.entries());
            matchingPacketCount = page.totalMatchingCount();
            totalStoredPacketCount = page.totalStoredCount();
            currentPageHasNewer = page.hasNewer();
            currentPageHasOlder = page.hasOlder();
            latestFrameDirty = false;

            boolean restored = restoreViewedSelection(packetIdToKeep);
            if (!restored) {
                clearTableSelectionSilently();
                if (selectFirstWhenNoViewedPacket && !packetItems.isEmpty()) {
                    selectPacket(packetItems.getFirst(), false, true);
                } else if (clearDetailsIfSelectionLost) {
                    updatePacketDetails(null);
                }
            }
            updateToolbarState();
        } finally {
            releaseTableScrollIgnoreLater(scrollToIndex);
        }
    }

    private void appendOlderChunk(PacketMonitorService.PacketPage page, int anchorIndex) {
        ignoreTableScrollEvents = true;
        restoringSelection = true;
        int removedFromTop = 0;
        try {
            packetItems.addAll(page.entries());
            removedFromTop = Math.max(0, packetItems.size() - PAGE_SIZE);
            for (int i = 0; i < removedFromTop; i++) {
                packetItems.removeFirst();
            }
            matchingPacketCount = page.totalMatchingCount();
            totalStoredPacketCount = page.totalStoredCount();
            currentPageHasOlder = page.hasOlder();
            currentPageHasNewer = removedFromTop > 0 || currentPageHasNewer || latestFrameDirty;
            latestFrameDirty = false;
            preserveViewedSelectionAfterWindowShift();
            updateToolbarState();
        } finally {
            restoringSelection = false;
        }
        releaseTableScrollIgnoreLater(Math.max(0, anchorIndex - removedFromTop));
    }

    private void prependNewerChunk(PacketMonitorService.PacketPage page, int anchorIndex) {
        ignoreTableScrollEvents = true;
        restoringSelection = true;
        int removedFromBottom = 0;
        try {
            packetItems.addAll(0, page.entries());
            removedFromBottom = Math.max(0, packetItems.size() - PAGE_SIZE);
            for (int i = 0; i < removedFromBottom; i++) {
                packetItems.removeLast();
            }
            matchingPacketCount = page.totalMatchingCount();
            totalStoredPacketCount = page.totalStoredCount();
            currentPageHasNewer = page.hasNewer();
            currentPageHasOlder = page.hasOlder() || removedFromBottom > 0;
            latestFrameDirty = false;
            preserveViewedSelectionAfterWindowShift();
            updateToolbarState();
        } finally {
            restoringSelection = false;
        }
        releaseTableScrollIgnoreLater(Math.min(packetItems.size() - 1, anchorIndex + page.entries().size()));
    }

    /**
     * Перестраивает набор значений фильтра по типу пакета.
     * Значения загружаются из БД по текущим фильтрам направления и поиска, но
     * ещё не ограничиваются уже выбранным типом.
     */
    private void refreshTypeFilters() {
        if (typeFilter == null) {
            return;
        }

        String previousSelection = getActiveTypeFilterSelection();
        List<String> refreshedOptions = buildTypeFilterOptions(
                packetMonitorService.loadPacketTypes(buildTypeOptionsQuery()),
                previousSelection);
        String restoredSelection = resolveTypeFilterSelection(previousSelection, refreshedOptions);
        suppressFilterReload = true;
        try {
            packetTypeFilters.setAll(refreshedOptions);
            typeFilter.getSelectionModel().select(restoredSelection);
            typeFilter.setValue(restoredSelection);
            rememberTypeFilterSelection(restoredSelection);
        } finally {
            suppressFilterReload = false;
        }
    }

    private void updateToolbarState() {
        boolean captureEnabled = packetMonitorService.isCaptureEnabled();
        btnStart.setDisable(captureEnabled);
        btnStop.setDisable(!captureEnabled);
        btnClear.setDisable(totalStoredPacketCount == 0);
        statusLabel.setText(captureEnabled ? "Сбор активен" : "Сбор остановлен");
    }

    /**
     * Обрабатывает live-приход нового пакета.
     * Автоматическая вставка выполняется только если пользователь реально находится
     * у верхней границы newest-page; иначе окно лишь помечает, что сверху появились
     * новые данные, и подхватит их при обратной прокрутке к верху.
     */
    private void handlePacketLogged(PacketLogEntry entry) {
        totalStoredPacketCount++;
        refreshTypeFilters();

        if (!matchesCurrentFilters(entry)) {
            updateToolbarState();
            return;
        }

        matchingPacketCount++;
        if (currentPageHasNewer || latestFrameDirty) {
            currentPageHasNewer = true;
            currentPageHasOlder = matchingPacketCount > packetItems.size();
            updateToolbarState();
            return;
        }

        if (!isTableNearTop()) {
            latestFrameDirty = true;
            currentPageHasNewer = true;
            currentPageHasOlder = matchingPacketCount > packetItems.size();
            updateToolbarState();
            return;
        }

        int insertionIndex = findInsertionIndex(entry);
        if (insertionIndex >= PAGE_SIZE) {
            currentPageHasOlder = matchingPacketCount > packetItems.size();
            updateToolbarState();
            return;
        }
        if (packetItems.size() >= PAGE_SIZE
                && !packetItems.isEmpty()
                && packetItems.getLast().getId() == viewedPacketId) {
            latestFrameDirty = true;
            currentPageHasNewer = true;
            currentPageHasOlder = matchingPacketCount > packetItems.size();
            updateToolbarState();
            return;
        }

        ignoreTableScrollEvents = true;
        packetItems.add(insertionIndex, entry);
        if (packetItems.size() > PAGE_SIZE) {
            packetItems.removeLast();
        }
        currentPageHasNewer = false;
        currentPageHasOlder = matchingPacketCount > packetItems.size();
        latestFrameDirty = false;

        if (viewedPacketId > 0) {
            restoreViewedSelection(viewedPacketId);
        } else {
            selectPacket(entry, false, true);
        }

        updateToolbarState();
        releaseTableScrollIgnoreLater(-1);
    }

    /**
     * Сбрасывает окно после полной очистки журнала.
     * После вызова не должно оставаться ссылки на ранее открытый пакет.
     */
    private void handleCleared() {
        packetItems.clear();
        matchingPacketCount = 0;
        totalStoredPacketCount = 0;
        currentPageHasNewer = false;
        currentPageHasOlder = false;
        latestFrameDirty = false;
        refreshTypeFilters();
        clearTableSelectionSilently();
        updatePacketDetails(null);
        updateToolbarState();
    }

    /**
     * Перестраивает дерево и предпросмотр под выбранный пакет.
     * При {@code null} переводит правую часть окна в placeholder-состояние.
     */
    private void updatePacketDetails(PacketLogEntry entry) {
        if (entry == null) {
            viewedPacketId = -1L;
            viewedPacketEntry = null;
            currentHexPreview = PacketDebugFormatter.formatHexPreview(null);
            addressPreview.setText("");
            hexPreview.setText("Выберите пакет в таблице");
            asciiPreview.setText("");
            clearPreviewSelection();
            TreeItem<PacketTreeNode> placeholder = new TreeItem<>(new PacketTreeNode("MeshPacket"));
            placeholder.setExpanded(true);
            placeholder.getChildren().add(new TreeItem<>(new PacketTreeNode("Выберите пакет в таблице")));
            packetTree.setRoot(placeholder);
            packetTree.getSelectionModel().clearSelection();
            updatePacketActionButtonsState();
            return;
        }

        viewedPacketId = entry.getId();
        viewedPacketEntry = entry;
        currentHexPreview = PacketDebugFormatter.formatHexPreview(entry.getPacketBytes());
        addressPreview.setText(currentHexPreview.addressText());
        hexPreview.setText(currentHexPreview.hexText());
        asciiPreview.setText(currentHexPreview.asciiText());
        clearPreviewSelection();
        TreeItem<PacketTreeNode> root = PacketDebugFormatter.buildPacketTree(entry.getPacketBytes());
        expandTree(root);
        packetTree.setRoot(root);
        packetTree.getSelectionModel().clearSelection();
        updatePacketActionButtonsState();
    }

    /**
     * Возвращает selection на уже просматриваемый пакет после замены страницы.
     * Не должен пересобирать детали, если пользователь по факту остаётся на той же записи.
     *
     * @return {@code true}, если пакет по-прежнему присутствует в текущей странице
     */
    private boolean restoreViewedSelection(long packetIdToKeep) {
        if (packetTable == null) {
            return false;
        }
        if (packetIdToKeep <= 0) {
            return false;
        }

        PacketLogEntry currentSelection = packetTable.getSelectionModel().getSelectedItem();
        if (currentSelection != null && currentSelection.getId() == packetIdToKeep) {
            return true;
        }

        PacketLogEntry entryToKeep = findVisiblePacketById(packetIdToKeep);
        if (entryToKeep != null) {
            Node focusOwner = stage != null && stage.getScene() != null ? stage.getScene().getFocusOwner() : null;
            selectPacket(entryToKeep, false, false);
            if (focusOwner != null && focusOwner != packetTable) {
                Platform.runLater(focusOwner::requestFocus);
            }
            return true;
        }
        return false;
    }

    /**
     * После сдвига sliding-window не позволяет JavaFX самовольно переключить выбор
     * на соседнюю строку, если исходный пакет вышел из окна.
     */
    private void preserveViewedSelectionAfterWindowShift() {
        synchronizeTableSelectionWithViewedPacket();
    }

    /**
     * Держит table selection согласованным с уже открытым пакетом.
     * Если пакет вышел из текущего sliding-window, выделение строки снимается,
     * но правая панель не переключается на соседний элемент.
     */
    private void synchronizeTableSelectionWithViewedPacket() {
        if (packetTable == null) {
            return;
        }
        restoringSelection = true;
        try {
            if (viewedPacketId > 0 && restoreViewedSelection(viewedPacketId)) {
                return;
            }
            packetTable.getSelectionModel().clearSelection();
        } finally {
            restoringSelection = false;
        }
    }

    private PacketLogEntry findVisiblePacketById(long packetId) {
        return packetItems.stream()
                .filter(item -> item.getId() == packetId)
                .findFirst()
                .orElse(null);
    }

    private int findInsertionIndex(PacketLogEntry entry) {
        for (int i = 0; i < packetItems.size(); i++) {
            if (comparePacketOrder(entry, packetItems.get(i)) < 0) {
                return i;
            }
        }
        return packetItems.size();
    }

    /**
     * Сравнивает записи в том же порядке, в котором они отображаются в таблице:
     * сначала более новое {@code capturedAt}, затем больший {@code id}.
     *
     * @return отрицательное значение, если {@code left} должен идти раньше {@code right}
     */
    private int comparePacketOrder(PacketLogEntry left, PacketLogEntry right) {
        int byCapturedAt = Long.compare(right.getCapturedAt(), left.getCapturedAt());
        if (byCapturedAt != 0) {
            return byCapturedAt;
        }
        return Long.compare(right.getId(), left.getId());
    }

    private PacketMonitorService.PacketQuery buildCurrentQuery() {
        PacketLogEntry.Direction direction = null;
        String directionValue = directionFilter != null ? directionFilter.getValue() : FILTER_ALL_DIRECTIONS;
        if (FILTER_INCOMING.equals(directionValue)) {
            direction = PacketLogEntry.Direction.INCOMING;
        } else if (FILTER_OUTGOING.equals(directionValue)) {
            direction = PacketLogEntry.Direction.OUTGOING;
        } else if (FILTER_INTERNAL.equals(directionValue)) {
            direction = PacketLogEntry.Direction.INTERNAL;
        }

        String selectedType = getActiveTypeFilterSelection();
        String packetType = FILTER_ALL_TYPES.equals(selectedType) ? null : selectedType;
        String searchText = searchField != null ? searchField.getText() : null;
        Long capturedAtFromMillis = resolveCapturedAtBoundary(fromDateTimeFilter, true);
        Long capturedAtToMillis = resolveCapturedAtBoundary(toDateTimeFilter, false);
        return new PacketMonitorService.PacketQuery(
                direction,
                packetType,
                searchText,
                capturedAtFromMillis,
                capturedAtToMillis
        );
    }

    private PacketMonitorService.PacketQuery buildTypeOptionsQuery() {
        PacketMonitorService.PacketQuery query = buildCurrentQuery();
        return new PacketMonitorService.PacketQuery(
                query.direction(),
                null,
                query.searchText(),
                query.capturedAtFromMillis(),
                query.capturedAtToMillis()
        );
    }

    /**
     * Локальная проверка live-пакета на соответствие текущим UI-фильтрам.
     * Используется только для инкрементального обновления счётчиков и верхнего среза,
     * тогда как основная фильтрация выполняется на стороне БД.
     */
    private boolean matchesCurrentFilters(PacketLogEntry entry) {
        if (entry == null) {
            return false;
        }

        String selectedDirection = directionFilter != null ? directionFilter.getValue() : FILTER_ALL_DIRECTIONS;
        if (FILTER_INCOMING.equals(selectedDirection) && entry.getDirection() != PacketLogEntry.Direction.INCOMING) {
            return false;
        }
        if (FILTER_OUTGOING.equals(selectedDirection) && entry.getDirection() != PacketLogEntry.Direction.OUTGOING) {
            return false;
        }
        if (FILTER_INTERNAL.equals(selectedDirection) && entry.getDirection() != PacketLogEntry.Direction.INTERNAL) {
            return false;
        }

        String selectedType = getActiveTypeFilterSelection();
        if (selectedType != null && !FILTER_ALL_TYPES.equals(selectedType)
                && !selectedType.equals(entry.getPacketType())) {
            return false;
        }

        Long capturedAtFromMillis = resolveCapturedAtBoundary(fromDateTimeFilter, true);
        if (capturedAtFromMillis != null && entry.getCapturedAt() < capturedAtFromMillis) {
            return false;
        }
        Long capturedAtToMillis = resolveCapturedAtBoundary(toDateTimeFilter, false);
        if (capturedAtToMillis != null && entry.getCapturedAt() > capturedAtToMillis) {
            return false;
        }

        String query = searchField != null ? searchField.getText() : null;
        if (query == null || query.isBlank()) {
            return true;
        }

        String lowerQuery = query.toLowerCase(java.util.Locale.ROOT);
        return containsIgnoreCase(entry.getPacketType(), lowerQuery)
                || containsIgnoreCase(formatPacketFromNode(entry), lowerQuery)
                || containsIgnoreCase(formatPacketToNode(entry), lowerQuery)
                || containsIgnoreCase(entry.getPayloadText(), lowerQuery)
                || containsIgnoreCase(entry.getDirectionText(), lowerQuery);
    }

    /**
     * Формирует UI-представление поля {@code from} в виде {@code Имя (!nodeId)}.
     * Если имя ноды недоступно, возвращается только стандартный {@code !nodeId};
     * сохранённое в БД значение используется только как fallback при ошибке разбора пакета.
     */
    private String formatPacketFromNode(PacketLogEntry entry) {
        return resolvePacketEndpoints(entry).fromNode();
    }

    /**
     * Формирует UI-представление поля {@code to} в виде {@code Имя (!nodeId)}.
     * Если имя ноды недоступно, возвращается только стандартный {@code !nodeId};
     * сохранённое в БД значение используется только как fallback при ошибке разбора пакета.
     */
    private String formatPacketToNode(PacketLogEntry entry) {
        return resolvePacketEndpoints(entry).toNode();
    }

    /**
     * Пересчитывает подписи {@code От}/{@code Кому} из байтов пакета без модификации БД.
     * Для уже сохранённых записей это позволяет показывать одинаковый формат в таблице
     * и текстовом экспорте независимо от исторического содержимого колонок {@code from_node/to_node}.
     */
    private PacketDebugFormatter.PacketEndpoints resolvePacketEndpoints(PacketLogEntry entry) {
        return PacketDebugFormatter.resolvePacketEndpoints(entry, resolveEntryDeviceState(entry));
    }

    /**
     * Подбирает {@link DeviceState}, соответствующий owner-нode записи пакета.
     * Контракт:
     * - при точном совпадении {@code ownerNodeId} используется связанный активный {@link DeviceState};
     * - если owner пустой и есть единственное активное подключение, используется его состояние;
     * - при отсутствии подходящего подключения возвращается {@code null}, и formatter
     *   отображает только стандартные {@code !nodeId} без имени.
     */
    private DeviceState resolveEntryDeviceState(PacketLogEntry entry) {
        if (entry == null) {
            return null;
        }

        String ownerNodeId = entry.getOwnerNodeId();
        ConnectionManager connectionManager = ConnectionManager.getInstance();
        DeviceState fallback = null;
        for (ConnectionEntry connectionEntry : connectionManager.getEntries()) {
            DeviceState deviceState = connectionManager.getDeviceState(connectionEntry.getId());
            if (deviceState == null) {
                continue;
            }

            if (fallback == null) {
                fallback = deviceState;
            }

            String connectionOwnerNodeId = connectionManager.getOwnerNodeId(connectionEntry.getId());
            if (ownerNodeId != null && !ownerNodeId.isBlank()
                    && ownerNodeId.equalsIgnoreCase(connectionOwnerNodeId)) {
                return deviceState;
            }
        }

        return (ownerNodeId == null || ownerNodeId.isBlank()) ? fallback : null;
    }

    private String getActiveTypeFilterSelection() {
        String selectedType = typeFilter != null ? typeFilter.getValue() : null;
        return normalizeTypeFilterSelection(selectedType != null ? selectedType : rememberedTypeFilterSelection);
    }

    private void rememberTypeFilterSelection(String selection) {
        rememberedTypeFilterSelection = normalizeTypeFilterSelection(selection);
    }

    static List<String> buildTypeFilterOptions(List<String> dynamicTypes, String selectedType) {
        LinkedHashSet<String> options = new LinkedHashSet<>();
        options.add(FILTER_ALL_TYPES);
        if (dynamicTypes != null) {
            for (String dynamicType : dynamicTypes) {
                String normalizedType = normalizeDynamicTypeOption(dynamicType);
                if (normalizedType != null) {
                    options.add(normalizedType);
                }
            }
        }

        String preservedSelection = normalizeDynamicTypeOption(selectedType);
        if (preservedSelection != null) {
            options.add(preservedSelection);
        }
        return new ArrayList<>(options);
    }

    static String resolveTypeFilterSelection(String selectedType, List<String> availableOptions) {
        String normalizedSelection = normalizeTypeFilterSelection(selectedType);
        return availableOptions != null && availableOptions.contains(normalizedSelection)
                ? normalizedSelection
                : FILTER_ALL_TYPES;
    }

    private static String normalizeTypeFilterSelection(String selection) {
        return selection == null || selection.isBlank() ? FILTER_ALL_TYPES : selection;
    }

    private static String normalizeDynamicTypeOption(String type) {
        if (type == null || type.isBlank() || FILTER_ALL_TYPES.equals(type)) {
            return null;
        }
        return type;
    }

    /**
     * Подключает наблюдение за вертикальным scrollbar таблицы.
     * Подгрузка выполняется только при достижении границ viewport и никогда не
     * приводит к накоплению нескольких страниц в памяти.
     */
    private void attachPacketTableScrollListener() {
        if (packetTable == null) {
            return;
        }

        ScrollBar newScrollBar = packetTable.lookupAll(".scroll-bar").stream()
                .filter(ScrollBar.class::isInstance)
                .map(ScrollBar.class::cast)
                .filter(scrollBar -> scrollBar.getOrientation() == Orientation.VERTICAL)
                .findFirst()
                .orElse(null);

        if (packetTableVerticalScrollBar == newScrollBar) {
            return;
        }
        if (packetTableVerticalScrollBar != null) {
            packetTableVerticalScrollBar.valueProperty().removeListener(packetTableScrollListener);
        }

        packetTableVerticalScrollBar = newScrollBar;
        if (packetTableVerticalScrollBar != null) {
            packetTableVerticalScrollBar.valueProperty().addListener(packetTableScrollListener);
        }
    }

    private void handlePacketTableScroll(double previousValue, double scrollValue) {
        if (ignoreTableScrollEvents || pageLoadInProgress || packetItems.isEmpty()) {
            return;
        }
        rearmEdgeLoads(scrollValue);
        if (scrollValue >= TABLE_SCROLL_PAGE_DOWN_THRESHOLD
                && scrollValue > previousValue
                && bottomEdgeLoadArmed) {
            bottomEdgeLoadArmed = false;
            loadOlderPageFromScroll();
        } else if (scrollValue <= TABLE_SCROLL_PAGE_UP_THRESHOLD
                && scrollValue < previousValue
                && topEdgeLoadArmed) {
            topEdgeLoadArmed = false;
            loadNewerPageFromScroll();
        }
    }

    /**
     * Повторная подгрузка страницы на той же границе разрешается только после того,
     * как пользователь реально ушёл от края scroll range и затем вернулся обратно.
     */
    private void rearmEdgeLoads(double scrollValue) {
        if (scrollValue > TABLE_SCROLL_PAGE_REARM_UP_THRESHOLD) {
            topEdgeLoadArmed = true;
        }
        if (scrollValue < TABLE_SCROLL_PAGE_REARM_DOWN_THRESHOLD) {
            bottomEdgeLoadArmed = true;
        }
    }

    private boolean isTableNearTop() {
        return packetTableVerticalScrollBar == null
                || packetTableVerticalScrollBar.getValue() <= TABLE_SCROLL_EDGE_THRESHOLD;
    }

    private boolean isTableInPagingUpZone() {
        return packetTableVerticalScrollBar == null
                || packetTableVerticalScrollBar.getValue() <= TABLE_SCROLL_PAGE_UP_THRESHOLD;
    }

    private boolean isTableNearBottom() {
        return packetTableVerticalScrollBar != null
                && packetTableVerticalScrollBar.getValue() >= 1.0 - TABLE_SCROLL_EDGE_THRESHOLD;
    }

    private boolean isTableInPagingDownZone() {
        return packetTableVerticalScrollBar != null
                && packetTableVerticalScrollBar.getValue() >= TABLE_SCROLL_PAGE_DOWN_THRESHOLD;
    }

    private void clearTableSelectionSilently() {
        if (packetTable == null) {
            return;
        }
        restoringSelection = true;
        try {
            packetTable.getSelectionModel().clearSelection();
        } finally {
            restoringSelection = false;
        }
    }

    private void releaseTableScrollIgnoreLater(int scrollToIndex) {
        Platform.runLater(() -> {
            if (packetTable != null && scrollToIndex >= 0 && !packetItems.isEmpty()) {
                int targetIndex = Math.min(scrollToIndex, packetItems.size() - 1);
                packetTable.scrollTo(targetIndex);
            }
            Platform.runLater(() -> {
                synchronizeTableSelectionWithViewedPacket();
                disarmCurrentEdgeIfNeeded();
                ignoreTableScrollEvents = false;
            });
        });
    }

    private void disarmCurrentEdgeIfNeeded() {
        if (isTableInPagingUpZone()) {
            topEdgeLoadArmed = false;
        }
        if (isTableInPagingDownZone()) {
            bottomEdgeLoadArmed = false;
        }
    }

    /**
     * Приблизительно вычисляет первую видимую строку таблицы.
     * Используется как anchor для плавного сдвига окна данных при подгрузке.
     */
    private int estimateFirstVisibleRow() {
        if (packetItems.isEmpty() || packetTable == null || packetTableVerticalScrollBar == null) {
            return 0;
        }

        int visibleRows = estimateVisibleRowCount();
        int maxFirstVisible = Math.max(0, packetItems.size() - visibleRows);
        double scrollValue = Math.max(0.0, Math.min(1.0, packetTableVerticalScrollBar.getValue()));
        return (int) Math.round(scrollValue * maxFirstVisible);
    }

    private int estimateVisibleRowCount() {
        if (packetTable == null || packetTable.getFixedCellSize() <= 0) {
            return 12;
        }
        double viewportHeight = Math.max(0, packetTable.getHeight() - PACKET_TABLE_HEADER_HEIGHT_ESTIMATE);
        int visibleRows = (int) Math.floor(viewportHeight / packetTable.getFixedCellSize());
        return Math.max(1, visibleRows);
    }

    /**
     * Программно выбирает пакет в таблице.
     * Флаг {@link #restoringSelection} подавляет побочные реакции selection listener-а
     * во время служебного восстановления выбора.
     */
    private void selectPacket(PacketLogEntry entry, boolean scrollToEntry, boolean refreshDetails) {
        if (entry == null || packetTable == null) {
            return;
        }

        restoringSelection = true;
        try {
            packetTable.getSelectionModel().select(entry);
            if (scrollToEntry) {
                packetTable.scrollTo(entry);
            }
        } finally {
            restoringSelection = false;
        }
        if (refreshDetails) {
            updatePacketDetails(entry);
        }
    }

    /**
     * Подсвечивает диапазон байт выбранного узла дерева в HEX и ASCII колонках.
     * Адресная колонка намеренно не участвует в подсветке.
     */
    private void highlightHexForSelection(TreeItem<PacketTreeNode> selectedItem) {
        if (selectedItem == null || selectedItem.getValue() == null || !selectedItem.getValue().hasByteRange()) {
            clearPreviewSelection();
            return;
        }

        PacketDebugFormatter.TextSelectionRange hexSelection = currentHexPreview.selectionForHexBytes(
                selectedItem.getValue().getStartByte(),
                selectedItem.getValue().getEndByte()
        );
        PacketDebugFormatter.TextSelectionRange asciiSelection = currentHexPreview.selectionForAsciiBytes(
                selectedItem.getValue().getStartByte(),
                selectedItem.getValue().getEndByte()
        );
        if (hexSelection == null || asciiSelection == null) {
            clearPreviewSelection();
            return;
        }
        hexPreview.selectRange(hexSelection.startChar(), hexSelection.endChar());
        asciiPreview.selectRange(asciiSelection.startChar(), asciiSelection.endChar());
    }

    /**
     * Снимает выделение со всех колонок предпросмотра.
     */
    private void clearPreviewSelection() {
        if (addressPreview != null) {
            addressPreview.selectRange(0, 0);
        }
        if (hexPreview != null) {
            hexPreview.selectRange(0, 0);
        }
        if (asciiPreview != null) {
            asciiPreview.selectRange(0, 0);
        }
    }

    /**
     * Синхронизирует доступность action-кнопок с наличием выбранного пакета.
     */
    private void updatePacketActionButtonsState() {
        boolean disabled = viewedPacketEntry == null;
        if (btnCopyText != null) {
            btnCopyText.setDisable(disabled);
        }
        if (btnCopyJson != null) {
            btnCopyJson.setDisable(disabled);
        }
        if (btnSaveText != null) {
            btnSaveText.setDisable(disabled);
        }
        if (btnSaveJson != null) {
            btnSaveJson.setDisable(disabled);
        }
    }

    /**
     * Копирует текущий пакет в текстовом экспортном формате.
     */
    private void copyViewedPacketAsText() {
        copyViewedPacket(PacketDebugFormatter.exportPacketAsText(
                        viewedPacketEntry,
                        resolveEntryDeviceState(viewedPacketEntry)),
                "Пакет скопирован в текстовом виде");
    }

    /**
     * Копирует текущий пакет как protobuf-style JSON, совместимый с Meshtastic Web.
     */
    private void copyViewedPacketAsJson() {
        copyViewedPacket(PacketDebugFormatter.exportPacketAsJson(viewedPacketEntry),
                "Пакет скопирован в JSON");
    }

    /**
     * Общая реализация копирования в clipboard.
     * Пустой экспорт тихо игнорируется и не считается ошибкой.
     */
    private void copyViewedPacket(String content, String successMessage) {
        if (viewedPacketEntry == null || content == null || content.isBlank()) {
            return;
        }
        ClipboardContent clipboardContent = new ClipboardContent();
        clipboardContent.putString(content);
        Clipboard.getSystemClipboard().setContent(clipboardContent);
        Toast.show(Toast.Type.SUCCESS, successMessage);
    }

    private void saveViewedPacketAsText() {
        saveViewedPacket(PacketDebugFormatter.exportPacketAsText(
                viewedPacketEntry,
                resolveEntryDeviceState(viewedPacketEntry)), false);
    }

    private void saveViewedPacketAsJson() {
        saveViewedPacket(PacketDebugFormatter.exportPacketAsJson(viewedPacketEntry), true);
    }

    /**
     * Сохраняет текущий пакет в файл.
     * Контракт:
     * - расширение приводится к ожидаемому формату;
     * - cancel не вызывает побочных эффектов;
     * - ошибки записи отражаются через toast.
     */
    private void saveViewedPacket(String content, boolean jsonFormat) {
        if (viewedPacketEntry == null || content == null || content.isBlank()) {
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle(jsonFormat ? "Сохранить пакет в JSON" : "Сохранить пакет в текстовый файл");
        chooser.setInitialFileName(buildPacketExportFileName(jsonFormat));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                jsonFormat ? "JSON (*.json)" : "Text (*.txt)",
                jsonFormat ? "*.json" : "*.txt"
        ));

        File selected = chooser.showSaveDialog(stage);
        if (selected == null) {
            return;
        }

        File target = ensureExtension(selected, jsonFormat ? ".json" : ".txt");
        try {
            Files.writeString(target.toPath(), content, StandardCharsets.UTF_8);
            Toast.show(Toast.Type.SUCCESS, "Пакет сохранён: " + target.getName());
        } catch (IOException e) {
            Toast.show(Toast.Type.ERROR, "Не удалось сохранить пакет");
        }
    }

    /**
     * Формирует имя файла экспорта из времени захвата и id записи.
     */
    private String buildPacketExportFileName(boolean jsonFormat) {
        if (viewedPacketEntry == null) {
            return jsonFormat ? "lora-packet.json" : "lora-packet.txt";
        }
        String timestamp = PACKET_EXPORT_FILE_TIME.format(
                Instant.ofEpochMilli(viewedPacketEntry.getCapturedAt()).atZone(ZoneId.systemDefault()).toLocalDateTime()
        );
        String extension = jsonFormat ? ".json" : ".txt";
        return "lora-packet-" + timestamp + "-" + viewedPacketEntry.getId() + extension;
    }

    /**
     * Гарантирует наличие ожидаемого расширения файла, даже если пользователь его не указал.
     */
    private File ensureExtension(File file, String extension) {
        if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(extension)) {
            return file;
        }
        return new File(file.getParentFile(), file.getName() + extension);
    }

    /**
     * Полностью раскрывает дерево пакета.
     * Вызывается только после пересборки дерева для нового выбранного пакета.
     */
    private void expandTree(TreeItem<PacketTreeNode> item) {
        if (item == null) {
            return;
        }
        item.setExpanded(true);
        for (TreeItem<PacketTreeNode> child : item.getChildren()) {
            expandTree(child);
        }
    }

    /**
     * Освобождает window-level ресурсы и снимает регистрацию singleton-окна.
     */
    private void dispose() {
        packetMonitorService.removeListener(packetListener);
        if (stage != null && stage.getScene() != null) {
            ThemeManager.unregisterScene(stage.getScene());
        }
        instance = null;
    }

    /**
     * Восстанавливает координаты, размер и флаг maximized.
     * Если сохранённый прямоугольник больше не попадает на доступные экраны,
     * окно возвращается в видимую область ближайшего дисплея.
     */
    private void restoreWindowState() {
        restoreWindowMaximized = AppPreferences.isPacketMonitorWindowMaximized();
        if (!AppPreferences.hasPacketMonitorWindowBounds()) {
            return;
        }

        applyWindowBounds(resolveVisibleWindowBounds(
                AppPreferences.getPacketMonitorWindowX(),
                AppPreferences.getPacketMonitorWindowY(),
                AppPreferences.getPacketMonitorWindowWidth(),
                AppPreferences.getPacketMonitorWindowHeight()
        ));
    }

    /**
     * Подписывает окно на отслеживание изменения геометрии.
     * Сохраняются только normal bounds, а не текущие размеры maximized-окна.
     */
    private void trackWindowBounds() {
        stage.xProperty().addListener((obs, oldValue, newValue) -> captureCurrentWindowBounds());
        stage.yProperty().addListener((obs, oldValue, newValue) -> captureCurrentWindowBounds());
        stage.widthProperty().addListener((obs, oldValue, newValue) -> captureCurrentWindowBounds());
        stage.heightProperty().addListener((obs, oldValue, newValue) -> captureCurrentWindowBounds());
        stage.maximizedProperty().addListener((obs, oldValue, newValue) -> {
            if (!Boolean.TRUE.equals(newValue)) {
                captureCurrentWindowBounds();
            }
        });
    }

    /**
     * Обновляет normal bounds окна, если оно не maximized.
     */
    private void captureCurrentWindowBounds() {
        if (stage == null || stage.isMaximized()) {
            return;
        }
        if (!Double.isNaN(stage.getX())) {
            normalWindowX = stage.getX();
        }
        if (!Double.isNaN(stage.getY())) {
            normalWindowY = stage.getY();
        }
        if (stage.getWidth() > 0) {
            normalWindowWidth = stage.getWidth();
        }
        if (stage.getHeight() > 0) {
            normalWindowHeight = stage.getHeight();
        }
    }

    /**
     * Сохраняет геометрию окна и положение вертикального разделителя в preferences.
     */
    private void saveWindowState() {
        boolean maximized = stage != null && stage.isMaximized();
        captureCurrentWindowBounds();
        WindowBounds visibleBounds = resolveVisibleWindowBounds(
                Double.isNaN(normalWindowX) ? stage.getX() : normalWindowX,
                Double.isNaN(normalWindowY) ? stage.getY() : normalWindowY,
                normalWindowWidth > 0 ? normalWindowWidth : stage.getWidth(),
                normalWindowHeight > 0 ? normalWindowHeight : stage.getHeight()
        );
        AppPreferences.savePacketMonitorWindowBounds(
                visibleBounds.x(),
                visibleBounds.y(),
                visibleBounds.width(),
                visibleBounds.height(),
                maximized
        );
        if (contentSplit != null && !contentSplit.getDividers().isEmpty()) {
            AppPreferences.setPacketMonitorDividerPos(contentSplit.getDividers().getFirst().getPosition());
        }
    }

    /**
     * Возвращает окно в видимую область, если его normal bounds ушли за пределы доступных экранов.
     */
    private void ensureWindowVisible() {
        if (stage == null || stage.isMaximized()) {
            return;
        }

        applyWindowBounds(resolveVisibleWindowBounds(
                Double.isNaN(normalWindowX) ? stage.getX() : normalWindowX,
                Double.isNaN(normalWindowY) ? stage.getY() : normalWindowY,
                normalWindowWidth > 0 ? normalWindowWidth : stage.getWidth(),
                normalWindowHeight > 0 ? normalWindowHeight : stage.getHeight()
        ));
    }

    private void applyWindowBounds(WindowBounds bounds) {
        if (bounds == null) {
            return;
        }

        stage.setX(bounds.x());
        stage.setY(bounds.y());
        stage.setWidth(bounds.width());
        stage.setHeight(bounds.height());
        normalWindowX = bounds.x();
        normalWindowY = bounds.y();
        normalWindowWidth = bounds.width();
        normalWindowHeight = bounds.height();
    }

    private static WindowBounds resolveVisibleWindowBounds(double x, double y, double width, double height) {
        List<Rectangle2D> visibleAreas = Screen.getScreens().stream()
                .map(Screen::getVisualBounds)
                .filter(Objects::nonNull)
                .toList();
        Rectangle2D fallbackArea = visibleAreas.isEmpty() ? defaultVisibleArea() : visibleAreas.getFirst();
        return normalizeWindowBounds(x, y, width, height, visibleAreas, fallbackArea);
    }

    static WindowBounds normalizeWindowBounds(double x,
                                              double y,
                                              double width,
                                              double height,
                                              List<Rectangle2D> visibleAreas,
                                              Rectangle2D fallbackArea) {
        Rectangle2D effectiveFallback = fallbackArea != null ? fallbackArea : defaultVisibleArea();
        List<Rectangle2D> safeVisibleAreas = visibleAreas == null ? List.of() : visibleAreas.stream()
                .filter(Objects::nonNull)
                .toList();
        Rectangle2D targetArea = selectTargetArea(x, y, width, height, safeVisibleAreas, effectiveFallback);

        double safeWidth = sanitizeWindowDimension(width, DEFAULT_WINDOW_WIDTH, targetArea.getWidth());
        double safeHeight = sanitizeWindowDimension(height, DEFAULT_WINDOW_HEIGHT, targetArea.getHeight());
        boolean intersectsVisibleArea = intersectsAnyVisibleArea(x, y, safeWidth, safeHeight, safeVisibleAreas);

        double targetX = intersectsVisibleArea && Double.isFinite(x)
                ? clamp(x, targetArea.getMinX(), targetArea.getMaxX() - safeWidth)
                : centerCoordinate(targetArea.getMinX(), targetArea.getWidth(), safeWidth);
        double targetY = intersectsVisibleArea && Double.isFinite(y)
                ? clamp(y, targetArea.getMinY(), targetArea.getMaxY() - safeHeight)
                : centerCoordinate(targetArea.getMinY(), targetArea.getHeight(), safeHeight);
        return new WindowBounds(targetX, targetY, safeWidth, safeHeight);
    }

    private static Rectangle2D selectTargetArea(double x,
                                                double y,
                                                double width,
                                                double height,
                                                List<Rectangle2D> visibleAreas,
                                                Rectangle2D fallbackArea) {
        if (visibleAreas == null || visibleAreas.isEmpty()) {
            return fallbackArea != null ? fallbackArea : defaultVisibleArea();
        }

        Rectangle2D candidate = candidateBounds(x, y, width, height);
        if (candidate != null) {
            Rectangle2D overlappingArea = visibleAreas.stream()
                    .filter(area -> intersects(area, candidate))
                    .max((left, right) -> Double.compare(intersectionArea(left, candidate), intersectionArea(right, candidate)))
                    .orElse(null);
            if (overlappingArea != null) {
                return overlappingArea;
            }

            double centerX = candidate.getMinX() + candidate.getWidth() / 2.0;
            double centerY = candidate.getMinY() + candidate.getHeight() / 2.0;
            return visibleAreas.stream()
                    .min((left, right) -> Double.compare(
                            distanceSquaredToRectangle(centerX, centerY, left),
                            distanceSquaredToRectangle(centerX, centerY, right)))
                    .orElse(visibleAreas.getFirst());
        }

        return fallbackArea != null ? fallbackArea : visibleAreas.getFirst();
    }

    private static Rectangle2D candidateBounds(double x, double y, double width, double height) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return null;
        }

        double safeWidth = width > 0 && Double.isFinite(width) ? width : DEFAULT_WINDOW_WIDTH;
        double safeHeight = height > 0 && Double.isFinite(height) ? height : DEFAULT_WINDOW_HEIGHT;
        return new Rectangle2D(x, y, safeWidth, safeHeight);
    }

    private static boolean intersectsAnyVisibleArea(double x,
                                                    double y,
                                                    double width,
                                                    double height,
                                                    List<Rectangle2D> visibleAreas) {
        Rectangle2D candidate = candidateBounds(x, y, width, height);
        return candidate != null && visibleAreas.stream().anyMatch(area -> intersects(area, candidate));
    }

    private static boolean intersects(Rectangle2D left, Rectangle2D right) {
        return left.getMaxX() > right.getMinX()
                && right.getMaxX() > left.getMinX()
                && left.getMaxY() > right.getMinY()
                && right.getMaxY() > left.getMinY();
    }

    private static double intersectionArea(Rectangle2D left, Rectangle2D right) {
        double intersectionWidth = Math.min(left.getMaxX(), right.getMaxX()) - Math.max(left.getMinX(), right.getMinX());
        double intersectionHeight = Math.min(left.getMaxY(), right.getMaxY()) - Math.max(left.getMinY(), right.getMinY());
        if (intersectionWidth <= 0 || intersectionHeight <= 0) {
            return 0.0;
        }
        return intersectionWidth * intersectionHeight;
    }

    private static double distanceSquaredToRectangle(double x, double y, Rectangle2D area) {
        double dx = x < area.getMinX() ? area.getMinX() - x : Math.max(0.0, x - area.getMaxX());
        double dy = y < area.getMinY() ? area.getMinY() - y : Math.max(0.0, y - area.getMaxY());
        return dx * dx + dy * dy;
    }

    private static double sanitizeWindowDimension(double requested, double defaultValue, double maxVisible) {
        double preferred = requested > 0 && Double.isFinite(requested) ? requested : defaultValue;
        return Math.min(maxVisible, Math.max(200.0, preferred));
    }

    private static double centerCoordinate(double min, double available, double size) {
        return min + Math.max(0.0, (available - size) / 2.0);
    }

    private static double clamp(double value, double min, double max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(value, max));
    }

    private static Rectangle2D defaultVisibleArea() {
        return new Rectangle2D(0, 0, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
    }

    record WindowBounds(double x, double y, double width, double height) {}

    /**
     * Преобразует выбранное значение date/time filter-а в границу SQL-диапазона.
     * Контракт:
     * - отсутствие даты выключает фильтр и возвращает {@code null};
     * - режим {@code Весь день} разворачивается в начало или конец суток;
     * - верхняя граница включительная и поэтому доводится до последней наносекунды минуты/дня.
     */
    private static Long resolveCapturedAtBoundary(DateTimePicker dateTimePicker, boolean lowerBound) {
        if (dateTimePicker == null || dateTimePicker.getDate() == null) {
            return null;
        }

        LocalTime selectedTime = dateTimePicker.getTime();
        LocalTime effectiveTime;
        if (selectedTime == null) {
            effectiveTime = lowerBound ? LocalTime.MIN : LocalTime.MAX;
        } else if (lowerBound) {
            effectiveTime = selectedTime.withSecond(0).withNano(0);
        } else {
            effectiveTime = selectedTime.withSecond(59).withNano(999_999_999);
        }
        LocalDateTime capturedAt = LocalDateTime.of(dateTimePicker.getDate(), effectiveTime);
        return capturedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query);
    }
}
