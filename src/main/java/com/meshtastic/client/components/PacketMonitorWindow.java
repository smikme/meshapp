package com.meshtastic.client.components;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.model.PacketTreeNode;
import com.meshtastic.client.service.PacketMonitorService;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.PacketDebugFormatter;
import com.meshtastic.client.utils.SvgIconLoader;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
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
    private static final String FILTER_ALL_TYPES = "Все типы";
    private static final int HEX_PREVIEW_VISIBLE_ROWS = 16;
    private static final int HEX_PREVIEW_ADDRESS_COLUMNS = 6;
    private static final int HEX_PREVIEW_BYTES_COLUMNS = 50;
    private static final int HEX_PREVIEW_ASCII_COLUMNS = 18;
    private static final double DEFAULT_WINDOW_WIDTH = 1260;
    private static final double DEFAULT_WINDOW_HEIGHT = 860;
    private static final double PACKET_TABLE_TIME_COLUMN_WIDTH = 190;
    private static final double PACKET_TABLE_TYPE_COLUMN_WIDTH = 130;
    private static final double PACKET_TABLE_NODE_COLUMN_WIDTH = 150;
    private static final double PACKET_TABLE_PAYLOAD_MIN_WIDTH = 260;
    private static final DateTimeFormatter PACKET_EXPORT_FILE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static PacketMonitorWindow instance;

    private final PacketMonitorService packetMonitorService;
    private final ObservableList<PacketLogEntry> packetItems = FXCollections.observableArrayList();
    private final ObservableList<String> packetTypeFilters = FXCollections.observableArrayList(FILTER_ALL_TYPES);
    private final FilteredList<PacketLogEntry> filteredItems = new FilteredList<>(packetItems);

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
    private TextField searchField;
    private Button btnStart;
    private Button btnStop;
    private Button btnClear;
    private Button btnCopyText;
    private Button btnCopyJson;
    private Button btnSaveText;
    private Button btnSaveJson;
    private Label statusLabel;
    private Label countLabel;
    private PacketLogEntry viewedPacketEntry;
    private long viewedPacketId = -1L;
    private boolean restoringSelection;
    private PacketDebugFormatter.HexPreview currentHexPreview = PacketDebugFormatter.formatHexPreview(null);
    private double normalWindowX = Double.NaN;
    private double normalWindowY = Double.NaN;
    private double normalWindowWidth = DEFAULT_WINDOW_WIDTH;
    private double normalWindowHeight = DEFAULT_WINDOW_HEIGHT;
    private boolean restoreWindowMaximized;

    private PacketMonitorWindow() {
        this.packetMonitorService = PacketMonitorService.getInstance();
        createStage();
        packetMonitorService.addListener(packetListener);
        packetItems.setAll(packetMonitorService.loadAll());
        refreshTypeFilters();
        applyFiltersPreservingView();
        updateToolbarState();
        if (!packetItems.isEmpty()) {
            PacketLogEntry latest = packetItems.getFirst();
            selectPacket(latest, true, true);
        } else {
            updatePacketDetails(null);
        }
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
     * - окно использует compact utility frame;
     * - геометрия восстанавливается до {@code show()};
     * - геометрия сохраняется на hiding, чтобы одинаково работать при нативном и программном закрытии.
     */
    private void createStage() {
        VBox root = new VBox(10);
        root.getStyleClass().add("packet-monitor-root");
        root.setPadding(new Insets(12));

        root.getChildren().addAll(createHeader(), createFilterBar(), createContentSplitPane());

        Scene scene = new Scene(root, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
        ThemeManager.applyTheme(scene, AppPreferences.isDarkMode());

        stage = new Stage();
        stage.initStyle(StageStyle.UTILITY);
        stage.setTitle("Мониторинг LoRa-пакетов");
        if (MeshApp.getPrimaryStage() != null) {
            stage.initOwner(MeshApp.getPrimaryStage());
        }
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
        title.setFont(Font.font("Roboto", FontWeight.BOLD, 16));

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
        btnClear.disableProperty().bind(Bindings.isEmpty(packetItems));

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
                FILTER_ALL_DIRECTIONS, FILTER_INCOMING, FILTER_OUTGOING));
        directionFilter.setValue(FILTER_ALL_DIRECTIONS);
        directionFilter.valueProperty().addListener((obs, oldValue, newValue) -> applyFiltersPreservingView());

        typeFilter = new ComboBox<>(packetTypeFilters);
        typeFilter.setValue(FILTER_ALL_TYPES);
        typeFilter.valueProperty().addListener((obs, oldValue, newValue) -> applyFiltersPreservingView());

        searchField = new TextField();
        searchField.setPromptText("Поиск по типу, узлам и payload");
        searchField.getStyleClass().add("packet-monitor-search-field");
        searchField.textProperty().addListener((obs, oldValue, newValue) -> applyFiltersPreservingView());

        SVGPath searchIcon = SvgIconLoader.load("/icons/search.svg", 16);
        HBox searchBox = new HBox(8);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.getStyleClass().add("packet-monitor-search-box");
        if (searchIcon != null) {
            searchBox.getChildren().add(searchIcon);
        }
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchBox.getChildren().add(searchField);

        countLabel = new Label();
        countLabel.getStyleClass().add("config-status-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        filterBar.getChildren().addAll(
                createFilterLabel("Направление"),
                directionFilter,
                createFilterLabel("Тип"),
                typeFilter,
                spacer,
                searchBox,
                countLabel
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
     */
    private TableView<PacketLogEntry> createPacketTable() {
        TableView<PacketLogEntry> table = new TableView<>(filteredItems);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<PacketLogEntry, String> colTime = new TableColumn<>("Дата/время");
        colTime.setCellValueFactory(new PropertyValueFactory<>("capturedAtText"));
        configureCompactColumn(colTime, PACKET_TABLE_TIME_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colType = new TableColumn<>("Тип пакета");
        colType.setCellValueFactory(new PropertyValueFactory<>("packetType"));
        configureCompactColumn(colType, PACKET_TABLE_TYPE_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colFrom = new TableColumn<>("От");
        colFrom.setCellValueFactory(new PropertyValueFactory<>("fromNode"));
        configureCompactColumn(colFrom, PACKET_TABLE_NODE_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colTo = new TableColumn<>("Кому");
        colTo.setCellValueFactory(new PropertyValueFactory<>("toNode"));
        configureCompactColumn(colTo, PACKET_TABLE_NODE_COLUMN_WIDTH);

        TableColumn<PacketLogEntry, String> colPayload = new TableColumn<>("Payload");
        colPayload.setCellValueFactory(new PropertyValueFactory<>("payloadText"));
        colPayload.setMinWidth(PACKET_TABLE_PAYLOAD_MIN_WIDTH);
        colPayload.setPrefWidth(PACKET_TABLE_PAYLOAD_MIN_WIDTH);
        colPayload.setSortable(false);

        table.getColumns().addAll(colTime, colType, colFrom, colTo, colPayload);
        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(PacketLogEntry item, boolean empty) {
                super.updateItem(item, empty);
                setStyle("");
                setTooltip(null);
                if (empty || item == null) {
                    return;
                }
                if (item.getDirection() == PacketLogEntry.Direction.INCOMING) {
                    setStyle("-fx-text-fill: -color-accent-emphasis;");
                } else {
                    setStyle("-fx-text-fill: -color-success-emphasis;");
                }
                setTooltip(new Tooltip(item.getDirectionText() + ": " + item.getPayloadText()));
            }
        });
        table.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldValue, newValue) -> {
                    if (restoringSelection || newValue == null) {
                        return;
                    }
                    if (newValue.getId() == viewedPacketId) {
                        return;
                    }
                    updatePacketDetails(newValue);
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
        column.setMinWidth(width);
        column.setPrefWidth(width);
        column.setMaxWidth(width);
        column.setSortable(false);
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
     * Применяет текущие фильтры и по возможности сохраняет открытый пакет в деталях.
     * Если пакет перестал проходить фильтрацию, выделение остаётся пустым.
     */
    private void applyFiltersPreservingView() {
        long packetIdToKeep = viewedPacketId;
        filteredItems.setPredicate(this::matchesFilters);
        restoreViewedSelection(packetIdToKeep);
        updateCountLabel();
    }

    /**
     * Проверяет, должна ли запись остаться в таблице после применения фильтров.
     * Поиск выполняется только по уже подготовленным UI-строкам.
     */
    private boolean matchesFilters(PacketLogEntry entry) {
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

        String selectedType = typeFilter != null ? typeFilter.getValue() : FILTER_ALL_TYPES;
        if (selectedType != null && !FILTER_ALL_TYPES.equals(selectedType)
                && !selectedType.equals(entry.getPacketType())) {
            return false;
        }

        String query = searchField != null ? searchField.getText() : null;
        if (query == null || query.isBlank()) {
            return true;
        }

        String lowerQuery = query.toLowerCase();
        return containsIgnoreCase(entry.getPacketType(), lowerQuery)
                || containsIgnoreCase(entry.getFromNode(), lowerQuery)
                || containsIgnoreCase(entry.getToNode(), lowerQuery)
                || containsIgnoreCase(entry.getPayloadText(), lowerQuery)
                || containsIgnoreCase(entry.getDirectionText(), lowerQuery);
    }

    /**
     * Перестраивает набор значений фильтра по типу пакета.
     * Контракт: по возможности сохраняет ранее выбранный тип.
     */
    private void refreshTypeFilters() {
        String previousSelection = typeFilter != null ? typeFilter.getValue() : FILTER_ALL_TYPES;
        packetTypeFilters.setAll(FILTER_ALL_TYPES);
        packetItems.stream()
                .map(PacketLogEntry::getPacketType)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .forEach(packetTypeFilters::add);

        if (typeFilter != null) {
            if (previousSelection != null && packetTypeFilters.contains(previousSelection)) {
                typeFilter.setValue(previousSelection);
            } else {
                typeFilter.setValue(FILTER_ALL_TYPES);
            }
        }
    }

    private void updateToolbarState() {
        boolean captureEnabled = packetMonitorService.isCaptureEnabled();
        btnStart.setDisable(captureEnabled);
        btnStop.setDisable(!captureEnabled);
        statusLabel.setText(captureEnabled ? "Сбор активен" : "Сбор остановлен");
        updateCountLabel();
    }

    private void updateCountLabel() {
        int total = packetItems.size();
        int visible = filteredItems.size();
        countLabel.setText("Показано " + visible + " из " + total);
    }

    /**
     * Обрабатывает live-приход нового пакета.
     * Новый пакет всегда добавляется в начало списка, но текущий просмотр пользователя
     * должен сохраниться, если он уже изучает другой пакет.
     */
    private void handlePacketLogged(PacketLogEntry entry) {
        long packetIdToKeep = viewedPacketId;
        packetItems.addFirst(entry);
        refreshTypeFilters();
        restoreViewedSelection(packetIdToKeep);
        updateCountLabel();

        if (viewedPacketId <= 0 && filteredItems.contains(entry)) {
            selectPacket(entry, true, true);
        }
    }

    /**
     * Сбрасывает окно после полной очистки журнала.
     * После вызова не должно оставаться ссылки на ранее открытый пакет.
     */
    private void handleCleared() {
        packetItems.clear();
        viewedPacketId = -1L;
        viewedPacketEntry = null;
        refreshTypeFilters();
        filteredItems.setPredicate(this::matchesFilters);
        packetTable.getSelectionModel().clearSelection();
        updateCountLabel();
        updatePacketDetails(null);
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
     * Возвращает selection на уже просматриваемый пакет после фильтрации или live-обновления.
     * Не должен пересобирать детали, если пользователь по факту остаётся на той же записи.
     */
    private void restoreViewedSelection(long packetIdToKeep) {
        if (packetTable == null) {
            return;
        }
        if (packetIdToKeep <= 0) {
            return;
        }

        PacketLogEntry currentSelection = packetTable.getSelectionModel().getSelectedItem();
        if (currentSelection != null && currentSelection.getId() == packetIdToKeep) {
            return;
        }

        PacketLogEntry entryToKeep = findVisiblePacketById(packetIdToKeep);
        if (entryToKeep != null) {
            Node focusOwner = stage != null && stage.getScene() != null ? stage.getScene().getFocusOwner() : null;
            selectPacket(entryToKeep, false, false);
            if (focusOwner != null && focusOwner != packetTable) {
                Platform.runLater(focusOwner::requestFocus);
            }
        }
    }

    private PacketLogEntry findVisiblePacketById(long packetId) {
        return filteredItems.stream()
                .filter(item -> item.getId() == packetId)
                .findFirst()
                .orElse(null);
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
        copyViewedPacket(PacketDebugFormatter.exportPacketAsText(viewedPacketEntry),
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
        saveViewedPacket(PacketDebugFormatter.exportPacketAsText(viewedPacketEntry), false);
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
     * Состояние применяется только если сохранённый прямоугольник попадает хотя бы
     * частично на один из доступных экранов.
     */
    private void restoreWindowState() {
        restoreWindowMaximized = AppPreferences.isPacketMonitorWindowMaximized();
        if (!AppPreferences.hasPacketMonitorWindowBounds()) {
            return;
        }

        double x = AppPreferences.getPacketMonitorWindowX();
        double y = AppPreferences.getPacketMonitorWindowY();
        double width = AppPreferences.getPacketMonitorWindowWidth();
        double height = AppPreferences.getPacketMonitorWindowHeight();
        ObservableList<Screen> screens = Screen.getScreensForRectangle(x, y, width, height);
        if (screens.isEmpty()) {
            return;
        }

        stage.setX(x);
        stage.setY(y);
        stage.setWidth(width);
        stage.setHeight(height);
        normalWindowX = x;
        normalWindowY = y;
        normalWindowWidth = width;
        normalWindowHeight = height;
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
        AppPreferences.savePacketMonitorWindowBounds(
                Double.isNaN(normalWindowX) ? stage.getX() : normalWindowX,
                Double.isNaN(normalWindowY) ? stage.getY() : normalWindowY,
                normalWindowWidth > 0 ? normalWindowWidth : stage.getWidth(),
                normalWindowHeight > 0 ? normalWindowHeight : stage.getHeight(),
                maximized
        );
        if (contentSplit != null && !contentSplit.getDividers().isEmpty()) {
            AppPreferences.setPacketMonitorDividerPos(contentSplit.getDividers().getFirst().getPosition());
        }
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query);
    }
}
