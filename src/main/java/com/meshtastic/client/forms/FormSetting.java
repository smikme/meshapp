package com.meshtastic.client.forms;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionState;
import com.meshtastic.client.service.ConfigSnapshotService;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.DatabaseResetService;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.system.FormManager;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.ConfigHelpContent;
import com.meshtastic.client.utils.ConfigDescriptionResolver;
import com.meshtastic.client.utils.ConfigValueFormatter;
import com.meshtastic.client.utils.ProtobufTreeBuilder;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.SystemForm;
import com.meshtastic.client.utils.TimeZoneSyncUtil;
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
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.OverrunStyle;
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
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@SystemForm(
    name = "Настройки",
    description = "Настройки клиента",
    tags = { "settings", "options" }
)
public class FormSetting extends Form {

    private static final Logger log = LoggerFactory.getLogger(
        FormSetting.class
    );
    /**
     * Base delay between admin packets while saving configuration.
     * For Serial/TCP, 200 ms is usually enough for firmware to process
     * begin/set/commit without packets bunching together.
     */
    private static final long CONFIG_SAVE_MESSAGE_DELAY_MS = 200;
    /**
     * BLE uses a longer delay between admin packets. Heltec V3 and similar
     * devices may reboot immediately after commit, and short intervals increase
     * the chance of a race between the last GATT writes and session shutdown.
     */
    private static final long BLE_CONFIG_SAVE_MESSAGE_DELAY_MS = 350;
    /**
     * Extra pause before commit after the last mutating step.
     * Even over TCP/Serial, firmware may need time to apply the final
     * set_config/set_module_config before a reboot-triggering commit.
     */
    private static final long CONFIG_SAVE_PRE_COMMIT_SETTLE_DELAY_MS = 1_000;
    /**
     * After commit, TCP/Serial can keep the socket alive for tens of seconds and
     * close it only when the actual reboot starts. For non-BLE transports we do
     * not force an immediate disconnect; we wait for the natural break and use
     * this value only as a fallback if the device never closes the transport.
     */
    private static final long CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS = 60_000;
    private static final long BLE_CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS = 4_000;
    /**
     * The final BLE set_config/set_module_config is sent asynchronously at the
     * GATT-write level. Before commit, we give the write-with-response extra time
     * to physically reach the device before commit places the reboot-triggering
     * packet into the same queue.
     */
    private static final long BLE_CONFIG_SAVE_PRE_COMMIT_SETTLE_DELAY_MS =
        1_000;
    /** Timeout for waiting for a routing ACK during a configuration-save step. */
    private static final long CONFIG_SAVE_ACK_TIMEOUT_MS = 8_000;
    /** Short delay before reboot/shutdown so the routing ACK can arrive before the link drops. */
    private static final int DEVICE_POWER_ACTION_DELAY_SECONDS = 1;
    private static final long DEVICE_POWER_ACTION_HANDOFF_DELAY_MS = 1_000;
    /** Maximum wait for a reboot/shutdown routing ACK before falling back. */
    private static final long DEVICE_POWER_ACTION_ACK_TIMEOUT_MS = 2_500;
    private static final double CONFIG_HELP_POPUP_WIDTH = 460;
    private static final double CONFIG_HELP_POPUP_HEIGHT = 360;
    private static final String OWNER_INFO_CONFIG_TYPE = "owner_info";
    private static final String OWNER_LONG_NAME_FIELD = "long_name";
    private static final String OWNER_SHORT_NAME_FIELD = "short_name";
    private static final String OWNER_IS_LICENSED_FIELD = "is_licensed";
    private static final String RINGTONE_CONFIG_TYPE = "ringtone";
    private static final String RINGTONE_FIELD = "ringtone";

    private DeviceState state;
    private ProtocolHandler handler;
    private MeshCoreCompanionState meshCoreCompanionState;
    private volatile String pendingTimeOnlySyncConnectionId;
    private volatile String configSaveNavigationLockConnectionId;
    private volatile boolean configSaveNavigationLockAwaitingReconnect;
    private volatile boolean configSaveNavigationLockDisconnectObserved;
    private final Runnable connectionListener = () ->
        Platform.runLater(() -> {
            reloadConfigTree();
            maybeResumeDeferredTimeOnlySync();
        });

    // Cache tab
    private TableView<NodeData> cacheTable;
    private final ObservableList<NodeData> cacheData =
        FXCollections.observableArrayList();
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
    private Popup configHelpPopup;
    private PauseTransition configHelpHideDelay;
    private volatile CompletableFuture<DeviceState> observedConfigLoadFuture;
    private volatile DeviceState ringtoneListenerState;
    private volatile Runnable ringtoneListener;
    private volatile DeviceState ringtoneRequestState;

    // Original protobuf objects used to rebuild messages during save.
    private List<ConfigProtos.Config> originalConfigs = new ArrayList<>();
    private List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs =
        new ArrayList<>();
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

        Label title = new Label(I18n.t("settings.title"));
        title.getStyleClass().add("form-title");

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        cacheTab = new Tab(I18n.t("settings.tab.cache"), createCachePanel());
        configTab = new Tab(I18n.t("settings.tab.config"), createConfigPanel());
        appearanceTab = new Tab(
            I18n.t("settings.tab.app"),
            createAppSettingsPanel()
        );

