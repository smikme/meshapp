package com.meshtastic.client.components;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.model.PacketTreeNode;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.rpc.RemoteChatJson;
import com.meshtastic.client.protocol.rpc.RemotePacketMonitorJson;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.rpc.RpcEventListener;
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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableCell;
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
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone monitor window for LoRa mesh packets.
 * The class encapsulates all UI subsystem state: filters, selected packet,
 * HEX/ASCII preview, parsed tree, export, and window geometry persistence.
 *
 * Window contract:
 * - new packets appear at the top of the table;
 * - if the user is inspecting the currently selected packet, incoming data must
 *   not reset row selection, tree selection, or HEX/ASCII highlighting;
 * - the tree and preview are rebuilt only when the selected packet actually changes.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class PacketMonitorWindow {

    private static final int PAGE_SIZE = 200;
    private static final int PAGE_SHIFT = 80;
    private static final int PACKET_EXPORT_BATCH_SIZE = 1_000;
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
    private static final double PACKET_TABLE_TRANSPORT_COLUMN_WIDTH = 170;
    private static final double PACKET_TABLE_NODE_COLUMN_WIDTH = 150;
    private static final double PACKET_TABLE_COMPACT_COLUMN_MIN_WIDTH = 72;
    private static final double PACKET_TABLE_PAYLOAD_MIN_WIDTH = 260;
    private static final double PACKET_TABLE_PAYLOAD_RUNTIME_MIN_WIDTH = 140;
    private static final int MAX_PENDING_LIVE_PACKET_EVENTS = 512;
    private static final DateTimeFormatter PACKET_EXPORT_FILE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter PACKET_EXPORT_FILTER_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter PACKET_EXPORT_FILTER_DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Duration REMOTE_RPC_TIMEOUT = Duration.ofSeconds(15);

    static record RouteFilterSelection(PacketLogEntry.Direction direction,
                                       String transportMechanism,
                                       boolean loraOnly) {

        boolean matches(PacketLogEntry entry) {
            if (entry == null) {
                return false;
            }
            if (direction != null && entry.getDirection() != direction) {
                return false;
            }
            if (loraOnly && !PacketMonitorService.isLoraMonitorTransport(entry.getTransportMechanism())) {
                return false;
            }
            if (transportMechanism == null) {
                return true;
            }

            String entryTransportMechanism = entry.getTransportMechanism();
            if (PacketMonitorService.TRANSPORT_MECHANISM_UNSPECIFIED.equals(transportMechanism)) {
                return entryTransportMechanism == null || entryTransportMechanism.isBlank();
            }
            return transportMechanism.equals(entryTransportMechanism);
        }
    }

    private static PacketMonitorWindow instance;

    private final PacketMonitorDataSource packetMonitorSource;
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "packet-monitor-export");
        thread.setDaemon(true);
        return thread;
    });
    private final ObservableList<PacketLogEntry> packetItems = FXCollections.observableArrayList();
    private final ObservableList<String> packetTypeFilters = FXCollections.observableArrayList(filterAllTypes());
    private final Queue<PacketLogEntry> pendingLivePackets = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingLivePacketCount = new AtomicInteger();
    private final AtomicBoolean livePacketFlushQueued = new AtomicBoolean();
    private final AtomicBoolean livePacketQueueOverflowed = new AtomicBoolean();
    private final BooleanProperty suppressPacketTableTooltips = new SimpleBooleanProperty(false);
    private final ChangeListener<Number> packetTableScrollListener =
            (obs, oldValue, newValue) -> handlePacketTableScroll(
                    oldValue != null ? oldValue.doubleValue() : 0.0,
                    newValue != null ? newValue.doubleValue() : 0.0
            );

    private final PacketMonitorService.Listener packetListener = new PacketMonitorService.Listener() {
        @Override
        public void onPacketLogged(PacketLogEntry entry) {
            enqueueLivePacket(entry);
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
    private ComboBox<String> routeFilter;
    private ComboBox<String> typeFilter;
    private DateTimePicker fromDateTimeFilter;
    private DateTimePicker toDateTimeFilter;
    private TextField searchField;
    private Button btnStart;
    private Button btnStop;
    private Button btnClear;
    private Button btnExportJson;
    private Button btnExportCsv;
    private Button btnCopyText;
    private Button btnCopyJson;
    private Button btnSaveText;
    private Button btnSaveJson;
    private Label statusLabel;
    private HBox exportProgressBox;
    private ProgressBar exportProgressBar;
    private Label exportProgressLabel;
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
    private String rememberedTypeFilterSelection = filterAllTypes();
    private PacketDebugFormatter.HexPreview currentHexPreview = PacketDebugFormatter.formatHexPreview(null);
    private ScrollBar packetTableVerticalScrollBar;
    private double normalWindowX = Double.NaN;
    private double normalWindowY = Double.NaN;
    private double normalWindowWidth = DEFAULT_WINDOW_WIDTH;
    private double normalWindowHeight = DEFAULT_WINDOW_HEIGHT;
    private boolean restoreWindowMaximized;
    private boolean closeRequested;
    private volatile boolean exportInProgress;

    private PacketMonitorWindow() {
        this.packetMonitorSource = createPacketMonitorSource();
        createStage();
        packetMonitorSource.addListener(packetListener);
        reloadCurrentFrame(true, true);
        updateToolbarState();
    }

    private PacketMonitorDataSource createPacketMonitorSource() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = manager.getSelectedConnectionEntry();
        if (entry != null && entry.isConnected()) {
            ProtocolRuntime<?> runtime = manager.getProtocolRuntime(entry.getId());
            if (runtime != null && runtime.getState() instanceof RemoteRpcState remoteState) {
                return new RemotePacketMonitorDataSource(remoteState);
            }
        }
        return new LocalPacketMonitorDataSource(PacketMonitorService.getInstance());
    }

    /**
     * Shows the singleton monitor window.
     * If the window has not been created yet, it is initialized together with all internal state.
     */
    public static void showWindow() {
        if (Platform.isFxApplicationThread()) {
            showWindowInternal();
        } else {
            Platform.runLater(PacketMonitorWindow::showWindowInternal);
        }
    }

    /**
     * Hides an open window without destroying singleton state.
     * Used when the whole application moves to tray or is hidden by the system.
     */
    public static void hideWindowIfOpen() {
        if (Platform.isFxApplicationThread()) {
            hideWindowIfOpenInternal();
        } else {
            Platform.runLater(PacketMonitorWindow::hideWindowIfOpenInternal);
        }
    }

    /**
     * Restores a previously hidden window only if the user opened it in this session.
     */
    public static void restoreWindowIfOpen() {
        if (Platform.isFxApplicationThread()) {
            restoreWindowIfOpenInternal();
        } else {
            Platform.runLater(PacketMonitorWindow::restoreWindowIfOpenInternal);
        }
    }

    /**
     * Fully closes the monitor window if it has been created.
     * Used during application shutdown, when singleton state no longer needs to stay in memory.
     */
    public static void closeWindowIfOpen() {
        if (Platform.isFxApplicationThread()) {
            closeWindowIfOpenInternal();
        } else {
            Platform.runLater(PacketMonitorWindow::closeWindowIfOpenInternal);
        }
    }

    /**
     * Creates the singleton window on first use and shows it again on later calls.
     * Window state lives until the stage is actually closed.
     */
    private static void showWindowInternal() {
        if (instance == null) {
            instance = new PacketMonitorWindow();
        }
        instance.showStage(true);
    }

    private static void hideWindowIfOpenInternal() {
        if (instance == null || instance.stage == null || !instance.stage.isShowing()) {
            return;
        }
        instance.stage.hide();
    }

    private static void restoreWindowIfOpenInternal() {
        if (instance == null) {
            return;
        }
        instance.showStage(false);
    }

    private static void closeWindowIfOpenInternal() {
        if (instance == null || instance.stage == null) {
            return;
        }
        instance.closeRequested = true;
        instance.stage.close();
    }

    private void showStage(boolean requestFocus) {
        boolean restoringHiddenOrIconified = stage.isIconified() || !stage.isShowing();
        ensureWindowVisible();
        if (stage.isIconified()) {
            stage.setIconified(false);
        }
        if (!stage.isShowing()) {
            stage.show();
        }
        restoreMaximizedStateAfterShow();
        if (restoringHiddenOrIconified) {
            stage.toFront();
        }
        if (requestFocus) {
            stage.requestFocus();
        }
    }

    private void restoreMaximizedStateAfterShow() {
        if (!restoreWindowMaximized || stage.isMaximized()) {
            return;
        }
        captureCurrentWindowBounds();
        stage.setMaximized(true);
        restoreWindowMaximized = false;
    }

    private void handleHidden() {
        boolean shouldDispose = closeRequested;
        closeRequested = false;
        if (shouldDispose) {
            dispose();
        }
    }

    /**
     * Creates the monitor window scene and stage.
     * Contract:
     * - the window uses the standard system frame;
     * - geometry is restored before {@code show()};
     * - geometry is saved on hiding, regardless of how the window is closed.
     */
    private void createStage() {
        VBox root = new VBox(10);
        root.getStyleClass().add("packet-monitor-root");
        root.setPadding(new Insets(12));

        root.getChildren().addAll(createHeader(), createFilterBar(), createContentSplitPane());

        Scene scene = new Scene(root, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
        ThemeManager.applyTheme(scene, AppPreferences.isDarkMode());
        EmojiRenderingSupport.install(scene);

        stage = new Stage();
        stage.initStyle(StageStyle.DECORATED);
        stage.setTitle(I18n.t("packetMonitor.title"));
        stage.setResizable(true);
        if (MeshApp.getPrimaryStage() != null && !MeshApp.getPrimaryStage().getIcons().isEmpty()) {
            stage.getIcons().setAll(MeshApp.getPrimaryStage().getIcons());
        }
        stage.setScene(scene);
        restoreWindowState();
        trackWindowBounds();
        stage.setOnCloseRequest(event -> closeRequested = true);
        stage.setOnHiding(event -> saveWindowState());
        stage.setOnHidden(event -> handleHidden());
    }

    private HBox createHeader() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(I18n.t("packetMonitor.title"));
        title.getStyleClass().add("form-title");

        statusLabel = new Label();
        statusLabel.getStyleClass().add("config-status-label");

        exportProgressLabel = new Label();
        exportProgressLabel.getStyleClass().add("config-status-label");

        exportProgressBar = new ProgressBar(0);
        exportProgressBar.setPrefWidth(220);
        exportProgressBar.setMinWidth(160);
        exportProgressBar.setMaxWidth(220);

        exportProgressBox = new HBox(8, exportProgressLabel, exportProgressBar);
        exportProgressBox.setAlignment(Pos.CENTER_LEFT);
        exportProgressBox.setVisible(false);
        exportProgressBox.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar toolBar = new ToolBar();
        toolBar.getStyleClass().add("logs-toolbar");

        btnStart = createToolbarButton(
                I18n.t("packetMonitor.action.start"),
                I18n.t("packetMonitor.action.start.tooltip"),
                "/icons/play.svg",
                packetMonitorSource::startCapture
        );
        btnStop = createToolbarButton(
                I18n.t("packetMonitor.action.stop"),
                I18n.t("packetMonitor.action.stop.tooltip"),
                "/icons/pause.svg",
                packetMonitorSource::stopCapture
        );
        btnClear = createToolbarButton(
                I18n.t("packetMonitor.action.clear"),
                I18n.t("packetMonitor.action.clear.tooltip"),
                "/icons/clear.svg",
                packetMonitorSource::clear
        );
        toolBar.getItems().addAll(
                btnStart,
                btnStop,
                new Separator(Orientation.VERTICAL),
                btnClear
        );

        header.getChildren().addAll(title, statusLabel, exportProgressBox, spacer, toolBar);
        return header;
    }

    private HBox createFilterBar() {
        HBox filterBar = new HBox(10);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.getStyleClass().add("packet-monitor-filter-bar");

        routeFilter = new ComboBox<>(FXCollections.observableArrayList(buildRouteFilterOptions()));
        routeFilter.setValue(filterAllRoutes());
        routeFilter.valueProperty().addListener((obs, oldValue, newValue) -> onFilterChanged());

        typeFilter = new ComboBox<>(packetTypeFilters);
        typeFilter.setValue(filterAllTypes());
        typeFilter.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!suppressFilterReload) {
                rememberTypeFilterSelection(newValue);
            }
            onFilterChanged();
        });

        fromDateTimeFilter = createDateTimePicker(I18n.t("packetMonitor.filter.date.placeholder"));
        toDateTimeFilter = createDateTimePicker(I18n.t("packetMonitor.filter.date.placeholder"));
        fromDateTimeFilter.popupShowingProperty().addListener((obs, oldValue, newValue) -> updatePacketTableTooltipSuppression());
        toDateTimeFilter.popupShowingProperty().addListener((obs, oldValue, newValue) -> updatePacketTableTooltipSuppression());

        searchField = new TextField();
        searchField.setPromptText(I18n.t("packetMonitor.search.placeholder"));
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
                createFilterLabel(I18n.t("packetMonitor.filter.route.label")),
                routeFilter,
                createFilterLabel(I18n.t("packetMonitor.filter.type.label")),
                typeFilter,
                createFilterLabel(I18n.t("packetMonitor.filter.from.label")),
                fromDateTimeFilter,
                createFilterLabel(I18n.t("packetMonitor.filter.to.label")),
                toDateTimeFilter,
                spacer,
                searchBox
        );
        HBox.setHgrow(searchBox, Priority.ALWAYS);
        return filterBar;
    }

    /**
     * Builds the main vertical split content: packet table on top, preview and
     * parsed tree below. The divider position is stored in {@link AppPreferences}.
     */
    private SplitPane createContentSplitPane() {
        packetTable = createPacketTable();

        addressPreview = createPreviewTextArea("packet-monitor-hex-address");
        hexPreview = createPreviewTextArea("packet-monitor-hex-bytes");
        asciiPreview = createPreviewTextArea("packet-monitor-hex-ascii");
        HBox previewBox = createHexPreviewBox(addressPreview, hexPreview, asciiPreview);

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

        VBox tableSection = createSection(I18n.t("packetMonitor.section.table"), packetTable);
        VBox hexSection = createSection(I18n.t("packetMonitor.section.preview"), previewBox);
        hexSection.setMinWidth(Region.USE_PREF_SIZE);
        hexSection.setMaxWidth(Region.USE_PREF_SIZE);
        VBox treeSection = createSection(I18n.t("packetMonitor.section.tree"), treeContent);
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
     * Creates the fixed three-column HEX/ASCII preview.
     * Addresses, HEX, and ASCII are rendered separately so highlighting never
     * affects the address column.
     */
    private HBox createHexPreviewBox(TextArea addressPreviewArea,
                                     TextArea hexPreviewArea,
                                     TextArea asciiPreviewArea) {
        addressPreviewArea.setPrefColumnCount(HEX_PREVIEW_ADDRESS_COLUMNS);
        addressPreviewArea.setMinWidth(Region.USE_PREF_SIZE);
        addressPreviewArea.setMaxWidth(Region.USE_PREF_SIZE);
        hexPreviewArea.setPrefColumnCount(HEX_PREVIEW_BYTES_COLUMNS);
        hexPreviewArea.setMinWidth(Region.USE_PREF_SIZE);
        hexPreviewArea.setMaxWidth(Region.USE_PREF_SIZE);
        asciiPreviewArea.setPrefColumnCount(HEX_PREVIEW_ASCII_COLUMNS);
        asciiPreviewArea.setMinWidth(Region.USE_PREF_SIZE);
        asciiPreviewArea.setMaxWidth(Region.USE_PREF_SIZE);

        HBox previewBox = new HBox(4, addressPreviewArea, hexPreviewArea, asciiPreviewArea);
        previewBox.getStyleClass().add("packet-monitor-hex-grid");
        previewBox.setMinWidth(Region.USE_PREF_SIZE);
        previewBox.setMaxWidth(Region.USE_PREF_SIZE);
        previewBox.setMinHeight(Region.USE_PREF_SIZE);
        previewBox.setMaxHeight(Region.USE_PREF_SIZE);
        return previewBox;
    }

    /**
     * Creates the vertical action toolbar for the current packet.
     * Buttons depend only on {@link #viewedPacketEntry} and must not change table selection.
     */
    private ToolBar createPacketActionsToolbar() {
        btnCopyText = createPacketActionButton(
                I18n.t("packetMonitor.action.copyText"),
                I18n.t("packetMonitor.action.copyText.tooltip"),
                "/icons/copy-text.svg",
                this::copyViewedPacketAsText
        );
        btnCopyJson = createPacketActionButton(
                I18n.t("packetMonitor.action.copyJson"),
                I18n.t("packetMonitor.action.copyJson.tooltip"),
                "/icons/copy-json.svg",
                this::copyViewedPacketAsJson
        );
        btnSaveText = createPacketActionButton(
                I18n.t("packetMonitor.action.saveText"),
                I18n.t("packetMonitor.action.saveText.tooltip"),
                "/icons/save-text.svg",
                this::saveViewedPacketAsText
        );
        btnSaveJson = createPacketActionButton(
                I18n.t("packetMonitor.action.saveJson"),
                I18n.t("packetMonitor.action.saveJson.tooltip"),
                "/icons/save-json.svg",
                this::saveViewedPacketAsJson
        );
        btnExportJson = createPacketActionButton(
                I18n.t("packetMonitor.action.exportJson"),
                I18n.t("packetMonitor.action.exportJson.tooltip"),
                "/icons/save-json.svg",
                this::exportFilteredPacketsAsJson
        );
        btnExportCsv = createPacketActionButton(
                I18n.t("packetMonitor.action.exportCsv"),
                I18n.t("packetMonitor.action.exportCsv.tooltip"),
                "/icons/save-text.svg",
                this::exportFilteredPacketsAsCsv
        );

        ToolBar toolBar = new ToolBar(
                btnCopyText,
                btnCopyJson,
                new Separator(Orientation.HORIZONTAL),
                btnSaveText,
                btnSaveJson,
                new Separator(Orientation.HORIZONTAL),
                btnExportJson,
                btnExportCsv
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
     * Creates the packet log table.
     * The selection listener rebuilds details only when a different packet is
     * selected, not when JavaFX re-emits an event for the same row. Initial column
     * widths are explicit, but the user can resize them freely after the window opens.
     */
    private TableView<PacketLogEntry> createPacketTable() {
        TableView<PacketLogEntry> table = new TableView<>(packetItems);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setFixedCellSize(PACKET_TABLE_FIXED_CELL_SIZE);

        TableColumn<PacketLogEntry, String> colTime = new TableColumn<>(I18n.t("packetMonitor.column.time"));
        colTime.setCellValueFactory(new PropertyValueFactory<>("capturedAtText"));
        configureCompactColumn(colTime, PACKET_TABLE_TIME_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colType = new TableColumn<>(I18n.t("packetMonitor.column.type"));
        colType.setCellValueFactory(new PropertyValueFactory<>("packetType"));
        configureCompactColumn(colType, PACKET_TABLE_TYPE_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colTransport = new TableColumn<>(I18n.t("packetMonitor.column.route"));
        colTransport.setCellValueFactory(new PropertyValueFactory<>("routeText"));
        configureCompactColumn(colTransport, PACKET_TABLE_TRANSPORT_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colFrom = new TableColumn<>(I18n.t("packetMonitor.column.from"));
        colFrom.setCellValueFactory(cellData -> new ReadOnlyStringWrapper(
                formatPacketFromNode(cellData.getValue())));
        colFrom.setCellFactory(column -> new EndpointTableCell());
        configureCompactColumn(colFrom, PACKET_TABLE_NODE_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colTo = new TableColumn<>(I18n.t("packetMonitor.column.to"));
        colTo.setCellValueFactory(cellData -> new ReadOnlyStringWrapper(
                formatPacketToNode(cellData.getValue())));
        colTo.setCellFactory(column -> new EndpointTableCell());
        configureCompactColumn(colTo, PACKET_TABLE_NODE_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colPayload = new TableColumn<>(I18n.t("packetMonitor.column.payload"));
        colPayload.setCellValueFactory(new PropertyValueFactory<>("payloadText"));
        colPayload.setCellFactory(column -> new PayloadTableCell());
        colPayload.setMinWidth(PACKET_TABLE_PAYLOAD_RUNTIME_MIN_WIDTH);
        colPayload.setPrefWidth(PACKET_TABLE_PAYLOAD_MIN_WIDTH);
        colPayload.setMaxWidth(Double.MAX_VALUE);
        colPayload.setResizable(true);
        colPayload.setSortable(false);

        restorePacketTableColumnWidths(colTime, colType, colTransport, colFrom, colTo, colPayload);
        trackPacketTableColumnWidth(colTime, AppPreferences.KEY_PACKET_MONITOR_COLUMN_TIME_WIDTH);
        trackPacketTableColumnWidth(colType, AppPreferences.KEY_PACKET_MONITOR_COLUMN_TYPE_WIDTH);
        trackPacketTableColumnWidth(colTransport, AppPreferences.KEY_PACKET_MONITOR_COLUMN_TRANSPORT_WIDTH);
        trackPacketTableColumnWidth(colFrom, AppPreferences.KEY_PACKET_MONITOR_COLUMN_FROM_WIDTH);
        trackPacketTableColumnWidth(colTo, AppPreferences.KEY_PACKET_MONITOR_COLUMN_TO_WIDTH);
        trackPacketTableColumnWidth(colPayload, AppPreferences.KEY_PACKET_MONITOR_COLUMN_PAYLOAD_WIDTH);

        table.getColumns().addAll(List.of(colTime, colType, colTransport, colFrom, colTo, colPayload));
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
                    rowTooltip.setText(item.getRouteText() + ": " + item.getPayloadText());
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

    private static final class EndpointTableCell extends TableCell<PacketLogEntry, String> {
        private static final double CELL_EMOJI_SIZE = 18;

        private final EmojiTextFlow flow = new EmojiTextFlow("", CELL_EMOJI_SIZE);

        private EndpointTableCell() {
            flow.setMouseTransparent(true);
            flow.setMinHeight(Region.USE_PREF_SIZE);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

            fontProperty().addListener((obs, oldFont, newFont) -> applyTextStyle());
            textFillProperty().addListener((obs, oldFill, newFill) -> applyTextStyle());
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null || item.isBlank()) {
                setGraphic(null);
                flow.setText("");
                return;
            }
            flow.setText(item);
            flow.setEmojiSize(CELL_EMOJI_SIZE);
            applyTextStyle();
            setGraphic(flow);
        }

        private void applyTextStyle() {
            flow.setTextFont(getFont());
            flow.setTextFill(getTextFill());
        }
    }

    private static final class PayloadTableCell extends TableCell<PacketLogEntry, String> {
        private static final double CELL_EMOJI_SIZE = 18;

        private final EmojiTextFlow flow = new EmojiTextFlow("", CELL_EMOJI_SIZE);

        private PayloadTableCell() {
            flow.getStyleClass().add("packet-monitor-payload-flow");
            flow.setMouseTransparent(true);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null || item.isBlank()) {
                setGraphic(null);
                flow.setText("");
                return;
            }
            flow.setText(item);
            flow.setEmojiSize(CELL_EMOJI_SIZE);
            flow.setTextFont(getFont());
            flow.setTextStyleClasses(payloadTextStyleClasses());
            setGraphic(flow);
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            if (getGraphic() == flow) {
                flow.setTextStyleClasses(payloadTextStyleClasses());
            }
        }

        private List<String> payloadTextStyleClasses() {
            List<String> classes = new ArrayList<>();
            classes.add("packet-monitor-payload-text");
            PacketLogEntry entry = getTableRow() != null ? getTableRow().getItem() : null;
            if (entry == null) {
                return classes;
            }
            switch (entry.getDirection()) {
                case INCOMING -> classes.add("packet-monitor-payload-incoming");
                case OUTGOING -> classes.add("packet-monitor-payload-outgoing");
                case INTERNAL -> classes.add("packet-monitor-payload-internal");
            }
            return classes;
        }
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
     * Restores user-defined table column widths from application preferences.
     * Initial widths remain fallback values on first launch or when preferences are absent.
     */
    private void restorePacketTableColumnWidths(TableColumn<PacketLogEntry, String> colTime,
                                                TableColumn<PacketLogEntry, String> colType,
                                                TableColumn<PacketLogEntry, String> colTransport,
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
        applyPacketTableColumnWidth(colTransport,
                AppPreferences.KEY_PACKET_MONITOR_COLUMN_TRANSPORT_WIDTH,
                PACKET_TABLE_TRANSPORT_COLUMN_WIDTH);
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
     * Applies a saved width to one column, respecting its runtime constraints.
     */
    private void applyPacketTableColumnWidth(TableColumn<PacketLogEntry, String> column,
                                             String preferenceKey,
                                             double defaultWidth) {
        double savedWidth = AppPreferences.getPacketMonitorColumnWidth(preferenceKey, defaultWidth);
        double boundedWidth = Math.max(column.getMinWidth(), savedWidth);
        column.setPrefWidth(boundedWidth);
    }

    /**
     * Subscribes a column so its current width is saved to preferences.
     * Width changes during initial restore are ignored so startup layout does
     * not overwrite already saved user settings.
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
     * Creates a date/time filter based on DatePicker with a custom calendar popup.
     * Time can be selected at the bottom of the popup with hour and minute sliders.
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
     * Runs when any UI filter changes.
     * The table always returns to the top slice because the client no longer
     * keeps the full row set in memory.
     */
    private void onFilterChanged() {
        if (suppressFilterReload) {
            return;
        }
        latestFrameDirty = false;
        reloadCurrentFrame(true, true);
    }

    /**
     * Loads the newest sliding-window slice from the database.
     * Used as the table baseline on window startup and filter changes.
     */
    private void reloadCurrentFrame(boolean preserveViewedPacket, boolean clearDetailsIfSelectionLost) {
        pageLoadInProgress = true;
        try {
            long packetIdToKeep = preserveViewedPacket ? viewedPacketId : -1L;
            refreshTypeFilters();
            PacketMonitorService.PacketPage page =
                    packetMonitorSource.loadLatestPage(buildCurrentQuery(), PAGE_SIZE);
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
     * Loads the next page of older packets when the user reaches the bottom of the table.
     * The new slice fully replaces the previous one, so memory never keeps more
     * than {@value #PAGE_SIZE} rows.
     */
    private void loadOlderPageFromScroll() {
        if (pageLoadInProgress || !currentPageHasOlder || packetItems.isEmpty()) {
            return;
        }

        pageLoadInProgress = true;
        try {
            int anchorIndex = estimateFirstVisibleRow();
            PacketMonitorService.PacketPage page =
                    packetMonitorSource.loadOlderPage(
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
     * Loads the previous frame of newer packets when the user reaches the top of the table.
     * If the current frame is already the newest but was marked dirty by live
     * inserts, the first frame is reloaded.
     */
    private void loadNewerPageFromScroll() {
        if (pageLoadInProgress || (!currentPageHasNewer && !latestFrameDirty) || packetItems.isEmpty()) {
            return;
        }

        pageLoadInProgress = true;
        try {
            int anchorIndex = estimateFirstVisibleRow();
            PacketMonitorService.PacketPage page =
                    packetMonitorSource.loadNewerPage(
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
     * Applies an already loaded page to the table.
     * Contract:
     * - the table list is fully replaced by the new slice;
     * - if the viewed packet is still present in the slice, selection is preserved;
     * - if the packet disappeared from the table, details are cleared only in
     *   scenarios explicitly initiated by the user, such as a filter change.
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
     * Rebuilds the packet-type filter values.
     * Values are loaded from the database using the current route and search
     * filters, but are not yet constrained by the already selected type.
     */
    private void refreshTypeFilters() {
        if (typeFilter == null) {
            return;
        }

        String previousSelection = getActiveTypeFilterSelection();
        List<String> refreshedOptions = buildTypeFilterOptions(
                packetMonitorSource.loadPacketTypes(buildTypeOptionsQuery()),
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
        boolean captureEnabled = packetMonitorSource.isCaptureEnabled();
        btnStart.setDisable(captureEnabled);
        btnStop.setDisable(!captureEnabled);
        btnClear.setDisable(exportInProgress || totalStoredPacketCount == 0);
        btnExportJson.setDisable(exportInProgress || matchingPacketCount == 0);
        btnExportCsv.setDisable(exportInProgress || matchingPacketCount == 0);
        updateExportControlsState();
        statusLabel.setText(captureEnabled
                ? I18n.t("packetMonitor.status.capture.active")
                : I18n.t("packetMonitor.status.capture.stopped"));
    }

    private void updateExportControlsState() {
        if (routeFilter != null) {
            routeFilter.setDisable(exportInProgress);
        }
        if (typeFilter != null) {
            typeFilter.setDisable(exportInProgress);
        }
        if (fromDateTimeFilter != null) {
            fromDateTimeFilter.setDisable(exportInProgress);
        }
        if (toDateTimeFilter != null) {
            toDateTimeFilter.setDisable(exportInProgress);
        }
        if (searchField != null) {
            searchField.setDisable(exportInProgress);
        }
    }

    private void enqueueLivePacket(PacketLogEntry entry) {
        if (entry == null) {
            return;
        }
        if (!livePacketQueueOverflowed.get()) {
            int queued = pendingLivePacketCount.incrementAndGet();
            if (queued <= MAX_PENDING_LIVE_PACKET_EVENTS) {
                pendingLivePackets.add(entry);
            } else {
                pendingLivePacketCount.decrementAndGet();
                pendingLivePackets.clear();
                pendingLivePacketCount.set(0);
                livePacketQueueOverflowed.set(true);
            }
        }
        scheduleLivePacketFlush();
    }

    private void scheduleLivePacketFlush() {
        if (livePacketFlushQueued.compareAndSet(false, true)) {
            Platform.runLater(this::flushLivePacketEvents);
        }
    }

    private void flushLivePacketEvents() {
        try {
            if (livePacketQueueOverflowed.getAndSet(false)) {
                pendingLivePackets.clear();
                pendingLivePacketCount.set(0);
                reconcileLivePacketsFromStore();
                return;
            }

            PacketLogEntry entry;
            while ((entry = pendingLivePackets.poll()) != null) {
                pendingLivePacketCount.updateAndGet(value -> Math.max(0, value - 1));
                handlePacketLogged(entry);
                if (livePacketQueueOverflowed.get()) {
                    break;
                }
            }
        } finally {
            livePacketFlushQueued.set(false);
            if ((pendingLivePacketCount.get() > 0 || livePacketQueueOverflowed.get())
                    && livePacketFlushQueued.compareAndSet(false, true)) {
                Platform.runLater(this::flushLivePacketEvents);
            }
        }
    }

    private void reconcileLivePacketsFromStore() {
        refreshTypeFilters();
        totalStoredPacketCount = packetMonitorSource.countAllPackets();
        matchingPacketCount = packetMonitorSource.countMatchingPackets(buildCurrentQuery());

        if (packetItems.isEmpty() || (isTableNearTop() && !currentPageHasNewer)) {
            reloadCurrentFrame(true, false);
            return;
        }

        latestFrameDirty = matchingPacketCount > packetItems.size();
        currentPageHasNewer = latestFrameDirty;
        currentPageHasOlder = matchingPacketCount > packetItems.size();
        updateToolbarState();
    }

    /**
     * Handles a live incoming packet.
     * Automatic insertion happens only while the user is truly at the top of the
     * newest page. Otherwise the window only marks that newer data is available
     * and picks it up when the user scrolls back to the top.
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
     * Resets the window after the packet log is fully cleared.
     * No reference to the previously open packet may remain afterward.
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
     * Rebuilds the tree and preview for the selected packet.
     * With {@code null}, moves the right side of the window into placeholder state.
     */
    private void updatePacketDetails(PacketLogEntry entry) {
        if (entry == null) {
            viewedPacketId = -1L;
            viewedPacketEntry = null;
            currentHexPreview = PacketDebugFormatter.formatHexPreview(null);
            addressPreview.setText("");
            hexPreview.setText(I18n.t("packetMonitor.placeholder.selectPacket"));
            asciiPreview.setText("");
            clearPreviewSelection();
            TreeItem<PacketTreeNode> placeholder = new TreeItem<>(new PacketTreeNode("MeshPacket"));
            placeholder.setExpanded(true);
            placeholder.getChildren().add(new TreeItem<>(
                    new PacketTreeNode(I18n.t("packetMonitor.placeholder.selectPacket"))));
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
        TreeItem<PacketTreeNode> root = entry.getPacketType() != null
                && entry.getPacketType().startsWith("MeshCore Companion")
                ? PacketDebugFormatter.buildRawPacketTree(entry.getPacketType(), entry.getPacketBytes())
                : PacketDebugFormatter.buildPacketTree(entry.getPacketBytes());
        expandTree(root);
        packetTree.setRoot(root);
        packetTree.getSelectionModel().clearSelection();
        updatePacketActionButtonsState();
    }

    /**
     * Restores selection to the already viewed packet after page replacement.
     * Must not rebuild details when the user effectively remains on the same row.
     *
     * @return {@code true} if the packet is still present in the current page
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
     * After the sliding window shifts, prevents JavaFX from silently selecting a
     * neighboring row when the original packet left the window.
     */
    private void preserveViewedSelectionAfterWindowShift() {
        synchronizeTableSelectionWithViewedPacket();
    }

    /**
     * Keeps table selection consistent with the currently opened packet.
     * If the packet leaves the current sliding window, row selection is cleared
     * but the right panel does not switch to a neighboring item.
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
     * Compares entries in the same order as the table: newest {@code capturedAt}
     * first, then larger {@code id}.
     *
     * @return negative value if {@code left} should come before {@code right}
     */
    private int comparePacketOrder(PacketLogEntry left, PacketLogEntry right) {
        int byCapturedAt = Long.compare(right.getCapturedAt(), left.getCapturedAt());
        if (byCapturedAt != 0) {
            return byCapturedAt;
        }
        return Long.compare(right.getId(), left.getId());
    }

    private PacketMonitorService.PacketQuery buildCurrentQuery() {
        RouteFilterSelection routeSelection =
                resolveRouteFilterSelection(routeFilter != null ? routeFilter.getValue() : filterAllRoutes());
        String selectedType = getActiveTypeFilterSelection();
        String packetType = isAllTypesSelection(selectedType) ? null : selectedType;
        String searchText = searchField != null ? searchField.getText() : null;
        Long capturedAtFromMillis = resolveCapturedAtBoundary(fromDateTimeFilter, true);
        Long capturedAtToMillis = resolveCapturedAtBoundary(toDateTimeFilter, false);
        return new PacketMonitorService.PacketQuery(
                routeSelection.direction(),
                packetType,
                routeSelection.transportMechanism(),
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
                query.transportMechanism(),
                query.searchText(),
                query.capturedAtFromMillis(),
                query.capturedAtToMillis()
        );
    }

    /**
     * Locally checks whether a live packet matches the current UI filters.
     * Used only for incremental counter and top-slice updates; authoritative
     * filtering remains on the database side.
     */
    private boolean matchesCurrentFilters(PacketLogEntry entry) {
        if (entry == null) {
            return false;
        }

        RouteFilterSelection routeSelection =
                resolveRouteFilterSelection(routeFilter != null ? routeFilter.getValue() : filterAllRoutes());
        if (!routeSelection.matches(entry)) {
            return false;
        }

        String selectedType = getActiveTypeFilterSelection();
        if (selectedType != null && !isAllTypesSelection(selectedType)
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
                || containsIgnoreCase(entry.getRouteText(), lowerQuery)
                || containsIgnoreCase(entry.getTransportMechanism(), lowerQuery)
                || containsIgnoreCase(entry.getTransportText(), lowerQuery)
                || containsIgnoreCase(formatPacketFromNode(entry), lowerQuery)
                || containsIgnoreCase(formatPacketToNode(entry), lowerQuery)
                || containsIgnoreCase(entry.getPayloadText(), lowerQuery)
                || containsIgnoreCase(entry.getDirectionText(), lowerQuery);
    }

    /**
     * Builds the UI representation of the {@code from} field as {@code Name (!nodeId)}.
     * If the node name is unavailable, only the standard {@code !nodeId} is returned;
     * the value saved in the database is used only as a fallback after packet parsing errors.
     */
    private String formatPacketFromNode(PacketLogEntry entry) {
        return resolvePacketEndpoints(entry).fromNode();
    }

    /**
     * Builds the UI representation of the {@code to} field as {@code Name (!nodeId)}.
     * If the node name is unavailable, only the standard {@code !nodeId} is returned;
     * the value saved in the database is used only as a fallback after packet parsing errors.
     */
    private String formatPacketToNode(PacketLogEntry entry) {
        return resolvePacketEndpoints(entry).toNode();
    }

    /**
     * Recomputes the From/To labels from packet bytes without modifying the database.
     * For already saved rows, this keeps table and text-export formatting consistent
     * regardless of historical contents of {@code from_node/to_node}.
     */
    private PacketDebugFormatter.PacketEndpoints resolvePacketEndpoints(PacketLogEntry entry) {
        return PacketDebugFormatter.resolvePacketEndpoints(entry, resolveEntryDeviceState(entry));
    }

    /**
     * Selects the {@link DeviceState} matching the packet row owner node.
     * Contract:
     * - an exact {@code ownerNodeId} match uses the linked active {@link DeviceState};
     * - if owner is blank and there is exactly one active connection, its state is used;
     * - without a suitable connection, {@code null} is returned and the formatter
     *   displays only standard {@code !nodeId} values without names.
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

    static String filterAllRoutes() {
        return I18n.t("packetMonitor.filter.route.all");
    }

    static String filterIncoming() {
        return I18n.t("packetMonitor.filter.route.incoming");
    }

    static String filterOutgoing() {
        return I18n.t("packetMonitor.filter.route.outgoing");
    }

    static String filterAllMqtt() {
        return I18n.t("packetMonitor.filter.route.allMqtt");
    }

    static String filterIncomingMqtt() {
        return I18n.t("packetMonitor.filter.route.incomingMqtt");
    }

    static String filterOutgoingMqtt() {
        return I18n.t("packetMonitor.filter.route.outgoingMqtt");
    }

    static String filterAllTypes() {
        return I18n.t("packetMonitor.filter.type.all");
    }

    private static boolean isAllRoutesSelection(String value) {
        return value == null || value.isBlank()
                || matchesLocalizedOption(value, filterAllRoutes(), "Все LoRa", "All LoRa");
    }

    private static boolean isIncomingRouteSelection(String value) {
        return matchesLocalizedOption(value, filterIncoming(), "Входящие", "Incoming");
    }

    private static boolean isOutgoingRouteSelection(String value) {
        return matchesLocalizedOption(value, filterOutgoing(), "Исходящие", "Outgoing");
    }

    private static boolean isAllMqttRouteSelection(String value) {
        return matchesLocalizedOption(
                value,
                filterAllMqtt(),
                "Все MQTT",
                "Все MQTT (входящие/исходящие)",
                "All MQTT",
                "All MQTT (incoming/outgoing)",
                "Alle MQTT (ein-/ausgehend)"
        );
    }

    private static boolean isIncomingMqttRouteSelection(String value) {
        return matchesLocalizedOption(value, filterIncomingMqtt(), "Входящие MQTT", "Incoming MQTT");
    }

    private static boolean isOutgoingMqttRouteSelection(String value) {
        return matchesLocalizedOption(value, filterOutgoingMqtt(), "Исходящие MQTT", "Outgoing MQTT");
    }

    private static boolean isAllTypesSelection(String value) {
        return value == null || value.isBlank()
                || matchesLocalizedOption(value, filterAllTypes(), "Все типы", "All types");
    }

    private static boolean matchesLocalizedOption(String value, String current, String... legacyValues) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.equals(current)) {
            return true;
        }
        for (String legacyValue : legacyValues) {
            if (value.equals(legacyValue)) {
                return true;
            }
        }
        return false;
    }

    static List<String> buildRouteFilterOptions() {
        return List.of(
                filterAllRoutes(),
                filterIncoming(),
                filterOutgoing(),
                filterAllMqtt(),
                filterIncomingMqtt(),
                filterOutgoingMqtt()
        );
    }

    static RouteFilterSelection resolveRouteFilterSelection(String selectedRoute) {
        if (isAllRoutesSelection(selectedRoute)) {
            return new RouteFilterSelection(null, null, true);
        }
        if (isIncomingRouteSelection(selectedRoute)) {
            return new RouteFilterSelection(PacketLogEntry.Direction.INCOMING, null, true);
        }
        if (isOutgoingRouteSelection(selectedRoute)) {
            return new RouteFilterSelection(PacketLogEntry.Direction.OUTGOING, null, true);
        }
        if (isAllMqttRouteSelection(selectedRoute)) {
            return new RouteFilterSelection(null, PacketMonitorService.TRANSPORT_MQTT, false);
        }
        if (isIncomingMqttRouteSelection(selectedRoute)) {
            return new RouteFilterSelection(
                    PacketLogEntry.Direction.INCOMING,
                    PacketMonitorService.TRANSPORT_MQTT,
                    false);
        }
        if (isOutgoingMqttRouteSelection(selectedRoute)) {
            return new RouteFilterSelection(
                    PacketLogEntry.Direction.OUTGOING,
                    PacketMonitorService.TRANSPORT_MQTT,
                    false);
        }
        return new RouteFilterSelection(null, null, true);
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
        options.add(filterAllTypes());
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
                : filterAllTypes();
    }

    private static String normalizeTypeFilterSelection(String selection) {
        return selection == null || selection.isBlank() || isAllTypesSelection(selection)
                ? filterAllTypes()
                : selection;
    }

    private static String normalizeDynamicTypeOption(String type) {
        if (type == null || type.isBlank() || isAllTypesSelection(type)) {
            return null;
        }
        return type;
    }

    /**
     * Attaches observation to the table's vertical scrollbar.
     * Loading happens only at viewport boundaries and never accumulates multiple
     * pages in memory.
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
     * Re-loading a page at the same boundary is allowed only after the user has
     * actually moved away from the scroll-range edge and returned to it.
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
     * Approximates the first visible table row.
     * Used as an anchor for smooth data-window shifts during loading.
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
     * Programmatically selects a packet in the table.
     * The {@link #restoringSelection} flag suppresses selection-listener side
     * effects during internal selection restore.
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
     * Highlights the byte range of the selected tree node in the HEX and ASCII columns.
     * The address column is intentionally excluded from highlighting.
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
     * Clears highlighting from all preview columns.
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
     * Synchronizes action-button availability with the selected packet state.
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
     * Copies the current packet in text export format.
     */
    private void copyViewedPacketAsText() {
                copyViewedPacket(PacketDebugFormatter.exportPacketAsText(
                        viewedPacketEntry,
                        resolveEntryDeviceState(viewedPacketEntry)),
                I18n.t("packetMonitor.toast.copiedText"));
    }

    /**
     * Copies the current packet as protobuf-style JSON compatible with Meshtastic Web.
     */
    private void copyViewedPacketAsJson() {
        copyViewedPacket(PacketDebugFormatter.exportPacketAsJson(viewedPacketEntry),
                I18n.t("packetMonitor.toast.copiedJson"));
    }

    /**
     * Shared clipboard-copy implementation.
     * Empty export is ignored quietly and is not treated as an error.
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
     * Saves the current packet to a file.
     * Contract:
     * - extension is normalized to the expected format;
     * - cancel has no side effects;
     * - write errors are reported through a toast.
     */
    private void saveViewedPacket(String content, boolean jsonFormat) {
        if (viewedPacketEntry == null || content == null || content.isBlank()) {
            return;
        }

        saveContentToFile(
                content,
                jsonFormat
                        ? I18n.t("packetMonitor.save.json.title")
                        : I18n.t("packetMonitor.save.text.title"),
                buildPacketExportFileName(jsonFormat),
                jsonFormat
                        ? I18n.t("packetMonitor.fileType.json")
                        : I18n.t("packetMonitor.fileType.text"),
                jsonFormat ? ".json" : ".txt",
                I18n.t("packetMonitor.toast.packetSavedPrefix"),
                I18n.t("packetMonitor.toast.packetSaveFailed")
        );
    }

    private void exportFilteredPacketsAsJson() {
        if (exportInProgress) {
            return;
        }
        PacketMonitorService.PacketQuery query = buildCurrentQuery();
        int totalToExport = packetMonitorSource.countMatchingPackets(query);
        if (totalToExport == 0) {
            Toast.show(Toast.Type.INFO, I18n.t("packetMonitor.toast.exportEmpty"));
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("packetMonitor.export.json.title"));
        chooser.setInitialFileName(buildFilteredPacketsExportFileName(".json"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t("packetMonitor.fileType.json"), "*.json"));

        File selected = chooser.showSaveDialog(stage);
        if (selected == null) {
            return;
        }
        File target = ensureExtension(selected, ".json");

        PacketDebugFormatter.PacketCollectionExportMetadata metadata = buildCurrentExportMetadata();
        beginExportProgress(totalToExport);
        exportExecutor.execute(() -> runFilteredPacketExport(query, metadata, target, totalToExport));
    }

    private void exportFilteredPacketsAsCsv() {
        if (exportInProgress) {
            return;
        }
        PacketMonitorService.PacketQuery query = buildCurrentQuery();
        int totalToExport = packetMonitorSource.countMatchingPackets(query);
        if (totalToExport == 0) {
            Toast.show(Toast.Type.INFO, I18n.t("packetMonitor.toast.exportEmpty"));
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("packetMonitor.export.csv.title"));
        chooser.setInitialFileName(buildFilteredPacketsExportFileName(".csv"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t("packetMonitor.fileType.csv"), "*.csv"));

        File selected = chooser.showSaveDialog(stage);
        if (selected == null) {
            return;
        }
        File target = ensureExtension(selected, ".csv");

        beginExportProgress(totalToExport);
        exportExecutor.execute(() -> runFilteredPacketCsvExport(query, target, totalToExport));
    }

    private void runFilteredPacketExport(PacketMonitorService.PacketQuery query,
                                         PacketDebugFormatter.PacketCollectionExportMetadata metadata,
                                         File target,
                                         long totalToExport) {
        try (BufferedWriter writer = Files.newBufferedWriter(target.toPath(), StandardCharsets.UTF_8)) {
            PacketDebugFormatter.PacketCollectionJsonExportState state =
                    PacketDebugFormatter.beginPacketCollectionJsonExport(writer, metadata);
            state = exportFilteredPacketsJsonBatches(query, state, totalToExport);
            PacketDebugFormatter.finishPacketCollectionJsonExport(state, state.packetCount());
            long exportedCount = state.packetCount();
            Platform.runLater(() -> {
                finishExportProgress();
                Toast.show(Toast.Type.SUCCESS,
                        I18n.t("packetMonitor.toast.exportSaved", target.getName(), Long.toString(exportedCount)));
            });
        } catch (IOException e) {
            Platform.runLater(() -> {
                finishExportProgress();
                Toast.show(Toast.Type.ERROR, I18n.t("packetMonitor.toast.exportSaveFailed"));
            });
        }
    }

    private PacketDebugFormatter.PacketCollectionJsonExportState exportFilteredPacketsJsonBatches(
            PacketMonitorService.PacketQuery query,
            PacketDebugFormatter.PacketCollectionJsonExportState initialState,
            long totalToExport) throws IOException {
        final PacketDebugFormatter.PacketCollectionJsonExportState[] stateHolder = {initialState};
        packetMonitorSource.forEachMatchingBatch(query, PACKET_EXPORT_BATCH_SIZE, batch -> {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Packet export interrupted");
            }
            PacketDebugFormatter.PacketCollectionJsonExportState state = stateHolder[0];
            for (PacketLogEntry entry : batch) {
                state = PacketDebugFormatter.writePacketCollectionJsonEntry(
                        state,
                        entry,
                        resolveEntryDeviceState(entry)
                );
            }
            stateHolder[0] = state;
            long exportedCount = state.packetCount();
            Platform.runLater(() -> updateExportProgress(exportedCount, totalToExport));
        });
        return stateHolder[0];
    }

    private void runFilteredPacketCsvExport(PacketMonitorService.PacketQuery query,
                                            File target,
                                            long totalToExport) {
        try (BufferedWriter writer = Files.newBufferedWriter(target.toPath(), StandardCharsets.UTF_8)) {
            PacketDebugFormatter.PacketCollectionCsvExportState state =
                    PacketDebugFormatter.beginPacketCollectionCsvExport(writer);
            state = exportFilteredPacketsCsvBatches(query, state, totalToExport);
            PacketDebugFormatter.finishPacketCollectionCsvExport(state);
            long exportedCount = state.packetCount();
            Platform.runLater(() -> {
                finishExportProgress();
                Toast.show(Toast.Type.SUCCESS,
                        I18n.t("packetMonitor.toast.exportSaved", target.getName(), Long.toString(exportedCount)));
            });
        } catch (IOException e) {
            Platform.runLater(() -> {
                finishExportProgress();
                Toast.show(Toast.Type.ERROR, I18n.t("packetMonitor.toast.exportSaveFailed"));
            });
        }
    }

    private PacketDebugFormatter.PacketCollectionCsvExportState exportFilteredPacketsCsvBatches(
            PacketMonitorService.PacketQuery query,
            PacketDebugFormatter.PacketCollectionCsvExportState initialState,
            long totalToExport) throws IOException {
        final PacketDebugFormatter.PacketCollectionCsvExportState[] stateHolder = {initialState};
        packetMonitorSource.forEachMatchingBatch(query, PACKET_EXPORT_BATCH_SIZE, batch -> {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Packet export interrupted");
            }
            PacketDebugFormatter.PacketCollectionCsvExportState state = stateHolder[0];
            for (PacketLogEntry entry : batch) {
                state = PacketDebugFormatter.writePacketCollectionCsvEntry(
                        state,
                        entry,
                        resolveEntryDeviceState(entry)
                );
            }
            stateHolder[0] = state;
            long exportedCount = state.packetCount();
            Platform.runLater(() -> updateExportProgress(exportedCount, totalToExport));
        });
        return stateHolder[0];
    }

    private void beginExportProgress(long totalToExport) {
        exportInProgress = true;
        updateExportProgress(0, totalToExport);
        if (exportProgressBox != null) {
            exportProgressBox.setManaged(true);
            exportProgressBox.setVisible(true);
        }
        updateToolbarState();
    }

    private void updateExportProgress(long exportedCount, long totalToExport) {
        long safeTotal = Math.max(1, totalToExport);
        long safeExported = Math.max(0, Math.min(exportedCount, safeTotal));
        if (exportProgressLabel != null) {
            exportProgressLabel.setText(formatExportProgressText(safeExported, safeTotal));
        }
        if (exportProgressBar != null) {
            exportProgressBar.setProgress((double) safeExported / safeTotal);
        }
    }

    private void finishExportProgress() {
        exportInProgress = false;
        if (exportProgressBox != null) {
            exportProgressBox.setVisible(false);
            exportProgressBox.setManaged(false);
        }
        if (exportProgressLabel != null) {
            exportProgressLabel.setText("");
        }
        if (exportProgressBar != null) {
            exportProgressBar.setProgress(0);
        }
        updateToolbarState();
    }

    private void saveContentToFile(String content,
                                   String dialogTitle,
                                   String initialFileName,
                                   String extensionFilterLabel,
                                   String extension,
                                   String successMessagePrefix,
                                   String errorMessage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(dialogTitle);
        chooser.setInitialFileName(initialFileName);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                extensionFilterLabel,
                "*" + extension
        ));

        File selected = chooser.showSaveDialog(stage);
        if (selected == null) {
            return;
        }

        File target = ensureExtension(selected, extension);
        try {
            Files.writeString(target.toPath(), content, StandardCharsets.UTF_8);
            Toast.show(Toast.Type.SUCCESS, successMessagePrefix + target.getName());
        } catch (IOException e) {
            Toast.show(Toast.Type.ERROR, errorMessage);
        }
    }

    /**
     * Builds the export file name from capture time and row id.
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

    private String buildFilteredPacketsExportFileName(String extension) {
        String timestamp = PACKET_EXPORT_FILE_TIME.format(
                Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDateTime()
        );
        return "lora-packets-" + timestamp + extension;
    }

    private PacketDebugFormatter.PacketCollectionExportMetadata buildCurrentExportMetadata() {
        return new PacketDebugFormatter.PacketCollectionExportMetadata(
                System.currentTimeMillis(),
                routeFilter != null ? routeFilter.getValue() : filterAllRoutes(),
                getActiveTypeFilterSelection(),
                searchField != null ? searchField.getText() : null,
                formatDateTimeFilterValue(fromDateTimeFilter),
                formatDateTimeFilterValue(toDateTimeFilter)
        );
    }

    /**
     * Ensures the expected file extension is present even when the user omitted it.
     */
    private File ensureExtension(File file, String extension) {
        if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(extension)) {
            return file;
        }
        return new File(file.getParentFile(), file.getName() + extension);
    }

    /**
     * Fully expands the packet tree.
     * Called only after the tree has been rebuilt for a newly selected packet.
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

    private interface PacketMonitorDataSource {
        boolean isCaptureEnabled();

        void startCapture();

        void stopCapture();

        void clear();

        void addListener(PacketMonitorService.Listener listener);

        void removeListener(PacketMonitorService.Listener listener);

        PacketMonitorService.PacketPage loadLatestPage(PacketMonitorService.PacketQuery query, int limit);

        PacketMonitorService.PacketPage loadOlderPage(PacketMonitorService.PacketQuery query,
                                                      PacketMonitorService.PageCursor cursor,
                                                      int limit);

        PacketMonitorService.PacketPage loadNewerPage(PacketMonitorService.PacketQuery query,
                                                      PacketMonitorService.PageCursor cursor,
                                                      int limit);

        List<String> loadPacketTypes(PacketMonitorService.PacketQuery query);

        int countAllPackets();

        int countMatchingPackets(PacketMonitorService.PacketQuery query);

        long forEachMatchingBatch(PacketMonitorService.PacketQuery query,
                                  int batchSize,
                                  PacketMonitorService.PacketBatchConsumer consumer) throws IOException;
    }

    private static final class LocalPacketMonitorDataSource implements PacketMonitorDataSource {
        private final PacketMonitorService service;

        private LocalPacketMonitorDataSource(PacketMonitorService service) {
            this.service = service;
        }

        @Override
        public boolean isCaptureEnabled() {
            return service.isCaptureEnabled();
        }

        @Override
        public void startCapture() {
            service.startCapture();
        }

        @Override
        public void stopCapture() {
            service.stopCapture();
        }

        @Override
        public void clear() {
            service.clear();
        }

        @Override
        public void addListener(PacketMonitorService.Listener listener) {
            service.addListener(listener);
        }

        @Override
        public void removeListener(PacketMonitorService.Listener listener) {
            service.removeListener(listener);
        }

        @Override
        public PacketMonitorService.PacketPage loadLatestPage(PacketMonitorService.PacketQuery query, int limit) {
            return service.loadLatestPage(query, limit);
        }

        @Override
        public PacketMonitorService.PacketPage loadOlderPage(PacketMonitorService.PacketQuery query,
                                                             PacketMonitorService.PageCursor cursor,
                                                             int limit) {
            return service.loadOlderPage(query, cursor, limit);
        }

        @Override
        public PacketMonitorService.PacketPage loadNewerPage(PacketMonitorService.PacketQuery query,
                                                             PacketMonitorService.PageCursor cursor,
                                                             int limit) {
            return service.loadNewerPage(query, cursor, limit);
        }

        @Override
        public List<String> loadPacketTypes(PacketMonitorService.PacketQuery query) {
            return service.loadPacketTypes(query);
        }

        @Override
        public int countAllPackets() {
            return service.countAllPackets();
        }

        @Override
        public int countMatchingPackets(PacketMonitorService.PacketQuery query) {
            return service.countMatchingPackets(query);
        }

        @Override
        public long forEachMatchingBatch(PacketMonitorService.PacketQuery query,
                                         int batchSize,
                                         PacketMonitorService.PacketBatchConsumer consumer) throws IOException {
            return service.forEachMatchingBatch(query, batchSize, consumer);
        }
    }

    private static final class RemotePacketMonitorDataSource implements PacketMonitorDataSource {
        private final RemoteRpcState rpcState;
        private final RpcEventListener rpcEventListener = this::handleRemoteEvent;
        private volatile PacketMonitorService.Listener listener;
        private volatile boolean captureEnabled;

        private RemotePacketMonitorDataSource(RemoteRpcState rpcState) {
            this.rpcState = rpcState;
            this.captureEnabled = RemotePacketMonitorJson.captureEnabled(
                    call("packetMonitor.captureState", new com.google.gson.JsonObject()));
        }

        @Override
        public boolean isCaptureEnabled() {
            return captureEnabled;
        }

        @Override
        public void startCapture() {
            captureEnabled = RemotePacketMonitorJson.captureEnabled(
                    call("packetMonitor.start", new com.google.gson.JsonObject()));
        }

        @Override
        public void stopCapture() {
            captureEnabled = RemotePacketMonitorJson.captureEnabled(
                    call("packetMonitor.stop", new com.google.gson.JsonObject()));
        }

        @Override
        public void clear() {
            call("packetMonitor.clear", new com.google.gson.JsonObject());
        }

        @Override
        public void addListener(PacketMonitorService.Listener listener) {
            this.listener = listener;
            rpcState.client().addEventListener(rpcEventListener);
        }

        @Override
        public void removeListener(PacketMonitorService.Listener listener) {
            rpcState.client().removeEventListener(rpcEventListener);
            if (this.listener == listener) {
                this.listener = null;
            }
        }

        @Override
        public PacketMonitorService.PacketPage loadLatestPage(PacketMonitorService.PacketQuery query, int limit) {
            return RemotePacketMonitorJson.parsePage(call("packetMonitor.page",
                    RemotePacketMonitorJson.pageParams("latest", query, null, limit)));
        }

        @Override
        public PacketMonitorService.PacketPage loadOlderPage(PacketMonitorService.PacketQuery query,
                                                             PacketMonitorService.PageCursor cursor,
                                                             int limit) {
            return RemotePacketMonitorJson.parsePage(call("packetMonitor.page",
                    RemotePacketMonitorJson.pageParams("older", query, cursor, limit)));
        }

        @Override
        public PacketMonitorService.PacketPage loadNewerPage(PacketMonitorService.PacketQuery query,
                                                             PacketMonitorService.PageCursor cursor,
                                                             int limit) {
            return RemotePacketMonitorJson.parsePage(call("packetMonitor.page",
                    RemotePacketMonitorJson.pageParams("newer", query, cursor, limit)));
        }

        @Override
        public List<String> loadPacketTypes(PacketMonitorService.PacketQuery query) {
            return RemotePacketMonitorJson.parseTypes(call("packetMonitor.types",
                    RemotePacketMonitorJson.queryParams(query)));
        }

        @Override
        public int countAllPackets() {
            return RemotePacketMonitorJson.totalCount(call("packetMonitor.counts",
                    RemotePacketMonitorJson.queryParams(null)));
        }

        @Override
        public int countMatchingPackets(PacketMonitorService.PacketQuery query) {
            return RemotePacketMonitorJson.matchingCount(call("packetMonitor.counts",
                    RemotePacketMonitorJson.queryParams(query)));
        }

        @Override
        public long forEachMatchingBatch(PacketMonitorService.PacketQuery query,
                                         int batchSize,
                                         PacketMonitorService.PacketBatchConsumer consumer) throws IOException {
            long processed = 0;
            PacketMonitorService.PageCursor cursor = null;
            String request = "latest";
            while (true) {
                PacketMonitorService.PacketPage page = RemotePacketMonitorJson.parsePage(call("packetMonitor.page",
                        RemotePacketMonitorJson.pageParams(request, query, cursor, batchSize)));
                if (page.entries().isEmpty()) {
                    return processed;
                }
                consumer.accept(page.entries());
                processed += page.entries().size();
                if (page.entries().size() < batchSize || !page.hasOlder()) {
                    return processed;
                }
                cursor = PacketMonitorService.PageCursor.fromEntry(page.entries().getLast());
                request = "older";
            }
        }

        private com.google.gson.JsonElement call(String method, com.google.gson.JsonObject params) {
            try {
                return rpcState.client().call(method, params, REMOTE_RPC_TIMEOUT).join();
            } catch (CompletionException e) {
                throw new IllegalStateException(RemoteChatJson.errorMessage(e), e);
            }
        }

        private void handleRemoteEvent(String event, com.google.gson.JsonElement payload) {
            PacketMonitorService.Listener currentListener = listener;
            if (currentListener == null) {
                return;
            }
            switch (event) {
                case "packet.monitor.logged" -> {
                    PacketLogEntry entry = RemotePacketMonitorJson.parseEventEntry(payload);
                    if (entry != null) {
                        currentListener.onPacketLogged(entry);
                    }
                }
                case "packet.monitor.capture" -> {
                    captureEnabled = RemotePacketMonitorJson.captureEnabled(payload);
                    currentListener.onCaptureStateChanged(captureEnabled);
                }
                case "packet.monitor.cleared" -> currentListener.onCleared();
                default -> {
                }
            }
        }
    }

    /**
     * Releases window-level resources and unregisters the singleton window.
     */
    private void dispose() {
        packetMonitorSource.removeListener(packetListener);
        exportExecutor.shutdownNow();
        if (stage != null && stage.getScene() != null) {
            ThemeManager.unregisterScene(stage.getScene());
        }
        instance = null;
    }

    /**
     * Restores coordinates, size, and maximized flag.
     * If the saved rectangle no longer intersects available screens, the window
     * is moved back into the visible area of the nearest display.
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
     * Subscribes the window to geometry changes.
     * Only normal bounds are saved, not the current size of a maximized window.
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
     * Updates normal window bounds when the window is not maximized.
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
     * Saves window geometry and vertical divider position to preferences.
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
     * Moves the window back into visible space if its normal bounds left all available screens.
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
     * Converts the selected date/time filter value into an SQL range boundary.
     * Contract:
     * - absence of a date disables the filter and returns {@code null};
     * - all-day mode expands to the beginning or end of the day;
     * - the upper bound is inclusive, so it is moved to the last nanosecond of the minute/day.
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

    private static String formatDateTimeFilterValue(DateTimePicker dateTimePicker) {
        if (dateTimePicker == null || dateTimePicker.getDate() == null) {
            return null;
        }
        if (dateTimePicker.getTime() == null) {
            return dateTimePicker.getDate().format(PACKET_EXPORT_FILTER_DATE);
        }
        return LocalDateTime.of(dateTimePicker.getDate(), dateTimePicker.getTime())
                .format(PACKET_EXPORT_FILTER_DATE_TIME);
    }

    static String formatExportProgressText(long exportedCount, long totalToExport) {
        long safeTotal = Math.max(1, totalToExport);
        long safeExported = Math.max(0, Math.min(exportedCount, safeTotal));
        int percent = (int) Math.round((double) safeExported * 100.0 / safeTotal);
        return I18n.t("packetMonitor.export.progress",
                Long.toString(safeExported),
                Long.toString(safeTotal),
                Integer.toString(percent));
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query);
    }
}
