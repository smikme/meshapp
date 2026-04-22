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
import com.meshtastic.client.service.DatabaseResetService;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.utils.ConfigValueFormatter;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.ProtobufTreeBuilder;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.SystemForm;
import com.meshtastic.client.utils.TimeZoneSyncUtil;
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
import javafx.scene.control.MenuButton;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
     * Дополнительная пауза перед commit после последнего mutating шага.
     * Даже на TCP/Serial прошивке может потребоваться время, чтобы применить
     * финальный set_config/set_module_config до reboot-triggering commit.
     */
    private static final long CONFIG_SAVE_PRE_COMMIT_SETTLE_DELAY_MS = 1_000;
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
    /** Короткая задержка перед reboot/shutdown, чтобы routing ACK успел вернуться до разрыва линка. */
    private static final int DEVICE_POWER_ACTION_DELAY_SECONDS = 1;
    /** Сколько максимум ждём routing ACK для reboot/shutdown перед fallback-поведением. */
    private static final long DEVICE_POWER_ACTION_ACK_TIMEOUT_MS = 2_500;

    private DeviceState state;
    private ProtocolHandler handler;
    private volatile String pendingTimeOnlySyncConnectionId;
    private final Runnable connectionListener = () -> Platform.runLater(() -> {
        reloadConfigTree();
        maybeResumeDeferredTimeOnlySync();
    });

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
    private Button syncDateTimeBtn;
    private Button resetDatabaseBtn;
    private Button restartHardwareBtn;
    private Button shutdownHardwareBtn;
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

    @Override
    public void formInit() {
        ConnectionManager.getInstance().addListener(connectionListener);
        reloadConfigTree();
    }

    private void init() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        Label title = new Label("Настройки");
        title.getStyleClass().add("form-title");

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
     * Сбрасывает локальный UI-контекст текущего устройства.
     * Нужен при disconnect, чтобы редактор не держал stale-конфигурацию.
     */
    private void clearConfigContext() {
        state = null;
        handler = null;
        observedConfigLoadFuture = null;
        fullConfigRoot = null;
        originalConfigs = new ArrayList<>();
        originalModuleConfigs = new ArrayList<>();
        originalChannels = new ArrayList<>();
        workingChannels = new ArrayList<>();
        if (configTree != null) {
            configTree.setRoot(null);
        }
        if (configSearchField != null) {
            configSearchField.clear();
        }
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
     * Ждёт routing ACK для шага сохранения конфигурации и считает отсутствие ACK ошибкой.
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
     * BLE save-flow должен упорядочивать admin-пакеты по routing ACK: иначе следующий
     * GATT write может догнать ещё не обработанный begin/set. Serial/TCP локальные
     * admin-пакеты на части прошивок routing ACK не присылают, поэтому там используем
     * transport-aware паузы, а ACK оставляем только диагностикой.
     */
    private void waitForTransportRequiredConfigSaveAck(ConnectionType transport,
                                                       CompletableFuture<MeshProtos.Routing.Error> ackFuture,
                                                       String stepName) {
        if (transport != ConnectionType.BLE) {
            observeOptionalConfigSaveAck(ackFuture, stepName);
            return;
        }
        waitForRequiredConfigSaveAck(ackFuture, stepName);
    }

    /**
     * Ждёт routing ACK для reboot-triggering commit, но не считает ошибкой случай,
     * когда устройство успело разорвать линк раньше подтверждения.
     * <p>
     * commit применяет изменения и почти сразу запускает reboot, поэтому на любом
     * транспорте ACK может потеряться уже после успешного принятия команды.
     * Явный routing NAK при этом остаётся настоящей ошибкой.
     */
    private void waitForCommitConfigSaveAckOrExpectedReboot(CompletableFuture<MeshProtos.Routing.Error> ackFuture,
                                                            String stepName) {
        if (ackFuture == null) {
            return;
        }

        try {
            MeshProtos.Routing.Error error = ackFuture.get(CONFIG_SAVE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (error != MeshProtos.Routing.Error.NONE) {
                throw new IllegalStateException("Config save step '" + stepName + "' failed with " + error);
            }
        } catch (TimeoutException e) {
            log.info("Config save: commit '{}' ACK timed out after {} ms, continuing with reconnect flow",
                    stepName, CONFIG_SAVE_ACK_TIMEOUT_MS);
        } catch (Exception e) {
            if (isExpectedRebootAckLoss(e)) {
                log.info("Config save: commit '{}' lost ACK during expected reboot/disconnect: {}",
                        stepName, rootCauseMessage(e));
                return;
            }
            throw new IllegalStateException("Config save step '" + stepName + "' ACK failed", e);
        }
    }

    private void handleCommitConfigSaveAck(ConnectionType transport,
                                           CompletableFuture<MeshProtos.Routing.Error> ackFuture,
                                           String stepName) {
        if (transport != ConnectionType.BLE) {
            observeOptionalConfigSaveAck(ackFuture, stepName);
            return;
        }
        observeDeferredConfigSaveAck(ackFuture, stepName);
        waitForCommitConfigSaveAckOrExpectedReboot(ackFuture, stepName);
    }

    private boolean isExpectedRebootAckLoss(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }

            String message = current.getMessage();
            if (message != null && (message.contains("Packet ACK waiter aborted: DISCONNECTED")
                    || message.contains("Packet ACK waiter aborted: STATE_CLEARED"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current != null && current.getMessage() != null
                ? current.getMessage()
                : error.getClass().getSimpleName();
    }

    private void observeOptionalConfigSaveAck(CompletableFuture<MeshProtos.Routing.Error> ackFuture,
                                              String stepName) {
        if (ackFuture == null) {
            return;
        }

        ackFuture
                .orTimeout(CONFIG_SAVE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((error, ex) -> {
                    if (ex != null) {
                        log.debug("Config save: optional ACK for '{}' was not observed: {}",
                                stepName, rootCauseMessage(ex));
                    } else if (error != null && error != MeshProtos.Routing.Error.NONE) {
                        log.warn("Config save: optional ACK for '{}' returned {}", stepName, error);
                    } else {
                        log.debug("Config save: optional ACK received for '{}'", stepName);
                    }
                });
    }

    /**
     * Подключает диагностику к ACK, который не должен блокировать commit/reconnect flow.
     * <p>
     * Финальный mutating step и BLE commit не должны держать транзакцию открытой в UI:
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
     * Перед {@code commitEditSettings} любому транспорту даём отдельное settle-окно
     * после последнего mutating шага. Для BLE оно длиннее, потому что
     * {@code writeToRadio()} возвращается раньше фактического завершения
     * CoreBluetooth write-with-response.
     */
    private long getConfigSaveInterTaskDelayMs(ConnectionType transport, int taskIndex, int totalTaskCount) {
        long delayMs = baseConfigMessageDelayMs(transport);
        boolean nextTaskIsCommit = taskIndex + 1 == totalTaskCount - 1;
        if (nextTaskIsCommit) {
            delayMs += transport == ConnectionType.BLE
                    ? BLE_CONFIG_SAVE_PRE_COMMIT_SETTLE_DELAY_MS
                    : CONFIG_SAVE_PRE_COMMIT_SETTLE_DELAY_MS;
        }
        return delayMs;
    }

    private long baseConfigMessageDelayMs(ConnectionType transport) {
        return transport == ConnectionType.BLE
                ? BLE_CONFIG_SAVE_MESSAGE_DELAY_MS
                : CONFIG_SAVE_MESSAGE_DELAY_MS;
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
        appearanceHeader.getStyleClass().add("section-title");

        CheckBox disableEffectsCb = new CheckBox("Выключить эффекты оформления");
        disableEffectsCb.setSelected(AppPreferences.isDisableEffectsEffective());
        if (OsDetect.isWindows10()) {
            disableEffectsCb.setDisable(true);
        }
        disableEffectsCb.selectedProperty().addListener((obs, old, val) ->
                AppPreferences.setDisableEffects(val));

        CheckBox softwareRenderingCb = new CheckBox("Включить программный рендеринг");
        softwareRenderingCb.setSelected(AppPreferences.isSoftwareRendering());
        softwareRenderingCb.selectedProperty().addListener((obs, old, val) ->
                AppPreferences.setSoftwareRendering(val));

        CheckBox minimizeToTrayCb = new CheckBox("Минимизация в трей");
        minimizeToTrayCb.setSelected(AppPreferences.isMinimizeToTray());
        minimizeToTrayCb.selectedProperty().addListener((obs, old, val) ->
                AppPreferences.setMinimizeToTray(val));

        VBox typographyGroup = new VBox(10,
                createFontSizeSettingRow(
                        "Размер шрифта приложения",
                        "Управляет шрифтом форм, таблиц и типовых диалогов.",
                        TypographyManager.MIN_APP_FONT_SIZE,
                        TypographyManager.MAX_APP_FONT_SIZE,
                        TypographyManager.getAppFontSize(),
                        TypographyManager.DEFAULT_APP_FONT_SIZE,
                        TypographyManager::setAppFontSize),
                createFontSizeSettingRow(
                        "Размер шрифта чатов",
                        "Управляет списком чатов, сообщениями и полем ввода.",
                        TypographyManager.MIN_CHAT_FONT_SIZE,
                        TypographyManager.MAX_CHAT_FONT_SIZE,
                        TypographyManager.getChatFontSize(),
                        TypographyManager.DEFAULT_CHAT_FONT_SIZE,
                        TypographyManager::setChatFontSize)
        );

        Label restartNote = new Label(OsDetect.isWindows10()
                ? "На Windows 10 эффекты оформления принудительно выключены"
                : "Изменения вступят в силу после перезапуска приложения");
        restartNote.getStyleClass().add("muted-note-label");

        VBox appearanceGroup = new VBox(8, appearanceHeader, typographyGroup,
                disableEffectsCb, softwareRenderingCb, minimizeToTrayCb, restartNote);

        // --- Группа «Интеграции» ---
        Label integrationsHeader = new Label("Интеграции");
        integrationsHeader.getStyleClass().add("section-title");

        CheckBox checkUpdatesCb = new CheckBox("Проверять обновления при старте приложения");
        checkUpdatesCb.setSelected(AppPreferences.isCheckUpdates());
        checkUpdatesCb.selectedProperty().addListener((obs, old, val) ->
                AppPreferences.setCheckUpdates(val));

        CheckBox jfrDiagnosticsCb = new CheckBox("JFR-диагностика зависаний и сбоев");
        jfrDiagnosticsCb.setSelected(AppPreferences.isJfrDiagnosticsEnabled());
        jfrDiagnosticsCb.selectedProperty().addListener((obs, old, val) ->
                AppPreferences.setJfrDiagnosticsEnabled(val));

        Label diagnosticsNote = new Label(
                "Увеличивает нагрузку на приложение. Включайте только для диагностики по запросу поддержки. Требует перезапуска.");
        diagnosticsNote.getStyleClass().add("muted-note-label");
        diagnosticsNote.setWrapText(true);

        VBox integrationsGroup = new VBox(8, integrationsHeader, checkUpdatesCb, jfrDiagnosticsCb, diagnosticsNote);

        panel.getChildren().addAll(appearanceGroup, new Separator(), integrationsGroup);
        return panel;
    }

    private VBox createFontSizeSettingRow(String title,
                                          String description,
                                          int min,
                                          int max,
                                          int initialValue,
                                          int defaultValue,
                                          java.util.function.DoubleConsumer onValueChanged) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("item-title");

        Label descriptionLabel = new Label(description);
        descriptionLabel.getStyleClass().add("muted-note-label");
        descriptionLabel.setWrapText(true);

        Label valueLabel = new Label(formatFontSizeLabel(initialValue));
        valueLabel.getStyleClass().add("section-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(12, titleLabel, spacer, valueLabel);
        header.setAlignment(Pos.CENTER_LEFT);

        Slider slider = new Slider(min, max, initialValue);
        slider.setMajorTickUnit(1);
        slider.setMinorTickCount(0);
        slider.setBlockIncrement(1);
        slider.setSnapToTicks(true);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setMinWidth(0);

        Button resetButton = new Button("Сброс");
        resetButton.setOnAction(event -> slider.setValue(defaultValue));

        HBox sliderRow = new HBox(10, slider, resetButton);
        sliderRow.setAlignment(Pos.CENTER_LEFT);

        slider.prefWidthProperty().bind(sliderRow.widthProperty().multiply(0.5));
        slider.maxWidthProperty().bind(sliderRow.widthProperty().multiply(0.5));

        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            int rounded = (int) Math.round(newValue.doubleValue());
            if (rounded == (int) Math.round(oldValue.doubleValue())) {
                return;
            }
            valueLabel.setText(formatFontSizeLabel(rounded));
            onValueChanged.accept(rounded);
        });

        return new VBox(6, header, descriptionLabel, sliderRow);
    }

    private String formatFontSizeLabel(int value) {
        return value + " px";
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
                sanitizeCacheDisplayText(cd.getValue().getLongName())));
        colLongName.setPrefWidth(150);

        TableColumn<NodeData, String> colShortName = new TableColumn<>("Короткое");
        colShortName.setCellValueFactory(cd -> new SimpleStringProperty(
                sanitizeCacheDisplayText(cd.getValue().getShortName())));
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

    /**
     * Вкладка «Кэш» показывает имена только для чтения, поэтому на macOS
     * отбрасываем glyph-комбинации, которые уже приводили JavaFX/CoreText к native crash.
     */
    static String sanitizeCacheDisplayText(String value) {
        String sanitized = com.meshtastic.client.utils.UnicodeTextUtils.sanitize(value);
        if (sanitized == null || sanitized.isEmpty()) {
            return "";
        }

        StringBuilder safe = new StringBuilder(sanitized.length());
        boolean previousWasWhitespace = false;

        for (int i = 0; i < sanitized.length(); ) {
            int codePoint = sanitized.codePointAt(i);
            i += Character.charCount(codePoint);

            if (isUnsafeCacheDisplayCodePoint(codePoint)) {
                continue;
            }

            if (Character.isWhitespace(codePoint)) {
                if (!previousWasWhitespace && safe.length() > 0) {
                    safe.append(' ');
                    previousWasWhitespace = true;
                }
                continue;
            }

            safe.appendCodePoint(codePoint);
            previousWasWhitespace = false;
        }

        int length = safe.length();
        if (length > 0 && safe.charAt(length - 1) == ' ') {
            safe.setLength(length - 1);
        }
        return safe.toString();
    }

    private static boolean isUnsafeCacheDisplayCodePoint(int codePoint) {
        if (Character.isSupplementaryCodePoint(codePoint) || Character.isISOControl(codePoint)) {
            return true;
        }

        return switch (Character.getType(codePoint)) {
            case Character.NON_SPACING_MARK,
                    Character.COMBINING_SPACING_MARK,
                    Character.ENCLOSING_MARK,
                    Character.FORMAT,
                    Character.SURROGATE -> true;
            default -> false;
        };
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

        syncDateTimeBtn = createConfigToolbarButton(
                "Синхронизировать дату и время",
                "Установить на ноде текущее время ПК и при необходимости обновить GMT",
                "/icons/sync-time.svg",
                this::onSyncDateTimeWithPc);
        syncDateTimeBtn.setDisable(true);

        saveConfigBtn = createConfigToolbarButton(
                "Сохранить на радио",
                "Отправить изменённые параметры на устройство и применить их",
                "/icons/save-radio.svg",
                this::onSaveConfig);
        saveConfigBtn.setDisable(true);

        restartHardwareBtn = createConfigToolbarButton(
                "Перезапуск оборудования",
                "Перезапустить подключенное устройство",
                "/icons/restart-radio.svg",
                this::onRestartHardware);
        restartHardwareBtn.setDisable(true);

        shutdownHardwareBtn = createConfigToolbarButton(
                "Выключение оборудования",
                "Выключить подключенное устройство",
                "/icons/shutdown-radio.svg",
                this::onShutdownHardware);
        shutdownHardwareBtn.setDisable(true);

        resetDatabaseBtn = createConfigToolbarButton(
                "Очистить базу данных",
                "Удалить локальные данные H2 и пересоздать все объекты БД",
                "/icons/clear.svg",
                this::onResetDatabaseRequested);

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
                syncDateTimeBtn,
                saveConfigBtn,
                new Separator(Orientation.VERTICAL),
                restartHardwareBtn,
                shutdownHardwareBtn,
                new Separator(Orientation.VERTICAL),
                resetDatabaseBtn,
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

    private void onResetDatabaseRequested() {
        ModalPane pane = ModalPane.getInstance();
        if (pane == null) {
            return;
        }
        pane.show(buildDatabaseResetConfirmationPanel(this::performDatabaseReset));
    }

    private VBox buildDatabaseResetConfirmationPanel(Runnable onConfirm) {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(380);
        panel.setMaxWidth(380);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        Label lblTitle = new Label("Очистка базы данных");
        lblTitle.getStyleClass().add("dialog-title");

        Label lblMessage = new Label(
                "Будут удалены сообщения, реакции, кэш нод, телеметрия и журнал LoRa-пакетов. "
                        + "Активные подключения будут разорваны. Это действие нельзя отменить."
        );
        lblMessage.setWrapText(true);

        CheckBox acknowledgeCheckBox = new CheckBox("Я понимаю что все данные будут удалены");
        acknowledgeCheckBox.setWrapText(true);

        Button btnCancel = new Button("Отмена");
        btnCancel.setOnAction(e -> {
            ModalPane pane = ModalPane.getInstance();
            if (pane != null) {
                pane.hide();
            }
        });

        Button btnConfirm = new Button("Удалить данные");
        btnConfirm.getStyleClass().add("accent");
        btnConfirm.disableProperty().bind(acknowledgeCheckBox.selectedProperty().not());
        btnConfirm.setOnAction(e -> {
            ModalPane pane = ModalPane.getInstance();
            if (pane != null) {
                pane.hide();
            }
            onConfirm.run();
        });

        HBox btnRow = new HBox(10, btnCancel, btnConfirm);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));

        panel.getChildren().addAll(
                lblTitle,
                new Separator(),
                lblMessage,
                acknowledgeCheckBox,
                btnRow
        );
        return panel;
    }

    private void performDatabaseReset() {
        if (resetDatabaseBtn != null) {
            resetDatabaseBtn.setDisable(true);
        }
        configStatusLabel.setText("Очистка базы данных...");

        Thread resetThread = new Thread(() -> {
            try {
                DatabaseResetService.resetAllData();
                Platform.runLater(() -> {
                    reloadCacheTable();
                    reloadConfigTree();
                    configStatusLabel.setText("База данных очищена и пересоздана");
                    if (resetDatabaseBtn != null) {
                        resetDatabaseBtn.setDisable(false);
                    }
                    Toast.show(Toast.Type.SUCCESS, "База данных очищена");
                });
            } catch (Exception e) {
                log.error("Database reset failed", e);
                Platform.runLater(() -> {
                    if (resetDatabaseBtn != null) {
                        resetDatabaseBtn.setDisable(false);
                    }
                    configStatusLabel.setText("Ошибка очистки базы данных: "
                            + (e.getMessage() != null ? e.getMessage() : "см. лог"));
                    ModalPane.showError(
                            "Ошибка очистки базы данных",
                            e.getMessage() != null ? e.getMessage() : "Не удалось пересоздать объекты БД"
                    );
                });
            }
        }, "database-reset");
        resetThread.setDaemon(true);
        resetThread.start();
    }

    private void setDevicePowerButtonsDisabled(boolean disabled) {
        if (restartHardwareBtn != null) {
            restartHardwareBtn.setDisable(disabled);
        }
        if (shutdownHardwareBtn != null) {
            shutdownHardwareBtn.setDisable(disabled);
        }
    }

    private void setSyncDateTimeButtonDisabled(boolean disabled) {
        if (syncDateTimeBtn != null) {
            syncDateTimeBtn.setDisable(disabled);
        }
    }

    private void onSyncDateTimeWithPc() {
        refreshConnection();
        if (state == null || handler == null) {
            configStatusLabel.setText("Нет подключения к радио");
            setSyncDateTimeButtonDisabled(true);
            return;
        }

        ConnectionEntry activeEntry = findActiveConnectionEntry();
        if (activeEntry == null) {
            configStatusLabel.setText("Нет активного подключения к радио");
            setSyncDateTimeButtonDisabled(true);
            return;
        }
        if (isConfigExchangeInProgress(activeEntry)) {
            watchConfigExchangeCompletion(activeEntry);
            configStatusLabel.setText("Дождитесь завершения чтения конфигурации");
            return;
        }

        ConfigProtos.Config deviceConfig = findLoadedDeviceConfig();
        if (deviceConfig == null || !deviceConfig.hasDevice()) {
            configStatusLabel.setText("Секция device ещё не загружена. Сначала обновите конфигурацию.");
            return;
        }

        DeviceState actionState = state;
        ProtocolHandler actionHandler = handler;
        Instant now = Instant.now();
        ZoneOffset systemOffset = TimeZoneSyncUtil.systemOffset(now);
        ZoneOffset nodeOffset = TimeZoneSyncUtil.resolveCurrentOffset(deviceConfig.getDevice().getTzdef(), now)
                .orElse(null);
        boolean gmtMatches = systemOffset.equals(nodeOffset);
        String targetTzDef = TimeZoneSyncUtil.buildFixedGmtTzDef(systemOffset);
        String systemGmtLabel = TimeZoneSyncUtil.formatGmtOffset(systemOffset);

        Runnable startSync = () -> requestDateTimeSync(activeEntry, actionState, actionHandler,
                deviceConfig, gmtMatches, targetTzDef, systemGmtLabel);

        if (!gmtMatches) {
            String nodeGmtLabel = nodeOffset != null
                    ? TimeZoneSyncUtil.formatGmtOffset(nodeOffset)
                    : "не определён";
            StringBuilder message = new StringBuilder()
                    .append("GMT ноды не совпадает с GMT ПК.")
                    .append(" Нода: ").append(nodeGmtLabel).append(".")
                    .append(" ПК: ").append(systemGmtLabel).append(".")
                    .append(" Для обновления GMT будет изменён параметр device.tzdef, после чего устройство перезагрузится.")
                    .append(" После переподключения время будет синхронизировано повторно, чтобы reboot не сбросил его.");
            if (hasPendingEditorChanges()) {
                message.append(" Несохранённые изменения в редакторе будут потеряны.");
            }
            message.append(" Продолжить?");
            ModalPane.showConfirm("Синхронизация времени и GMT", message.toString(), confirmed -> {
                if (confirmed) {
                    startSync.run();
                }
            });
            return;
        }

        startSync.run();
    }

    private void requestDateTimeSync(ConnectionEntry activeEntry,
                                     DeviceState actionState,
                                     ProtocolHandler actionHandler,
                                     ConfigProtos.Config deviceConfig,
                                     boolean gmtMatches,
                                     String targetTzDef,
                                     String systemGmtLabel) {
        String actionLabel = gmtMatches ? "синхронизации времени" : "синхронизации времени и GMT";
        setSyncDateTimeButtonDisabled(true);
        configStatusLabel.setText("Запрос session key для " + actionLabel + "...");

        AtomicBoolean dispatchStarted = new AtomicBoolean(false);
        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () -> Platform.runLater(() -> {
            actionState.removeOwnerInfoListener(listenerHolder[0]);
            if (dispatchStarted.compareAndSet(false, true)) {
                sendDateTimeSync(activeEntry, actionState, actionHandler,
                        deviceConfig, gmtMatches, targetTzDef, systemGmtLabel);
            }
        });
        actionState.addOwnerInfoListener(listenerHolder[0]);

        Thread timeoutThread = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                return;
            }
            Platform.runLater(() -> {
                actionState.removeOwnerInfoListener(listenerHolder[0]);
                if (dispatchStarted.compareAndSet(false, true)) {
                    configStatusLabel.setText("Отправка синхронизации без session key...");
                    sendDateTimeSync(activeEntry, actionState, actionHandler,
                            deviceConfig, gmtMatches, targetTzDef, systemGmtLabel);
                }
            });
        }, "time-sync-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        MessageService.requestSessionPasskey(actionHandler, actionState);
    }

    private void sendDateTimeSync(ConnectionEntry activeEntry,
                                  DeviceState actionState,
                                  ProtocolHandler actionHandler,
                                  ConfigProtos.Config deviceConfig,
                                  boolean gmtMatches,
                                  String targetTzDef,
                                  String systemGmtLabel) {
        configStatusLabel.setText(gmtMatches
                ? "Синхронизация времени с ПК..."
                : "Синхронизация времени и GMT с ПК...");

        ConnectionType transport = activeEntry != null
                ? activeEntry.getEffectiveType()
                : ConnectionType.TCP;

        Thread syncThread = new Thread(() -> {
            try {
                if (!gmtMatches) {
                    ConfigProtos.Config deviceTzConfig = buildDeviceTimeZoneConfig(deviceConfig, targetTzDef);

                    Thread.sleep(baseConfigMessageDelayMs(transport));
                    waitForTransportRequiredConfigSaveAck(transport,
                            MessageService.beginEditSettings(actionHandler, actionState),
                            "beginEditSettings");

                    Thread.sleep(getConfigSaveInterTaskDelayMs(transport, 0, 3));
                    CompletableFuture<MeshProtos.Routing.Error> setConfigAck =
                            MessageService.setConfig(actionHandler, actionState, deviceTzConfig);
                    observeDeferredConfigSaveAck(setConfigAck, "setConfig/DEVICE");

                    Thread.sleep(getConfigSaveInterTaskDelayMs(transport, 1, 3));
                    CompletableFuture<MeshProtos.Routing.Error> commitAck =
                            MessageService.commitEditSettings(actionHandler, actionState);
                    handleCommitConfigSaveAck(transport, commitAck, "commitEditSettings");

                    if (activeEntry != null) {
                        pendingTimeOnlySyncConnectionId = activeEntry.getId();
                    }
                    log.info("Time sync: GMT update requires reboot, deferring set_time_only until reconnect");
                    Platform.runLater(() -> configStatusLabel.setText(
                            "GMT обновлён. Устройство перезагрузится, после переподключения время будет синхронизировано повторно..."));

                    Thread.sleep(getDevicePowerActionHandoffDelayMs(transport));
                    if (activeEntry != null) {
                        ConnectionManager.getInstance().disconnectForDeviceReboot(activeEntry.getId());
                        Platform.runLater(() -> {
                            state = null;
                            handler = null;
                            reloadConfigTree();
                        });
                    } else {
                        Platform.runLater(() -> {
                            setSyncDateTimeButtonDisabled(false);
                            configStatusLabel.setText("GMT синхронизирован с ПК (" + systemGmtLabel
                                    + "). Синхронизируйте время после переподключения.");
                        });
                    }
                    return;
                }

                long epochSeconds = Instant.now().getEpochSecond();
                waitForTransportRequiredConfigSaveAck(transport,
                        MessageService.setTimeOnly(actionHandler, actionState, epochSeconds),
                        "setTimeOnly");

                Platform.runLater(() -> {
                    setSyncDateTimeButtonDisabled(false);
                    configStatusLabel.setText("Время ноды синхронизировано с ПК (" + systemGmtLabel + ").");
                    Toast.show(Toast.Type.SUCCESS, "Время ноды синхронизировано с ПК");
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Time sync thread interrupted");
                Platform.runLater(() -> setSyncDateTimeButtonDisabled(false));
            } catch (Exception e) {
                log.error("Time sync failed", e);
                if (activeEntry != null && activeEntry.getId().equals(pendingTimeOnlySyncConnectionId)) {
                    pendingTimeOnlySyncConnectionId = null;
                }
                Platform.runLater(() -> {
                    setSyncDateTimeButtonDisabled(false);
                    configStatusLabel.setText("Ошибка синхронизации: "
                            + (e.getMessage() != null ? e.getMessage() : "см. лог"));
                });
            }
        }, "time-sync-sender");
        syncThread.setDaemon(true);
        syncThread.start();
    }

    private ConfigProtos.Config findLoadedDeviceConfig() {
        for (ConfigProtos.Config config : originalConfigs) {
            if (config.hasDevice()) {
                return config;
            }
        }
        return null;
    }

    private ConfigProtos.Config buildDeviceTimeZoneConfig(ConfigProtos.Config originalDeviceConfig, String tzdef) {
        ConfigProtos.Config baseConfig = originalDeviceConfig != null
                ? originalDeviceConfig
                : ConfigProtos.Config.newBuilder().setDevice(ConfigProtos.Config.DeviceConfig.getDefaultInstance()).build();
        ConfigProtos.Config.DeviceConfig.Builder deviceBuilder = baseConfig.hasDevice()
                ? baseConfig.getDevice().toBuilder()
                : ConfigProtos.Config.DeviceConfig.newBuilder();
        deviceBuilder.setTzdef(tzdef);
        return ConfigProtos.Config.newBuilder(baseConfig)
                .setDevice(deviceBuilder.build())
                .build();
    }

    private void maybeResumeDeferredTimeOnlySync() {
        String pendingConnectionId = pendingTimeOnlySyncConnectionId;
        if (pendingConnectionId == null || pendingConnectionId.isBlank()) {
            return;
        }

        ConnectionEntry activeEntry = findActiveConnectionEntry();
        if (activeEntry == null || !pendingConnectionId.equals(activeEntry.getId())) {
            return;
        }
        if (isConfigExchangeInProgress(activeEntry)) {
            watchConfigExchangeCompletion(activeEntry);
            return;
        }

        refreshConnection();
        if (state == null || handler == null) {
            return;
        }

        pendingTimeOnlySyncConnectionId = null;
        Instant now = Instant.now();
        String systemGmtLabel = TimeZoneSyncUtil.formatGmtOffset(TimeZoneSyncUtil.systemOffset(now));
        log.info("Time sync: reconnect complete, repeating set_time_only for '{}'", activeEntry.getName());
        configStatusLabel.setText("Устройство переподключено. Повторная синхронизация времени...");
        requestDateTimeSync(activeEntry, state, handler, null, true, null, systemGmtLabel);
    }

    private void onRestartHardware() {
        ModalPane.showConfirm(
                "Перезапуск оборудования",
                "Вы уверены, что хотите перезапустить оборудование? Соединение с устройством будет временно разорвано.",
                confirmed -> {
                    if (confirmed) {
                        requestDevicePowerAction(true);
                    }
                });
    }

    private void onShutdownHardware() {
        ModalPane.showConfirm(
                "Выключение оборудования",
                "Вы уверены, что хотите выключить оборудование? Для повторного подключения устройство нужно будет включить вручную.",
                confirmed -> {
                    if (confirmed) {
                        requestDevicePowerAction(false);
                    }
                });
    }

    private void requestDevicePowerAction(boolean reboot) {
        refreshConnection();
        if (state == null || handler == null) {
            configStatusLabel.setText("Нет подключения к радио");
            setDevicePowerButtonsDisabled(true);
            return;
        }

        ConnectionEntry activeEntry = findActiveConnectionEntry();
        if (activeEntry == null) {
            configStatusLabel.setText("Нет активного подключения к радио");
            setDevicePowerButtonsDisabled(true);
            return;
        }

        DeviceState actionState = state;
        ProtocolHandler actionHandler = handler;
        String actionLabel = reboot ? "перезапуска" : "выключения";

        setDevicePowerButtonsDisabled(true);
        configStatusLabel.setText("Запрос session key для " + actionLabel + "...");

        AtomicBoolean dispatchStarted = new AtomicBoolean(false);
        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () -> Platform.runLater(() -> {
            actionState.removeOwnerInfoListener(listenerHolder[0]);
            if (dispatchStarted.compareAndSet(false, true)) {
                sendDevicePowerAction(activeEntry, actionState, actionHandler, reboot);
            }
        });
        actionState.addOwnerInfoListener(listenerHolder[0]);

        Thread timeoutThread = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                return;
            }
            Platform.runLater(() -> {
                actionState.removeOwnerInfoListener(listenerHolder[0]);
                if (dispatchStarted.compareAndSet(false, true)) {
                    configStatusLabel.setText("Отправка команды " + actionLabel + " без session key...");
                    sendDevicePowerAction(activeEntry, actionState, actionHandler, reboot);
                }
            });
        }, reboot ? "device-restart-timeout" : "device-shutdown-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        MessageService.requestSessionPasskey(actionHandler, actionState);
    }

    private void sendDevicePowerAction(ConnectionEntry activeEntry,
                                       DeviceState actionState,
                                       ProtocolHandler actionHandler,
                                       boolean reboot) {
        String actionLabel = reboot ? "перезапуска" : "выключения";
        String stepName = reboot ? "rebootDevice" : "shutdownDevice";
        ConnectionType transport = activeEntry.getEffectiveType();

        configStatusLabel.setText("Отправка команды " + actionLabel + "...");

        CompletableFuture<MeshProtos.Routing.Error> ackFuture;
        try {
            ackFuture = reboot
                    ? MessageService.rebootDevice(actionHandler, actionState, DEVICE_POWER_ACTION_DELAY_SECONDS)
                    : MessageService.shutdownDevice(actionHandler, actionState, DEVICE_POWER_ACTION_DELAY_SECONDS);
        } catch (Exception e) {
            log.error("Device {} command send failed", stepName, e);
            setDevicePowerButtonsDisabled(false);
            configStatusLabel.setText("Ошибка отправки команды " + actionLabel);
            return;
        }

        observeDevicePowerActionAck(ackFuture, stepName);

        Thread actionThread = new Thread(() -> {
            boolean ackConfirmed = false;
            try {
                try {
                    MeshProtos.Routing.Error error = ackFuture.get(
                            DEVICE_POWER_ACTION_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (error != null && error != MeshProtos.Routing.Error.NONE) {
                        throw new IllegalStateException(stepName + " failed with " + error);
                    }
                    ackConfirmed = true;
                } catch (TimeoutException e) {
                    log.info("Device power action '{}' ACK timed out, proceeding with fallback flow", stepName);
                }

                Platform.runLater(() -> configStatusLabel.setText(reboot
                        ? "Команда перезапуска отправлена. Ожидание переподключения..."
                        : "Команда выключения отправлена. Ожидание отключения устройства..."));

                Thread.sleep(getDevicePowerActionHandoffDelayMs(transport));

                if (reboot) {
                    ConnectionManager.getInstance().disconnectForDeviceReboot(activeEntry.getId());
                    Platform.runLater(() -> {
                        state = null;
                        handler = null;
                        reloadConfigTree();
                    });
                } else if (ackConfirmed) {
                    ConnectionManager.getInstance().disconnect(activeEntry.getId());
                    Platform.runLater(() -> {
                        state = null;
                        handler = null;
                        reloadConfigTree();
                    });
                } else {
                    Platform.runLater(() -> setDevicePowerButtonsDisabled(false));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Device power action thread interrupted: {}", stepName);
                Platform.runLater(() -> setDevicePowerButtonsDisabled(false));
            } catch (Exception e) {
                log.error("Device power action '{}' failed", stepName, e);
                Platform.runLater(() -> {
                    setDevicePowerButtonsDisabled(false);
                    configStatusLabel.setText("Ошибка отправки команды " + actionLabel + ": " +
                            (e.getMessage() != null ? e.getMessage() : "см. лог"));
                });
            }
        }, reboot ? "device-restart-sender" : "device-shutdown-sender");
        actionThread.setDaemon(true);
        actionThread.start();
    }

    private void observeDevicePowerActionAck(CompletableFuture<MeshProtos.Routing.Error> ackFuture,
                                             String stepName) {
        if (ackFuture == null) {
            return;
        }

        ackFuture.whenComplete((error, ex) -> {
            if (ex != null) {
                log.info("Device power action '{}' ACK completed exceptionally: {}", stepName, ex.getMessage());
            } else if (error != null && error != MeshProtos.Routing.Error.NONE) {
                log.warn("Device power action '{}' returned {}", stepName, error);
            } else {
                log.debug("Device power action '{}' ACK received", stepName);
            }
        });
    }

    private long getDevicePowerActionHandoffDelayMs(ConnectionType transport) {
        return transport == ConnectionType.BLE
                ? BLE_CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS
                : CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS;
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
        refreshConnection();

        boolean connected = state != null && handler != null;
        setDevicePowerButtonsDisabled(!connected);
        setSyncDateTimeButtonDisabled(!connected);

        if (!connected) {
            clearConfigContext();
            configStatusLabel.setText("Нет подключения к радио");
            saveConfigBtn.setDisable(true);
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
            setSyncDateTimeButtonDisabled(true);
        } else if (originalConfigs.isEmpty() && originalModuleConfigs.isEmpty()) {
            configStatusLabel.setText("Конфигурация не получена от устройства");
            saveConfigBtn.setDisable(true);
            setSyncDateTimeButtonDisabled(true);
        } else {
            saveConfigBtn.setDisable(false);
            setSyncDateTimeButtonDisabled(findLoadedDeviceConfig() == null);
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
        DeviceState actionState = state;
        ProtocolHandler actionHandler = handler;
        if (actionState == null || actionHandler == null) {
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
            actionState.removeOwnerInfoListener(listenerHolder[0]);
            if (saveDispatchStarted.compareAndSet(false, true)) {
                sendConfigChanges(activeEntry, actionState, actionHandler,
                        modifiedConfigs, modifiedModuleConfigs, modifiedChannels,
                        fOwnerModified, fLongName, fShortName,
                        fPositionModified, fLat, fLon, fAlt,
                        totalChanges);
            }
        });
        actionState.addOwnerInfoListener(listenerHolder[0]);

        // Таймаут — отправить без passkey
        Thread timeoutThread = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
            Platform.runLater(() -> {
                actionState.removeOwnerInfoListener(listenerHolder[0]);
                if (saveDispatchStarted.compareAndSet(false, true)) {
                    configStatusLabel.setText("Отправка без session key...");
                    sendConfigChanges(activeEntry, actionState, actionHandler,
                            modifiedConfigs, modifiedModuleConfigs, modifiedChannels,
                            fOwnerModified, fLongName, fShortName,
                            fPositionModified, fLat, fLon, fAlt,
                            totalChanges);
                }
            });
        }, "config-save-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        MessageService.requestSessionPasskey(actionHandler, actionState);
    }

    /**
     * Отправляет изменённые конфигурации на устройство.
     * Виртуальные секции (имя, позиция) отправляются отдельными admin-сообщениями.
     * Protobuf-секции оборачиваются в begin/commit edit.
     */
    private void sendConfigChanges(ConnectionEntry activeEntry,
                                   DeviceState actionState,
                                   ProtocolHandler actionHandler,
                                   List<ConfigProtos.Config> configs,
                                   List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
                                   List<ChannelProtos.Channel> channels,
                                   boolean ownerModified, String newLongName, String newShortName,
                                   boolean positionModified, double newLat, double newLon, int newAlt,
                                   int totalChanges) {
        configStatusLabel.setText("Отправка настроек...");
        ConnectionType activeTransport = activeEntry != null
                ? activeEntry.getEffectiveType()
                : ConnectionType.TCP;

        // Виртуальные секции — отправить напрямую
        if (ownerModified && newLongName != null && newShortName != null) {
            MessageService.setOwnerInfo(actionHandler, actionState,
                    newLongName, newShortName, actionState.getSessionPasskey());
            NodeData myNode = actionState.getNodeDb().get(actionState.getMyNodeNum());
            if (myNode != null) {
                myNode.setLongName(newLongName);
                myNode.setShortName(newShortName);
                actionState.fireNodeUpdateListeners(actionState.getMyNodeNum());
            }
        }

        if (positionModified) {
            if (newLat == 0 && newLon == 0 && newAlt == 0) {
                MessageService.removeFixedPosition(actionHandler, actionState);
            } else {
                MessageService.setFixedPosition(actionHandler, actionState, newLat, newLon, newAlt);
                actionState.setPendingFixedPosition(newLat, newLon, newAlt);
                NodeData myNode = actionState.getNodeDb().get(actionState.getMyNodeNum());
                if (myNode != null) {
                    // Round-trip through int to show what the device will actually store
                    myNode.setLatitude(Math.round(newLat * 1e7) * 1e-7);
                    myNode.setLongitude(Math.round(newLon * 1e7) * 1e-7);
                    myNode.setAltitude(newAlt);
                    actionState.fireNodeUpdateListeners(actionState.getMyNodeNum());
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
                    waitForTransportRequiredConfigSaveAck(activeTransport,
                            MessageService.setChannel(actionHandler, actionState, channel,
                                    actionState.getSessionPasskey()),
                            stepName);
                    actionState.updateChannel(channel);
                });
            }

            if (useImplicitBleModuleSave) {
                ModuleConfigProtos.ModuleConfig mqttConfig = moduleConfigs.get(0);
                tasks.add(() -> {
                    String stepName = "setModuleConfig/" + mqttConfig.getPayloadVariantCase();
                    log.info("Config save: implicit BLE {} variant={} size={}",
                            stepName, mqttConfig.getPayloadVariantCase(), mqttConfig.getSerializedSize());
                    CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                            MessageService.setModuleConfig(actionHandler, actionState, mqttConfig);
                    observeDeferredConfigSaveAck(ackFuture, stepName);
                });
            } else if (requiresReconnect) {
                tasks.add(() -> {
                    log.info("Config save: beginEditSettings");
                    waitForTransportRequiredConfigSaveAck(activeTransport,
                            MessageService.beginEditSettings(actionHandler, actionState),
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
                                MessageService.setConfig(actionHandler, actionState, c);
                        if (waitForAckBeforeCommit) {
                            waitForTransportRequiredConfigSaveAck(activeTransport, ackFuture, stepName);
                        } else {
                            // On BLE the final payload step can trigger a disconnect before its routing
                            // ACK is observed, so commit must still be allowed to proceed.
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
                                MessageService.setModuleConfig(actionHandler, actionState, mc);
                        if (waitForAckBeforeCommit) {
                            waitForTransportRequiredConfigSaveAck(activeTransport, ackFuture, stepName);
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
                            MessageService.commitEditSettings(actionHandler, actionState);
                    handleCommitConfigSaveAck(activeTransport, ackFuture, stepName);
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
                            log.error("Config save task {} failed: {}", i,
                                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
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
                        if (state == actionState) {
                            state = null;
                        }
                        if (handler == actionHandler) {
                            handler = null;
                        }
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

    private void syncRepeatedEditorSlots(ConfigTreeItem editedItem) {
        if (editedItem == null || editedItem.getFieldDescriptor() == null || !editedItem.getFieldDescriptor().isRepeated()) {
            return;
        }

        TreeItem<ConfigTreeItem> root = currentEditorRoot();
        if (root == null) {
            return;
        }

        TreeItem<ConfigTreeItem> valueItem = findTreeItemByValue(root, editedItem);
        if (valueItem == null || valueItem.getParent() == null) {
            return;
        }

        ProtobufTreeBuilder.adjustRepeatedGroupAfterEdit(valueItem.getParent());
        refreshConfigTreeView();
    }

    private TreeItem<ConfigTreeItem> findTreeItemByValue(TreeItem<ConfigTreeItem> root, ConfigTreeItem target) {
        if (root == null || target == null) {
            return null;
        }
        if (root.getValue() == target) {
            return root;
        }
        for (TreeItem<ConfigTreeItem> child : root.getChildren()) {
            TreeItem<ConfigTreeItem> match = findTreeItemByValue(child, target);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private void refreshConfigTreeView() {
        if (configTree == null) {
            return;
        }
        if (fullConfigRoot != null && configTree.getRoot() != fullConfigRoot) {
            filterConfigTree(configSearchField != null ? configSearchField.getText() : null);
            return;
        }
        configTree.refresh();
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
    private final class ConfigValueCell extends TreeTableCell<ConfigTreeItem, ConfigTreeItem> {

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
                setText(ConfigValueFormatter.formatValue(item));
                return;
            }

            Class<?> type = item.getValueType();

            if (ConfigValueFormatter.hasBitmaskOptions(item)) {
                setGraphic(createBitmaskEditor(item));
            } else if (type == Boolean.class) {
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
            } else if (type == String.class
                    || type == Integer.class
                    || type == Long.class
                    || type == Float.class
                    || type == Double.class) {
                setGraphic(createTextEditor(item));
            } else {
                // Fallback — просто текст
                setText(ConfigValueFormatter.formatValue(item));
            }
        }

        /**
         * Создаёт текстовый редактор для строковых и числовых полей.
         * Если для поля подключён форматтер, в текстовое поле подставляется
         * уже человекочитаемое представление значения.
         */
        private TextField createTextEditor(ConfigTreeItem item) {
            TextField textField = new TextField(ConfigValueFormatter.formatValue(item));
            textField.setMaxWidth(Double.MAX_VALUE);

            String prompt = ConfigValueFormatter.promptText(item);
            if (prompt != null && !prompt.isBlank()) {
                textField.setPromptText(prompt);
            }

            String hint = ConfigValueFormatter.validationHint(item);
            if (hint != null && !hint.isBlank()) {
                textField.setTooltip(new Tooltip(hint));
            }

            textField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused) {
                    commitTextValue(item, textField);
                }
            });
            textField.setOnAction(e -> commitTextValue(item, textField));
            return textField;
        }

        /**
         * Создаёт selector для bitmask-полей, которые хранятся как число,
         * но по смыслу состоят из набора включаемых флагов.
         */
        private MenuButton createBitmaskEditor(ConfigTreeItem item) {
            MenuButton menuButton = new MenuButton();
            menuButton.setMaxWidth(Double.MAX_VALUE);
            menuButton.setText(ConfigValueFormatter.formatValue(item));

            List<ConfigValueFormatter.BitmaskOption> options = ConfigValueFormatter.bitmaskOptions(item);
            List<CheckMenuItem> menuItems = new ArrayList<>();
            for (ConfigValueFormatter.BitmaskOption option : options) {
                CheckMenuItem menuItem = new CheckMenuItem(option.label());
                menuItem.setSelected(ConfigValueFormatter.isBitmaskOptionSelected(item, option));
                menuItems.add(menuItem);
                menuButton.getItems().add(menuItem);
            }

            for (int i = 0; i < menuItems.size(); i++) {
                menuItems.get(i).selectedProperty().addListener((obs, oldVal, newVal) -> {
                    List<ConfigValueFormatter.BitmaskOption> selectedOptions = new ArrayList<>();
                    for (int j = 0; j < menuItems.size(); j++) {
                        if (menuItems.get(j).isSelected()) {
                            selectedOptions.add(options.get(j));
                        }
                    }
                    item.setValue(ConfigValueFormatter.buildBitmaskValue(item, selectedOptions));
                    menuButton.setText(ConfigValueFormatter.formatValue(item));
                });
            }

            return menuButton;
        }

        /**
         * Применяет текст из редактора к модели поля. При успешном парсинге
         * нормализует отображение значения, при ошибке подсвечивает поле.
         */
        private void commitTextValue(ConfigTreeItem item, TextField textField) {
            try {
                if (item.getFieldDescriptor() != null
                        && item.getFieldDescriptor().isRepeated()
                        && textField.getText().trim().isEmpty()) {
                    item.setValue(null);
                    syncRepeatedEditorSlots(item);
                    textField.setText("");
                    textField.setStyle("");
                    return;
                }
                item.setValue(ConfigValueFormatter.parseTextValue(item, textField.getText()));
                syncRepeatedEditorSlots(item);
                textField.setText(ConfigValueFormatter.formatValue(item));
                textField.setStyle("");
            } catch (IllegalArgumentException ex) {
                textField.setStyle("-fx-border-color: #E53935;");
            }
        }
    }
}
