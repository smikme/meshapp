package com.meshtastic.client.forms;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.ConfigSnapshotService;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.ProtobufTreeBuilder;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@SystemForm(name = "Настройки", description = "Настройки клиента", tags = {"settings", "options"})
public class FormSetting extends Form {

    private static final Logger log = LoggerFactory.getLogger(FormSetting.class);
    /**
     * Базовая пауза между admin-пакетами при сохранении конфигурации.
     * Для Serial/TCP 200ms обычно достаточно, чтобы прошивка успела обработать
     * begin/set/commit без "слипания" сообщений.
     */
    private static final long CONFIG_SAVE_MESSAGE_DELAY_MS = 200;
    /**
     * BLE получает более длинную паузу между admin-пакетами: Heltec V3 и похожие
     * устройства иногда успевают уйти в reboot прямо после commit, и короткие интервалы
     * повышают шанс гонки между последними GATT write и закрытием сессии.
     */
    private static final long BLE_CONFIG_SAVE_MESSAGE_DELAY_MS = 350;
    /**
     * Сколько ждём после последнего admin-пакета перед handoff в reconnect flow.
     * Для BLE оставляем больше времени, чтобы commit успел дойти и устройство
     * начало собственный disconnect/reboot без одновременного ручного разрыва.
     */
    private static final long CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS = 1_000;
    private static final long BLE_CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS = 4_000;
    /**
     * Последний BLE set_config/set_module_config отправляется асинхронно на уровне GATT write.
     * Перед commit даём дополнительное время, чтобы write с response успел физически дойти
     * до устройства до того, как commit поставит reboot-triggering пакет в ту же очередь.
     */
    private static final long BLE_CONFIG_SAVE_PRE_COMMIT_SETTLE_DELAY_MS = 1_000;
    /** Таймаут ожидания routing ACK для шага сохранения конфигурации. */
    private static final long CONFIG_SAVE_ACK_TIMEOUT_MS = 8_000;

    private DeviceState state;
    private ProtocolHandler handler;

    // Cache tab
    private TableView<NodeData> cacheTable;
    private final ObservableList<NodeData> cacheData = FXCollections.observableArrayList();
    private Label cacheStatusLabel;
    private int cacheOffset = 0;
    private static final int PAGE_SIZE = 100;
    private int cacheTotalInDb = 0;

    private Tab cacheTab;
    private Tab appearanceTab;

    // Config tab
    private TreeTableView<ConfigTreeItem> configTree;
    private TextField configSearchField;
    private TreeItem<ConfigTreeItem> fullConfigRoot;
    private Label configStatusLabel;
    private Button saveConfigBtn;
    private Button refreshConfigBtn;
    private Tab configTab;
    private volatile CompletableFuture<DeviceState> observedConfigLoadFuture;

    // Оригинальные protobuf-объекты для пересборки при сохранении
    private List<ConfigProtos.Config> originalConfigs = new ArrayList<>();
    private List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs = new ArrayList<>();
    private List<ChannelProtos.Channel> originalChannels = new ArrayList<>();
    private List<ChannelProtos.Channel> workingChannels = new ArrayList<>();

    public FormSetting() {
        init();
    }

    private void init() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        Label title = new Label("Настройки");
        title.setFont(Font.font("Roboto", FontWeight.BOLD, 16));

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        cacheTab = new Tab("Кэш", createCachePanel());
        configTab = new Tab("Конфигурация", createConfigPanel());
        appearanceTab = new Tab("Настройки приложения", createAppSettingsPanel());