        tabPane.getTabs().addAll(configTab, cacheTab, appearanceTab);
        tabPane
            .getSelectionModel()
            .selectedItemProperty()
            .addListener((obs, oldTab, newTab) -> {
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
     * Finds the selected active connection and updates the state/handler references.
     */
    private void refreshConnection() {
        ConnectionManager mgr = ConnectionManager.getInstance();
        DeviceState newState = null;
        ProtocolHandler newHandler = null;
        MeshCoreCompanionState newMeshCoreState = null;
        ConnectionEntry entry = mgr.getSelectedConnectionEntry();
        if (entry != null && entry.isConnected()) {
            newState = mgr.getDeviceState(entry.getId());
            newHandler = mgr.getProtocolHandler(entry.getId());
            newMeshCoreState = mgr.getMeshCoreCompanionState(entry.getId());
        }
        this.state = newState;
        this.handler = newHandler;
        this.meshCoreCompanionState = newMeshCoreState;
        observeRingtoneState(newState);
    }

    /**
     * Resets the local UI context for the current device.
     * Used on disconnect so the editor does not keep stale configuration.
     */
    private void clearConfigContext() {
        observeRingtoneState(null);
        state = null;
        handler = null;
        meshCoreCompanionState = null;
        observedConfigLoadFuture = null;
        ringtoneRequestState = null;
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

    private void observeRingtoneState(DeviceState newState) {
        if (ringtoneListenerState == newState) {
            return;
        }
        if (ringtoneListenerState != null && ringtoneListener != null) {
            ringtoneListenerState.removeRingtoneListener(ringtoneListener);
        }
        ringtoneListenerState = null;
        ringtoneListener = null;
        ringtoneRequestState = null;

        if (newState == null) {
            return;
        }

        DeviceState observedState = newState;
        ringtoneListener = () ->
            Platform.runLater(() -> {
                ringtoneRequestState = null;
                applyLoadedRingtoneToEditor(observedState);
            });
        observedState.addRingtoneListener(ringtoneListener);
        ringtoneListenerState = observedState;
    }

    /**
     * Returns the full active connection profile.
     * The save flow uses it to choose transport-aware pacing and to hand the
     * connection to the reconnect path after the device reboots.
     */
    private ConnectionEntry findActiveConnectionEntry() {
        ConnectionManager mgr = ConnectionManager.getInstance();
        ConnectionEntry entry = mgr.getSelectedConnectionEntry();
        return entry != null && entry.isConnected() ? entry : null;
    }

    private void beginConfigSaveNavigationBlock(ConnectionEntry activeEntry) {
        configSaveNavigationLockConnectionId =
            activeEntry != null ? activeEntry.getId() : null;
        configSaveNavigationLockAwaitingReconnect = false;
        configSaveNavigationLockDisconnectObserved = false;
        FormManager.setConfigSaveNavigationBlocked(true);
    }

    private void markConfigSaveNavigationBlockAwaitingReconnect(
        ConnectionEntry activeEntry
    ) {
        if (activeEntry == null) {
            return;
        }
        String lockedConnectionId = configSaveNavigationLockConnectionId;
        if (
            lockedConnectionId == null ||
            lockedConnectionId.equals(activeEntry.getId())
        ) {
            configSaveNavigationLockConnectionId = activeEntry.getId();
            configSaveNavigationLockAwaitingReconnect = true;
            configSaveNavigationLockDisconnectObserved = false;
        }
    }

    private void finishConfigSaveNavigationBlock() {
        configSaveNavigationLockConnectionId = null;
        configSaveNavigationLockAwaitingReconnect = false;
        configSaveNavigationLockDisconnectObserved = false;
        FormManager.setConfigSaveNavigationBlocked(false);
    }

    private void maybeFinishConfigSaveNavigationBlockAfterReconnect(
        ConnectionEntry activeEntry,
        boolean configExchangeInProgress
    ) {
        String lockedConnectionId = configSaveNavigationLockConnectionId;
        if (
            lockedConnectionId == null ||
            !configSaveNavigationLockAwaitingReconnect
        ) {
            return;
        }

        ConnectionManager mgr = ConnectionManager.getInstance();
        ConnectionEntry lockedEntry = findConnectionEntryById(
            lockedConnectionId
        );
        boolean activeOrPending = mgr.isConnectionActiveOrPending(
            lockedConnectionId
        );
        if (
            lockedEntry == null ||
            (!lockedEntry.isReconnecting() && !activeOrPending)
        ) {
            finishConfigSaveNavigationBlock();
            return;
        }

        if (
            lockedEntry.isReconnecting() ||
            !activeOrPending ||
            activeEntry == null ||
            !activeEntry.isConnected()
        ) {
            configSaveNavigationLockDisconnectObserved = true;
        }

        if (
            configSaveNavigationLockDisconnectObserved &&
            activeEntry != null &&
            lockedConnectionId.equals(activeEntry.getId()) &&
            activeEntry.isConnected() &&
            !configExchangeInProgress
        ) {
            finishConfigSaveNavigationBlock();
        }
    }

    private ConnectionEntry findConnectionEntryById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (ConnectionEntry entry : ConnectionManager.getInstance().getEntries()) {
            if (id.equals(entry.getId())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Returns {@code true} while the active connection is still running the initial
     * configuration exchange. Until {@code config_complete_id} arrives, the tree
     * may be only partially populated, and starting the save flow would conflict
     * with the in-flight configuration read.
     */
    private boolean isConfigExchangeInProgress(ConnectionEntry entry) {
        if (entry == null) {
            return false;
        }
        CompletableFuture<DeviceState> future =
            ConnectionManager.getInstance().getConfigFuture(entry.getId());
        return future != null && !future.isDone();
    }

    /**
     * Subscribes to completion of the current configuration exchange so the form
     * can leave the loading state automatically, without a manual refresh.
     */
    private void watchConfigExchangeCompletion(ConnectionEntry entry) {
        if (entry == null) {
            observedConfigLoadFuture = null;
            return;
        }
        CompletableFuture<DeviceState> future =
            ConnectionManager.getInstance().getConfigFuture(entry.getId());
        if (
            future == null ||
            future.isDone() ||
            future == observedConfigLoadFuture
        ) {
            return;
        }
        observedConfigLoadFuture = future;
        future.whenComplete((ds, ex) ->
            Platform.runLater(() -> {
                if (observedConfigLoadFuture == future) {
                    observedConfigLoadFuture = null;
                }
                reloadConfigTree();
            })
        );
    }

    private void requestRingtoneIfNeeded(
        DeviceState actionState,
        ProtocolHandler actionHandler
    ) {
        if (
            actionState == null ||
            actionHandler == null ||
            actionState.isRingtoneLoaded()
        ) {
            return;
        }
        if (ringtoneRequestState == actionState) {
            return;
        }

        ringtoneRequestState = actionState;
        MessageService.requestRingtone(actionHandler, actionState)
            .orTimeout(CONFIG_SAVE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .whenComplete((error, ex) -> {
                if (ringtoneRequestState == actionState) {
                    ringtoneRequestState = null;
                }
                if (ex != null) {
                    log.debug(
                        "Ringtone request ACK was not observed: {}",
                        rootCauseMessage(ex)
                    );
                } else if (
                    error != null && error != MeshProtos.Routing.Error.NONE
                ) {
                    log.warn("Ringtone request returned {}", error);
                }
            });
    }

    private void applyLoadedRingtoneToEditor(DeviceState sourceState) {
        if (
            sourceState == null ||
            sourceState != state ||
            !sourceState.isRingtoneLoaded()
        ) {
            return;
        }

        TreeItem<ConfigTreeItem> root = currentEditorRoot();
        if (root == null) {
            return;
        }
        TreeItem<ConfigTreeItem> ringtoneSection = findTopLevelSection(
            root,
            RINGTONE_CONFIG_TYPE
        );
        if (ringtoneSection == null || hasMoifiedFields(ringtoneSection)) {
            return;
        }

        for (TreeItem<ConfigTreeItem> child : ringtoneSection.getChildren()) {
            ConfigTreeItem data = child.getValue();
            if (data != null && RINGTONE_FIELD.equals(data.getFieldName())) {
                data.setValue(sourceState.getRingtone());
                data.resetOriginal();
                refreshConfigTreeView();
                return;
            }
        }
    }

    /**
     * Waits for the routing ACK for a configuration-save step and treats a missing ACK as an error.
     */
    private void waitForRequiredConfigSaveAck(
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName
    ) {
        if (ackFuture == null) {
            return;
        }

        try {
            MeshProtos.Routing.Error error = ackFuture
                .orTimeout(CONFIG_SAVE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .get();
            if (error != MeshProtos.Routing.Error.NONE) {
                throw new IllegalStateException(
                    "Config save step '" + stepName + "' failed with " + error
                );
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                "Config save step '" + stepName + "' ACK failed",
                e
            );
        }
    }

    /**
     * The BLE save flow must order admin packets by routing ACK; otherwise the next
     * GATT write can overtake a begin/set packet that the device has not processed
     * yet. Some firmware does not send routing ACKs for local Serial/TCP admin
     * packets, so those transports use transport-aware delays and keep ACKs as
     * diagnostics only.
     */
    private void waitForTransportRequiredConfigSaveAck(
        ConnectionType transport,
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName
    ) {
        if (transport != ConnectionType.BLE) {
            observeOptionalConfigSaveAck(ackFuture, stepName);
            return;
        }
        waitForRequiredConfigSaveAck(ackFuture, stepName);
    }

    /**
     * Waits for the routing ACK for a reboot-triggering commit, but does not fail
     * if the device drops the link before the ACK arrives.
     * <p>
     * Commit applies changes and almost immediately starts a reboot, so on any
     * transport the ACK can be lost after the command was accepted successfully.
     * An explicit routing NAK is still a real error.
     */
    private void waitForCommitConfigSaveAckOrExpectedReboot(
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName
    ) {
        if (ackFuture == null) {
            return;
        }

        try {
            MeshProtos.Routing.Error error = ackFuture.get(
                CONFIG_SAVE_ACK_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            );
            if (error != MeshProtos.Routing.Error.NONE) {
                throw new IllegalStateException(
                    "Config save step '" + stepName + "' failed with " + error
                );
            }
        } catch (TimeoutException e) {
            log.info(
                "Config save: commit '{}' ACK timed out after {} ms, continuing with reconnect flow",
                stepName,
                CONFIG_SAVE_ACK_TIMEOUT_MS
            );
        } catch (Exception e) {
            if (isExpectedRebootAckLoss(e)) {
                log.info(
                    "Config save: commit '{}' lost ACK during expected reboot/disconnect: {}",
                    stepName,
                    rootCauseMessage(e)
                );
                return;
            }
            throw new IllegalStateException(
                "Config save step '" + stepName + "' ACK failed",
                e
            );
        }
    }

    private void handleCommitConfigSaveAck(
        ConnectionType transport,
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName
    ) {
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
            if (
                message != null &&
                (message.contains("Packet ACK waiter aborted: DISCONNECTED") ||
                    message.contains(
                        "Packet ACK waiter aborted: STATE_CLEARED"
                    ))
            ) {
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

    private void observeOptionalConfigSaveAck(
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName
    ) {
        if (ackFuture == null) {
            return;
        }

        ackFuture
            .orTimeout(CONFIG_SAVE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .whenComplete((error, ex) -> {
                if (ex != null) {
                    log.debug(
                        "Config save: optional ACK for '{}' was not observed: {}",
                        stepName,
                        rootCauseMessage(ex)
                    );
                } else if (
                    error != null && error != MeshProtos.Routing.Error.NONE
                ) {
                    log.warn(
                        "Config save: optional ACK for '{}' returned {}",
                        stepName,
                        error
                    );
                } else {
                    log.debug(
                        "Config save: optional ACK received for '{}'",
                        stepName
                    );
                }
            });
    }

    /**
     * Attaches diagnostics to an ACK that must not block the commit/reconnect flow.
     * <p>
     * The final mutating step and BLE commit must not keep the UI transaction open:
     * some devices drop the link almost immediately after applying the step, and
     * waiting for this ACK synchronously would prevent commit from being sent.
     */
    private void observeDeferredConfigSaveAck(
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName
    ) {
        if (ackFuture == null) {
            return;
        }

        ackFuture.whenComplete((error, ex) -> {
            if (ex != null) {
                log.info(
                    "Config save: deferred ACK for '{}' completed exceptionally: {}",
                    stepName,
                    ex.getMessage()
                );
            } else if (
                error != null && error != MeshProtos.Routing.Error.NONE
            ) {
                log.warn(
                    "Config save: deferred ACK for '{}' returned {}",
                    stepName,
                    error
                );
            } else {
                log.debug(
                    "Config save: deferred ACK received for '{}'",
                    stepName
                );
            }
        });
    }

    /**
     * Returns the delay between two save-flow steps.
     * <p>
     * Before {@code commitEditSettings}, every transport gets a separate settle
     * window after the last mutating step. BLE gets a longer one because
     * {@code writeToRadio()} returns before CoreBluetooth's write-with-response
     * has actually completed.
     */
    private long getConfigSaveInterTaskDelayMs(
        ConnectionType transport,
        int taskIndex,
        int totalTaskCount
    ) {
        long delayMs = baseConfigMessageDelayMs(transport);
        boolean nextTaskIsCommit = taskIndex + 1 == totalTaskCount - 1;
        if (nextTaskIsCommit) {
            delayMs +=
                transport == ConnectionType.BLE
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
     * Some BLE devices drop the link immediately after {@code set_module_config(MQTT)}
     * without waiting for {@code commit_edit_settings}, even after accepting
     * {@code begin_edit_settings}. In this narrow case the transactional wrapper
     * only gets in the way: the save has already started on the device, and commit
     * cannot arrive in time.
     * <p>
     * For a single-section BLE MQTT save, the form therefore uses an implicit save
     * path: it sends only {@code set_module_config}, then waits for reboot/disconnect
     * and hands the connection to the reconnect flow.
     */
    static boolean shouldUseImplicitBleModuleSave(
        ConnectionType transport,
        boolean ownerModified,
        boolean positionModified,
        List<ConfigProtos.Config> configs,
        List<ModuleConfigProtos.ModuleConfig> moduleConfigs
    ) {
        return (
            transport == ConnectionType.BLE &&
            !ownerModified &&
            !positionModified &&
            configs.isEmpty() &&
            moduleConfigs.size() == 1 &&
            moduleConfigs.get(0).getPayloadVariantCase() ==
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.MQTT
        );
    }

    static boolean requiresConfigSaveReconnect(
        boolean ownerModified,
        List<ConfigProtos.Config> configs,
        List<ModuleConfigProtos.ModuleConfig> moduleConfigs
    ) {
        return (
            ownerModified ||
            (configs != null && !configs.isEmpty()) ||
            (moduleConfigs != null && !moduleConfigs.isEmpty())
        );
    }

    // ==================== Application Settings ====================

    private VBox createAppSettingsPanel() {
        VBox panel = new VBox(16);
        panel.setPadding(new Insets(15));

        // --- Appearance Group ---
        Label appearanceHeader = new Label(I18n.t("settings.appearance.title"));
        appearanceHeader.getStyleClass().add("section-title");

        CheckBox disableEffectsCb = new CheckBox(
            I18n.t("settings.effects.disable")
        );
        disableEffectsCb.setSelected(
            AppPreferences.isDisableEffectsEffective()
        );
        if (OsDetect.isWindows10()) {
            disableEffectsCb.setDisable(true);
        }
        disableEffectsCb
            .selectedProperty()
            .addListener((obs, old, val) ->
                AppPreferences.setDisableEffects(val)
            );

        CheckBox softwareRenderingCb = new CheckBox(
            I18n.t("settings.rendering.software")
        );
        softwareRenderingCb.setSelected(AppPreferences.isSoftwareRendering());
        softwareRenderingCb
            .selectedProperty()
            .addListener((obs, old, val) ->
                AppPreferences.setSoftwareRendering(val)
            );

        CheckBox minimizeToTrayCb = new CheckBox(I18n.t("settings.tray.minimize"));
        minimizeToTrayCb.setSelected(AppPreferences.isMinimizeToTray());
        minimizeToTrayCb
            .selectedProperty()
            .addListener((obs, old, val) ->
                AppPreferences.setMinimizeToTray(val)
            );

        VBox typographyGroup = new VBox(
            10,
            createFontSizeSettingRow(
                I18n.t("settings.font.app.title"),
                I18n.t("settings.font.app.description"),
                TypographyManager.MIN_APP_FONT_SIZE,
                TypographyManager.MAX_APP_FONT_SIZE,
                TypographyManager.getAppFontSize(),
                TypographyManager.DEFAULT_APP_FONT_SIZE,
                TypographyManager::setAppFontSize
            ),
            createFontSizeSettingRow(
                I18n.t("settings.font.chat.title"),
                I18n.t("settings.font.chat.description"),
                TypographyManager.MIN_CHAT_FONT_SIZE,
                TypographyManager.MAX_CHAT_FONT_SIZE,
                TypographyManager.getChatFontSize(),
                TypographyManager.DEFAULT_CHAT_FONT_SIZE,
                TypographyManager::setChatFontSize
            )
        );

        Label restartNote = new Label(
            OsDetect.isWindows10()
                ? I18n.t("settings.restart.windows10")
                : I18n.t("settings.restart.required")
        );
        restartNote.getStyleClass().add("muted-note-label");

        VBox languageGroup = createLanguageSettingRow();
        VBox appearanceGroup = new VBox(
            8,
            appearanceHeader,
            typographyGroup,
            languageGroup,
            disableEffectsCb,
            softwareRenderingCb,
            minimizeToTrayCb,
            restartNote
        );

        // --- Integrations Group ---
        Label integrationsHeader = new Label(I18n.t("settings.integrations.title"));
        integrationsHeader.getStyleClass().add("section-title");

        CheckBox checkUpdatesCb = new CheckBox(
            I18n.t("settings.updates.checkOnStart")
        );
        checkUpdatesCb.setSelected(AppPreferences.isCheckUpdates());
        checkUpdatesCb
            .selectedProperty()
            .addListener((obs, old, val) ->
                AppPreferences.setCheckUpdates(val)
            );

        CheckBox jfrDiagnosticsCb = new CheckBox(
            I18n.t("settings.diagnostics.jfr")
        );
        jfrDiagnosticsCb.setSelected(AppPreferences.isJfrDiagnosticsEnabled());
        jfrDiagnosticsCb
            .selectedProperty()
            .addListener((obs, old, val) ->
                AppPreferences.setJfrDiagnosticsEnabled(val)
            );

        Label diagnosticsNote = new Label(
            I18n.t("settings.diagnostics.note")
        );
        diagnosticsNote.getStyleClass().add("muted-note-label");
        diagnosticsNote.setWrapText(true);

        VBox integrationsGroup = new VBox(
            8,
            integrationsHeader,
            checkUpdatesCb,
            jfrDiagnosticsCb,
            diagnosticsNote
        );

        panel
            .getChildren()
            .addAll(appearanceGroup, new Separator(), integrationsGroup);
        return panel;
    }

    private VBox createLanguageSettingRow() {
        Label titleLabel = new Label(I18n.t("settings.language.title"));
        titleLabel.getStyleClass().add("item-title");

        Label descriptionLabel = new Label(I18n.t("settings.language.description"));
        descriptionLabel.getStyleClass().add("muted-note-label");
        descriptionLabel.setWrapText(true);

        ComboBox<I18n.LanguageOption> languageBox = new ComboBox<>(
                FXCollections.observableArrayList(I18n.supportedLanguages()));
        languageBox.setButtonCell(createLanguageCell());
        languageBox.setCellFactory(ignored -> createLanguageCell());
        languageBox.getSelectionModel().select(I18n.languageOption(AppPreferences.getLanguageTag()));
        languageBox.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null && !newValue.tag().equals(I18n.getLanguageTag())) {
                I18n.setLanguageTag(newValue.tag());
            }
        });

        Label restartLabel = new Label(I18n.t("settings.language.restartRequired"));
        restartLabel.getStyleClass().add("muted-note-label");
        restartLabel.setWrapText(true);

        return new VBox(6, titleLabel, descriptionLabel, languageBox, restartLabel);
    }

    private ListCell<I18n.LanguageOption> createLanguageCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(I18n.LanguageOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : I18n.t(item.displayKey()));
            }
        };
    }

    private VBox createFontSizeSettingRow(
        String title,
        String description,
        int min,
        int max,
        int initialValue,
        int defaultValue,
        java.util.function.DoubleConsumer onValueChanged
    ) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("item-title");

        Label descriptionLabel = new Label(description);
        descriptionLabel.getStyleClass().add("muted-note-label");
        descriptionLabel.setWrapText(true);

        Label valueLabel = new Label(formatFontSizeLabel(initialValue));
        valueLabel.getStyleClass().add("section-title");

        HBox header = new HBox(12, titleLabel, valueLabel);
        header.setAlignment(Pos.CENTER_LEFT);

        Slider slider = new Slider(min, max, initialValue);
        slider.setMajorTickUnit(1);
        slider.setMinorTickCount(0);
        slider.setBlockIncrement(1);
        slider.setSnapToTicks(true);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setMinWidth(0);

        Button resetButton = new Button(I18n.t("common.reset"));
        resetButton.setOnAction(event -> slider.setValue(defaultValue));

        HBox sliderRow = new HBox(10, slider, resetButton);
        sliderRow.setAlignment(Pos.CENTER_LEFT);

        slider
            .prefWidthProperty()
            .bind(sliderRow.widthProperty().multiply(0.5));
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

    // ==================== Cache ====================

    @SuppressWarnings("unchecked")
    private VBox createCachePanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(5));

        HBox btnRow = new HBox(8);
        Button importButton = new Button(I18n.t("settings.cache.importOneMesh"));
        importButton.setOnAction(e -> onImportFromOneMesh(importButton));

        Button clearButton = new Button(I18n.t("settings.cache.clear"));
        clearButton.setStyle("-fx-text-fill: #E53935;");
        clearButton.setOnAction(e -> onClearCache());

        btnRow.getChildren().addAll(importButton, clearButton);

        cacheTable = new TableView<>(cacheData);
        cacheTable.setFixedCellSize(28);

        TableColumn<NodeData, String> colLongName = new TableColumn<>(
            I18n.t("settings.cache.column.longName")
        );
        colLongName.setCellValueFactory(cd ->
            new SimpleStringProperty(
                sanitizeCacheDisplayText(cd.getValue().getLongName())
            )
        );
        colLongName.setPrefWidth(150);

        TableColumn<NodeData, String> colShortName = new TableColumn<>(
            I18n.t("settings.cache.column.shortName")
        );
        colShortName.setCellValueFactory(cd ->
            new SimpleStringProperty(
                sanitizeCacheDisplayText(cd.getValue().getShortName())
            )
        );
        colShortName.setPrefWidth(80);

        TableColumn<NodeData, String> colNodeId = new TableColumn<>(
            I18n.t("settings.cache.column.nodeId")
        );
        colNodeId.setCellValueFactory(cd ->
            new SimpleStringProperty(
                cd.getValue().getNodeId() != null
                    ? cd.getValue().getNodeId()
                    : ""
            )
        );
        colNodeId.setPrefWidth(100);

        TableColumn<NodeData, String> colHwModel = new TableColumn<>(
            I18n.t("settings.cache.column.model")
        );
        colHwModel.setCellValueFactory(cd ->
            new SimpleStringProperty(
                cd.getValue().getHwModel() != null
                    ? cd.getValue().getHwModel()
                    : ""
            )
        );
        colHwModel.setPrefWidth(120);

        TableColumn<NodeData, String> colLat = new TableColumn<>(
            I18n.t("settings.cache.column.latitude")
        );
        colLat.setCellValueFactory(cd -> {
            double lat = cd.getValue().getLatitude();
            return new SimpleStringProperty(
                lat != 0 ? String.format("%.3f", lat) : ""
            );
        });
        colLat.setPrefWidth(70);

        TableColumn<NodeData, String> colLon = new TableColumn<>(
            I18n.t("settings.cache.column.longitude")
        );
        colLon.setCellValueFactory(cd -> {
            double lon = cd.getValue().getLongitude();
            return new SimpleStringProperty(
                lon != 0 ? String.format("%.3f", lon) : ""
            );
        });
        colLon.setPrefWidth(70);

        cacheTable
            .getColumns()
            .addAll(
                colLongName,
                colShortName,
                colNodeId,
                colHwModel,
                colLat,
                colLon
            );
        cacheTable.setColumnResizePolicy(
            TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        );

        // Lazy-load by observing the table's vertical ScrollBar.
        cacheTable.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                cacheTable
                    .lookupAll(".scroll-bar")
                    .stream()
                    .filter(n -> n instanceof javafx.scene.control.ScrollBar)
                    .map(n -> (javafx.scene.control.ScrollBar) n)
                    .filter(
                        sb ->
                            sb.getOrientation() ==
                            javafx.geometry.Orientation.VERTICAL
                    )
                    .findFirst()
                    .ifPresent(sb ->
                        sb.valueProperty().addListener((o, oldVal, newVal) -> {
                            if (newVal.doubleValue() > 0.9) {
                                loadNextCachePage();
                            }
                        })
                    );
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
        cacheStatusLabel.setText(I18n.t("settings.cache.loading"));

        new Thread(() -> {
            try {
                int count = NodeCacheService.getInstance().importFromOneMesh();
                Platform.runLater(() -> {
                    button.setDisable(false);
                    reloadCacheTable();
                    ModalPane.showInfo(
                        I18n.t("settings.cache.import.title"),
                        I18n.t("settings.cache.import.success", count)
                    );
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    button.setDisable(false);
                    reloadCacheTable();
                    ModalPane.showError(
                        I18n.t("settings.cache.import.error.title"),
                        I18n.t("settings.cache.import.error.message", ex.getMessage())
                    );
                });
            }
        }, "onemesh-import").start();
    }

    private void onClearCache() {
        ModalPane.showConfirm(
            I18n.t("settings.cache.clear.title"),
            I18n.t("settings.cache.clear.confirm"),
            confirmed -> {
                if (confirmed) {
                    NodeCacheService.getInstance().clearAll();
                    reloadCacheTable();
                }
            }
        );
    }

    private void reloadCacheTable() {
        cacheOffset = 0;
        cacheData.clear();
        cacheTotalInDb = NodeCacheService.getInstance().countNodesInDb();
        loadNextCachePage();
        updateCacheStatus();
    }

    private void loadNextCachePage() {
        if (cacheOffset >= cacheTotalInDb) {
            return;
        }
        List<NodeData> page = NodeCacheService.getInstance().loadPage(
            cacheOffset,
            PAGE_SIZE
        );
        cacheData.addAll(page);
        cacheOffset += page.size();
        updateCacheStatus();
    }

    private void updateCacheStatus() {
        int loaded = cacheData.size();
        if (cacheTotalInDb == 0) {
            cacheStatusLabel.setText(I18n.t("settings.cache.empty"));
        } else if (loaded >= cacheTotalInDb) {
            cacheStatusLabel.setText(
                I18n.t("settings.cache.loaded", loaded, cacheTotalInDb)
            );
        } else {
            cacheStatusLabel.setText(
                I18n.t(
                    "settings.cache.loadedMore",
                    loaded,
                    cacheTotalInDb
                )
            );
        }
    }

    /**
     * The Cache tab displays names as read-only values. Keep the general Unicode
     * normalization for user text without removing valid emoji.
     */
    static String sanitizeCacheDisplayText(String value) {
        return com.meshtastic.client.utils.UnicodeTextUtils.sanitizeForJavaFxDisplay(
            value
        );
    }

    // ==================== Config Tab ====================

    @SuppressWarnings("unchecked")
    private VBox createConfigPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(5));

        // Search.
        configSearchField = new TextField();
        configSearchField.setPromptText(
            I18n.t("settings.config.search.placeholder")
        );
        configSearchField
            .textProperty()
            .addListener((obs, oldVal, newVal) -> filterConfigTree(newVal));

        // Action toolbar.
        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("config-toolbar");

        refreshConfigBtn = createConfigToolbarButton(
            I18n.t("settings.config.toolbar.refresh.title"),
            I18n.t("settings.config.toolbar.refresh.description"),
            "/icons/refresh.svg",
            this::reloadConfigTree
        );

        syncDateTimeBtn = createConfigToolbarButton(
            I18n.t("settings.config.toolbar.syncTime.title"),
            I18n.t("settings.config.toolbar.syncTime.description"),
            "/icons/sync-time.svg",
            this::onSyncDateTimeWithPc
        );
        syncDateTimeBtn.setDisable(true);

        saveConfigBtn = createConfigToolbarButton(
            I18n.t("settings.config.toolbar.saveRadio.title"),
            I18n.t("settings.config.toolbar.saveRadio.description"),
            "/icons/save-radio.svg",
            this::onSaveConfig
        );
        saveConfigBtn.setDisable(true);

        restartHardwareBtn = createConfigToolbarButton(
            I18n.t("settings.config.toolbar.restart.title"),
            I18n.t("settings.config.toolbar.restart.description"),
            "/icons/restart-radio.svg",
            this::onRestartHardware
        );
        restartHardwareBtn.setDisable(true);

        shutdownHardwareBtn = createConfigToolbarButton(
            I18n.t("settings.config.toolbar.shutdown.title"),
            I18n.t("settings.config.toolbar.shutdown.description"),
            "/icons/shutdown-radio.svg",
            this::onShutdownHardware
        );
        shutdownHardwareBtn.setDisable(true);

        resetDatabaseBtn = createConfigToolbarButton(
            I18n.t("settings.config.toolbar.resetDatabase.title"),
            I18n.t("settings.config.toolbar.resetDatabase.description"),
            "/icons/clear.svg",
            this::onResetDatabaseRequested
        );

        Button exportConfigBtn = createConfigToolbarButton(
            I18n.t("settings.config.toolbar.saveConfig.title"),
            I18n.t("settings.config.toolbar.saveConfig.description"),
            "/icons/save-config.svg",
            () -> onExportSnapshot(ConfigSnapshotService.SnapshotKind.CONFIG)
        );

        Button importConfigBtn = createConfigToolbarButton(
            I18n.t("settings.config.toolbar.loadConfig.title"),
            I18n.t("settings.config.toolbar.loadConfig.description"),
            "/icons/load-config.svg",
            () -> onImportSnapshot(ConfigSnapshotService.SnapshotKind.CONFIG)
        );

        Button exportTemplateBtn = createConfigToolbarButton(
            I18n.t("settings.config.toolbar.saveTemplate.title"),
            I18n.t("settings.config.toolbar.saveTemplate.description"),
            "/icons/save-template.svg",
            () -> onExportSnapshot(ConfigSnapshotService.SnapshotKind.TEMPLATE)
        );

        Button importTemplateBtn = createConfigToolbarButton(
            I18n.t("settings.config.toolbar.loadTemplate.title"),
            I18n.t("settings.config.toolbar.loadTemplate.description"),
            "/icons/load-template.svg",
            () -> onImportSnapshot(ConfigSnapshotService.SnapshotKind.TEMPLATE)
        );

        actionToolbar
            .getItems()
            .addAll(
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

        // Parameter column.
        TreeTableColumn<ConfigTreeItem, String> nameCol = new TreeTableColumn<>(
            I18n.t("settings.config.column.parameter")
        );
        nameCol.setCellValueFactory(param -> {
            ConfigTreeItem item = param.getValue().getValue();
            return new SimpleStringProperty(item != null ? item.getName() : "");
        });
        nameCol.setPrefWidth(280);
        nameCol.setEditable(false);
        nameCol.setCellFactory(col ->
            new TreeTableCell<>() {
                private final Label nameLabel = new Label();
                private final Label helpIcon = new Label("?");
                private final HBox content = new HBox(6, nameLabel, helpIcon);
                private ConfigHelpContent helpContent;

                {
                    content.setAlignment(Pos.CENTER_LEFT);
                    nameLabel.setMinWidth(0);
                    nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
                    HBox.setHgrow(nameLabel, Priority.NEVER);

                    helpIcon.getStyleClass().add("config-help-icon");
                    helpIcon.setMinSize(16, 16);
                    helpIcon.setPrefSize(16, 16);
                    helpIcon.setMaxSize(16, 16);
                    helpIcon.setAlignment(Pos.CENTER);
                    helpIcon.setFocusTraversable(true);
                    helpIcon.setOnMouseEntered(e ->
                        showConfigHelpPopup(helpIcon, helpContent)
                    );
                    helpIcon.setOnMouseExited(e ->
                        scheduleHideConfigHelpPopup()
                    );
                    helpIcon
                        .focusedProperty()
                        .addListener((obs, wasFocused, focused) -> {
                            if (focused) {
                                showConfigHelpPopup(helpIcon, helpContent);
                            } else {
                                scheduleHideConfigHelpPopup();
                            }
                        });
                }

                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        helpContent = null;
                        setText(null);
                        setGraphic(null);
                        setContentDisplay(ContentDisplay.LEFT);
                        setStyle("");
                    } else {
                        TreeItem<ConfigTreeItem> treeItem =
                            getTableRow() != null
                                ? getTableRow().getTreeItem()
                                : null;
                        ConfigTreeItem data =
                            treeItem != null ? treeItem.getValue() : null;
                        helpContent = ConfigDescriptionResolver.helpFor(data);
                        String description = helpContent.plainText();

                        nameLabel.setText(item);
                        helpIcon.setAccessibleText(
                            I18n.t("settings.config.help.icon") +
                            (
                                !helpContent.hasDetails()
                                    ? ""
                                    : ": " + description
                            )
                        );

                        setText(null);
                        setGraphic(content);
                        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                        if (
                            treeItem != null &&
                            data != null &&
                            data.isCategory()
                        ) {
                            setStyle("-fx-font-weight: bold;");
                        } else {
                            setStyle("");
                        }
                    }
                }
            }
        );

        // Value column with custom editors.
        TreeTableColumn<ConfigTreeItem, ConfigTreeItem> valueCol =
            new TreeTableColumn<>(I18n.t("settings.config.column.value"));
        valueCol.setCellValueFactory(param ->
            new javafx.beans.property.SimpleObjectProperty<>(
                param.getValue().getValue()
            )
        );
        valueCol.setPrefWidth(300);
        valueCol.setCellFactory(col -> new ConfigValueCell());

        configTree.getColumns().addAll(nameCol, valueCol);
        configTree.setColumnResizePolicy(
            TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        );

        VBox.setVgrow(configTree, Priority.ALWAYS);

        panel
            .getChildren()
            .addAll(
                configSearchField,
                actionToolbar,
                configStatusLabel,
                configTree
            );
        return panel;
    }

    private void showConfigHelpPopup(
        Node anchor,
        ConfigHelpContent helpContent
    ) {
        if (
            anchor == null ||
            helpContent == null ||
            !helpContent.hasDetails() ||
            anchor.getScene() == null ||
            anchor.getScene().getWindow() == null
        ) {
            return;
        }

        cancelConfigHelpPopupHide();
        hideConfigHelpPopup();

        VBox popupContent = createConfigHelpPopupContent(
            helpContent,
            isLightTheme(anchor)
        );
        popupContent.setOnMouseEntered(e -> cancelConfigHelpPopupHide());
        popupContent.setOnMouseExited(e -> scheduleHideConfigHelpPopup());

        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.getContent().add(popupContent);

        Bounds anchorBounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (anchorBounds == null) {
            return;
        }
        Rectangle2D screenBounds = Screen
            .getScreensForRectangle(
                anchorBounds.getMinX(),
                anchorBounds.getMinY(),
                anchorBounds.getWidth(),
                anchorBounds.getHeight()
            )
            .stream()
            .findFirst()
            .orElse(Screen.getPrimary())
            .getVisualBounds();

        double x = anchorBounds.getMaxX() + 6;
        if (x + CONFIG_HELP_POPUP_WIDTH > screenBounds.getMaxX()) {
            x = anchorBounds.getMinX() - CONFIG_HELP_POPUP_WIDTH - 6;
        }
        x = Math.max(screenBounds.getMinX() + 8, x);

        double y = anchorBounds.getMinY();
        if (y + CONFIG_HELP_POPUP_HEIGHT > screenBounds.getMaxY()) {
            y = screenBounds.getMaxY() - CONFIG_HELP_POPUP_HEIGHT - 8;
        }
        y = Math.max(screenBounds.getMinY() + 8, y);

        configHelpPopup = popup;
        popup.show(anchor, x, y);
    }

    private VBox createConfigHelpPopupContent(
        ConfigHelpContent helpContent,
        boolean lightTheme
    ) {
        VBox popup = new VBox(8);
        popup.getStyleClass().add("config-help-popup");
        if (lightTheme) {
            popup.getStyleClass().add("light-theme");
        }
        popup.setPrefWidth(CONFIG_HELP_POPUP_WIDTH);
        popup.setMaxWidth(CONFIG_HELP_POPUP_WIDTH);
        popup.setPrefHeight(CONFIG_HELP_POPUP_HEIGHT);

        Label title = new Label(helpContent.title());
        title.getStyleClass().add("config-help-title");
        title.setWrapText(true);

        Label path = new Label(helpContent.path());
        path.getStyleClass().add("config-help-path");
        path.setWrapText(true);

        VBox body = new VBox(10);
        body.getStyleClass().add("config-help-body");
        addConfigHelpBlock(
            body,
            I18n.t("settings.config.help.block.summary"),
            helpContent.summary()
        );
        addConfigHelpBlock(
            body,
            I18n.t("settings.config.help.block.whenToUse"),
            helpContent.whenToUse()
        );
        addConfigHelpBlock(
            body,
            I18n.t("settings.config.help.block.defaultBehavior"),
            helpContent.defaultBehavior()
        );
        addConfigHelpBlock(
            body,
            I18n.t("settings.config.help.block.valueHint"),
            helpContent.valueHint()
        );
        addConfigHelpValuesBlock(body, helpContent.values());
        addConfigHelpListBlock(
            body,
            I18n.t("settings.config.help.block.notes"),
            helpContent.notes()
        );
        addConfigHelpBlock(
            body,
            I18n.t("settings.config.help.block.technical"),
            helpContent.technicalDetails()
        );

        ScrollPane scrollPane = new ScrollPane(body);
        scrollPane.getStyleClass().add("config-help-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPrefViewportWidth(CONFIG_HELP_POPUP_WIDTH - 28);
        scrollPane.setPrefViewportHeight(CONFIG_HELP_POPUP_HEIGHT - 82);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        popup.getChildren().addAll(title, path, scrollPane);
        return popup;
    }

    private boolean isLightTheme(Node node) {
        return node != null &&
            node.getScene() != null &&
            node
                .getScene()
                .getRoot()
                .getStyleClass()
                .contains("light-theme");
    }

    private void addConfigHelpBlock(VBox body, String header, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        VBox block = new VBox(3);
        block.getStyleClass().add("config-help-block");
        Label headerLabel = new Label(header);
        headerLabel.getStyleClass().add("config-help-block-title");
        Label textLabel = new Label(text);
        textLabel.getStyleClass().add("config-help-text");
        textLabel.setWrapText(true);
        block.getChildren().addAll(headerLabel, textLabel);
        body.getChildren().add(block);
    }

    private void addConfigHelpValuesBlock(
        VBox body,
        List<ConfigHelpContent.ValueHelp> values
    ) {
        if (values == null || values.isEmpty()) {
            return;
        }
        VBox block = new VBox(5);
        block.getStyleClass().add("config-help-block");
        Label headerLabel = new Label(
            I18n.t("settings.config.help.block.values")
        );
        headerLabel.getStyleClass().add("config-help-block-title");
        block.getChildren().add(headerLabel);
        for (ConfigHelpContent.ValueHelp value : values) {
            VBox row = new VBox(2);
            row.getStyleClass().add("config-help-value-row");
            String title = value.value();
            if (value.title() != null && !value.title().isBlank()) {
                title += " - " + value.title();
            }
            Label valueTitle = new Label(title);
            valueTitle.getStyleClass().add("config-help-value-title");
            valueTitle.setWrapText(true);
            Label valueDescription = new Label(value.description());
            valueDescription.getStyleClass().add("config-help-text");
            valueDescription.setWrapText(true);
            row.getChildren().addAll(valueTitle, valueDescription);
            block.getChildren().add(row);
        }
        body.getChildren().add(block);
    }

    private void addConfigHelpListBlock(
        VBox body,
        String header,
        List<String> items
    ) {
        if (items == null || items.isEmpty()) {
            return;
        }
        VBox block = new VBox(4);
        block.getStyleClass().add("config-help-block");
        Label headerLabel = new Label(header);
        headerLabel.getStyleClass().add("config-help-block-title");
        block.getChildren().add(headerLabel);
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            Label itemLabel = new Label("- " + item);
            itemLabel.getStyleClass().add("config-help-text");
            itemLabel.setWrapText(true);
            block.getChildren().add(itemLabel);
        }
        body.getChildren().add(block);
    }

    private void scheduleHideConfigHelpPopup() {
        if (configHelpHideDelay == null) {
            configHelpHideDelay = new PauseTransition(
                Duration.millis(220)
            );
            configHelpHideDelay.setOnFinished(e -> hideConfigHelpPopup());
        }
        configHelpHideDelay.playFromStart();
    }

    private void cancelConfigHelpPopupHide() {
        if (configHelpHideDelay != null) {
            configHelpHideDelay.stop();
        }
    }

    private void hideConfigHelpPopup() {
        if (configHelpPopup != null) {
            configHelpPopup.hide();
            configHelpPopup = null;
        }
    }

    private Button createConfigToolbarButton(
        String title,
        String description,
        String iconPath,
        Runnable action
    ) {
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
        pane.show(
            buildDatabaseResetConfirmationPanel(this::performDatabaseReset)
        );
    }

    private VBox buildDatabaseResetConfirmationPanel(Runnable onConfirm) {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(380);
        panel.setMaxWidth(380);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        Label lblTitle = new Label(I18n.t("settings.databaseReset.title"));
        lblTitle.getStyleClass().add("dialog-title");

        Label lblMessage = new Label(
            I18n.t("settings.databaseReset.message")
        );
        lblMessage.setWrapText(true);

        CheckBox acknowledgeCheckBox = new CheckBox(
            I18n.t("settings.databaseReset.acknowledge")
        );
        acknowledgeCheckBox.setWrapText(true);

        Button btnCancel = new Button(I18n.t("common.cancel"));
        btnCancel.setOnAction(e -> {
            ModalPane pane = ModalPane.getInstance();
            if (pane != null) {
                pane.hide();
            }
        });

        Button btnConfirm = new Button(I18n.t("settings.databaseReset.confirm"));
        btnConfirm.getStyleClass().add("accent");
        btnConfirm
            .disableProperty()
            .bind(acknowledgeCheckBox.selectedProperty().not());
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

        panel
            .getChildren()
            .addAll(
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
        configStatusLabel.setText(I18n.t("settings.databaseReset.inProgress"));

        Thread resetThread = new Thread(() -> {
            try {
                DatabaseResetService.resetAllData();
                Platform.runLater(() -> {
                    reloadCacheTable();
                    reloadConfigTree();
                    configStatusLabel.setText(
                        I18n.t("settings.databaseReset.successStatus")
                    );
                    if (resetDatabaseBtn != null) {
                        resetDatabaseBtn.setDisable(false);
                    }
                    Toast.show(Toast.Type.SUCCESS, I18n.t("settings.databaseReset.successToast"));
                });
            } catch (Exception e) {
                log.error("Database reset failed", e);
                Platform.runLater(() -> {
                    if (resetDatabaseBtn != null) {
                        resetDatabaseBtn.setDisable(false);
                    }
                    configStatusLabel.setText(
                        I18n.t(
                            "settings.databaseReset.errorStatus",
                            errorDetail(e)
                        )
                    );
                    ModalPane.showError(
                        I18n.t("settings.databaseReset.errorTitle"),
                        e.getMessage() != null
                            ? e.getMessage()
                            : I18n.t("settings.databaseReset.errorFallback")
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
            configStatusLabel.setText(I18n.t("settings.status.noRadio"));
            setSyncDateTimeButtonDisabled(true);
            return;
        }

        ConnectionEntry activeEntry = findActiveConnectionEntry();
        if (activeEntry == null) {
            configStatusLabel.setText(I18n.t("settings.status.noActiveRadio"));
            setSyncDateTimeButtonDisabled(true);
            return;
        }
        if (isConfigExchangeInProgress(activeEntry)) {
            watchConfigExchangeCompletion(activeEntry);
            configStatusLabel.setText(
                I18n.t("settings.status.waitConfigRead")
            );
            return;
        }

        ConfigProtos.Config deviceConfig = findLoadedDeviceConfig();
        if (deviceConfig == null || !deviceConfig.hasDevice()) {
            configStatusLabel.setText(
                I18n.t("settings.status.deviceSectionMissing")
            );
            return;
        }

        DeviceState actionState = state;
        ProtocolHandler actionHandler = handler;
        Instant now = Instant.now();
        ZoneOffset systemOffset = TimeZoneSyncUtil.systemOffset(now);
        ZoneOffset nodeOffset = TimeZoneSyncUtil.resolveCurrentOffset(
            deviceConfig.getDevice().getTzdef(),
            now
        ).orElse(null);
        boolean gmtMatches = systemOffset.equals(nodeOffset);
        String targetTzDef = TimeZoneSyncUtil.buildFixedGmtTzDef(systemOffset);
        String systemGmtLabel = TimeZoneSyncUtil.formatGmtOffset(systemOffset);

        Runnable startSync = () ->
            requestDateTimeSync(
                activeEntry,
                actionState,
                actionHandler,
                deviceConfig,
                gmtMatches,
                targetTzDef,
                systemGmtLabel
            );

        if (!gmtMatches) {
            String nodeGmtLabel =
                nodeOffset != null
                    ? TimeZoneSyncUtil.formatGmtOffset(nodeOffset)
                    : I18n.t("settings.timeSync.unknown");
            String unsavedWarning = hasPendingEditorChanges()
                ? I18n.t("settings.timeSync.unsavedWarning")
                : "";
            ModalPane.showConfirm(
                I18n.t("settings.timeSync.gmtMismatch.title"),
                I18n.t(
                    "settings.timeSync.gmtMismatch.message",
                    nodeGmtLabel,
                    systemGmtLabel,
                    unsavedWarning
                ),
                confirmed -> {
                    if (confirmed) {
                        startSync.run();
                    }
                }
            );
            return;
        }

        startSync.run();
    }

    private void requestDateTimeSync(
        ConnectionEntry activeEntry,
        DeviceState actionState,
        ProtocolHandler actionHandler,
        ConfigProtos.Config deviceConfig,
        boolean gmtMatches,
        String targetTzDef,
        String systemGmtLabel
    ) {
        String actionLabel = gmtMatches
            ? I18n.t("settings.timeSync.action.time")
            : I18n.t("settings.timeSync.action.timeAndGmt");
        setSyncDateTimeButtonDisabled(true);
        configStatusLabel.setText(
            I18n.t("settings.status.requestSessionKeyFor", actionLabel)
        );

        AtomicBoolean dispatchStarted = new AtomicBoolean(false);
        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () ->
            Platform.runLater(() -> {
                actionState.removeOwnerInfoListener(listenerHolder[0]);
                if (dispatchStarted.compareAndSet(false, true)) {
                    sendDateTimeSync(
                        activeEntry,
                        actionState,
                        actionHandler,
                        deviceConfig,
                        gmtMatches,
                        targetTzDef,
                        systemGmtLabel
                    );
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
                    configStatusLabel.setText(
                        I18n.t("settings.timeSync.sendingWithoutKey")
                    );
                    sendDateTimeSync(
                        activeEntry,
                        actionState,
                        actionHandler,
                        deviceConfig,
                        gmtMatches,
                        targetTzDef,
                        systemGmtLabel
                    );
                }
            });
        }, "time-sync-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        MessageService.requestSessionPasskey(actionHandler, actionState);
    }

    private void sendDateTimeSync(
        ConnectionEntry activeEntry,
        DeviceState actionState,
        ProtocolHandler actionHandler,
        ConfigProtos.Config deviceConfig,
        boolean gmtMatches,
        String targetTzDef,
        String systemGmtLabel
    ) {
        configStatusLabel.setText(
            gmtMatches
                ? I18n.t("settings.timeSync.sendingTime")
                : I18n.t("settings.timeSync.sendingTimeAndGmt")
        );

        ConnectionType transport =
            activeEntry != null
                ? activeEntry.getEffectiveType()
                : ConnectionType.TCP;
        long reconnectHandoffGeneration =
            !gmtMatches && activeEntry != null
                ? ConnectionManager.getInstance().getConnectionGeneration(
                      activeEntry.getId()
                  )
                : -1;
        if (!gmtMatches && activeEntry != null) {
            ConnectionManager.getInstance().expectDeviceReboot(
                activeEntry.getId()
            );
        }

        Thread syncThread = new Thread(() -> {
            try {
                if (!gmtMatches) {
                    ConfigProtos.Config deviceTzConfig =
                        buildDeviceTimeZoneConfig(deviceConfig, targetTzDef);

                    Thread.sleep(baseConfigMessageDelayMs(transport));
                    waitForTransportRequiredConfigSaveAck(
                        transport,
                        MessageService.beginEditSettings(
                            actionHandler,
                            actionState
                        ),
                        "beginEditSettings"
                    );

                    Thread.sleep(
                        getConfigSaveInterTaskDelayMs(transport, 0, 3)
                    );
                    CompletableFuture<MeshProtos.Routing.Error> setConfigAck =
                        MessageService.setConfig(
                            actionHandler,
                            actionState,
                            deviceTzConfig
                        );
                    observeDeferredConfigSaveAck(
                        setConfigAck,
                        "setConfig/DEVICE"
                    );

                    Thread.sleep(
                        getConfigSaveInterTaskDelayMs(transport, 1, 3)
                    );
                    CompletableFuture<MeshProtos.Routing.Error> commitAck =
                        MessageService.commitEditSettings(
                            actionHandler,
                            actionState
                        );
                    handleCommitConfigSaveAck(
                        transport,
                        commitAck,
                        "commitEditSettings"
                    );

                    if (activeEntry != null) {
                        pendingTimeOnlySyncConnectionId = activeEntry.getId();
                    }
                    log.info(
                        "Time sync: GMT update requires reboot, deferring set_time_only until reconnect"
                    );
                    Platform.runLater(() ->
                        configStatusLabel.setText(
                            I18n.t("settings.timeSync.gmtUpdated")
                        )
                    );

                    Thread.sleep(getDevicePowerActionHandoffDelayMs(transport));
                    if (activeEntry != null) {
                        boolean handoffStarted =
                            ConnectionManager.getInstance().disconnectForDeviceReboot(
                                activeEntry.getId(),
                                reconnectHandoffGeneration
                            );
                        if (handoffStarted) {
                            Platform.runLater(() -> {
                                state = null;
                                handler = null;
                                reloadConfigTree();
                            });
                        }
                    } else {
                        Platform.runLater(() -> {
                            setSyncDateTimeButtonDisabled(false);
                            configStatusLabel.setText(
                                I18n.t("settings.timeSync.gmtSyncedStatus", systemGmtLabel)
                            );
                        });
                    }
                    return;
                }

                long epochSeconds = Instant.now().getEpochSecond();
                MessageService.sendPhoneTimePosition(
                    actionHandler,
                    actionState,
                    epochSeconds
                );
                waitForTransportRequiredConfigSaveAck(
                    transport,
                    MessageService.setTimeOnly(
                        actionHandler,
                        actionState,
                        epochSeconds
                    ),
                    "setTimeOnly"
                );

                Platform.runLater(() -> {
                    setSyncDateTimeButtonDisabled(false);
                    configStatusLabel.setText(
                        I18n.t("settings.timeSync.timeSyncedStatus", systemGmtLabel)
                    );
                    Toast.show(
                        Toast.Type.SUCCESS,
                        I18n.t("settings.timeSync.timeSyncedToast")
                    );
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Time sync thread interrupted");
                if (!gmtMatches && activeEntry != null) {
                    ConnectionManager.getInstance().clearExpectedDeviceReboot(
                        activeEntry.getId()
                    );
                }
                Platform.runLater(() -> setSyncDateTimeButtonDisabled(false));
            } catch (Exception e) {
                log.error("Time sync failed", e);
                if (!gmtMatches && activeEntry != null) {
                    ConnectionManager.getInstance().clearExpectedDeviceReboot(
                        activeEntry.getId()
                    );
                }
                if (
                    activeEntry != null &&
                    activeEntry.getId().equals(pendingTimeOnlySyncConnectionId)
                ) {
                    pendingTimeOnlySyncConnectionId = null;
                }
                Platform.runLater(() -> {
                    setSyncDateTimeButtonDisabled(false);
                    configStatusLabel.setText(
                        I18n.t("settings.timeSync.error", errorDetail(e))
                    );
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

    private ConfigProtos.Config buildDeviceTimeZoneConfig(
        ConfigProtos.Config originalDeviceConfig,
        String tzdef
    ) {
        ConfigProtos.Config baseConfig =
            originalDeviceConfig != null
                ? originalDeviceConfig
                : ConfigProtos.Config.newBuilder()
                      .setDevice(
                          ConfigProtos.Config.DeviceConfig.getDefaultInstance()
                      )
                      .build();
        ConfigProtos.Config.DeviceConfig.Builder deviceBuilder =
            baseConfig.hasDevice()
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
        if (
            activeEntry == null ||
            !pendingConnectionId.equals(activeEntry.getId())
        ) {
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
        String systemGmtLabel = TimeZoneSyncUtil.formatGmtOffset(
            TimeZoneSyncUtil.systemOffset(now)
        );
        log.info(
            "Time sync: reconnect complete, repeating set_time_only for '{}'",
            activeEntry.getName()
        );
        configStatusLabel.setText(
            I18n.t("settings.timeSync.reconnected")
        );
        requestDateTimeSync(
            activeEntry,
            state,
            handler,
            null,
            true,
            null,
            systemGmtLabel
        );
    }

    private void onRestartHardware() {
        ModalPane.showConfirm(
            I18n.t("settings.devicePower.restart.title"),
            I18n.t("settings.devicePower.restart.confirm"),
            confirmed -> {
                if (confirmed) {
                    requestDevicePowerAction(true);
                }
            }
        );
    }

    private void onShutdownHardware() {
        ModalPane.showConfirm(
            I18n.t("settings.devicePower.shutdown.title"),
            I18n.t("settings.devicePower.shutdown.confirm"),
            confirmed -> {
                if (confirmed) {
                    requestDevicePowerAction(false);
                }
            }
        );
    }

    private void requestDevicePowerAction(boolean reboot) {
        refreshConnection();
        if (state == null || handler == null) {
            configStatusLabel.setText(I18n.t("settings.status.noRadio"));
            setDevicePowerButtonsDisabled(true);
            return;
        }

        ConnectionEntry activeEntry = findActiveConnectionEntry();
        if (activeEntry == null) {
            configStatusLabel.setText(I18n.t("settings.status.noActiveRadio"));
            setDevicePowerButtonsDisabled(true);
            return;
        }

        DeviceState actionState = state;
        ProtocolHandler actionHandler = handler;
        String actionLabel = devicePowerActionLabel(reboot);

        setDevicePowerButtonsDisabled(true);
        configStatusLabel.setText(
            I18n.t("settings.status.requestSessionKeyFor", actionLabel)
        );

        AtomicBoolean dispatchStarted = new AtomicBoolean(false);
        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () ->
            Platform.runLater(() -> {
                actionState.removeOwnerInfoListener(listenerHolder[0]);
                if (dispatchStarted.compareAndSet(false, true)) {
                    sendDevicePowerAction(
                        activeEntry,
                        actionState,
                        actionHandler,
                        reboot
                    );
                }
            });
        actionState.addOwnerInfoListener(listenerHolder[0]);

        Thread timeoutThread = new Thread(
            () -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    return;
                }
                Platform.runLater(() -> {
                    actionState.removeOwnerInfoListener(listenerHolder[0]);
                    if (dispatchStarted.compareAndSet(false, true)) {
                        configStatusLabel.setText(
                            I18n.t("settings.devicePower.sendingWithoutKey", actionLabel)
                        );
                        sendDevicePowerAction(
                            activeEntry,
                            actionState,
                            actionHandler,
                            reboot
                        );
                    }
                });
            },
            reboot ? "device-restart-timeout" : "device-shutdown-timeout"
        );
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        MessageService.requestSessionPasskey(actionHandler, actionState);
    }

    private void sendDevicePowerAction(
        ConnectionEntry activeEntry,
        DeviceState actionState,
        ProtocolHandler actionHandler,
        boolean reboot
    ) {
        String actionLabel = devicePowerActionLabel(reboot);
        String stepName = reboot ? "rebootDevice" : "shutdownDevice";
        ConnectionType transport = activeEntry.getEffectiveType();
        long reconnectHandoffGeneration = reboot
            ? ConnectionManager.getInstance().getConnectionGeneration(
                  activeEntry.getId()
              )
            : -1;
        if (reboot) {
            ConnectionManager.getInstance().expectDeviceReboot(
                activeEntry.getId()
            );
        }

        configStatusLabel.setText(
            I18n.t("settings.devicePower.sending", actionLabel)
        );

        CompletableFuture<MeshProtos.Routing.Error> ackFuture;
        try {
            ackFuture = reboot
                ? MessageService.rebootDevice(
                      actionHandler,
                      actionState,
                      DEVICE_POWER_ACTION_DELAY_SECONDS
                  )
                : MessageService.shutdownDevice(
                      actionHandler,
                      actionState,
                      DEVICE_POWER_ACTION_DELAY_SECONDS
                  );
        } catch (Exception e) {
            log.error("Device {} command send failed", stepName, e);
            if (reboot) {
                ConnectionManager.getInstance().clearExpectedDeviceReboot(
                    activeEntry.getId()
                );
            }
            setDevicePowerButtonsDisabled(false);
            configStatusLabel.setText(
                I18n.t("settings.devicePower.sendError", actionLabel)
            );
            return;
        }

        observeDevicePowerActionAck(ackFuture, stepName);

        Thread actionThread = new Thread(
            () -> {
                boolean ackConfirmed = false;
                try {
                    try {
                        MeshProtos.Routing.Error error = ackFuture.get(
                            DEVICE_POWER_ACTION_ACK_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS
                        );
                        if (
                            error != null &&
                            error != MeshProtos.Routing.Error.NONE
                        ) {
                            throw new IllegalStateException(
                                stepName + " failed with " + error
                            );
                        }
                        ackConfirmed = true;
                    } catch (TimeoutException e) {
                        log.info(
                            "Device power action '{}' ACK timed out, proceeding with fallback flow",
                            stepName
                        );
                    }

                    Platform.runLater(() ->
                        configStatusLabel.setText(
                            reboot
                                ? I18n.t("settings.devicePower.restartSent")
                                : I18n.t("settings.devicePower.shutdownSent")
                        )
                    );

                    Thread.sleep(getDevicePowerActionHandoffDelayMs(transport));

                    if (reboot) {
                        boolean handoffStarted =
                            ConnectionManager.getInstance().disconnectForDeviceReboot(
                                activeEntry.getId(),
                                reconnectHandoffGeneration
                            );
                        if (handoffStarted) {
                            Platform.runLater(() -> {
                                state = null;
                                handler = null;
                                reloadConfigTree();
                            });
                        }
                    } else if (ackConfirmed) {
                        ConnectionManager.getInstance().disconnect(
                            activeEntry.getId()
                        );
                        Platform.runLater(() -> {
                            state = null;
                            handler = null;
                            reloadConfigTree();
                        });
                    } else {
                        Platform.runLater(() ->
                            setDevicePowerButtonsDisabled(false)
                        );
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn(
                        "Device power action thread interrupted: {}",
                        stepName
                    );
                    if (reboot) {
                        ConnectionManager.getInstance().clearExpectedDeviceReboot(
                            activeEntry.getId()
                        );
                    }
                    Platform.runLater(() ->
                        setDevicePowerButtonsDisabled(false)
                    );
                } catch (Exception e) {
                    log.error("Device power action '{}' failed", stepName, e);
                    if (reboot) {
                        ConnectionManager.getInstance().clearExpectedDeviceReboot(
                            activeEntry.getId()
                        );
                    }
                    Platform.runLater(() -> {
                        setDevicePowerButtonsDisabled(false);
                        configStatusLabel.setText(
                            I18n.t(
                                "settings.devicePower.sendErrorDetails",
                                actionLabel,
                                errorDetail(e)
                            )
                        );
                    });
                }
            },
            reboot ? "device-restart-sender" : "device-shutdown-sender"
        );
        actionThread.setDaemon(true);
        actionThread.start();
    }

    private void observeDevicePowerActionAck(
        CompletableFuture<MeshProtos.Routing.Error> ackFuture,
        String stepName
    ) {
        if (ackFuture == null) {
            return;
        }

        ackFuture.whenComplete((error, ex) -> {
            if (ex != null) {
                log.info(
                    "Device power action '{}' ACK completed exceptionally: {}",
                    stepName,
                    ex.getMessage()
                );
            } else if (
                error != null && error != MeshProtos.Routing.Error.NONE
            ) {
                log.warn(
                    "Device power action '{}' returned {}",
                    stepName,
                    error
                );
            } else {
                log.debug("Device power action '{}' ACK received", stepName);
            }
        });
    }

    private long getDevicePowerActionHandoffDelayMs(ConnectionType transport) {
        return transport == ConnectionType.BLE
            ? BLE_CONFIG_SAVE_REBOOT_HANDOFF_DELAY_MS
            : DEVICE_POWER_ACTION_HANDOFF_DELAY_MS;
    }

    private long getConfigSaveRebootHandoffDelayMs(ConnectionType transport) {
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
            ConfigSnapshotService.ConfigSnapshot snapshot =
                ConfigSnapshotService.createSnapshot(
                    kind,
                    extractOwnerInfo(root),
                    extractFixedPosition(root),
                    extractRingtone(root),
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
            Toast.show(
                Toast.Type.SUCCESS,
                switch (kind) {
                    case CONFIG -> I18n.t(
                        "settings.snapshot.saved.config",
                        outputFile.getName()
                    );
                    case TEMPLATE -> I18n.t(
                        "settings.snapshot.saved.template",
                        outputFile.getName()
                    );
                }
            );
        } catch (Exception e) {
            log.error("Snapshot export failed", e);
            ModalPane.showError(
                kind == ConfigSnapshotService.SnapshotKind.CONFIG
                    ? I18n.t("settings.snapshot.saveConfig.error.title")
                    : I18n.t("settings.snapshot.saveTemplate.error.title"),
                e.getMessage() != null
                    ? e.getMessage()
                    : I18n.t("settings.snapshot.save.errorFallback")
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
                    ? I18n.t("settings.snapshot.loadConfig.confirmTitle")
                    : I18n.t("settings.snapshot.loadTemplate.confirmTitle"),
                I18n.t("settings.snapshot.unsavedConfirm"),
                confirmed -> {
                    if (confirmed) {
                        importAction.run();
                    }
                }
            );
        } else {
            importAction.run();
        }
    }

    private void importSnapshot(
        File source,
        ConfigSnapshotService.SnapshotKind expectedKind
    ) {
        try {
            reloadConfigTree();
            TreeItem<ConfigTreeItem> root = currentEditorRoot();
            if (root == null) {
                throw new IllegalStateException(
                    I18n.t("settings.snapshot.editorNotLoaded")
                );
            }

            ConfigSnapshotService.ConfigSnapshot snapshot =
                ConfigSnapshotService.readSnapshot(source.toPath());
            if (snapshot.kind() != expectedKind) {
                throw new IllegalArgumentException(
                    I18n.t(
                        "settings.snapshot.wrongType",
                        expectedKind.extension()
                    )
                );
            }

            applySnapshotToEditor(snapshot);
            configTree.refresh();
            saveConfigBtn.setDisable(false);
            String fileKind = snapshotKindLabel(expectedKind);
            configStatusLabel.setText(
                I18n.t("settings.snapshot.loadedStatus", fileKind, source.getName())
            );
            Toast.show(
                Toast.Type.SUCCESS,
                I18n.t("settings.snapshot.loadedToast", fileKind, source.getName())
            );
        } catch (Exception e) {
            log.error("Snapshot import failed", e);
            ModalPane.showError(
                expectedKind == ConfigSnapshotService.SnapshotKind.CONFIG
                    ? I18n.t("settings.snapshot.loadConfig.error.title")
                    : I18n.t("settings.snapshot.loadTemplate.error.title"),
                e.getMessage() != null
                    ? e.getMessage()
                    : I18n.t("settings.snapshot.load.errorFallback")
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
            Toast.show(
                Toast.Type.WARNING,
                I18n.t("settings.status.waitDeviceConfigRead")
            );
            return false;
        }

        if (currentEditorRoot() == null) {
            Toast.show(
                Toast.Type.WARNING,
                I18n.t("settings.status.loadRadioConfigFirst")
            );
            return false;
        }
        return true;
    }

    private FileChooser createSnapshotFileChooser(
        ConfigSnapshotService.SnapshotKind kind,
        boolean saveMode
    ) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(
            switch (kind) {
                case CONFIG -> saveMode
                    ? I18n.t("settings.snapshot.fileChooser.saveConfig")
                    : I18n.t("settings.snapshot.fileChooser.loadConfig");
                case TEMPLATE -> saveMode
                    ? I18n.t("settings.snapshot.fileChooser.saveTemplate")
                    : I18n.t("settings.snapshot.fileChooser.loadTemplate");
            }
        );
        chooser
            .getExtensionFilters()
            .add(
                new FileChooser.ExtensionFilter(
                    kind == ConfigSnapshotService.SnapshotKind.CONFIG
                        ? I18n.t("settings.snapshot.fileFilter.config", kind.extension())
                        : I18n.t("settings.snapshot.fileFilter.template", kind.extension()),
                    "*." + kind.extension()
                )
            );
        if (saveMode) {
            chooser.setInitialFileName(buildSuggestedSnapshotName(kind));
        }
        return chooser;
    }

    private String buildSuggestedSnapshotName(
        ConfigSnapshotService.SnapshotKind kind
    ) {
        String baseName = "mesh-config";
        if (state != null) {
            NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
            if (myNode != null) {
                if (
                    myNode.getLongName() != null &&
                    !myNode.getLongName().isBlank()
                ) {
                    baseName = myNode.getLongName().trim();
                } else if (
                    myNode.getNodeId() != null && !myNode.getNodeId().isBlank()
                ) {
                    baseName = myNode.getNodeId().trim();
                }
            }
        }
        baseName = baseName
            .replaceAll("[\\\\/:*?\"<>|]+", "_")
            .replaceAll("\\s+", "_");
        if (baseName.isBlank()) {
            baseName = "mesh-config";
        }
        if (kind == ConfigSnapshotService.SnapshotKind.TEMPLATE) {
            baseName += "-template";
        }
        return baseName;
    }

    private File ensureSnapshotExtension(
        File file,
        ConfigSnapshotService.SnapshotKind kind
    ) {
        String expectedSuffix = "." + kind.extension();
        String fileName = file.getName();
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        String duplicateSuffix = expectedSuffix + expectedSuffix;

        if (lowerName.endsWith(duplicateSuffix)) {
            fileName = fileName.substring(
                0,
                fileName.length() - expectedSuffix.length()
            );
            lowerName = fileName.toLowerCase(Locale.ROOT);
        }

        if (lowerName.endsWith(expectedSuffix)) {
            File parent = file.getParentFile();
            return parent != null
                ? new File(parent, fileName)
                : new File(fileName);
        }
        File parent = file.getParentFile();
        return parent != null
            ? new File(parent, fileName + expectedSuffix)
            : new File(fileName + expectedSuffix);
    }

    private Window getCurrentWindow() {
        return getScene() != null ? getScene().getWindow() : null;
    }

    private String snapshotKindLabel(ConfigSnapshotService.SnapshotKind kind) {
        return kind == ConfigSnapshotService.SnapshotKind.CONFIG
            ? I18n.t("settings.snapshot.kind.config")
            : I18n.t("settings.snapshot.kind.template");
    }

    private String devicePowerActionLabel(boolean reboot) {
        return reboot
            ? I18n.t("settings.devicePower.action.restart")
            : I18n.t("settings.devicePower.action.shutdown");
    }

    private String configSaveReconnectMessage(
        ConnectionType transport,
        int totalChanges
    ) {
        return transport == ConnectionType.BLE
            ? I18n.t("settings.config.status.sentSectionsBle", totalChanges)
            : I18n.t(
                  "settings.config.status.sentSectionsReconnect",
                  totalChanges
              );
    }

    private String errorDetail(Exception e) {
        return e.getMessage() != null
            ? e.getMessage()
            : I18n.t("settings.status.seeLog");
    }

    private TreeItem<ConfigTreeItem> currentEditorRoot() {
        return fullConfigRoot != null
            ? fullConfigRoot
            : configTree != null
                ? configTree.getRoot()
                : null;
    }

    private ConfigSnapshotService.OwnerInfo extractOwnerInfo(
        TreeItem<ConfigTreeItem> root
    ) {
        TreeItem<ConfigTreeItem> ownerSection = findTopLevelSection(
            root,
            OWNER_INFO_CONFIG_TYPE
        );
        if (ownerSection == null) {
            return null;
        }
        return new ConfigSnapshotService.OwnerInfo(
            stringValue(ownerSection, OWNER_LONG_NAME_FIELD),
            stringValue(ownerSection, OWNER_SHORT_NAME_FIELD),
            booleanValue(ownerSection, OWNER_IS_LICENSED_FIELD)
        );
    }

    private ConfigSnapshotService.FixedPosition extractFixedPosition(
        TreeItem<ConfigTreeItem> root
    ) {
        TreeItem<ConfigTreeItem> positionSection = findTopLevelSection(
            root,
            "fixed_position"
        );
        if (positionSection == null) {
            return null;
        }
        return new ConfigSnapshotService.FixedPosition(
            doubleValue(positionSection, "latitude"),
            doubleValue(positionSection, "longitude"),
            intValue(positionSection, "altitude")
        );
    }

    private String extractRingtone(TreeItem<ConfigTreeItem> root) {
        TreeItem<ConfigTreeItem> ringtoneSection = findTopLevelSection(
            root,
            RINGTONE_CONFIG_TYPE
        );
        if (ringtoneSection == null) {
            return null;
        }
        if (
            state != null &&
            !state.isRingtoneLoaded() &&
            !hasMoifiedFields(ringtoneSection)
        ) {
            return null;
        }
        return stringValue(ringtoneSection, RINGTONE_FIELD);
    }

    private List<ConfigProtos.Config> collectCurrentConfigMessages(
        TreeItem<ConfigTreeItem> root
    ) {
        TreeItem<ConfigTreeItem> configRoot = findTopLevelSection(
            root,
            "config"
        );
        List<ConfigProtos.Config> result = new ArrayList<>();
        if (configRoot == null) {
            return result;
        }

        for (TreeItem<ConfigTreeItem> section : configRoot.getChildren()) {
            ConfigTreeItem sectionData = section.getValue();
            if (sectionData == null) {
                continue;
            }
            ConfigProtos.Config original = findOriginalConfig(
                sectionData.getConfigVariantNumber()
            );
            if (original == null) {
                continue;
            }
            ConfigProtos.Config rebuilt = ProtobufTreeBuilder.rebuildConfig(
                section,
                original
            );
            if (rebuilt != null) {
                result.add(rebuilt);
            }
        }
        return result;
    }

    private List<
        ModuleConfigProtos.ModuleConfig
    > collectCurrentModuleConfigMessages(TreeItem<ConfigTreeItem> root) {
        TreeItem<ConfigTreeItem> moduleRoot = findTopLevelSection(
            root,
            "module_config"
        );
        List<ModuleConfigProtos.ModuleConfig> result = new ArrayList<>();
        if (moduleRoot == null) {
            return result;
        }

        for (TreeItem<ConfigTreeItem> section : moduleRoot.getChildren()) {
            ConfigTreeItem sectionData = section.getValue();
            if (sectionData == null) {
                continue;
            }
            ModuleConfigProtos.ModuleConfig original = findOriginalModuleConfig(
                sectionData.getConfigVariantNumber()
            );
            if (original == null) {
                continue;
            }
            ModuleConfigProtos.ModuleConfig rebuilt =
                ProtobufTreeBuilder.rebuildModuleConfig(section, original);
            if (rebuilt != null) {
                result.add(rebuilt);
            }
        }
        return result;
    }

    private void applySnapshotToEditor(
        ConfigSnapshotService.ConfigSnapshot snapshot
    ) {
        TreeItem<ConfigTreeItem> root = currentEditorRoot();
        if (root == null) {
            return;
        }

        if (configSearchField != null) {
            configSearchField.clear();
        }

        applyOwnerInfo(snapshot.ownerInfo(), root);
        applyFixedPosition(snapshot.fixedPosition(), root);
        applyRingtone(snapshot.ringtone(), root);
        applyConfigSnapshot(snapshot.configs(), root);
        applyModuleConfigSnapshot(snapshot.moduleConfigs(), root);
        applyChannelSnapshot(snapshot.channels());
    }

    private void applyOwnerInfo(
        ConfigSnapshotService.OwnerInfo ownerInfo,
        TreeItem<ConfigTreeItem> root
    ) {
        if (ownerInfo == null) {
            return;
        }
        TreeItem<ConfigTreeItem> ownerSection = findTopLevelSection(
            root,
            OWNER_INFO_CONFIG_TYPE
        );
        if (ownerSection == null) {
            return;
        }
        setTreeFieldValue(
            ownerSection,
            OWNER_LONG_NAME_FIELD,
            ownerInfo.longName()
        );
        setTreeFieldValue(
            ownerSection,
            OWNER_SHORT_NAME_FIELD,
            ownerInfo.shortName()
        );
        setTreeFieldValue(
            ownerSection,
            OWNER_IS_LICENSED_FIELD,
            ownerInfo.isLicensed()
        );
    }

    private void applyFixedPosition(
        ConfigSnapshotService.FixedPosition fixedPosition,
        TreeItem<ConfigTreeItem> root
    ) {
        if (fixedPosition == null) {
            return;
        }
        TreeItem<ConfigTreeItem> positionSection = findTopLevelSection(
            root,
            "fixed_position"
        );
        if (positionSection == null) {
            return;
        }
        setTreeFieldValue(
            positionSection,
            "latitude",
            fixedPosition.latitude()
        );
        setTreeFieldValue(
            positionSection,
            "longitude",
            fixedPosition.longitude()
        );
        setTreeFieldValue(
            positionSection,
            "altitude",
            fixedPosition.altitude()
        );
    }

    private void applyRingtone(String ringtone, TreeItem<ConfigTreeItem> root) {
        if (ringtone == null) {
            return;
        }
        TreeItem<ConfigTreeItem> ringtoneSection = findTopLevelSection(
            root,
            RINGTONE_CONFIG_TYPE
        );
        if (ringtoneSection == null) {
            return;
        }
        setTreeFieldValue(ringtoneSection, RINGTONE_FIELD, ringtone);
    }

    private void applyConfigSnapshot(
        List<com.google.gson.JsonObject> configs,
        TreeItem<ConfigTreeItem> root
    ) {
        TreeItem<ConfigTreeItem> configRoot = findTopLevelSection(
            root,
            "config"
        );
        if (configRoot == null) {
            return;
        }

        for (com.google.gson.JsonObject configJson : configs) {
            String variantField =
                ConfigSnapshotService.detectActiveVariantField(configJson);
            if (variantField == null) {
                continue;
            }
            int variantNumber = resolveVariantNumber(
                ConfigProtos.Config.getDescriptor().findFieldByName(
                    variantField
                )
            );
            if (variantNumber < 0) {
                continue;
            }
            ConfigProtos.Config baseConfig = findOriginalConfig(variantNumber);
            if (baseConfig == null) {
                baseConfig = ConfigProtos.Config.getDefaultInstance();
            }
            ConfigProtos.Config mergedConfig =
                ConfigSnapshotService.mergeJsonIntoMessage(
                    baseConfig,
                    configJson
                );
            TreeItem<ConfigTreeItem> section = findSectionByVariant(
                configRoot,
                variantNumber
            );
            if (section != null) {
                var payload = getActiveConfigPayload(mergedConfig);
                if (payload != null) {
                    ProtobufTreeBuilder.applyMessageToTree(section, payload);
                }
            }
        }
    }

    private void applyModuleConfigSnapshot(
        List<com.google.gson.JsonObject> moduleConfigs,
        TreeItem<ConfigTreeItem> root
    ) {
        TreeItem<ConfigTreeItem> moduleRoot = findTopLevelSection(
            root,
            "module_config"
        );
        if (moduleRoot == null) {
            return;
        }

        for (com.google.gson.JsonObject moduleJson : moduleConfigs) {
            String variantField =
                ConfigSnapshotService.detectActiveVariantField(moduleJson);
            if (variantField == null) {
                continue;
            }
            int variantNumber = resolveVariantNumber(
                ModuleConfigProtos.ModuleConfig.getDescriptor().findFieldByName(
                    variantField
                )
            );
            if (variantNumber < 0) {
                continue;
            }
            ModuleConfigProtos.ModuleConfig baseConfig =
                findOriginalModuleConfig(variantNumber);
            if (baseConfig == null) {
                baseConfig =
                    ModuleConfigProtos.ModuleConfig.getDefaultInstance();
            }
            ModuleConfigProtos.ModuleConfig mergedConfig =
                ConfigSnapshotService.mergeJsonIntoMessage(
                    baseConfig,
                    moduleJson
                );
            TreeItem<ConfigTreeItem> section = findSectionByVariant(
                moduleRoot,
                variantNumber
            );
            if (section != null) {
                var payload = getActiveModulePayload(mergedConfig);
                if (payload != null) {
                    ProtobufTreeBuilder.applyMessageToTree(section, payload);
                }
            }
        }
    }

    private void applyChannelSnapshot(
        List<com.google.gson.JsonObject> channelPatches
    ) {
        if (channelPatches == null || channelPatches.isEmpty()) {
            return;
        }

        List<ChannelProtos.Channel> importedChannels = new ArrayList<>();
        for (com.google.gson.JsonObject channelJson : channelPatches) {
            if (!channelJson.has("index")) {
                continue;
            }
            int channelIndex = channelJson.get("index").getAsInt();
            ChannelProtos.Channel baseChannel = findChannelByIndex(
                originalChannels,
                channelIndex
            );
            if (baseChannel == null) {
                baseChannel = disabledChannel(channelIndex);
            }
            importedChannels.add(
                ConfigSnapshotService.mergeJsonIntoMessage(
                    baseChannel,
                    channelJson
                )
            );
        }

        importedChannels.sort(
            Comparator.comparingInt(ChannelProtos.Channel::getIndex)
        );
        workingChannels = importedChannels;
    }

    private boolean hasPendingEditorChanges() {
        TreeItem<ConfigTreeItem> root = currentEditorRoot();
        return (
            (root != null && hasMoifiedFields(root)) ||
            !collectModifiedChannels().isEmpty()
        );
    }

    private List<ChannelProtos.Channel> collectModifiedChannels() {
        List<ChannelProtos.Channel> targetChannels =
            getWorkingChannelsSnapshot();
        TreeSet<Integer> allIndexes = new TreeSet<>();
        for (ChannelProtos.Channel channel : originalChannels) {
            allIndexes.add(channel.getIndex());
        }
        for (ChannelProtos.Channel channel : targetChannels) {
            allIndexes.add(channel.getIndex());
        }

        List<ChannelProtos.Channel> modified = new ArrayList<>();
        for (Integer index : allIndexes) {
            ChannelProtos.Channel original = findChannelByIndex(
                originalChannels,
                index
            );
            ChannelProtos.Channel target = findChannelByIndex(
                targetChannels,
                index
            );
            ChannelProtos.Channel originalNormalized =
                original != null ? original : disabledChannel(index);
            ChannelProtos.Channel targetNormalized =
                target != null ? target : disabledChannel(index);
            if (!originalNormalized.equals(targetNormalized)) {
                modified.add(targetNormalized);
            }
        }
        modified.sort(Comparator.comparingInt(ChannelProtos.Channel::getIndex));
        return modified;
    }

    private List<ChannelProtos.Channel> getWorkingChannelsSnapshot() {
        List<ChannelProtos.Channel> source = !workingChannels.isEmpty()
            ? workingChannels
            : originalChannels;
        return new ArrayList<>(source);
    }

    private TreeItem<ConfigTreeItem> findTopLevelSection(
        TreeItem<ConfigTreeItem> root,
        String configType
    ) {
        for (TreeItem<ConfigTreeItem> child : root.getChildren()) {
            ConfigTreeItem data = child.getValue();
            if (data != null && configType.equals(data.getConfigType())) {
                return child;
            }
        }
        return null;
    }

    private TreeItem<ConfigTreeItem> findSectionByVariant(
        TreeItem<ConfigTreeItem> sectionRoot,
        int variantNumber
    ) {
        for (TreeItem<ConfigTreeItem> child : sectionRoot.getChildren()) {
            ConfigTreeItem data = child.getValue();
            if (
                data != null && data.getConfigVariantNumber() == variantNumber
            ) {
                return child;
            }
        }
        return null;
    }

    private void setTreeFieldValue(
        TreeItem<ConfigTreeItem> section,
        String fieldName,
        Object value
    ) {
        for (TreeItem<ConfigTreeItem> child : section.getChildren()) {
            ConfigTreeItem data = child.getValue();
            if (data != null && fieldName.equals(data.getFieldName())) {
                data.setValue(value);
                return;
            }
        }
    }

    private String stringValue(
        TreeItem<ConfigTreeItem> section,
        String fieldName
    ) {
        Object value = findTreeFieldValue(section, fieldName);
        return value != null ? value.toString() : "";
    }

    private double doubleValue(
        TreeItem<ConfigTreeItem> section,
        String fieldName
    ) {
        Object value = findTreeFieldValue(section, fieldName);
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private int intValue(TreeItem<ConfigTreeItem> section, String fieldName) {
        Object value = findTreeFieldValue(section, fieldName);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private boolean booleanValue(
        TreeItem<ConfigTreeItem> section,
        String fieldName
    ) {
        Object value = findTreeFieldValue(section, fieldName);
        return value instanceof Boolean bool
            ? bool
            : Boolean.parseBoolean(String.valueOf(value));
    }

    private Object findTreeFieldValue(
        TreeItem<ConfigTreeItem> section,
        String fieldName
    ) {
        for (TreeItem<ConfigTreeItem> child : section.getChildren()) {
            ConfigTreeItem data = child.getValue();
            if (data != null && fieldName.equals(data.getFieldName())) {
                return data.getValue();
            }
        }
        return null;
    }

    private int resolveVariantNumber(
        com.google.protobuf.Descriptors.FieldDescriptor fieldDescriptor
    ) {
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

    private ModuleConfigProtos.ModuleConfig findOriginalModuleConfig(
        int variantNumber
    ) {
        for (ModuleConfigProtos.ModuleConfig config : originalModuleConfigs) {
            if (getActiveModuleOneofFieldNumber(config) == variantNumber) {
                return config;
            }
        }
        return null;
    }

    private ChannelProtos.Channel findChannelByIndex(
        List<ChannelProtos.Channel> channels,
        int index
    ) {
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

    private com.google.protobuf.Message getActiveConfigPayload(
        ConfigProtos.Config config
    ) {
        var oneof = config
            .getDescriptorForType()
            .getOneofs()
            .stream()
            .filter(o -> "payload_variant".equals(o.getName()))
            .findFirst()
            .orElse(null);
        if (oneof == null) {
            return null;
        }
        var field = config.getOneofFieldDescriptor(oneof);
        return field != null
            ? (com.google.protobuf.Message) config.getField(field)
            : null;
    }

    private com.google.protobuf.Message getActiveModulePayload(
        ModuleConfigProtos.ModuleConfig config
    ) {
        var oneof = config
            .getDescriptorForType()
            .getOneofs()
            .stream()
            .filter(o -> "payload_variant".equals(o.getName()))
            .findFirst()
            .orElse(null);
        if (oneof == null) {
            return null;
        }
        var field = config.getOneofFieldDescriptor(oneof);
        return field != null
            ? (com.google.protobuf.Message) config.getField(field)
            : null;
    }

    /**
     * Loads configuration from DeviceState and builds the tree.
     */
    private void reloadConfigTree() {
        refreshConnection();

        boolean meshtasticConnected = state != null && handler != null;
        boolean meshCoreConnected =
            state != null && meshCoreCompanionState != null;
        boolean connected = meshtasticConnected || meshCoreConnected;
        setDevicePowerButtonsDisabled(!meshtasticConnected);
        setSyncDateTimeButtonDisabled(!meshtasticConnected);

        if (!connected) {
            maybeFinishConfigSaveNavigationBlockAfterReconnect(null, false);
            clearConfigContext();
            configStatusLabel.setText(I18n.t("settings.status.noRadio"));
            saveConfigBtn.setDisable(true);
            return;
        }

        if (meshCoreConnected && !meshtasticConnected) {
            showMeshCoreSettingsTree(meshCoreCompanionState);
            return;
        }

        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
        ConnectionEntry activeEntry = findActiveConnectionEntry();
        boolean configExchangeInProgress = isConfigExchangeInProgress(
            activeEntry
        );
        maybeFinishConfigSaveNavigationBlockAfterReconnect(
            activeEntry,
            configExchangeInProgress
        );

        // Keep original protobuf objects for rebuilding.
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

        // Root item, hidden in the UI.
        TreeItem<ConfigTreeItem> root = new TreeItem<>(
            new ConfigTreeItem(I18n.t("settings.config.root"), null, 0)
        );
        root.setExpanded(true);

        // Virtual section: device name.
        TreeItem<ConfigTreeItem> ownerSection = new TreeItem<>(
            new ConfigTreeItem(I18n.t("settings.config.ownerInfo"), OWNER_INFO_CONFIG_TYPE, 0)
        );
        MeshProtos.User ownerInfo = state.getOwnerInfo();
        String longName = resolveOwnerLongName(ownerInfo, myNode);
        ownerSection
            .getChildren()
            .add(
                new TreeItem<>(
                    new ConfigTreeItem(
                        I18n.t("settings.config.ownerLongName"),
                        OWNER_LONG_NAME_FIELD,
                        longName,
                        String.class,
                        null,
                        null,
                        OWNER_INFO_CONFIG_TYPE,
                        0
                    )
                )
            );
        String shortName = resolveOwnerShortName(ownerInfo, myNode);
        ownerSection
            .getChildren()
            .add(
                new TreeItem<>(
                    new ConfigTreeItem(
                        I18n.t("settings.config.ownerShortName"),
                        OWNER_SHORT_NAME_FIELD,
                        shortName,
                        String.class,
                        null,
                        null,
                        OWNER_INFO_CONFIG_TYPE,
                        0
                    )
                )
            );
        ownerSection
            .getChildren()
            .add(
                new TreeItem<>(
                    new ConfigTreeItem(
                        I18n.t("settings.config.licensedOperator"),
                        OWNER_IS_LICENSED_FIELD,
                        resolveOwnerLicensed(ownerInfo, myNode),
                        Boolean.class,
                        null,
                        null,
                        OWNER_INFO_CONFIG_TYPE,
                        0
                    )
                )
            );
        root.getChildren().add(ownerSection);

        // Virtual section: fixed position.
        TreeItem<ConfigTreeItem> posSection = new TreeItem<>(
            new ConfigTreeItem(I18n.t("settings.config.fixedPosition"), "fixed_position", 0)
        );
        double lat = myNode != null ? myNode.getLatitude() : 0;
        double lon = myNode != null ? myNode.getLongitude() : 0;
        int alt = myNode != null ? myNode.getAltitude() : 0;
        posSection
            .getChildren()
            .add(
                new TreeItem<>(
                    new ConfigTreeItem(
                        I18n.t("settings.config.latitude"),
                        "latitude",
                        lat,
                        Double.class,
                        null,
                        null,
                        "fixed_position",
                        0
                    )
                )
            );
        posSection
            .getChildren()
            .add(
                new TreeItem<>(
                    new ConfigTreeItem(
                        I18n.t("settings.config.longitude"),
                        "longitude",
                        lon,
                        Double.class,
                        null,
                        null,
                        "fixed_position",
                        0
                    )
                )
            );
        posSection
            .getChildren()
            .add(
                new TreeItem<>(
                    new ConfigTreeItem(
                        I18n.t("settings.config.altitudeMeters"),
                        "altitude",
                        alt,
                        Integer.class,
                        null,
                        null,
                        "fixed_position",
                        0
                    )
                )
            );
        root.getChildren().add(posSection);

        // Virtual section: RTTTL ringtone for External Notification.
        TreeItem<ConfigTreeItem> ringtoneSection = new TreeItem<>(
            new ConfigTreeItem(I18n.t("settings.config.ringtone"), RINGTONE_CONFIG_TYPE, 0)
        );
        ringtoneSection
            .getChildren()
            .add(
                new TreeItem<>(
                    new ConfigTreeItem(
                        "RTTTL",
                        RINGTONE_FIELD,
                        state.isRingtoneLoaded() ? state.getRingtone() : "",
                        String.class,
                        null,
                        null,
                        RINGTONE_CONFIG_TYPE,
                        0
                    )
                )
            );
        root.getChildren().add(ringtoneSection);

        // Device configuration.
        if (!originalConfigs.isEmpty()) {
            TreeItem<ConfigTreeItem> configRoot =
                ProtobufTreeBuilder.buildConfigTree(originalConfigs);
            root.getChildren().add(configRoot);
        }

        // Module configuration.
        if (!originalModuleConfigs.isEmpty()) {
            TreeItem<ConfigTreeItem> moduleRoot =
                ProtobufTreeBuilder.buildModuleConfigTree(
                    originalModuleConfigs
                );
            root.getChildren().add(moduleRoot);
        }

        fullConfigRoot = root;
        configTree.setRoot(root);
        configSearchField.clear();

        if (configExchangeInProgress) {
            watchConfigExchangeCompletion(activeEntry);
            configStatusLabel.setText(
                I18n.t("settings.config.status.loadingDevice")
            );
            saveConfigBtn.setDisable(true);
            setSyncDateTimeButtonDisabled(true);
        } else if (
            originalConfigs.isEmpty() && originalModuleConfigs.isEmpty()
        ) {
            configStatusLabel.setText(I18n.t("settings.config.status.notReceived"));
            saveConfigBtn.setDisable(true);
            setSyncDateTimeButtonDisabled(true);
        } else {
            requestRingtoneIfNeeded(state, handler);
            saveConfigBtn.setDisable(false);
            setSyncDateTimeButtonDisabled(findLoadedDeviceConfig() == null);
            int totalFields = countFields(root);
            configStatusLabel.setText(
                I18n.t(
                    "settings.config.status.loaded",
                    originalConfigs.size() + originalModuleConfigs.size(),
                    totalFields
                )
            );
        }
    }

    /**
     * Builds the read-only settings tree for MeshCore Companion Protocol.
     * <p>
     * MeshCore Companion does not expose the Meshtastic Admin protobuf
     * configuration, so this tab shows available metadata, radio parameters,
     * storage, and channels while disabling Meshtastic configuration save/reboot actions.
     *
     * @param meshCoreState MeshCore Companion runtime state
     */
    private void showMeshCoreSettingsTree(
        MeshCoreCompanionState meshCoreState
    ) {
        originalConfigs = new ArrayList<>();
        originalModuleConfigs = new ArrayList<>();
        originalChannels = new ArrayList<>(
            state != null ? state.getChannels() : List.of()
        );
        workingChannels = new ArrayList<>(originalChannels);

        TreeItem<ConfigTreeItem> root = new TreeItem<>(
            new ConfigTreeItem(I18n.t("settings.config.root"), null, 0)
        );
        root.setExpanded(true);

        TreeItem<ConfigTreeItem> deviceSection = section(
            I18n.t("settings.meshCore.section.device"),
            "meshcore_device"
        );
        addValue(
            deviceSection,
            I18n.t("settings.meshCore.field.name"),
            "device_name",
            valueOrDash(meshCoreState.getDeviceName()),
            String.class
        );
        addValue(
            deviceSection,
            I18n.t("settings.meshCore.field.ownerId"),
            "owner_id",
            valueOrDash(meshCoreState.getOwnerId()),
            String.class
        );
        addValue(
            deviceSection,
            I18n.t("settings.meshCore.field.publicKey"),
            "public_key",
            valueOrDash(meshCoreState.getPublicKeyHex()),
            String.class
        );
        addValue(
            deviceSection,
            I18n.t("settings.meshCore.field.model"),
            "model",
            valueOrDash(meshCoreState.getModel()),
            String.class
        );
        addValue(
            deviceSection,
            I18n.t("settings.meshCore.field.firmware"),
            "firmware_version",
            valueOrDash(meshCoreState.getFirmwareVersion()),
            String.class
        );
        addValue(
            deviceSection,
            I18n.t("settings.meshCore.field.build"),
            "firmware_build",
            valueOrDash(meshCoreState.getFirmwareBuild()),
            String.class
        );
        addValue(
            deviceSection,
            I18n.t("settings.meshCore.field.protocolVersion"),
            "protocol_version",
            valueOrDash(meshCoreState.getFirmwareProtocolVersion()),
            String.class
        );
        addValue(
            deviceSection,
            I18n.t("settings.meshCore.field.blePin"),
            "ble_pin",
            valueOrDash(meshCoreState.getBlePin()),
            String.class
        );
        root.getChildren().add(deviceSection);

        TreeItem<ConfigTreeItem> radioSection = section(
            I18n.t("settings.meshCore.section.radio"),
            "meshcore_radio"
        );
        addValue(
            radioSection,
            I18n.t("settings.meshCore.field.txPower"),
            "tx_power",
            valueOrDash(meshCoreState.getTxPowerDbm()),
            String.class
        );
        addValue(
            radioSection,
            I18n.t("settings.meshCore.field.maxTxPower"),
            "max_tx_power",
            valueOrDash(meshCoreState.getMaxTxPowerDbm()),
            String.class
        );
        addValue(
            radioSection,
            I18n.t("settings.meshCore.field.frequency"),
            "frequency",
            valueOrDash(meshCoreState.getRadioFrequencyKhz()),
            String.class
        );
        addValue(
            radioSection,
            I18n.t("settings.meshCore.field.bandwidth"),
            "bandwidth",
            valueOrDash(meshCoreState.getRadioBandwidthKhz()),
            String.class
        );
        addValue(
            radioSection,
            I18n.t("settings.meshCore.field.spreadingFactor"),
            "spreading_factor",
            valueOrDash(meshCoreState.getRadioSpreadingFactor()),
            String.class
        );
        addValue(
            radioSection,
            I18n.t("settings.meshCore.field.codingRate"),
            "coding_rate",
            valueOrDash(meshCoreState.getRadioCodingRate()),
            String.class
        );
        root.getChildren().add(radioSection);

        TreeItem<ConfigTreeItem> limitsSection = section(
            I18n.t("settings.meshCore.section.data"),
            "meshcore_limits"
        );
        addValue(
            limitsSection,
            I18n.t("settings.meshCore.field.maxContacts"),
            "max_contacts",
            valueOrDash(meshCoreState.getMaxContacts()),
            String.class
        );
        addValue(
            limitsSection,
            I18n.t("settings.meshCore.field.contactCount"),
            "contact_count",
            valueOrDash(meshCoreState.getContactCount()),
            String.class
        );
        addValue(
            limitsSection,
            I18n.t("settings.meshCore.field.maxChannels"),
            "max_channels",
            valueOrDash(meshCoreState.getMaxChannels()),
            String.class
        );
        addValue(
            limitsSection,
            I18n.t("settings.meshCore.field.battery"),
            "battery_mv",
            valueOrDash(meshCoreState.getBatteryMillivolts()),
            String.class
        );
        addValue(
            limitsSection,
            I18n.t("settings.meshCore.field.storageUsed"),
            "storage_used",
            valueOrDash(meshCoreState.getUsedStorageKb()),
            String.class
        );
        addValue(
            limitsSection,
            I18n.t("settings.meshCore.field.storageTotal"),
            "storage_total",
            valueOrDash(meshCoreState.getTotalStorageKb()),
            String.class
        );
        addValue(
            limitsSection,
            I18n.t("settings.meshCore.field.lastError"),
            "last_error",
            valueOrDash(meshCoreState.getLastError()),
            String.class
        );
        root.getChildren().add(limitsSection);

        TreeItem<ConfigTreeItem> channelsSection = section(
            I18n.t("settings.meshCore.section.channels"),
            "meshcore_channels"
        );
        for (ChannelProtos.Channel channel : state.getChannels()) {
            String role = channel.getRole().name();
            String name = channel.hasSettings()
                ? channel.getSettings().getName()
                : "";
            addValue(
                channelsSection,
                I18n.t("settings.meshCore.channel", channel.getIndex()),
                "channel_" + channel.getIndex(),
                (name == null || name.isBlank()
                    ? I18n.t("settings.meshCore.channelFallback", channel.getIndex())
                    : name) +
                    " (" +
                    role +
                    ")",
                String.class
            );
        }
        root.getChildren().add(channelsSection);

        fullConfigRoot = root;
        configTree.setRoot(root);
        configSearchField.clear();
        saveConfigBtn.setDisable(true);
        setSyncDateTimeButtonDisabled(true);
        configStatusLabel.setText(
            I18n.t("settings.meshCore.status.readOnly")
        );
    }

    private TreeItem<ConfigTreeItem> section(String name, String configType) {
        return new TreeItem<>(new ConfigTreeItem(name, configType, 0));
    }

    private void addValue(
        TreeItem<ConfigTreeItem> section,
        String name,
        String fieldName,
        Object value,
        Class<?> valueType
    ) {
        section
            .getChildren()
            .add(
                new TreeItem<>(
                    new ConfigTreeItem(
                        name,
                        fieldName,
                        value,
                        valueType,
                        null,
                        null,
                        section.getValue().getConfigType(),
                        0
                    )
                )
            );
    }

    private String valueOrDash(Object value) {
        if (value == null) {
            return "—";
        }
        String text = String.valueOf(value);
        return text.isBlank() ? "—" : text;
    }

    private String resolveOwnerLongName(
        MeshProtos.User ownerInfo,
        NodeData myNode
    ) {
        if (
            ownerInfo != null &&
            ownerInfo.getLongName() != null &&
            !ownerInfo.getLongName().isEmpty()
        ) {
            return ownerInfo.getLongName();
        }
        return myNode != null && myNode.getLongName() != null
            ? myNode.getLongName()
            : "";
    }

    private String resolveOwnerShortName(
        MeshProtos.User ownerInfo,
        NodeData myNode
    ) {
        if (
            ownerInfo != null &&
            ownerInfo.getShortName() != null &&
            !ownerInfo.getShortName().isEmpty()
        ) {
            return ownerInfo.getShortName();
        }
        return myNode != null && myNode.getShortName() != null
            ? myNode.getShortName()
            : "";
    }

    private boolean resolveOwnerLicensed(
        MeshProtos.User ownerInfo,
        NodeData myNode
    ) {
        if (ownerInfo != null) {
            return ownerInfo.getIsLicensed();
        }
        return myNode != null && myNode.isLicensed();
    }

    /**
     * Counts editable fields in the tree.
     */
    private int countFields(TreeItem<ConfigTreeItem> item) {
        int count = 0;
        if (item.getValue() != null && item.getValue().isEditable()) {
            count++;
        }
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            count += countFields(child);
        }
        return count;
    }

    /**
     * Filters the configuration tree by the search string.
     * Shows only parameters whose name or fieldName contains the query, along
     * with their parent categories.
     */
    private void filterConfigTree(String query) {
        if (fullConfigRoot == null) {
            return;
        }

        if (query == null || query.isBlank()) {
            configTree.setRoot(fullConfigRoot);
            return;
        }

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        TreeItem<ConfigTreeItem> filteredRoot = new TreeItem<>(
            fullConfigRoot.getValue()
        );
        filteredRoot.setExpanded(true);

        for (TreeItem<ConfigTreeItem> topLevel : fullConfigRoot.getChildren()) {
            TreeItem<ConfigTreeItem> filteredTopLevel = filterTreeItem(
                topLevel,
                lowerQuery
            );
            if (filteredTopLevel != null) {
                filteredRoot.getChildren().add(filteredTopLevel);
            }
        }

        configTree.setRoot(filteredRoot);
    }

    /**
     * Recursively filters a tree node and returns a copy with matching descendants, or null.
     */
    private TreeItem<ConfigTreeItem> filterTreeItem(
        TreeItem<ConfigTreeItem> item,
        String lowerQuery
    ) {
        ConfigTreeItem data = item.getValue();
        boolean selfMatches = false;

        if (data != null && !data.isCategory()) {
            String name =
                data.getName() != null
                    ? data.getName().toLowerCase(Locale.ROOT)
                    : "";
            String fieldName =
                data.getFieldName() != null
                    ? data.getFieldName().toLowerCase(Locale.ROOT)
                    : "";
            selfMatches =
                name.contains(lowerQuery) || fieldName.contains(lowerQuery);
        }

            // Category name matched; show the whole section.
        if (data != null && data.isCategory()) {
            String name =
                data.getName() != null
                    ? data.getName().toLowerCase(Locale.ROOT)
                    : "";
            if (name.contains(lowerQuery)) {
            // Show the full section.
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

        // Check children.
        List<TreeItem<ConfigTreeItem>> matchedChildren = new ArrayList<>();
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            TreeItem<ConfigTreeItem> filtered = filterTreeItem(
                child,
                lowerQuery
            );
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
     * Deep-copies a tree node so a full section can be shown when its category matches.
     */
    private TreeItem<ConfigTreeItem> copyTreeItem(
        TreeItem<ConfigTreeItem> item
    ) {
        TreeItem<ConfigTreeItem> copy = new TreeItem<>(item.getValue());
        copy.setExpanded(item.isExpanded());
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            copy.getChildren().add(copyTreeItem(child));
        }
        return copy;
    }

    /**
     * Saves changed settings to the device.
     * Uses begin_edit_settings / commit_edit_settings for batched delivery.
     */
    private void onSaveConfig() {
        DeviceState actionState = state;
        ProtocolHandler actionHandler = handler;
        if (actionState == null || actionHandler == null) {
            configStatusLabel.setText(I18n.t("settings.status.noRadio"));
            return;
        }
        ConnectionEntry activeEntry = findActiveConnectionEntry();
        if (isConfigExchangeInProgress(activeEntry)) {
            watchConfigExchangeCompletion(activeEntry);
            configStatusLabel.setText(
                I18n.t("settings.status.waitConfigRead")
            );
            saveConfigBtn.setDisable(true);
            return;
        }

        TreeItem<ConfigTreeItem> root =
            fullConfigRoot != null ? fullConfigRoot : configTree.getRoot();
        if (root == null) {
            return;
        }

        // Collect virtual admin changes.
        boolean ownerModified = false;
        String newLongName = null;
        String newShortName = null;
        boolean newIsLicensed = resolveOwnerLicensed(
            actionState.getOwnerInfo(),
            actionState.getNodeDb().get(actionState.getMyNodeNum())
        );
        boolean positionModified = false;
        double newLat = 0;
        double newLon = 0;
        int newAlt = 0;
        boolean ringtoneModified = false;
        String newRingtone = "";

        // Collect protobuf changes.
        List<ConfigProtos.Config> modifiedConfigs = new ArrayList<>();
        List<ModuleConfigProtos.ModuleConfig> modifiedModuleConfigs =
            new ArrayList<>();

        for (TreeItem<ConfigTreeItem> topLevel : root.getChildren()) {
            ConfigTreeItem topData = topLevel.getValue();
            if (topData == null) {
                continue;
            }

        // Virtual section: device name.
            if (
                OWNER_INFO_CONFIG_TYPE.equals(topData.getConfigType()) &&
                hasMoifiedFields(topLevel)
            ) {
                ownerModified = true;
                for (TreeItem<ConfigTreeItem> child : topLevel.getChildren()) {
                    ConfigTreeItem ci = child.getValue();
                    if (OWNER_LONG_NAME_FIELD.equals(ci.getFieldName())) {
                        newLongName = ci.getValue().toString();
                    }
                    if (OWNER_SHORT_NAME_FIELD.equals(ci.getFieldName())) {
                        newShortName = ci.getValue().toString();
                    }
                    if (OWNER_IS_LICENSED_FIELD.equals(ci.getFieldName())) {
                        newIsLicensed = Boolean.TRUE.equals(ci.getValue());
                    }
                }
            }

        // Virtual section: fixed position.
            if (
                "fixed_position".equals(topData.getConfigType()) &&
                hasMoifiedFields(topLevel)
            ) {
                positionModified = true;
                for (TreeItem<ConfigTreeItem> child : topLevel.getChildren()) {
                    ConfigTreeItem ci = child.getValue();
                    if ("latitude".equals(ci.getFieldName())) {
                        newLat = ((Number) ci.getValue()).doubleValue();
                    }
                    if ("longitude".equals(ci.getFieldName())) {
                        newLon = ((Number) ci.getValue()).doubleValue();
                    }
                    if ("altitude".equals(ci.getFieldName())) {
                        newAlt = ((Number) ci.getValue()).intValue();
                    }
                }
            }

        // Virtual section: ringtone.
            if (
                RINGTONE_CONFIG_TYPE.equals(topData.getConfigType()) &&
                hasMoifiedFields(topLevel)
            ) {
                ringtoneModified = true;
                newRingtone = stringValue(topLevel, RINGTONE_FIELD);
            }

        // Protobuf sections: device configuration / module configuration.
            if (
                "config".equals(topData.getConfigType()) ||
                "module_config".equals(topData.getConfigType())
            ) {
                for (TreeItem<
                    ConfigTreeItem
                > section : topLevel.getChildren()) {
                    if (!hasMoifiedFields(section)) {
                        continue;
                    }

                    ConfigTreeItem sectionData = section.getValue();
                    if ("config".equals(sectionData.getConfigType())) {
                        for (ConfigProtos.Config orig : originalConfigs) {
                            var oneofField = getActiveOneofFieldNumber(orig);
                            if (
                                oneofField ==
                                sectionData.getConfigVariantNumber()
                            ) {
                                ConfigProtos.Config rebuilt =
                                    ProtobufTreeBuilder.rebuildConfig(
                                        section,
                                        orig
                                    );
                                if (rebuilt != null) {
                                    modifiedConfigs.add(rebuilt);
                                }
                                break;
                            }
                        }
                    } else if (
                        "module_config".equals(sectionData.getConfigType())
                    ) {
                        for (ModuleConfigProtos.ModuleConfig orig : originalModuleConfigs) {
                            var oneofField = getActiveModuleOneofFieldNumber(
                                orig
                            );
                            if (
                                oneofField ==
                                sectionData.getConfigVariantNumber()
                            ) {
                                ModuleConfigProtos.ModuleConfig rebuilt =
                                    ProtobufTreeBuilder.rebuildModuleConfig(
                                        section,
                                        orig
                                    );
                                if (rebuilt != null) {
                                    modifiedModuleConfigs.add(rebuilt);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }

        List<ChannelProtos.Channel> modifiedChannels =
            collectModifiedChannels();

        if (
            !ownerModified &&
            !positionModified &&
            !ringtoneModified &&
            modifiedConfigs.isEmpty() &&
            modifiedModuleConfigs.isEmpty() &&
            modifiedChannels.isEmpty()
        ) {
            configStatusLabel.setText(I18n.t("settings.config.status.noChanges"));
            return;
        }

        beginConfigSaveNavigationBlock(activeEntry);
        int totalChanges =
            modifiedConfigs.size() +
            modifiedModuleConfigs.size() +
            modifiedChannels.size() +
            (ownerModified ? 1 : 0) +
            (positionModified ? 1 : 0) +
            (ringtoneModified ? 1 : 0);
        saveConfigBtn.setDisable(true);
        configStatusLabel.setText(I18n.t("settings.status.requestSessionKey"));

        // Capture final values for the lambda.
        final boolean fOwnerModified = ownerModified;
        final String fLongName = newLongName;
        final String fShortName = newShortName;
        final boolean fIsLicensed = newIsLicensed;
        final boolean fPositionModified = positionModified;
        final double fLat = newLat;
        final double fLon = newLon;
        final int fAlt = newAlt;
        final boolean fRingtoneModified = ringtoneModified;
        final String fRingtone = newRingtone;

        // Request the session key, then send settings. The OwnerInfo listener and
        // timeout fallback run in parallel, so a single-shot guard is required:
        // repeating begin/set/commit after about five seconds breaks the save flow
        // on any transport if owner info already arrived before the timeout.
        AtomicBoolean saveDispatchStarted = new AtomicBoolean(false);
        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () ->
            Platform.runLater(() -> {
                actionState.removeOwnerInfoListener(listenerHolder[0]);
                if (saveDispatchStarted.compareAndSet(false, true)) {
                    sendConfigChanges(
                        activeEntry,
                        actionState,
                        actionHandler,
                        modifiedConfigs,
                        modifiedModuleConfigs,
                        modifiedChannels,
                        fOwnerModified,
                        fLongName,
                        fShortName,
                        fIsLicensed,
                        fPositionModified,
                        fLat,
                        fLon,
                        fAlt,
                        fRingtoneModified,
                        fRingtone,
                        totalChanges
                    );
                }
            });
        actionState.addOwnerInfoListener(listenerHolder[0]);

            // Timeout: send without a passkey.
        Thread timeoutThread = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                return;
            }
            Platform.runLater(() -> {
                actionState.removeOwnerInfoListener(listenerHolder[0]);
                if (saveDispatchStarted.compareAndSet(false, true)) {
                    configStatusLabel.setText(
                        I18n.t("settings.status.sendingWithoutSessionKey")
                    );
                    sendConfigChanges(
                        activeEntry,
                        actionState,
                        actionHandler,
                        modifiedConfigs,
                        modifiedModuleConfigs,
                        modifiedChannels,
                        fOwnerModified,
                        fLongName,
                        fShortName,
                        fIsLicensed,
                        fPositionModified,
                        fLat,
                        fLon,
                        fAlt,
                        fRingtoneModified,
                        fRingtone,
                        totalChanges
                    );
                }
            });
        }, "config-save-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        try {
            MessageService.requestSessionPasskey(actionHandler, actionState);
        } catch (RuntimeException e) {
            actionState.removeOwnerInfoListener(listenerHolder[0]);
            finishConfigSaveNavigationBlock();
            throw e;
        }
    }

    /**
     * Sends changed configuration to the device.
     * Virtual sections such as name and position are sent as separate admin
     * messages. Protobuf sections are wrapped in begin/commit edit.
     */
    private void sendConfigChanges(
        ConnectionEntry activeEntry,
        DeviceState actionState,
        ProtocolHandler actionHandler,
        List<ConfigProtos.Config> configs,
        List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
        List<ChannelProtos.Channel> channels,
        boolean ownerModified,
        String newLongName,
        String newShortName,
        boolean newIsLicensed,
        boolean positionModified,
        double newLat,
        double newLon,
        int newAlt,
        boolean ringtoneModified,
        String newRingtone,
        int totalChanges
    ) {
        configStatusLabel.setText(I18n.t("settings.config.status.sendingSettings"));
        ConnectionType activeTransport =
            activeEntry != null
                ? activeEntry.getEffectiveType()
                : ConnectionType.TCP;
        boolean hasPacketConfigChanges =
            !channels.isEmpty() ||
            !configs.isEmpty() ||
            !moduleConfigs.isEmpty();
        boolean requiresReconnect = requiresConfigSaveReconnect(
            ownerModified,
            configs,
            moduleConfigs
        );
        long reconnectHandoffGeneration =
            requiresReconnect && activeEntry != null
                ? ConnectionManager.getInstance().getConnectionGeneration(
                      activeEntry.getId()
                  )
                : -1;
        if (requiresReconnect && activeEntry != null) {
            ConnectionManager.getInstance().expectDeviceReboot(
                activeEntry.getId()
            );
            if (ownerModified) {
                markConfigSaveNavigationBlockAwaitingReconnect(activeEntry);
            }
        }

        // Send virtual sections directly.
        if (ownerModified && newLongName != null && newShortName != null) {
            MessageService.setOwnerInfo(
                actionHandler,
                actionState,
                newLongName,
                newShortName,
                newIsLicensed,
                actionState.getSessionPasskey()
            );
            MeshProtos.User currentOwnerInfo = actionState.getOwnerInfo();
            MeshProtos.User updatedOwnerInfo = (currentOwnerInfo != null
                ? currentOwnerInfo.toBuilder()
                : MeshProtos.User.newBuilder()
            )
                .setLongName(newLongName)
                .setShortName(newShortName)
                .setIsLicensed(newIsLicensed)
                .build();
            actionState.setOwnerInfo(updatedOwnerInfo);
            NodeData myNode = actionState
                .getNodeDb()
                .get(actionState.getMyNodeNum());
            if (myNode != null) {
                myNode.setLongName(newLongName);
                myNode.setShortName(newShortName);
                myNode.setLicensed(newIsLicensed);
                actionState.fireNodeUpdateListeners(actionState.getMyNodeNum());
            }
        }

        if (positionModified) {
            if (newLat == 0 && newLon == 0 && newAlt == 0) {
                MessageService.removeFixedPosition(actionHandler, actionState);
            } else {
                MessageService.setFixedPosition(
                    actionHandler,
                    actionState,
                    newLat,
                    newLon,
                    newAlt
                );
                actionState.setPendingFixedPosition(newLat, newLon, newAlt);
                NodeData myNode = actionState
                    .getNodeDb()
                    .get(actionState.getMyNodeNum());
                if (myNode != null) {
                    // Round-trip through int to show what the device will actually store
                    myNode.setLatitude(Math.round(newLat * 1e7) * 1e-7);
                    myNode.setLongitude(Math.round(newLon * 1e7) * 1e-7);
                    myNode.setAltitude(newAlt);
                    actionState.fireNodeUpdateListeners(
                        actionState.getMyNodeNum()
                    );
                }
            }
        }

        if (ringtoneModified) {
            String ringtone = newRingtone != null ? newRingtone : "";
            observeOptionalConfigSaveAck(
                MessageService.setRingtone(
                    actionHandler,
                    actionState,
                    ringtone
                ),
                "setRingtone"
            );
            actionState.setRingtone(ringtone);
        }

        // Protobuf sections usually go through begin/commit edit with delays
        // between messages. The exception below is a single-section BLE MQTT save:
        // some devices reboot/disconnect at set_module_config and never let commit
        // reach firmware.
        if (hasPacketConfigChanges) {
            List<Runnable> tasks = new ArrayList<>();
            AtomicBoolean saveFailed = new AtomicBoolean(false);
            AtomicBoolean saveCompletionAnnounced = new AtomicBoolean(false);
            boolean useImplicitBleModuleSave =
                shouldUseImplicitBleModuleSave(
                    activeTransport,
                    ownerModified,
                    positionModified,
                    configs,
                    moduleConfigs
                ) && channels.isEmpty();

            for (ChannelProtos.Channel channel : channels) {
                tasks.add(() -> {
                    String stepName = "setChannel/" + channel.getIndex();
                    log.info(
                        "Config save: setChannel index={} role={}",
                        channel.getIndex(),
                        channel.getRole()
                    );
                    waitForTransportRequiredConfigSaveAck(
                        activeTransport,
                        MessageService.setChannel(
                            actionHandler,
                            actionState,
                            channel,
                            actionState.getSessionPasskey()
                        ),
                        stepName
                    );
                    actionState.updateChannel(channel);
                });
            }

            if (useImplicitBleModuleSave) {
                ModuleConfigProtos.ModuleConfig mqttConfig = moduleConfigs.get(
                    0
                );
                tasks.add(() -> {
                    String stepName =
                        "setModuleConfig/" + mqttConfig.getPayloadVariantCase();
                    log.info(
                        "Config save: implicit BLE {} variant={} size={}",
                        stepName,
                        mqttConfig.getPayloadVariantCase(),
                        mqttConfig.getSerializedSize()
                    );
                    CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                        MessageService.setModuleConfig(
                            actionHandler,
                            actionState,
                            mqttConfig
                        );
                    observeDeferredConfigSaveAck(ackFuture, stepName);
                });
            } else if (requiresReconnect) {
                tasks.add(() -> {
                    log.info("Config save: beginEditSettings");
                    waitForTransportRequiredConfigSaveAck(
                        activeTransport,
                        MessageService.beginEditSettings(
                            actionHandler,
                            actionState
                        ),
                        "beginEditSettings"
                    );
                });
                int totalMutatingSteps = configs.size() + moduleConfigs.size();
                int mutatingStepIndex = 0;
                for (ConfigProtos.Config c : configs) {
                    boolean waitForAckBeforeCommit =
                        ++mutatingStepIndex < totalMutatingSteps;
                    tasks.add(() -> {
                        String stepName =
                            "setConfig/" + c.getPayloadVariantCase();
                        log.info(
                            "Config save: setConfig variant={} size={}",
                            c.getPayloadVariantCase(),
                            c.getSerializedSize()
                        );
                        CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                            MessageService.setConfig(
                                actionHandler,
                                actionState,
                                c
                            );
                        if (waitForAckBeforeCommit) {
                            waitForTransportRequiredConfigSaveAck(
                                activeTransport,
                                ackFuture,
                                stepName
                            );
                        } else {
                            // On BLE the final payload step can trigger a disconnect before its routing
                            // ACK is observed, so commit must still be allowed to proceed.
                            observeDeferredConfigSaveAck(ackFuture, stepName);
                        }
                    });
                }
                for (ModuleConfigProtos.ModuleConfig mc : moduleConfigs) {
                    boolean waitForAckBeforeCommit =
                        ++mutatingStepIndex < totalMutatingSteps;
                    tasks.add(() -> {
                        String stepName =
                            "setModuleConfig/" + mc.getPayloadVariantCase();
                        log.info(
                            "Config save: setModuleConfig variant={} size={}",
                            mc.getPayloadVariantCase(),
                            mc.getSerializedSize()
                        );
                        CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                            MessageService.setModuleConfig(
                                actionHandler,
                                actionState,
                                mc
                            );
                        if (waitForAckBeforeCommit) {
                            waitForTransportRequiredConfigSaveAck(
                                activeTransport,
                                ackFuture,
                                stepName
                            );
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
                        MessageService.commitEditSettings(
                            actionHandler,
                            actionState
                        );
                    handleCommitConfigSaveAck(
                        activeTransport,
                        ackFuture,
                        stepName
                    );
                });
            }

            long rebootHandoffDelay = getConfigSaveRebootHandoffDelayMs(
                activeTransport
            );

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
                                log.warn(
                                    "Config save task {} failed after completion was announced",
                                    i,
                                    e
                                );
                                return;
                            }
                            saveFailed.set(true);
                            log.error(
                                "Config save task {} failed: {}",
                                i,
                                e.getMessage() != null
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName(),
                                e
                            );
                            if (activeEntry != null) {
                                ConnectionManager.getInstance().clearExpectedDeviceReboot(
                                    activeEntry.getId()
                                );
                            }
                            Platform.runLater(() -> {
                                finishConfigSaveNavigationBlock();
                                saveConfigBtn.setDisable(false);
                                configStatusLabel.setText(
                                    I18n.t(
                                        "settings.config.status.saveError",
                                        errorDetail(e)
                                    )
                                );
                            });
                            return;
                        }

                        if (i + 1 < tasks.size()) {
                            long interTaskDelayMs =
                                getConfigSaveInterTaskDelayMs(
                                    activeTransport,
                                    i,
                                    tasks.size()
                                );
                            log.debug(
                                "Config save: waiting {}ms before {}",
                                interTaskDelayMs,
                                i + 1 == tasks.size() - 1
                                    ? "commitEditSettings"
                                    : "next step"
                            );
                            Thread.sleep(interTaskDelayMs);
                        }
                    }

                    if (saveFailed.get()) {
                        return;
                    }

                    saveCompletionAnnounced.set(true);
                    if (requiresReconnect) {
                        markConfigSaveNavigationBlockAwaitingReconnect(
                            activeEntry
                        );
                    }
                    Platform.runLater(() -> {
                        resetModifiedFlags(
                            fullConfigRoot != null
                                ? fullConfigRoot
                                : configTree.getRoot()
                        );
                        originalChannels = getWorkingChannelsSnapshot();
                        saveConfigBtn.setDisable(false);
                        if (requiresReconnect) {
                            String reconnectMessage = configSaveReconnectMessage(
                                activeTransport,
                                totalChanges
                            );
                            configStatusLabel.setText(reconnectMessage);
                        } else {
                            configStatusLabel.setText(
                                I18n.t("settings.config.status.sentSections", totalChanges)
                            );
                        }
                    });

            // After commit, hand the connection to the reboot-aware reconnect path.
            // A normal user disconnect is harmful here: it marks the break as manual
            // and disables auto-reconnect, while BLE needs a soft handoff during the
            // device reboot and advertising window.
                    if (!requiresReconnect) {
                        finishConfigSaveNavigationBlock();
                        return;
                    }
                    Thread.sleep(rebootHandoffDelay);
                    if (saveFailed.get()) {
                        return;
                    }
                    if (activeEntry != null) {
                        log.info(
                            "Config save: handoff to reboot reconnect flow (transport={})",
                            activeTransport
                        );
                        boolean handoffStarted =
                            ConnectionManager.getInstance().disconnectForDeviceReboot(
                                activeEntry.getId(),
                                reconnectHandoffGeneration
                            );
                        if (!handoffStarted) {
                            return;
                        }
                    } else {
                        log.warn(
                            "Config save: no active connection to hand off after commit"
                        );
                        finishConfigSaveNavigationBlock();
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
                    if (activeEntry != null) {
                        ConnectionManager.getInstance().clearExpectedDeviceReboot(
                            activeEntry.getId()
                        );
                    }
                    finishConfigSaveNavigationBlock();
                } catch (Exception e) {
                    log.error("Config save: disconnect failed", e);
                    if (activeEntry != null) {
                        ConnectionManager.getInstance().clearExpectedDeviceReboot(
                            activeEntry.getId()
                        );
                    }
                    finishConfigSaveNavigationBlock();
                }
            }, "config-save-sender");
            saveThread.setDaemon(true);
            saveThread.start();
        } else if (requiresReconnect && activeEntry != null) {
            long rebootHandoffDelay = getConfigSaveRebootHandoffDelayMs(
                activeTransport
            );
            Platform.runLater(() -> {
                resetModifiedFlags(
                    fullConfigRoot != null
                        ? fullConfigRoot
                        : configTree.getRoot()
                );
                originalChannels = getWorkingChannelsSnapshot();
                saveConfigBtn.setDisable(false);
                String reconnectMessage = configSaveReconnectMessage(
                    activeTransport,
                    totalChanges
                );
                configStatusLabel.setText(reconnectMessage);
            });

            Thread reconnectThread = new Thread(() -> {
                try {
                    Thread.sleep(rebootHandoffDelay);
                    log.info(
                        "Config save: handoff to reboot reconnect flow after owner info update (transport={})",
                        activeTransport
                    );
                    boolean handoffStarted =
                        ConnectionManager.getInstance().disconnectForDeviceReboot(
                            activeEntry.getId(),
                            reconnectHandoffGeneration
                        );
                    if (!handoffStarted) {
                        return;
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
                    log.warn("Config save owner reconnect handoff interrupted");
                    ConnectionManager.getInstance().clearExpectedDeviceReboot(
                        activeEntry.getId()
                    );
                    finishConfigSaveNavigationBlock();
                } catch (Exception e) {
                    log.error("Config save: owner reconnect handoff failed", e);
                    ConnectionManager.getInstance().clearExpectedDeviceReboot(
                        activeEntry.getId()
                    );
                    finishConfigSaveNavigationBlock();
                }
            }, "config-save-owner-reconnect");
            reconnectThread.setDaemon(true);
            reconnectThread.start();
        } else {
            // Virtual sections only: finish immediately because the device does not reboot.
            resetModifiedFlags(
                fullConfigRoot != null ? fullConfigRoot : configTree.getRoot()
            );
            originalChannels = getWorkingChannelsSnapshot();
            saveConfigBtn.setDisable(false);
            configStatusLabel.setText(
                I18n.t("settings.config.status.sentSections", totalChanges)
            );
            finishConfigSaveNavigationBlock();
        }
    }

    /**
     * Checks whether a section contains changed fields.
     */
    private boolean hasMoifiedFields(TreeItem<ConfigTreeItem> item) {
        ConfigTreeItem data = item.getValue();
        if (data != null && data.isModified()) {
            return true;
        }
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            if (hasMoifiedFields(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clears modification flags after saving.
     */
    private void resetModifiedFlags(TreeItem<ConfigTreeItem> item) {
        if (item == null) {
            return;
        }
        if (item.getValue() != null) {
            item.getValue().resetOriginal();
        }
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            resetModifiedFlags(child);
        }
    }

    private void syncRepeatedEditorSlots(ConfigTreeItem editedItem) {
        if (
            editedItem == null ||
            editedItem.getFieldDescriptor() == null ||
            !editedItem.getFieldDescriptor().isRepeated()
        ) {
            return;
        }

        TreeItem<ConfigTreeItem> root = currentEditorRoot();
        if (root == null) {
            return;
        }

        TreeItem<ConfigTreeItem> valueItem = findTreeItemByValue(
            root,
            editedItem
        );
        if (valueItem == null || valueItem.getParent() == null) {
            return;
        }

        ProtobufTreeBuilder.adjustRepeatedGroupAfterEdit(valueItem.getParent());
        refreshConfigTreeView();
    }

    private TreeItem<ConfigTreeItem> findTreeItemByValue(
        TreeItem<ConfigTreeItem> root,
        ConfigTreeItem target
    ) {
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
            filterConfigTree(
                configSearchField != null ? configSearchField.getText() : null
            );
            return;
        }
        configTree.refresh();
    }

    /**
     * Gets the active oneof field number from Config.
     */
    private int getActiveOneofFieldNumber(ConfigProtos.Config config) {
        var oneof = config
            .getDescriptorForType()
            .getOneofs()
            .stream()
            .filter(o -> "payload_variant".equals(o.getName()))
            .findFirst()
            .orElse(null);
        if (oneof == null) {
            return -1;
        }
        var fd = config.getOneofFieldDescriptor(oneof);
        return fd != null ? fd.getNumber() : -1;
    }

    /**
     * Gets the active oneof field number from ModuleConfig.
     */
    private int getActiveModuleOneofFieldNumber(
        ModuleConfigProtos.ModuleConfig mc
    ) {
        var oneof = mc
            .getDescriptorForType()
            .getOneofs()
            .stream()
            .filter(o -> "payload_variant".equals(o.getName()))
            .findFirst()
            .orElse(null);
        if (oneof == null) {
            return -1;
        }
        var fd = mc.getOneofFieldDescriptor(oneof);
        return fd != null ? fd.getNumber() : -1;
    }

    // ==================== Config Value Cell ====================

    /**
     * Custom cell for the Value column in the TreeTableView.
     * Displays CheckBox for booleans, ComboBox for enums, and TextField for strings/numbers.
     */
    private final class ConfigValueCell
        extends TreeTableCell<ConfigTreeItem, ConfigTreeItem>
    {

        @Override
        protected void updateItem(ConfigTreeItem item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            setGraphic(null);
            setStyle("");

            if (empty || item == null) {
                return;
            }

            if (item.isCategory()) {
                // Categories have no value.
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
                checkBox
                    .selectedProperty()
                    .addListener((obs, oldVal, newVal) ->
                        item.setValue(newVal)
                    );
                setGraphic(checkBox);
            } else if (
                type == EnumValueDescriptor.class &&
                item.getEnumValues() != null
            ) {
                ComboBox<EnumValueDescriptor> comboBox = new ComboBox<>();
                for (Object ev : item.getEnumValues()) {
                    if (ev instanceof EnumValueDescriptor evd) {
                        comboBox.getItems().add(evd);
                    }
                }
                    // Display enum name.
                comboBox.setCellFactory(lv ->
                    new ListCell<>() {
                        @Override
                        protected void updateItem(
                            EnumValueDescriptor evd,
                            boolean emp
                        ) {
                            super.updateItem(evd, emp);
                            setText(emp || evd == null ? "" : evd.getName());
                        }
                    }
                );
                comboBox.setButtonCell(
                    new ListCell<>() {
                        @Override
                        protected void updateItem(
                            EnumValueDescriptor evd,
                            boolean emp
                        ) {
                            super.updateItem(evd, emp);
                            setText(emp || evd == null ? "" : evd.getName());
                        }
                    }
                );
                if (item.getValue() instanceof EnumValueDescriptor current) {
                    comboBox.setValue(current);
                }
                comboBox.setMaxWidth(Double.MAX_VALUE);
                comboBox
                    .valueProperty()
                    .addListener((obs, oldVal, newVal) ->
                        item.setValue(newVal)
                    );
                setGraphic(comboBox);
            } else if (
                type == String.class ||
                type == Integer.class ||
                type == Long.class ||
                type == Float.class ||
                type == Double.class
            ) {
                setGraphic(createTextEditor(item));
            } else {
            // Fallback: plain text.
                setText(ConfigValueFormatter.formatValue(item));
            }
        }

        /**
         * Creates a text editor for string and numeric fields.
         * If the field has a formatter, the editor receives the existing
         * human-readable representation of the value.
         */
        private TextField createTextEditor(ConfigTreeItem item) {
            TextField textField = new TextField(
                ConfigValueFormatter.formatValue(item)
            );
            textField.setMaxWidth(Double.MAX_VALUE);

            String prompt = ConfigValueFormatter.promptText(item);
            if (prompt != null && !prompt.isBlank()) {
                textField.setPromptText(prompt);
            }

            String hint = ConfigValueFormatter.validationHint(item);
            if (hint != null && !hint.isBlank()) {
                textField.setTooltip(new Tooltip(hint));
            }

            textField
                .focusedProperty()
                .addListener((obs, wasFocused, isFocused) -> {
                    if (!isFocused) {
                        commitTextValue(item, textField);
                    }
                });
            textField.setOnAction(e -> commitTextValue(item, textField));
            return textField;
        }

        /**
         * Creates a selector for bitmask fields stored as numbers but representing
         * a set of independently enabled flags.
         */
        private MenuButton createBitmaskEditor(ConfigTreeItem item) {
            MenuButton menuButton = new MenuButton();
            menuButton.setMaxWidth(Double.MAX_VALUE);
            menuButton.setText(ConfigValueFormatter.formatValue(item));

            List<ConfigValueFormatter.BitmaskOption> options =
                ConfigValueFormatter.bitmaskOptions(item);
            List<CheckMenuItem> menuItems = new ArrayList<>();
            for (ConfigValueFormatter.BitmaskOption option : options) {
                CheckMenuItem menuItem = new CheckMenuItem(option.label());
                menuItem.setSelected(
                    ConfigValueFormatter.isBitmaskOptionSelected(item, option)
                );
                menuItems.add(menuItem);
                menuButton.getItems().add(menuItem);
            }

            for (int i = 0; i < menuItems.size(); i++) {
                menuItems
                    .get(i)
                    .selectedProperty()
                    .addListener((obs, oldVal, newVal) -> {
                        List<
                            ConfigValueFormatter.BitmaskOption
                        > selectedOptions = new ArrayList<>();
                        for (int j = 0; j < menuItems.size(); j++) {
                            if (menuItems.get(j).isSelected()) {
                                selectedOptions.add(options.get(j));
                            }
                        }
                        item.setValue(
                            ConfigValueFormatter.buildBitmaskValue(
                                item,
                                selectedOptions
                            )
                        );
                        menuButton.setText(
                            ConfigValueFormatter.formatValue(item)
                        );
                    });
            }

            return menuButton;
        }

        /**
         * Applies editor text to the field model. On successful parsing it
         * normalizes the displayed value; on failure it highlights the field.
         */
        private void commitTextValue(ConfigTreeItem item, TextField textField) {
            try {
                if (
                    item.getFieldDescriptor() != null &&
                    item.getFieldDescriptor().isRepeated() &&
                    textField.getText().trim().isEmpty()
                ) {
                    item.setValue(null);
                    syncRepeatedEditorSlots(item);
                    textField.setText("");
                    textField.setStyle("");
                    return;
                }
                item.setValue(
                    ConfigValueFormatter.parseTextValue(
                        item,
                        textField.getText()
                    )
                );
                syncRepeatedEditorSlots(item);
                textField.setText(ConfigValueFormatter.formatValue(item));
                textField.setStyle("");
            } catch (IllegalArgumentException ex) {
                textField.setStyle("-fx-border-color: #E53935;");
            }
        }
    }
}