        tabPane.getTabs().addAll(configTab, cacheTab, appearanceTab);
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == cacheTab) {
                reloadCacheTable();
            } else if (newTab == configTab) {
                reloadConfigTree();
            }
        });

        VBox.setVgrow(tabPane, Priority.ALWAYS);
        content.getChildren().addAll(title, tabPane);
        getChildren().add(content);
    }

    @Override
    public void formOpen() {
        reloadConfigTree();
    }

    @Override
    public void formRefresh() {
        reloadConfigTree();
    }

    /**
     * Находит активное подключение и обновляет ссылки state/handler.
     */
    private void refreshConnection() {
        ConnectionManager mgr = ConnectionManager.getInstance();
        DeviceState newState = null;
        ProtocolHandler newHandler = null;
        for (ConnectionEntry entry : mgr.getEntries()) {
            if (entry.isConnected()) {
                newState = mgr.getDeviceState(entry.getId());
                newHandler = mgr.getProtocolHandler(entry.getId());
                if (newState != null) { break; }
            }
        }
        this.state = newState;
        this.handler = newHandler;
    }

    /**
     * Возвращает активный профиль подключения целиком.
     * Нужен save-flow, чтобы выбрать transport-aware pacing и корректно передать
     * соединение в reconnect path после reboot устройства.
     */
    private ConnectionEntry findActiveConnectionEntry() {
        ConnectionManager mgr = ConnectionManager.getInstance();
        for (ConnectionEntry entry : mgr.getEntries()) {
            if (entry.isConnected()) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Возвращает {@code true}, если для активного подключения ещё идёт initial config exchange.
     * Пока не пришёл {@code config_complete_id}, дерево может быть заполнено лишь частично,
     * и запуск save-flow в этот момент конфликтует с продолжающимся чтением конфигурации.
     */
    private boolean isConfigExchangeInProgress(ConnectionEntry entry) {
        if (entry == null) {
            return false;
        }
        CompletableFuture<DeviceState> future = ConnectionManager.getInstance().getConfigFuture(entry.getId());
        return future != null && !future.isDone();
    }

    /**
     * Подписывается на завершение текущего config exchange, чтобы автоматически
     * перевести форму из "идёт загрузка" в обычный режим без ручного refresh.
     */
    private void watchConfigExchangeCompletion(ConnectionEntry entry) {
        if (entry == null) {
            observedConfigLoadFuture = null;
            return;
        }
        CompletableFuture<DeviceState> future = ConnectionManager.getInstance().getConfigFuture(entry.getId());
        if (future == null || future.isDone() || future == observedConfigLoadFuture) {
            return;
        }
        observedConfigLoadFuture = future;
        future.whenComplete((ds, ex) -> Platform.runLater(() -> {
            if (observedConfigLoadFuture == future) {
                observedConfigLoadFuture = null;
            }
            reloadConfigTree();
        }));
    }

    /**
     * Ждёт routing ACK для шага сохранения конфигурации.
     * <p>
     * Теперь это делается не только для BLE: begin_edit_settings и промежуточные
     * set_config/set_module_config должны завершиться подтверждением до отправки
     * следующего шага, иначе commit может догнать ещё не обработанную транзакцию.
     */
    private void waitForRequiredConfigSaveAck(CompletableFuture<MeshProtos.Routing.Error> ackFuture,
                                              String stepName) {
        if (ackFuture == null) {
            return;
        }

        try {
            MeshProtos.Routing.Error error = ackFuture
                    .orTimeout(CONFIG_SAVE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .get();
            if (error != MeshProtos.Routing.Error.NONE) {
                throw new IllegalStateException("Config save step '" + stepName + "' failed with " + error);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Config save step '" + stepName + "' ACK failed", e);
        }
    }

    /**
     * Подключает диагностику к ACK, который не должен блокировать commit/reconnect flow.
     * <p>
     * Последний mutating step и BLE commit не должны держать транзакцию открытой в UI:
     * некоторые устройства рвут линк почти сразу после применения шага, и если ждать
     * этот ACK синхронно, commit просто не будет отправлен.
     */
    private void observeDeferredConfigSaveAck(CompletableFuture<MeshProtos.Routing.Error> ackFuture,
                                              String stepName) {
        if (ackFuture == null) {
            return;
        }

        ackFuture.whenComplete((error, ex) -> {
            if (ex != null) {
                log.info("Config save: deferred ACK for '{}' completed exceptionally: {}",
                        stepName, ex.getMessage());
            } else if (error != null && error != MeshProtos.Routing.Error.NONE) {
                log.warn("Config save: deferred ACK for '{}' returned {}", stepName, error);
            } else {
                log.debug("Config save: deferred ACK received for '{}'", stepName);
            }
        });
    }

    /**
     * Возвращает паузу между двумя шагами save-flow.
     * <p>
     * Для BLE обычной межшаговой задержки недостаточно перед {@code commitEditSettings},
     * потому что {@code writeToRadio()} возвращается раньше фактического завершения
     * CoreBluetooth write-with-response. Поэтому перед commit добавляется отдельное
     * settle-окно после последнего mutating шага.
     */
    private long getConfigSaveInterTaskDelayMs(ConnectionType transport, int taskIndex, int totalTaskCount) {
        long delayMs = transport == ConnectionType.BLE
                ? BLE_CONFIG_SAVE_MESSAGE_DELAY_MS
                : CONFIG_SAVE_MESSAGE_DELAY_MS;
        boolean nextTaskIsCommit = taskIndex + 1 == totalTaskCount - 1;
        if (transport == ConnectionType.BLE && nextTaskIsCommit) {
            delayMs += BLE_CONFIG_SAVE_PRE_COMMIT_SETTLE_DELAY_MS;
        }
        return delayMs;
    }

    /**
     * Некоторые BLE-устройства рвут линк сразу после {@code set_module_config(MQTT)},
     * не дожидаясь {@code commit_edit_settings}, даже если {@code begin_edit_settings}
     * был принят. Для такого узкого кейса транзакционная обёртка только мешает:
     * save уже стартовал на стороне устройства, а commit гарантированно не успевает.
     * <p>
     * Поэтому для одиночного BLE-save секции MQTT используется implicit save path:
     * отправляется только {@code set_module_config}, после чего приложение ждёт
     * reboot/disconnect и передаёт соединение в reconnect flow.
     */
    static boolean shouldUseImplicitBleModuleSave(ConnectionType transport,
                                                  boolean ownerModified,
                                                  boolean positionModified,
                                                  List<ConfigProtos.Config> configs,
                                                  List<ModuleConfigProtos.ModuleConfig> moduleConfigs) {
        return transport == ConnectionType.BLE
                && !ownerModified
                && !positionModified
                && configs.isEmpty()
                && moduleConfigs.size() == 1
                && moduleConfigs.get(0).getPayloadVariantCase()
                == ModuleConfigProtos.ModuleConfig.PayloadVariantCase.MQTT;
    }

    // ==================== Настройки приложения ====================

    private VBox createAppSettingsPanel() {
        VBox panel = new VBox(16);
        panel.setPadding(new Insets(15));

        // --- Группа «Оформление» ---
        Label appearanceHeader = new Label("Оформление");
        appearanceHeader.setFont(Font.font("Roboto", FontWeight.BOLD, 13));

        CheckBox disableEffectsCb = new CheckBox("Выключить эффекты оформления");
        disableEffectsCb.setSelected(AppPreferences.isDisableEffects());
        disableEffectsCb.selectedProperty().addListener((obs, old, val) ->
                AppPreferences.setDisableEffects(val));

        CheckBox minimizeToTrayCb = new CheckBox("Минимизация в трей");
        minimizeToTrayCb.setSelected(AppPreferences.isMinimizeToTray());
        minimizeToTrayCb.selectedProperty().addListener((obs, old, val) ->
                AppPreferences.setMinimizeToTray(val));

        Label restartNote = new Label("Изменения вступят в силу после перезапуска приложения");
        restartNote.setStyle("-fx-opacity: 0.6; -fx-font-size: 11;");

        VBox appearanceGroup = new VBox(8, appearanceHeader, disableEffectsCb, minimizeToTrayCb, restartNote);

        // --- Группа «Интеграции» ---
        Label integrationsHeader = new Label("Интеграции");
        integrationsHeader.setFont(Font.font("Roboto", FontWeight.BOLD, 13));

        CheckBox checkUpdatesCb = new CheckBox("Проверять обновления при старте приложения");
        checkUpdatesCb.setSelected(AppPreferences.isCheckUpdates());
        checkUpdatesCb.selectedProperty().addListener((obs, old, val) ->
                AppPreferences.setCheckUpdates(val));

        VBox integrationsGroup = new VBox(8, integrationsHeader, checkUpdatesCb);

        panel.getChildren().addAll(appearanceGroup, new Separator(), integrationsGroup);
        return panel;
    }

    // ==================== Кэш ====================

    @SuppressWarnings("unchecked")
    private VBox createCachePanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(5));

        HBox btnRow = new HBox(8);
        Button importButton = new Button("Загрузить из OneMesh");
        importButton.setOnAction(e -> onImportFromOneMesh(importButton));

        Button clearButton = new Button("Очистить кэш");
        clearButton.setStyle("-fx-text-fill: #E53935;");
        clearButton.setOnAction(e -> onClearCache());

        btnRow.getChildren().addAll(importButton, clearButton);

        cacheTable = new TableView<>(cacheData);
        cacheTable.setFixedCellSize(28);

        TableColumn<NodeData, String> colLongName = new TableColumn<>("Имя");
        colLongName.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().getLongName() != null ? cd.getValue().getLongName() : ""));
        colLongName.setPrefWidth(150);

        TableColumn<NodeData, String> colShortName = new TableColumn<>("Короткое");
        colShortName.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().getShortName() != null ? cd.getValue().getShortName() : ""));
        colShortName.setPrefWidth(80);

        TableColumn<NodeData, String> colNodeId = new TableColumn<>("ID ноды");
        colNodeId.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().getNodeId() != null ? cd.getValue().getNodeId() : ""));
        colNodeId.setPrefWidth(100);

        TableColumn<NodeData, String> colHwModel = new TableColumn<>("Модель");
        colHwModel.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().getHwModel() != null ? cd.getValue().getHwModel() : ""));
        colHwModel.setPrefWidth(120);

        TableColumn<NodeData, String> colLat = new TableColumn<>("Широта");
        colLat.setCellValueFactory(cd -> {
            double lat = cd.getValue().getLatitude();
            return new SimpleStringProperty(lat != 0 ? String.format("%.3f", lat) : "");
        });
        colLat.setPrefWidth(70);

        TableColumn<NodeData, String> colLon = new TableColumn<>("Долгота");
        colLon.setCellValueFactory(cd -> {
            double lon = cd.getValue().getLongitude();
            return new SimpleStringProperty(lon != 0 ? String.format("%.3f", lon) : "");
        });
        colLon.setPrefWidth(70);

        cacheTable.getColumns().addAll(colLongName, colShortName, colNodeId, colHwModel, colLat, colLon);
        cacheTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Lazy-load: слушаем вертикальный ScrollBar таблицы
        cacheTable.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                cacheTable.lookupAll(".scroll-bar").stream()
                        .filter(n -> n instanceof javafx.scene.control.ScrollBar)
                        .map(n -> (javafx.scene.control.ScrollBar) n)
                        .filter(sb -> sb.getOrientation() == javafx.geometry.Orientation.VERTICAL)
                        .findFirst()
                        .ifPresent(sb -> sb.valueProperty().addListener((o, oldVal, newVal) -> {
                            if (newVal.doubleValue() > 0.9) {
                                loadNextCachePage();
                            }
                        }));
            }
        });

        VBox.setVgrow(cacheTable, Priority.ALWAYS);

        cacheStatusLabel = new Label("");
        cacheStatusLabel.setStyle("-fx-opacity: 0.6;");

        panel.getChildren().addAll(btnRow, cacheTable, cacheStatusLabel);
        return panel;
    }

    private void onImportFromOneMesh(Button button) {
        button.setDisable(true);
        cacheStatusLabel.setText("Загрузка из OneMesh...");

        new Thread(() -> {
            try {
                int count = NodeCacheService.getInstance().importFromOneMesh();
                Platform.runLater(() -> {
                    button.setDisable(false);
                    reloadCacheTable();
                    ModalPane.showInfo("Импорт из OneMesh",
                            "Импорт завершен. Загружено нод: " + count);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    button.setDisable(false);
                    reloadCacheTable();
                    ModalPane.showError("Ошибка импорта",
                            "Ошибка при импорте: " + ex.getMessage());
                });
            }
        }, "onemesh-import").start();
    }

    private void onClearCache() {
        ModalPane.showConfirm(
                "Очистка кэша",
                "Вы уверены, что хотите удалить все данные из кэша?",
                confirmed -> {
                    if (confirmed) {
                        NodeCacheService.getInstance().clearAll();
                        reloadCacheTable();
                    }
                });
    }

    private void reloadCacheTable() {
        cacheOffset = 0;
        cacheData.clear();
        cacheTotalInDb = NodeCacheService.getInstance().countNodesInDb();
        loadNextCachePage();
        updateCacheStatus();
    }

    private void loadNextCachePage() {
        if (cacheOffset >= cacheTotalInDb) { return; }
        List<NodeData> page = NodeCacheService.getInstance().loadPage(cacheOffset, PAGE_SIZE);
        cacheData.addAll(page);
        cacheOffset += page.size();
        updateCacheStatus();
    }

    private void updateCacheStatus() {
        int loaded = cacheData.size();
        if (cacheTotalInDb == 0) {
            cacheStatusLabel.setText("Кэш пуст");
        } else if (loaded >= cacheTotalInDb) {
            cacheStatusLabel.setText("Показано %d из %d".formatted(loaded, cacheTotalInDb));
        } else {
            cacheStatusLabel.setText("Показано %d из %d (прокрутите для загрузки)".formatted(loaded, cacheTotalInDb));
        }
    }

    // ==================== Config Tab ====================

    @SuppressWarnings("unchecked")
    private VBox createConfigPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(5));

        // Поиск
        configSearchField = new TextField();
        configSearchField.setPromptText("Поиск параметров...");
        configSearchField.textProperty().addListener((obs, oldVal, newVal) -> filterConfigTree(newVal));

        // Toolbar действий
        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("config-toolbar");

        refreshConfigBtn = createConfigToolbarButton(
                "Обновить конфигурацию",
                "Запросить актуальные параметры у подключенного радио",
                "/icons/refresh.svg",
                this::reloadConfigTree);

        saveConfigBtn = createConfigToolbarButton(
                "Сохранить на радио",
                "Отправить изменённые параметры на устройство и применить их",
                "/icons/save-radio.svg",
                this::onSaveConfig);
        saveConfigBtn.setDisable(true);

        Button exportConfigBtn = createConfigToolbarButton(
                "Сохранить конфигурацию",
                "Сохранить текущую конфигурацию в файл .mcf",
                "/icons/save-config.svg",
                () -> onExportSnapshot(ConfigSnapshotService.SnapshotKind.CONFIG));

        Button importConfigBtn = createConfigToolbarButton(
                "Загрузить конфигурацию",
                "Загрузить конфигурацию из файла .mcf в редактор",
                "/icons/load-config.svg",
                () -> onImportSnapshot(ConfigSnapshotService.SnapshotKind.CONFIG));

        Button exportTemplateBtn = createConfigToolbarButton(
                "Сохранить шаблон",
                "Сохранить шаблон без персональных и секретных данных в файл .mtp",
                "/icons/save-template.svg",
                () -> onExportSnapshot(ConfigSnapshotService.SnapshotKind.TEMPLATE));

        Button importTemplateBtn = createConfigToolbarButton(
                "Загрузить шаблон",
                "Загрузить шаблон из файла .mtp в редактор",
                "/icons/load-template.svg",
                () -> onImportSnapshot(ConfigSnapshotService.SnapshotKind.TEMPLATE));

        actionToolbar.getItems().addAll(
                refreshConfigBtn,
                saveConfigBtn,
                new Separator(Orientation.VERTICAL),
                exportConfigBtn,
                importConfigBtn,
                new Separator(Orientation.VERTICAL),
                exportTemplateBtn,
                importTemplateBtn
        );

        configStatusLabel = new Label("");
        configStatusLabel.getStyleClass().add("config-status-label");
        configStatusLabel.setWrapText(true);

        // TreeTableView
        configTree = new TreeTableView<>();
        configTree.setShowRoot(false);
        configTree.setEditable(true);

        // Колонка «Параметр»
        TreeTableColumn<ConfigTreeItem, String> nameCol = new TreeTableColumn<>("Параметр");
        nameCol.setCellValueFactory(param -> {
            ConfigTreeItem item = param.getValue().getValue();
            return new SimpleStringProperty(item != null ? item.getName() : "");
        });
        nameCol.setPrefWidth(280);
        nameCol.setEditable(false);
        nameCol.setCellFactory(col -> new TreeTableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    TreeItem<ConfigTreeItem> treeItem = getTreeTableRow().getTreeItem();
                    if (treeItem != null && treeItem.getValue() != null && treeItem.getValue().isCategory()) {
                        setStyle("-fx-font-weight: bold;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        // Колонка «Значение» с кастомными редакторами
        TreeTableColumn<ConfigTreeItem, ConfigTreeItem> valueCol = new TreeTableColumn<>("Значение");
        valueCol.setCellValueFactory(param -> new javafx.beans.property.SimpleObjectProperty<>(param.getValue().getValue()));
        valueCol.setPrefWidth(300);
        valueCol.setCellFactory(col -> new ConfigValueCell());

        configTree.getColumns().addAll(nameCol, valueCol);
        configTree.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        VBox.setVgrow(configTree, Priority.ALWAYS);

        panel.getChildren().addAll(configSearchField, actionToolbar, configStatusLabel, configTree);
        return panel;
    }

    private Button createConfigToolbarButton(String title, String description, String iconPath, Runnable action) {
        SVGPath icon = SvgIconLoader.load(iconPath, 18);

        Button button = new Button();
        button.getStyleClass().add("config-toolbar-button");
        button.setMinSize(34, 34);
        button.setPrefSize(34, 34);
        button.setMaxSize(34, 34);
        if (icon != null) {
            button.setGraphic(icon);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            button.setText(title);
        }
        button.setTooltip(new Tooltip(title + "\n" + description));
        button.setOnAction(e -> action.run());
        return button;
    }

    private void onExportSnapshot(ConfigSnapshotService.SnapshotKind kind) {
        if (!ensureEditorAvailableForSnapshotOperation()) {
            return;
        }

        TreeItem<ConfigTreeItem> root = currentEditorRoot();
        if (root == null) {
            return;
        }

        try {
            ConfigSnapshotService.ConfigSnapshot snapshot = ConfigSnapshotService.createSnapshot(
                    kind,
                    extractOwnerInfo(root),
                    extractFixedPosition(root),
                    collectCurrentConfigMessages(root),
                    collectCurrentModuleConfigMessages(root),
                    getWorkingChannelsSnapshot()
            );

            FileChooser chooser = createSnapshotFileChooser(kind, true);
            File target = chooser.showSaveDialog(getCurrentWindow());
            if (target == null) {
                return;
            }

            File outputFile = ensureSnapshotExtension(target, kind);
            ConfigSnapshotService.writeSnapshot(outputFile.toPath(), snapshot);
            Toast.show(Toast.Type.SUCCESS, switch (kind) {
                case CONFIG -> "Конфигурация сохранена: " + outputFile.getName();
                case TEMPLATE -> "Шаблон сохранён: " + outputFile.getName();
            });
        } catch (Exception e) {
            log.error("Snapshot export failed", e);
            ModalPane.showError(
                    kind == ConfigSnapshotService.SnapshotKind.CONFIG
                            ? "Ошибка сохранения конфигурации"
                            : "Ошибка сохранения шаблона",
                    e.getMessage() != null ? e.getMessage() : "Не удалось сохранить файл"
            );
        }
    }

    private void onImportSnapshot(ConfigSnapshotService.SnapshotKind kind) {
        if (!ensureEditorAvailableForSnapshotOperation()) {
            return;
        }

        FileChooser chooser = createSnapshotFileChooser(kind, false);
        File source = chooser.showOpenDialog(getCurrentWindow());
        if (source == null) {
            return;
        }

        Runnable importAction = () -> importSnapshot(source, kind);
        if (hasPendingEditorChanges()) {
            ModalPane.showConfirm(
                    kind == ConfigSnapshotService.SnapshotKind.CONFIG
                            ? "Загрузить конфигурацию?"
                            : "Загрузить шаблон?",
                    "Текущие несохранённые изменения будут потеряны. Продолжить?",
                    confirmed -> {
                        if (confirmed) {
                            importAction.run();
                        }
                    });
        } else {
            importAction.run();
        }
    }

    private void importSnapshot(File source, ConfigSnapshotService.SnapshotKind expectedKind) {
        try {
            reloadConfigTree();
            TreeItem<ConfigTreeItem> root = currentEditorRoot();
            if (root == null) {
                throw new IllegalStateException("Конфигурация не загружена в редактор");
            }

            ConfigSnapshotService.ConfigSnapshot snapshot = ConfigSnapshotService.readSnapshot(source.toPath());
            if (snapshot.kind() != expectedKind) {
                throw new IllegalArgumentException("Выбран файл другого типа: ожидался ." + expectedKind.extension());
            }

            applySnapshotToEditor(snapshot);
            configTree.refresh();
            saveConfigBtn.setDisable(false);
            String fileKind = expectedKind == ConfigSnapshotService.SnapshotKind.CONFIG ? "Конфигурация" : "Шаблон";
            configStatusLabel.setText(fileKind + " загружен из файла: " + source.getName());
            Toast.show(Toast.Type.SUCCESS, fileKind + " загружен: " + source.getName());
        } catch (Exception e) {
            log.error("Snapshot import failed", e);
            ModalPane.showError(
                    expectedKind == ConfigSnapshotService.SnapshotKind.CONFIG
                            ? "Ошибка загрузки конфигурации"
                            : "Ошибка загрузки шаблона",
                    e.getMessage() != null ? e.getMessage() : "Не удалось прочитать файл"
            );
        }
    }

    private boolean ensureEditorAvailableForSnapshotOperation() {
        if (currentEditorRoot() == null) {
            reloadConfigTree();
        }

        ConnectionEntry activeEntry = findActiveConnectionEntry();
        if (isConfigExchangeInProgress(activeEntry)) {
            watchConfigExchangeCompletion(activeEntry);
            Toast.show(Toast.Type.WARNING, "Дождитесь завершения чтения конфигурации с устройства");
            return false;
        }

        if (currentEditorRoot() == null) {
            Toast.show(Toast.Type.WARNING, "Сначала загрузите конфигурацию с подключённого радио");
            return false;
        }
        return true;
    }

    private FileChooser createSnapshotFileChooser(ConfigSnapshotService.SnapshotKind kind, boolean saveMode) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(switch (kind) {
            case CONFIG -> saveMode ? "Сохранить конфигурацию" : "Загрузить конфигурацию";
            case TEMPLATE -> saveMode ? "Сохранить шаблон" : "Загрузить шаблон";
        });
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                kind == ConfigSnapshotService.SnapshotKind.CONFIG
                        ? "MeshApp Config (*." + kind.extension() + ")"
                        : "MeshApp Template (*." + kind.extension() + ")",
                "*." + kind.extension()
        ));
        if (saveMode) {
            chooser.setInitialFileName(buildSuggestedSnapshotName(kind));
        }
        return chooser;
    }

    private String buildSuggestedSnapshotName(ConfigSnapshotService.SnapshotKind kind) {
        String baseName = "mesh-config";
        if (state != null) {
            NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
            if (myNode != null) {
                if (myNode.getLongName() != null && !myNode.getLongName().isBlank()) {
                    baseName = myNode.getLongName().trim();
                } else if (myNode.getNodeId() != null && !myNode.getNodeId().isBlank()) {
                    baseName = myNode.getNodeId().trim();
                }
            }
        }
        baseName = baseName.replaceAll("[\\\\/:*?\"<>|]+", "_").replaceAll("\\s+", "_");
        if (baseName.isBlank()) {
            baseName = "mesh-config";
        }
        if (kind == ConfigSnapshotService.SnapshotKind.TEMPLATE) {
            baseName += "-template";
        }
        return baseName;
    }

    private File ensureSnapshotExtension(File file, ConfigSnapshotService.SnapshotKind kind) {
        String expectedSuffix = "." + kind.extension();
        String fileName = file.getName();
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        String duplicateSuffix = expectedSuffix + expectedSuffix;

        if (lowerName.endsWith(duplicateSuffix)) {
            fileName = fileName.substring(0, fileName.length() - expectedSuffix.length());
            lowerName = fileName.toLowerCase(Locale.ROOT);
        }

        if (lowerName.endsWith(expectedSuffix)) {
            File parent = file.getParentFile();
            return parent != null ? new File(parent, fileName) : new File(fileName);
        }
        File parent = file.getParentFile();
        return parent != null
                ? new File(parent, fileName + expectedSuffix)
                : new File(fileName + expectedSuffix);
    }

    private Window getCurrentWindow() {
        return getScene() != null ? getScene().getWindow() : null;
    }

    private TreeItem<ConfigTreeItem> currentEditorRoot() {
        return fullConfigRoot != null ? fullConfigRoot : configTree != null ? configTree.getRoot() : null;
    }

    private ConfigSnapshotService.OwnerInfo extractOwnerInfo(TreeItem<ConfigTreeItem> root) {
        TreeItem<ConfigTreeItem> ownerSection = findTopLevelSection(root, "owner_info");
        if (ownerSection == null) {
            return null;
        }
        return new ConfigSnapshotService.OwnerInfo(
                stringValue(ownerSection, "long_name"),
                stringValue(ownerSection, "short_name")
        );
    }

    private ConfigSnapshotService.FixedPosition extractFixedPosition(TreeItem<ConfigTreeItem> root) {
        TreeItem<ConfigTreeItem> positionSection = findTopLevelSection(root, "fixed_position");
        if (positionSection == null) {
            return null;
        }
        return new ConfigSnapshotService.FixedPosition(
                doubleValue(positionSection, "latitude"),
                doubleValue(positionSection, "longitude"),
                intValue(positionSection, "altitude")
        );
    }

    private List<ConfigProtos.Config> collectCurrentConfigMessages(TreeItem<ConfigTreeItem> root) {
        TreeItem<ConfigTreeItem> configRoot = findTopLevelSection(root, "config");
        List<ConfigProtos.Config> result = new ArrayList<>();
        if (configRoot == null) {
            return result;
        }

        for (TreeItem<ConfigTreeItem> section : configRoot.getChildren()) {
            ConfigTreeItem sectionData = section.getValue();
            if (sectionData == null) {
                continue;
            }
            ConfigProtos.Config original = findOriginalConfig(sectionData.getConfigVariantNumber());
            if (original == null) {
                continue;
            }
            ConfigProtos.Config rebuilt = ProtobufTreeBuilder.rebuildConfig(section, original);
            if (rebuilt != null) {
                result.add(rebuilt);
            }
        }
        return result;
    }

    private List<ModuleConfigProtos.ModuleConfig> collectCurrentModuleConfigMessages(TreeItem<ConfigTreeItem> root) {
        TreeItem<ConfigTreeItem> moduleRoot = findTopLevelSection(root, "module_config");
        List<ModuleConfigProtos.ModuleConfig> result = new ArrayList<>();
        if (moduleRoot == null) {
            return result;
        }

        for (TreeItem<ConfigTreeItem> section : moduleRoot.getChildren()) {
            ConfigTreeItem sectionData = section.getValue();
            if (sectionData == null) {
                continue;
            }
            ModuleConfigProtos.ModuleConfig original = findOriginalModuleConfig(sectionData.getConfigVariantNumber());
            if (original == null) {
                continue;
            }
            ModuleConfigProtos.ModuleConfig rebuilt = ProtobufTreeBuilder.rebuildModuleConfig(section, original);
            if (rebuilt != null) {
                result.add(rebuilt);
            }
        }
        return result;
    }

    private void applySnapshotToEditor(ConfigSnapshotService.ConfigSnapshot snapshot) {
        TreeItem<ConfigTreeItem> root = currentEditorRoot();
        if (root == null) {
            return;
        }

        if (configSearchField != null) {
            configSearchField.clear();
        }

        applyOwnerInfo(snapshot.ownerInfo(), root);
        applyFixedPosition(snapshot.fixedPosition(), root);
        applyConfigSnapshot(snapshot.configs(), root);
        applyModuleConfigSnapshot(snapshot.moduleConfigs(), root);
        applyChannelSnapshot(snapshot.channels());
    }

    private void applyOwnerInfo(ConfigSnapshotService.OwnerInfo ownerInfo, TreeItem<ConfigTreeItem> root) {
        if (ownerInfo == null) {
            return;
        }
        TreeItem<ConfigTreeItem> ownerSection = findTopLevelSection(root, "owner_info");
        if (ownerSection == null) {
            return;
        }
        setTreeFieldValue(ownerSection, "long_name", ownerInfo.longName());
        setTreeFieldValue(ownerSection, "short_name", ownerInfo.shortName());
    }

    private void applyFixedPosition(ConfigSnapshotService.FixedPosition fixedPosition, TreeItem<ConfigTreeItem> root) {
        if (fixedPosition == null) {
            return;
        }
        TreeItem<ConfigTreeItem> positionSection = findTopLevelSection(root, "fixed_position");
        if (positionSection == null) {
            return;
        }
        setTreeFieldValue(positionSection, "latitude", fixedPosition.latitude());
        setTreeFieldValue(positionSection, "longitude", fixedPosition.longitude());
        setTreeFieldValue(positionSection, "altitude", fixedPosition.altitude());
    }

    private void applyConfigSnapshot(List<com.google.gson.JsonObject> configs, TreeItem<ConfigTreeItem> root) {
        TreeItem<ConfigTreeItem> configRoot = findTopLevelSection(root, "config");
        if (configRoot == null) {
            return;
        }

        for (com.google.gson.JsonObject configJson : configs) {
            String variantField = ConfigSnapshotService.detectActiveVariantField(configJson);
            if (variantField == null) {
                continue;
            }
            int variantNumber = resolveVariantNumber(ConfigProtos.Config.getDescriptor().findFieldByName(variantField));
            if (variantNumber < 0) {
                continue;
            }
            ConfigProtos.Config baseConfig = findOriginalConfig(variantNumber);
            if (baseConfig == null) {
                baseConfig = ConfigProtos.Config.getDefaultInstance();
            }
            ConfigProtos.Config mergedConfig = ConfigSnapshotService.mergeJsonIntoMessage(baseConfig, configJson);
            TreeItem<ConfigTreeItem> section = findSectionByVariant(configRoot, variantNumber);
            if (section != null) {
                var payload = getActiveConfigPayload(mergedConfig);
                if (payload != null) {
                    ProtobufTreeBuilder.applyMessageToTree(section, payload);
                }
            }
        }
    }

    private void applyModuleConfigSnapshot(List<com.google.gson.JsonObject> moduleConfigs, TreeItem<ConfigTreeItem> root) {
        TreeItem<ConfigTreeItem> moduleRoot = findTopLevelSection(root, "module_config");
        if (moduleRoot == null) {
            return;
        }

        for (com.google.gson.JsonObject moduleJson : moduleConfigs) {
            String variantField = ConfigSnapshotService.detectActiveVariantField(moduleJson);
            if (variantField == null) {
                continue;
            }
            int variantNumber = resolveVariantNumber(ModuleConfigProtos.ModuleConfig.getDescriptor().findFieldByName(variantField));
            if (variantNumber < 0) {
                continue;
            }
            ModuleConfigProtos.ModuleConfig baseConfig = findOriginalModuleConfig(variantNumber);
            if (baseConfig == null) {
                baseConfig = ModuleConfigProtos.ModuleConfig.getDefaultInstance();
            }
            ModuleConfigProtos.ModuleConfig mergedConfig =
                    ConfigSnapshotService.mergeJsonIntoMessage(baseConfig, moduleJson);
            TreeItem<ConfigTreeItem> section = findSectionByVariant(moduleRoot, variantNumber);
            if (section != null) {
                var payload = getActiveModulePayload(mergedConfig);
                if (payload != null) {
                    ProtobufTreeBuilder.applyMessageToTree(section, payload);
                }
            }
        }
    }

    private void applyChannelSnapshot(List<com.google.gson.JsonObject> channelPatches) {
        if (channelPatches == null || channelPatches.isEmpty()) {
            return;
        }

        List<ChannelProtos.Channel> importedChannels = new ArrayList<>();
        for (com.google.gson.JsonObject channelJson : channelPatches) {
            if (!channelJson.has("index")) {
                continue;
            }
            int channelIndex = channelJson.get("index").getAsInt();
            ChannelProtos.Channel baseChannel = findChannelByIndex(originalChannels, channelIndex);
            if (baseChannel == null) {
                baseChannel = disabledChannel(channelIndex);
            }
            importedChannels.add(ConfigSnapshotService.mergeJsonIntoMessage(baseChannel, channelJson));
        }

        importedChannels.sort(Comparator.comparingInt(ChannelProtos.Channel::getIndex));
        workingChannels = importedChannels;
    }

    private boolean hasPendingEditorChanges() {
        TreeItem<ConfigTreeItem> root = currentEditorRoot();
        return (root != null && hasMoifiedFields(root)) || !collectModifiedChannels().isEmpty();
    }

    private List<ChannelProtos.Channel> collectModifiedChannels() {
        List<ChannelProtos.Channel> targetChannels = getWorkingChannelsSnapshot();
        TreeSet<Integer> allIndexes = new TreeSet<>();
        for (ChannelProtos.Channel channel : originalChannels) {
            allIndexes.add(channel.getIndex());
        }
        for (ChannelProtos.Channel channel : targetChannels) {
            allIndexes.add(channel.getIndex());
        }

        List<ChannelProtos.Channel> modified = new ArrayList<>();
        for (Integer index : allIndexes) {
            ChannelProtos.Channel original = findChannelByIndex(originalChannels, index);
            ChannelProtos.Channel target = findChannelByIndex(targetChannels, index);
            ChannelProtos.Channel originalNormalized = original != null ? original : disabledChannel(index);
            ChannelProtos.Channel targetNormalized = target != null ? target : disabledChannel(index);
            if (!originalNormalized.equals(targetNormalized)) {
                modified.add(targetNormalized);
            }
        }
        modified.sort(Comparator.comparingInt(ChannelProtos.Channel::getIndex));
        return modified;
    }

    private List<ChannelProtos.Channel> getWorkingChannelsSnapshot() {
        List<ChannelProtos.Channel> source = !workingChannels.isEmpty() ? workingChannels : originalChannels;
        return new ArrayList<>(source);
    }

    private TreeItem<ConfigTreeItem> findTopLevelSection(TreeItem<ConfigTreeItem> root, String configType) {
        for (TreeItem<ConfigTreeItem> child : root.getChildren()) {
            ConfigTreeItem data = child.getValue();
            if (data != null && configType.equals(data.getConfigType())) {
                return child;
            }
        }
        return null;
    }

    private TreeItem<ConfigTreeItem> findSectionByVariant(TreeItem<ConfigTreeItem> sectionRoot, int variantNumber) {
        for (TreeItem<ConfigTreeItem> child : sectionRoot.getChildren()) {
            ConfigTreeItem data = child.getValue();
            if (data != null && data.getConfigVariantNumber() == variantNumber) {
                return child;
            }
        }
        return null;
    }

    private void setTreeFieldValue(TreeItem<ConfigTreeItem> section, String fieldName, Object value) {
        for (TreeItem<ConfigTreeItem> child : section.getChildren()) {
            ConfigTreeItem data = child.getValue();
            if (data != null && fieldName.equals(data.getFieldName())) {
                data.setValue(value);
                return;
            }
        }
    }

    private String stringValue(TreeItem<ConfigTreeItem> section, String fieldName) {
        Object value = findTreeFieldValue(section, fieldName);
        return value != null ? value.toString() : "";
    }

    private double doubleValue(TreeItem<ConfigTreeItem> section, String fieldName) {
        Object value = findTreeFieldValue(section, fieldName);
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private int intValue(TreeItem<ConfigTreeItem> section, String fieldName) {
        Object value = findTreeFieldValue(section, fieldName);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Object findTreeFieldValue(TreeItem<ConfigTreeItem> section, String fieldName) {
        for (TreeItem<ConfigTreeItem> child : section.getChildren()) {
            ConfigTreeItem data = child.getValue();
            if (data != null && fieldName.equals(data.getFieldName())) {
                return data.getValue();
            }
        }
        return null;
    }

    private int resolveVariantNumber(com.google.protobuf.Descriptors.FieldDescriptor fieldDescriptor) {
        return fieldDescriptor != null ? fieldDescriptor.getNumber() : -1;
    }

    private ConfigProtos.Config findOriginalConfig(int variantNumber) {
        for (ConfigProtos.Config config : originalConfigs) {
            if (getActiveOneofFieldNumber(config) == variantNumber) {
                return config;
            }
        }
        return null;
    }

    private ModuleConfigProtos.ModuleConfig findOriginalModuleConfig(int variantNumber) {
        for (ModuleConfigProtos.ModuleConfig config : originalModuleConfigs) {
            if (getActiveModuleOneofFieldNumber(config) == variantNumber) {
                return config;
            }
        }
        return null;
    }

    private ChannelProtos.Channel findChannelByIndex(List<ChannelProtos.Channel> channels, int index) {
        for (ChannelProtos.Channel channel : channels) {
            if (channel.getIndex() == index) {
                return channel;
            }
        }
        return null;
    }

    private ChannelProtos.Channel disabledChannel(int index) {
        return ChannelProtos.Channel.newBuilder()
                .setIndex(index)
                .setRole(ChannelProtos.Channel.Role.DISABLED)
                .build();
    }

    private com.google.protobuf.Message getActiveConfigPayload(ConfigProtos.Config config) {
        var oneof = config.getDescriptorForType().getOneofs().stream()
                .filter(o -> "payload_variant".equals(o.getName()))
                .findFirst()
                .orElse(null);
        if (oneof == null) {
            return null;
        }
        var field = config.getOneofFieldDescriptor(oneof);
        return field != null ? (com.google.protobuf.Message) config.getField(field) : null;
    }

    private com.google.protobuf.Message getActiveModulePayload(ModuleConfigProtos.ModuleConfig config) {
        var oneof = config.getDescriptorForType().getOneofs().stream()
                .filter(o -> "payload_variant".equals(o.getName()))
                .findFirst()
                .orElse(null);
        if (oneof == null) {
            return null;
        }
        var field = config.getOneofFieldDescriptor(oneof);
        return field != null ? (com.google.protobuf.Message) config.getField(field) : null;
    }

    /**
     * Загружает конфигурацию из DeviceState и строит дерево.
     */
    private void reloadConfigTree() {
        // Обновить ссылку на state если не установлена
        if (state == null || handler == null) {
            refreshConnection();
        }

        boolean connected = state != null && handler != null;

        if (!connected) {
            configStatusLabel.setText("Нет подключения к радио");
            saveConfigBtn.setDisable(true);
            originalChannels = new ArrayList<>();
            workingChannels = new ArrayList<>();
            configTree.setRoot(null);
            return;
        }

        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        ConnectionEntry activeEntry = findActiveConnectionEntry();
        boolean configExchangeInProgress = isConfigExchangeInProgress(activeEntry);

        // Сохраняем оригинальные protobuf для пересборки
        List<ConfigProtos.Config> stateConfigs;
        List<ModuleConfigProtos.ModuleConfig> stateModuleConfigs;
        List<ChannelProtos.Channel> stateChannels;
        synchronized (state.getConfigs()) {
            stateConfigs = new ArrayList<>(state.getConfigs());
        }
        synchronized (state.getModuleConfigs()) {
            stateModuleConfigs = new ArrayList<>(state.getModuleConfigs());
        }
        synchronized (state.getChannels()) {
            stateChannels = new ArrayList<>(state.getChannels());
        }
        originalConfigs = stateConfigs;
        originalModuleConfigs = stateModuleConfigs;
        originalChannels = stateChannels;
        workingChannels = new ArrayList<>(stateChannels);

        // Корневой элемент (невидимый)
        TreeItem<ConfigTreeItem> root = new TreeItem<>(new ConfigTreeItem("Корень", null, 0));
        root.setExpanded(true);

        // Виртуальная секция: Имя устройства
        TreeItem<ConfigTreeItem> ownerSection = new TreeItem<>(
                new ConfigTreeItem("Имя устройства", "owner_info", 0));
        String longName = myNode != null && myNode.getLongName() != null ? myNode.getLongName() : "";
        ownerSection.getChildren().add(new TreeItem<>(
                new ConfigTreeItem("Длинное имя", "long_name", longName, String.class,
                        null, null, "owner_info", 0)));
        String shortName = myNode != null && myNode.getShortName() != null ? myNode.getShortName() : "";
        ownerSection.getChildren().add(new TreeItem<>(
                new ConfigTreeItem("Короткое имя", "short_name", shortName, String.class,
                        null, null, "owner_info", 0)));
        root.getChildren().add(ownerSection);

        // Виртуальная секция: Фиксированная позиция
        TreeItem<ConfigTreeItem> posSection = new TreeItem<>(
                new ConfigTreeItem("Фиксированная позиция", "fixed_position", 0));
        double lat = myNode != null ? myNode.getLatitude() : 0;
        double lon = myNode != null ? myNode.getLongitude() : 0;
        int alt = myNode != null ? myNode.getAltitude() : 0;
        posSection.getChildren().add(new TreeItem<>(
                new ConfigTreeItem("Широта", "latitude", lat, Double.class,
                        null, null, "fixed_position", 0)));
        posSection.getChildren().add(new TreeItem<>(
                new ConfigTreeItem("Долгота", "longitude", lon, Double.class,
                        null, null, "fixed_position", 0)));
        posSection.getChildren().add(new TreeItem<>(
                new ConfigTreeItem("Высота (м)", "altitude", alt, Integer.class,
                        null, null, "fixed_position", 0)));
        root.getChildren().add(posSection);

        // Конфигурация устройства
        if (!originalConfigs.isEmpty()) {
            TreeItem<ConfigTreeItem> configRoot = ProtobufTreeBuilder.buildConfigTree(originalConfigs);
            root.getChildren().add(configRoot);
        }

        // Конфигурация модулей
        if (!originalModuleConfigs.isEmpty()) {
            TreeItem<ConfigTreeItem> moduleRoot = ProtobufTreeBuilder.buildModuleConfigTree(originalModuleConfigs);
            root.getChildren().add(moduleRoot);
        }

        fullConfigRoot = root;
        configTree.setRoot(root);
        configSearchField.clear();

        if (configExchangeInProgress) {
            watchConfigExchangeCompletion(activeEntry);
            configStatusLabel.setText("Идёт чтение конфигурации с устройства...");
            saveConfigBtn.setDisable(true);
        } else if (originalConfigs.isEmpty() && originalModuleConfigs.isEmpty()) {
            configStatusLabel.setText("Конфигурация не получена от устройства");
            saveConfigBtn.setDisable(true);
        } else {
            saveConfigBtn.setDisable(false);
            int totalFields = countFields(root);
            configStatusLabel.setText("Загружено: %d секций, %d параметров".formatted(
                    originalConfigs.size() + originalModuleConfigs.size(), totalFields));
        }
    }

    /**
     * Подсчитывает количество редактируемых полей в дереве.
     */
    private int countFields(TreeItem<ConfigTreeItem> item) {
        int count = 0;
        if (item.getValue() != null && item.getValue().isEditable()) { count++; }
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            count += countFields(child);
        }
        return count;
    }

    /**
     * Фильтрует дерево конфигурации по строке поиска.
     * Показывает только параметры, содержащие запрос в имени или fieldName,
     * а также их родительские категории.
     */
    private void filterConfigTree(String query) {
        if (fullConfigRoot == null) { return; }

        if (query == null || query.isBlank()) {
            configTree.setRoot(fullConfigRoot);
            return;
        }

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        TreeItem<ConfigTreeItem> filteredRoot = new TreeItem<>(fullConfigRoot.getValue());
        filteredRoot.setExpanded(true);

        for (TreeItem<ConfigTreeItem> topLevel : fullConfigRoot.getChildren()) {
            TreeItem<ConfigTreeItem> filteredTopLevel = filterTreeItem(topLevel, lowerQuery);
            if (filteredTopLevel != null) {
                filteredRoot.getChildren().add(filteredTopLevel);
            }
        }

        configTree.setRoot(filteredRoot);
    }

    /**
     * Рекурсивно фильтрует узел дерева. Возвращает копию с совпадающими потомками или null.
     */
    private TreeItem<ConfigTreeItem> filterTreeItem(TreeItem<ConfigTreeItem> item, String lowerQuery) {
        ConfigTreeItem data = item.getValue();
        boolean selfMatches = false;

        if (data != null && !data.isCategory()) {
            String name = data.getName() != null ? data.getName().toLowerCase(Locale.ROOT) : "";
            String fieldName = data.getFieldName() != null ? data.getFieldName().toLowerCase(Locale.ROOT) : "";
            selfMatches = name.contains(lowerQuery) || fieldName.contains(lowerQuery);
        }

        // Категория с совпадающим именем — показать целиком
        if (data != null && data.isCategory()) {
            String name = data.getName() != null ? data.getName().toLowerCase(Locale.ROOT) : "";
            if (name.contains(lowerQuery)) {
                // Показать всю секцию
                TreeItem<ConfigTreeItem> copy = new TreeItem<>(data);
                copy.setExpanded(true);
                for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
                    copy.getChildren().add(copyTreeItem(child));
                }
                return copy;
            }
        }

        if (selfMatches) {
            return new TreeItem<>(data);
        }

        // Проверить детей
        List<TreeItem<ConfigTreeItem>> matchedChildren = new ArrayList<>();
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            TreeItem<ConfigTreeItem> filtered = filterTreeItem(child, lowerQuery);
            if (filtered != null) {
                matchedChildren.add(filtered);
            }
        }

        if (!matchedChildren.isEmpty()) {
            TreeItem<ConfigTreeItem> copy = new TreeItem<>(data);
            copy.setExpanded(true);
            copy.getChildren().addAll(matchedChildren);
            return copy;
        }

        return null;
    }

    /**
     * Глубокая копия узла дерева (для показа полной секции при совпадении категории).
     */
    private TreeItem<ConfigTreeItem> copyTreeItem(TreeItem<ConfigTreeItem> item) {
        TreeItem<ConfigTreeItem> copy = new TreeItem<>(item.getValue());
        copy.setExpanded(item.isExpanded());
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            copy.getChildren().add(copyTreeItem(child));
        }
        return copy;
    }

    /**
     * Сохраняет изменённые настройки на устройство.
     * Использует begin_edit_settings / commit_edit_settings для батч-отправки.
     */
    private void onSaveConfig() {
        if (state == null || handler == null) {
            configStatusLabel.setText("Нет подключения к радио");
            return;
        }
        ConnectionEntry activeEntry = findActiveConnectionEntry();
        if (isConfigExchangeInProgress(activeEntry)) {
            watchConfigExchangeCompletion(activeEntry);
            configStatusLabel.setText("Дождитесь завершения чтения конфигурации");
            saveConfigBtn.setDisable(true);
            return;
        }

        TreeItem<ConfigTreeItem> root = fullConfigRoot != null ? fullConfigRoot : configTree.getRoot();
        if (root == null) { return; }

        // Собрать виртуальные (admin) изменения
        boolean ownerModified = false;
        String newLongName = null;
        String newShortName = null;
        boolean positionModified = false;
        double newLat = 0;
        double newLon = 0;
        int newAlt = 0;

        // Собрать protobuf-изменения
        List<ConfigProtos.Config> modifiedConfigs = new ArrayList<>();
        List<ModuleConfigProtos.ModuleConfig> modifiedModuleConfigs = new ArrayList<>();

        for (TreeItem<ConfigTreeItem> topLevel : root.getChildren()) {
            ConfigTreeItem topData = topLevel.getValue();
            if (topData == null) { continue; }

            // Виртуальная секция: Имя устройства
            if ("owner_info".equals(topData.getConfigType()) && hasMoifiedFields(topLevel)) {
                ownerModified = true;
                for (TreeItem<ConfigTreeItem> child : topLevel.getChildren()) {
                    ConfigTreeItem ci = child.getValue();
                    if ("long_name".equals(ci.getFieldName())) { newLongName = ci.getValue().toString(); }
                    if ("short_name".equals(ci.getFieldName())) { newShortName = ci.getValue().toString(); }
                }
            }

            // Виртуальная секция: Фиксированная позиция
            if ("fixed_position".equals(topData.getConfigType()) && hasMoifiedFields(topLevel)) {
                positionModified = true;
                for (TreeItem<ConfigTreeItem> child : topLevel.getChildren()) {
                    ConfigTreeItem ci = child.getValue();
                    if ("latitude".equals(ci.getFieldName())) { newLat = ((Number) ci.getValue()).doubleValue(); }
                    if ("longitude".equals(ci.getFieldName())) { newLon = ((Number) ci.getValue()).doubleValue(); }
                    if ("altitude".equals(ci.getFieldName())) { newAlt = ((Number) ci.getValue()).intValue(); }
                }
            }

            // Protobuf-секции: "Конфигурация устройства" / "Конфигурация модулей"
            if ("config".equals(topData.getConfigType()) || "module_config".equals(topData.getConfigType())) {
                for (TreeItem<ConfigTreeItem> section : topLevel.getChildren()) {
                    if (!hasMoifiedFields(section)) { continue; }

                    ConfigTreeItem sectionData = section.getValue();
                    if ("config".equals(sectionData.getConfigType())) {
                        for (ConfigProtos.Config orig : originalConfigs) {
                            var oneofField = getActiveOneofFieldNumber(orig);
                            if (oneofField == sectionData.getConfigVariantNumber()) {
                                ConfigProtos.Config rebuilt = ProtobufTreeBuilder.rebuildConfig(section, orig);
                                if (rebuilt != null) { modifiedConfigs.add(rebuilt); }
                                break;
                            }
                        }
                    } else if ("module_config".equals(sectionData.getConfigType())) {
                        for (ModuleConfigProtos.ModuleConfig orig : originalModuleConfigs) {
                            var oneofField = getActiveModuleOneofFieldNumber(orig);
                            if (oneofField == sectionData.getConfigVariantNumber()) {
                                ModuleConfigProtos.ModuleConfig rebuilt =
                                        ProtobufTreeBuilder.rebuildModuleConfig(section, orig);
                                if (rebuilt != null) { modifiedModuleConfigs.add(rebuilt); }
                                break;
                            }
                        }
                    }
                }
            }
        }

        List<ChannelProtos.Channel> modifiedChannels = collectModifiedChannels();

        if (!ownerModified && !positionModified
                && modifiedConfigs.isEmpty() && modifiedModuleConfigs.isEmpty()
                && modifiedChannels.isEmpty()) {
            configStatusLabel.setText("Нет изменений для сохранения");
            return;
        }

        int totalChanges = modifiedConfigs.size() + modifiedModuleConfigs.size()
                + modifiedChannels.size()
                + (ownerModified ? 1 : 0) + (positionModified ? 1 : 0);
        saveConfigBtn.setDisable(true);
        configStatusLabel.setText("Запрос session key...");

        // Захватываем финальные значения для лямбды
        final boolean fOwnerModified = ownerModified;
        final String fLongName = newLongName;
        final String fShortName = newShortName;
        final boolean fPositionModified = positionModified;
        final double fLat = newLat;
        final double fLon = newLon;
        final int fAlt = newAlt;

        // Запрашиваем session key → отправляем настройки.
        // OwnerInfo listener и timeout fallback работают параллельно, поэтому нужен
        // single-shot guard: повторный begin/set/commit через ~5 секунд ломает save-flow
        // на любом транспорте, если owner info уже успел прийти раньше таймаута.
        AtomicBoolean saveDispatchStarted = new AtomicBoolean(false);
        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () -> Platform.runLater(() -> {
            state.removeOwnerInfoListener(listenerHolder[0]);
            if (saveDispatchStarted.compareAndSet(false, true)) {
                sendConfigChanges(modifiedConfigs, modifiedModuleConfigs, modifiedChannels,
                        fOwnerModified, fLongName, fShortName,
                        fPositionModified, fLat, fLon, fAlt,
                        totalChanges);
            }
        });
        state.addOwnerInfoListener(listenerHolder[0]);

        // Таймаут — отправить без passkey
        Thread timeoutThread = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
            Platform.runLater(() -> {
                state.removeOwnerInfoListener(listenerHolder[0]);
                if (saveDispatchStarted.compareAndSet(false, true)) {
                    configStatusLabel.setText("Отправка без session key...");
                    sendConfigChanges(modifiedConfigs, modifiedModuleConfigs, modifiedChannels,
                            fOwnerModified, fLongName, fShortName,
                            fPositionModified, fLat, fLon, fAlt,
                            totalChanges);
                }
            });
        }, "config-save-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        MessageService.requestSessionPasskey(handler, state);
    }

    /**
     * Отправляет изменённые конфигурации на устройство.
     * Виртуальные секции (имя, позиция) отправляются отдельными admin-сообщениями.
     * Protobuf-секции оборачиваются в begin/commit edit.
     */
    private void sendConfigChanges(List<ConfigProtos.Config> configs,
                                    List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
                                    List<ChannelProtos.Channel> channels,
                                    boolean ownerModified, String newLongName, String newShortName,
                                    boolean positionModified, double newLat, double newLon, int newAlt,
                                    int totalChanges) {
        configStatusLabel.setText("Отправка настроек...");
        ConnectionEntry activeEntry = findActiveConnectionEntry();
        ConnectionType activeTransport = activeEntry != null
                ? activeEntry.getEffectiveType()
                : ConnectionType.TCP;

        // Виртуальные секции — отправить напрямую
        if (ownerModified && newLongName != null && newShortName != null) {
            MessageService.setOwnerInfo(handler, state, newLongName, newShortName, state.getSessionPasskey());
            NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
            if (myNode != null) {
                myNode.setLongName(newLongName);
                myNode.setShortName(newShortName);
                state.fireNodeUpdateListeners(state.getMyNodeNum());
            }
        }

        if (positionModified) {
            if (newLat == 0 && newLon == 0 && newAlt == 0) {
                MessageService.removeFixedPosition(handler, state);
            } else {
                MessageService.setFixedPosition(handler, state, newLat, newLon, newAlt);
                state.setPendingFixedPosition(newLat, newLon, newAlt);
                NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
                if (myNode != null) {
                    // Round-trip through int to show what the device will actually store
                    myNode.setLatitude(Math.round(newLat * 1e7) * 1e-7);
                    myNode.setLongitude(Math.round(newLon * 1e7) * 1e-7);
                    myNode.setAltitude(newAlt);
                    state.fireNodeUpdateListeners(state.getMyNodeNum());
                }
            }
        }

        // Protobuf-секции обычно идут через begin/commit edit с задержками между сообщениями.
        // Исключение ниже — одиночный BLE-save MQTT, где некоторые устройства reboot/disconnect
        // уже на set_module_config и не дают commit дойти до firmware.
        if (!channels.isEmpty() || !configs.isEmpty() || !moduleConfigs.isEmpty()) {
            List<Runnable> tasks = new ArrayList<>();
            AtomicBoolean saveFailed = new AtomicBoolean(false);
            AtomicBoolean saveCompletionAnnounced = new AtomicBoolean(false);
            boolean requiresReconnect = !configs.isEmpty() || !moduleConfigs.isEmpty();
            boolean useImplicitBleModuleSave = shouldUseImplicitBleModuleSave(
                    activeTransport, ownerModified, positionModified, configs, moduleConfigs)
                    && channels.isEmpty();

            for (ChannelProtos.Channel channel : channels) {
                tasks.add(() -> {
                    String stepName = "setChannel/" + channel.getIndex();
                    log.info("Config save: setChannel index={} role={}",
                            channel.getIndex(), channel.getRole());
                    waitForRequiredConfigSaveAck(
                            MessageService.setChannel(handler, state, channel, state.getSessionPasskey()),
                            stepName);
                    state.updateChannel(channel);
                });
            }

            if (useImplicitBleModuleSave) {
                ModuleConfigProtos.ModuleConfig mqttConfig = moduleConfigs.get(0);
                tasks.add(() -> {
                    String stepName = "setModuleConfig/" + mqttConfig.getPayloadVariantCase();
                    log.info("Config save: implicit BLE {} variant={} size={}",
                            stepName, mqttConfig.getPayloadVariantCase(), mqttConfig.getSerializedSize());
                    CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                            MessageService.setModuleConfig(handler, state, mqttConfig);
                    observeDeferredConfigSaveAck(ackFuture, stepName);
                });
            } else if (requiresReconnect) {
                tasks.add(() -> {
                    log.info("Config save: beginEditSettings");
                    waitForRequiredConfigSaveAck(
                            MessageService.beginEditSettings(handler, state),
                            "beginEditSettings");
                });
                int totalMutatingSteps = configs.size() + moduleConfigs.size();
                int mutatingStepIndex = 0;
                for (ConfigProtos.Config c : configs) {
                    boolean waitForAckBeforeCommit = ++mutatingStepIndex < totalMutatingSteps;
                    tasks.add(() -> {
                        String stepName = "setConfig/" + c.getPayloadVariantCase();
                        log.info("Config save: setConfig variant={} size={}",
                                c.getPayloadVariantCase(), c.getSerializedSize());
                        CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                                MessageService.setConfig(handler, state, c);
                        if (waitForAckBeforeCommit) {
                            waitForRequiredConfigSaveAck(ackFuture, stepName);
                        } else {
                            // The final payload step is followed only by commit. Requiring its routing
                            // ACK here can block commit entirely on nodes that reboot or drop the link
                            // immediately after applying the last config section.
                            observeDeferredConfigSaveAck(ackFuture, stepName);
                        }
                    });
                }
                for (ModuleConfigProtos.ModuleConfig mc : moduleConfigs) {
                    boolean waitForAckBeforeCommit = ++mutatingStepIndex < totalMutatingSteps;
                    tasks.add(() -> {
                        String stepName = "setModuleConfig/" + mc.getPayloadVariantCase();
                        log.info("Config save: setModuleConfig variant={} size={}",
                                mc.getPayloadVariantCase(), mc.getSerializedSize());
                        CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                                MessageService.setModuleConfig(handler, state, mc);
                        if (waitForAckBeforeCommit) {
                            waitForRequiredConfigSaveAck(ackFuture, stepName);
                        } else {
                            // MQTT and other reboot-sensitive module updates should not prevent the
                            // trailing commit from being sent just because their routing ACK is late.
                            observeDeferredConfigSaveAck(ackFuture, stepName);
                        }
                    });
                }
                tasks.add(() -> {
                    String stepName = "commitEditSettings";
                    log.info("Config save: commitEditSettings");
                    CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                            MessageService.commitEditSettings(handler, state);
                    if (activeTransport == ConnectionType.BLE) {
                        // BLE devices often reboot/disconnect immediately after commit, so the save
                        // flow must hand off to reconnect even if the routing ACK never comes back.
                        observeDeferredConfigSaveAck(ackFuture, stepName);
                    } else {
                        waitForRequiredConfigSaveAck(ackFuture, stepName);
                    }
                });
            }

            long rebootHandoffDelay = activeTransport == ConnectionType.BLE
                    ? BLE_CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS
                    : CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS;

            Thread saveThread = new Thread(() -> {
                try {
                    // Steps must be delayed relative to the completion of the previous step.
                    // The previous absolute scheduler caused set/commit to collapse into the same
                    // moment whenever beginEditSettings spent time waiting for its ACK.
                    for (int i = 0; i < tasks.size(); i++) {
                        if (saveFailed.get()) {
                            return;
                        }
                        try {
                            tasks.get(i).run();
                        } catch (Exception e) {
                            if (saveCompletionAnnounced.get()) {
                                log.warn("Config save task {} failed after completion was announced", i, e);
                                return;
                            }
                            saveFailed.set(true);
                            log.error("Config save task {} failed", i, e);
                            Platform.runLater(() -> {
                                saveConfigBtn.setDisable(false);
                                configStatusLabel.setText("Ошибка сохранения: " +
                                        (e.getMessage() != null ? e.getMessage() : "см. лог"));
                            });
                            return;
                        }

                        if (i + 1 < tasks.size()) {
                            long interTaskDelayMs =
                                    getConfigSaveInterTaskDelayMs(activeTransport, i, tasks.size());
                            log.debug("Config save: waiting {}ms before {}",
                                    interTaskDelayMs,
                                    i + 1 == tasks.size() - 1 ? "commitEditSettings" : "next step");
                            Thread.sleep(interTaskDelayMs);
                        }
                    }

                    if (saveFailed.get()) {
                        return;
                    }

                    saveCompletionAnnounced.set(true);
                    Platform.runLater(() -> {
                        resetModifiedFlags(fullConfigRoot != null ? fullConfigRoot : configTree.getRoot());
                        originalChannels = getWorkingChannelsSnapshot();
                        saveConfigBtn.setDisable(false);
                        if (requiresReconnect) {
                            String reconnectMessage = activeTransport == ConnectionType.BLE
                                    ? "Отправлено секций: " + totalChanges + ". Ожидание переподключения по BLE..."
                                    : "Отправлено секций: " + totalChanges + ". Устройство перезагрузится. Переподключение...";
                            configStatusLabel.setText(reconnectMessage);
                        } else {
                            configStatusLabel.setText("Отправлено секций: " + totalChanges);
                        }
                    });

                    // После commit переводим соединение в reboot-aware reconnect path.
                    // Обычный user disconnect здесь вреден: он помечает разрыв как ручной
                    // и запрещает auto-reconnect, а BLE как раз нуждается в мягком handoff
                    // на время device reboot / повторной рекламы.
                    if (!requiresReconnect) {
                        return;
                    }
                    Thread.sleep(rebootHandoffDelay);
                    if (saveFailed.get()) {
                        return;
                    }
                    if (activeEntry != null) {
                        log.info("Config save: handoff to reboot reconnect flow (transport={})",
                                activeTransport);
                        ConnectionManager.getInstance().disconnectForDeviceReboot(activeEntry.getId());
                    } else {
                        log.warn("Config save: no active connection to hand off after commit");
                    }
                    Platform.runLater(() -> {
                        state = null;
                        handler = null;
                        reloadConfigTree();
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Config save thread interrupted");
                } catch (Exception e) {
                    log.error("Config save: disconnect failed", e);
                }
            }, "config-save-sender");
            saveThread.setDaemon(true);
            saveThread.start();

        } else {
            // Только виртуальные секции — завершить сразу (устройство не перезагружается)
            resetModifiedFlags(fullConfigRoot != null ? fullConfigRoot : configTree.getRoot());
            originalChannels = getWorkingChannelsSnapshot();
            saveConfigBtn.setDisable(false);
            configStatusLabel.setText("Отправлено секций: " + totalChanges);
        }
    }

    /**
     * Проверяет, есть ли изменённые поля в секции.
     */
    private boolean hasMoifiedFields(TreeItem<ConfigTreeItem> item) {
        ConfigTreeItem data = item.getValue();
        if (data != null && data.isModified()) { return true; }
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            if (hasMoifiedFields(child)) { return true; }
        }
        return false;
    }

    /**
     * Сбрасывает флаги модификации после сохранения.
     */
    private void resetModifiedFlags(TreeItem<ConfigTreeItem> item) {
        if (item == null) { return; }
        if (item.getValue() != null) { item.getValue().resetOriginal(); }
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            resetModifiedFlags(child);
        }
    }

    /**
     * Получает номер активного oneof-поля у Config.
     */
    private int getActiveOneofFieldNumber(ConfigProtos.Config config) {
        var oneof = config.getDescriptorForType().getOneofs().stream()
                .filter(o -> "payload_variant".equals(o.getName()))
                .findFirst().orElse(null);
        if (oneof == null) { return -1; }
        var fd = config.getOneofFieldDescriptor(oneof);
        return fd != null ? fd.getNumber() : -1;
    }

    /**
     * Получает номер активного oneof-поля у ModuleConfig.
     */
    private int getActiveModuleOneofFieldNumber(ModuleConfigProtos.ModuleConfig mc) {
        var oneof = mc.getDescriptorForType().getOneofs().stream()
                .filter(o -> "payload_variant".equals(o.getName()))
                .findFirst().orElse(null);
        if (oneof == null) { return -1; }
        var fd = mc.getOneofFieldDescriptor(oneof);
        return fd != null ? fd.getNumber() : -1;
    }

    // ==================== Config Value Cell ====================

    /**
     * Кастомная ячейка для колонки «Значение» в TreeTableView.
     * Отображает CheckBox для boolean, ComboBox для enum, TextField для строк/чисел.
     */
    private static final class ConfigValueCell extends TreeTableCell<ConfigTreeItem, ConfigTreeItem> {

        @Override
        protected void updateItem(ConfigTreeItem item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            setGraphic(null);
            setStyle("");

            if (empty || item == null) { return; }

            if (item.isCategory()) {
                // Категории — без значения
                return;
            }

            if (!item.isEditable()) {
                setText(item.getValue() != null ? item.getValue().toString() : "");
                return;
            }

            Class<?> type = item.getValueType();

            if (type == Boolean.class) {
                CheckBox checkBox = new CheckBox();
                checkBox.setSelected(item.getValue() instanceof Boolean b && b);
                checkBox.selectedProperty().addListener((obs, oldVal, newVal) -> item.setValue(newVal));
                setGraphic(checkBox);
            } else if (type == EnumValueDescriptor.class && item.getEnumValues() != null) {
                @SuppressWarnings("unchecked")
                ComboBox<EnumValueDescriptor> comboBox = new ComboBox<>();
                for (Object ev : item.getEnumValues()) {
                    if (ev instanceof EnumValueDescriptor evd) {
                        comboBox.getItems().add(evd);
                    }
                }
                // Отображать имя enum
                comboBox.setCellFactory(lv -> new ListCell<>() {
                    @Override
                    protected void updateItem(EnumValueDescriptor evd, boolean emp) {
                        super.updateItem(evd, emp);
                        setText(emp || evd == null ? "" : evd.getName());
                    }
                });
                comboBox.setButtonCell(new ListCell<>() {
                    @Override
                    protected void updateItem(EnumValueDescriptor evd, boolean emp) {
                        super.updateItem(evd, emp);
                        setText(emp || evd == null ? "" : evd.getName());
                    }
                });
                if (item.getValue() instanceof EnumValueDescriptor current) {
                    comboBox.setValue(current);
                }
                comboBox.setMaxWidth(Double.MAX_VALUE);
                comboBox.valueProperty().addListener((obs, oldVal, newVal) -> item.setValue(newVal));
                setGraphic(comboBox);
            } else if (type == String.class) {
                TextField textField = new TextField(item.getValue() != null ? item.getValue().toString() : "");
                textField.setMaxWidth(Double.MAX_VALUE);
                textField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                    if (!isFocused) { item.setValue(textField.getText()); }
                });
                textField.setOnAction(e -> item.setValue(textField.getText()));
                setGraphic(textField);
            } else if (type == Integer.class || type == Long.class) {
                String val = item.getValue() != null ? item.getValue().toString() : "0";
                TextField textField = new TextField(val);
                textField.setMaxWidth(Double.MAX_VALUE);
                textField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                    if (!isFocused) {
                        try {
                            if (type == Integer.class) {
                                item.setValue(Integer.parseInt(textField.getText()));
                            } else {
                                item.setValue(Long.parseLong(textField.getText()));
                            }
                            textField.setStyle("");
                        } catch (NumberFormatException ex) {
                            textField.setStyle("-fx-border-color: #E53935;");
                        }
                    }
                });
                textField.setOnAction(e -> {
                    try {
                        if (type == Integer.class) {
                            item.setValue(Integer.parseInt(textField.getText()));
                        } else {
                            item.setValue(Long.parseLong(textField.getText()));
                        }
                        textField.setStyle("");
                    } catch (NumberFormatException ex) {
                        textField.setStyle("-fx-border-color: #E53935;");
                    }
                });
                setGraphic(textField);
            } else if (type == Float.class || type == Double.class) {
                String val = item.getValue() != null ? item.getValue().toString() : "0.0";
                TextField textField = new TextField(val);
                textField.setMaxWidth(Double.MAX_VALUE);
                textField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                    if (!isFocused) {
                        try {
                            if (type == Float.class) {
                                item.setValue(Float.parseFloat(textField.getText()));
                            } else {
                                item.setValue(Double.parseDouble(textField.getText()));
                            }
                            textField.setStyle("");
                        } catch (NumberFormatException ex) {
                            textField.setStyle("-fx-border-color: #E53935;");
                        }
                    }
                });
                textField.setOnAction(e -> {
                    try {
                        if (type == Float.class) {
                            item.setValue(Float.parseFloat(textField.getText()));
                        } else {
                            item.setValue(Double.parseDouble(textField.getText()));
                        }
                        textField.setStyle("");
                    } catch (NumberFormatException ex) {
                        textField.setStyle("-fx-border-color: #E53935;");
                    }
                });
                setGraphic(textField);
            } else {
                // Fallback — просто текст
                setText(item.getValue() != null ? item.getValue().toString() : "");
            }
        }
    }
}
