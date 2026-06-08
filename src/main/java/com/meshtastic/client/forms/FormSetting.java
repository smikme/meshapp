package com.meshtastic.client.forms;

import com.meshtastic.client.forms.settings.ApplicationSettingsPanelFactory;
import com.meshtastic.client.forms.settings.ConfigHelpPopupController;
import com.meshtastic.client.forms.settings.ConfigPanelFactory;
import com.meshtastic.client.forms.settings.ConfigProtobufSupport;
import com.meshtastic.client.forms.settings.ConfigSaveController;
import com.meshtastic.client.forms.settings.ConfigSnapshotController;
import com.meshtastic.client.forms.settings.ConfigSnapshotEditor;
import com.meshtastic.client.forms.settings.ConfigTreeItemSupport;
import com.meshtastic.client.forms.settings.DatabaseResetConfirmationPanelFactory;
import com.meshtastic.client.forms.settings.DatabaseResetController;
import com.meshtastic.client.forms.settings.DevicePowerActionController;
import com.meshtastic.client.forms.settings.DeviceTimeSyncController;
import com.meshtastic.client.forms.settings.FirmwareUpdateController;
import com.meshtastic.client.forms.settings.MeshCoreSettingsTreeBuilder;
import com.meshtastic.client.forms.settings.MeshtasticConfigTreeBuilder;
import com.meshtastic.client.forms.settings.NodeCacheSettingsController;
import com.meshtastic.client.forms.settings.RingtoneSettingsController;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionState;
import com.meshtastic.client.service.ConfigSnapshotService;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.ProtobufTreeBuilder;
import com.meshtastic.client.utils.SystemForm;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
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
    private DeviceState state;
    private ProtocolHandler handler;
    private MeshCoreCompanionState meshCoreCompanionState;
    private final ConfigSaveController configSaveController =
        new ConfigSaveController(new ConfigSaveHost());
    private final ConfigSnapshotController configSnapshotController =
        new ConfigSnapshotController(new ConfigSnapshotHost());
    private final DatabaseResetController databaseResetController =
        new DatabaseResetController(new DatabaseResetHost());
    private final DevicePowerActionController devicePowerActionController =
        new DevicePowerActionController(new DevicePowerHost());
    private final DeviceTimeSyncController timeSyncController =
        new DeviceTimeSyncController(new TimeSyncHost());
    private final RingtoneSettingsController ringtoneController =
        new RingtoneSettingsController(new RingtoneHost());
    private final FirmwareUpdateController firmwareUpdateController =
        new FirmwareUpdateController();
    private final Runnable connectionListener = () ->
        Platform.runLater(() -> {
            reloadConfigTree();
            timeSyncController.maybeResumeDeferredTimeOnlySync();
            firmwareUpdateController.reload();
        });

    // Cache tab
    private final NodeCacheSettingsController cacheController =
        new NodeCacheSettingsController();

    private Tab cacheTab;
    private Tab appearanceTab;
    private Tab firmwareTab;

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
    private final ConfigHelpPopupController configHelpPopupController =
        new ConfigHelpPopupController();
    private volatile CompletableFuture<DeviceState> observedConfigLoadFuture;

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

        cacheTab = new Tab(
            I18n.t("settings.tab.cache"),
            cacheController.createPanel()
        );
        configTab = new Tab(I18n.t("settings.tab.config"), createConfigPanel());
        firmwareTab = new Tab(
            I18n.t("settings.tab.firmware"),
            firmwareUpdateController.createPanel()
        );
        appearanceTab = new Tab(
            I18n.t("settings.tab.app"),
            ApplicationSettingsPanelFactory.create()
        );

        tabPane.getTabs().addAll(configTab, firmwareTab, cacheTab, appearanceTab);
        tabPane
            .getSelectionModel()
            .selectedItemProperty()
            .addListener((obs, oldTab, newTab) -> {
                if (newTab == cacheTab) {
                    cacheController.reload();
                } else if (newTab == configTab) {
                    reloadConfigTree();
                } else if (newTab == firmwareTab) {
                    firmwareUpdateController.reload();
                }
            });

        VBox.setVgrow(tabPane, Priority.ALWAYS);
        content.getChildren().addAll(title, tabPane);
        getChildren().add(content);
    }

    @Override
    public void formOpen() {
        reloadConfigTree();
        firmwareUpdateController.reload();
    }

    @Override
    public void formRefresh() {
        reloadConfigTree();
        firmwareUpdateController.reload();
    }

    /**
     * Finds the selected active connection and updates the state/handler references.
     */
    private void refreshConnection() {
        ConnectionManager mgr = ConnectionManager.getInstance();
        Optional<ConnectionEntry> entry = Optional
            .ofNullable(mgr.getSelectedConnectionEntry())
            .filter(ConnectionEntry::isConnected);
        DeviceState newState = entry
            .map(ConnectionEntry::getId)
            .map(mgr::getDeviceState)
            .orElse(null);
        ProtocolHandler newHandler = entry
            .map(ConnectionEntry::getId)
            .map(mgr::getProtocolHandler)
            .orElse(null);
        MeshCoreCompanionState newMeshCoreState = entry
            .map(ConnectionEntry::getId)
            .map(mgr::getMeshCoreCompanionState)
            .orElse(null);
        this.state = newState;
        this.handler = newHandler;
        this.meshCoreCompanionState = newMeshCoreState;
        ringtoneController.observe(newState);
    }

    /**
     * Resets the local UI context for the current device.
     * Used on disconnect so the editor does not keep stale configuration.
     */
    private void clearConfigContext() {
        ringtoneController.observe(null);
        state = null;
        handler = null;
        meshCoreCompanionState = null;
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
     * Returns the full active connection profile.
     * The save flow uses it to choose transport-aware pacing and to hand the
     * connection to the reconnect path after the device reboots.
     */
    private ConnectionEntry findActiveConnectionEntry() {
        ConnectionManager mgr = ConnectionManager.getInstance();
        return Optional
            .ofNullable(mgr.getSelectedConnectionEntry())
            .filter(ConnectionEntry::isConnected)
            .orElse(null);
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

    /**
     * The Cache tab displays names as read-only values. Keep the general Unicode
     * normalization for user text without removing valid emoji.
     */
    static String sanitizeCacheDisplayText(String value) {
        return NodeCacheSettingsController.sanitize(value);
    }

    // ==================== Config Tab ====================

    private VBox createConfigPanel() {
        ConfigPanelFactory.Controls controls = ConfigPanelFactory.create(
            new ConfigPanelFactory.ToolbarActions(
                this::reloadConfigTree,
                this::onSyncDateTimeWithPc,
                this::onSaveConfig,
                this::onRestartHardware,
                this::onShutdownHardware,
                this::onResetDatabaseRequested,
                () -> onExportSnapshot(ConfigSnapshotService.SnapshotKind.CONFIG),
                () -> onImportSnapshot(ConfigSnapshotService.SnapshotKind.CONFIG),
                () -> onExportSnapshot(ConfigSnapshotService.SnapshotKind.TEMPLATE),
                () -> onImportSnapshot(ConfigSnapshotService.SnapshotKind.TEMPLATE)
            ),
            configHelpPopupController,
            this::filterConfigTree,
            this::syncRepeatedEditorSlots
        );
        configSearchField = controls.searchField();
        configStatusLabel = controls.statusLabel();
        configTree = controls.configTree();
        refreshConfigBtn = controls.refreshButton();
        syncDateTimeBtn = controls.syncTimeButton();
        saveConfigBtn = controls.saveRadioButton();
        restartHardwareBtn = controls.restartButton();
        shutdownHardwareBtn = controls.shutdownButton();
        resetDatabaseBtn = controls.resetDatabaseButton();
        return controls.panel();
    }

    private void onResetDatabaseRequested() {
        databaseResetController.requestReset();
    }

    private VBox buildDatabaseResetConfirmationPanel(Runnable onConfirm) {
        return DatabaseResetConfirmationPanelFactory.create(onConfirm);
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
        timeSyncController.syncWithPc();
    }

    private ConfigProtos.Config findLoadedDeviceConfig() {
        return originalConfigs
            .stream()
            .filter(ConfigProtos.Config::hasDevice)
            .findFirst()
            .orElse(null);
    }

    private void onRestartHardware() {
        devicePowerActionController.restart();
    }

    private void onShutdownHardware() {
        devicePowerActionController.shutdown();
    }

    private void onExportSnapshot(ConfigSnapshotService.SnapshotKind kind) {
        configSnapshotController.exportSnapshot(kind);
    }

    private void onImportSnapshot(ConfigSnapshotService.SnapshotKind kind) {
        configSnapshotController.importSnapshot(kind);
    }

    private String errorDetail(Exception e) {
        return e.getMessage() != null
            ? e.getMessage()
            : I18n.t("settings.status.seeLog");
    }

    private void clearConnectionContext(
        DeviceState expectedState,
        ProtocolHandler expectedHandler
    ) {
        if (state == expectedState) {
            state = null;
        }
        if (handler == expectedHandler) {
            handler = null;
        }
    }

    private TreeItem<ConfigTreeItem> currentEditorRoot() {
        return fullConfigRoot != null
            ? fullConfigRoot
            : configTree != null
                ? configTree.getRoot()
                : null;
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

        ConfigSnapshotEditor.ApplyResult result =
            ConfigSnapshotEditor.applySnapshot(
                snapshot,
                root,
                originalConfigs,
                originalModuleConfigs,
                originalChannels,
                workingChannels
            );
        workingChannels = new ArrayList<>(result.workingChannels());
    }

    private boolean hasPendingEditorChanges() {
        return ConfigSnapshotEditor.hasPendingEditorChanges(
            currentEditorRoot(),
            originalChannels,
            workingChannels
        );
    }

    private List<ChannelProtos.Channel> collectModifiedChannels() {
        return ConfigSnapshotEditor.collectModifiedChannels(
            originalChannels,
            workingChannels
        );
    }

    private List<ChannelProtos.Channel> getWorkingChannelsSnapshot() {
        return ConfigSnapshotEditor.workingChannelsSnapshot(
            originalChannels,
            workingChannels
        );
    }

    private TreeItem<ConfigTreeItem> findTopLevelSection(
        TreeItem<ConfigTreeItem> root,
        String configType
    ) {
        return ConfigTreeItemSupport
            .findTopLevelSection(root, configType)
            .orElse(null);
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
            configSaveController.maybeFinishNavigationBlockAfterReconnect(
                null,
                false
            );
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
        configSaveController.maybeFinishNavigationBlockAfterReconnect(
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

        TreeItem<ConfigTreeItem> root = MeshtasticConfigTreeBuilder.build(
            state,
            myNode,
            originalConfigs,
            originalModuleConfigs
        );

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
            ringtoneController.requestIfNeeded(state, handler);
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
        originalChannels = Optional
            .ofNullable(state)
            .map(DeviceState::getChannels)
            .map(ArrayList::new)
            .orElseGet(ArrayList::new);
        workingChannels = new ArrayList<>(originalChannels);

        TreeItem<ConfigTreeItem> root = MeshCoreSettingsTreeBuilder.build(
            meshCoreState,
            originalChannels
        );

        fullConfigRoot = root;
        configTree.setRoot(root);
        configSearchField.clear();
        saveConfigBtn.setDisable(true);
        setSyncDateTimeButtonDisabled(true);
        configStatusLabel.setText(
            I18n.t("settings.meshCore.status.readOnly")
        );
    }

    /**
     * Counts editable fields in the tree.
     */
    private int countFields(TreeItem<ConfigTreeItem> item) {
        return ConfigTreeItemSupport.countEditableFields(item);
    }

    /**
     * Filters the configuration tree by the search string.
     * Shows only parameters whose name or fieldName contains the query, along
     * with their parent categories.
     */
    private void filterConfigTree(String query) {
        ConfigTreeItemSupport
            .filter(fullConfigRoot, query)
            .ifPresent(configTree::setRoot);
    }

    /**
     * Recursively filters a tree node and returns a copy with matching descendants, or null.
     */
    private TreeItem<ConfigTreeItem> filterTreeItem(
        TreeItem<ConfigTreeItem> item,
        String lowerQuery
    ) {
        return ConfigTreeItemSupport
            .filter(item, lowerQuery)
            .filter(filtered -> !filtered.getChildren().isEmpty() || item.getValue() == filtered.getValue())
            .orElse(null);
    }

    /**
     * Deep-copies a tree node so a full section can be shown when its category matches.
     */
    private TreeItem<ConfigTreeItem> copyTreeItem(
        TreeItem<ConfigTreeItem> item
    ) {
        return ConfigTreeItemSupport.copyTreeItem(item);
    }

    /**
     * Saves changed settings to the device.
     * Uses begin_edit_settings / commit_edit_settings for batched delivery.
     */
    private void onSaveConfig() {
        configSaveController.save();
    }

    /**
     * Checks whether a section contains changed fields.
     */
    private boolean hasMoifiedFields(TreeItem<ConfigTreeItem> item) {
        return ConfigTreeItemSupport.hasModifiedFields(item);
    }

    /**
     * Clears modification flags after saving.
     */
    private void resetModifiedFlags(TreeItem<ConfigTreeItem> item) {
        ConfigTreeItemSupport.resetModifiedFlags(item);
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
        return ConfigTreeItemSupport
            .findTreeItemByValue(root, target)
            .orElse(null);
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
        return ConfigProtobufSupport.activeOneofFieldNumber(config);
    }

    /**
     * Gets the active oneof field number from ModuleConfig.
     */
    private int getActiveModuleOneofFieldNumber(
        ModuleConfigProtos.ModuleConfig mc
    ) {
        return ConfigProtobufSupport.activeModuleOneofFieldNumber(mc);
    }

    /**
     * Adapts UI updates for {@link DatabaseResetController}.
     *
     * @author Konstantin A. Smirnov (ks@privatepractice.app)
     */
    private final class DatabaseResetHost
        implements DatabaseResetController.Host
    {

        @Override
        public void setResetDatabaseButtonDisabled(boolean disabled) {
            if (resetDatabaseBtn != null) {
                resetDatabaseBtn.setDisable(disabled);
            }
        }

        @Override
        public void setStatus(String status) {
            if (configStatusLabel != null) {
                configStatusLabel.setText(status);
            }
        }

        @Override
        public void reloadCache() {
            cacheController.reload();
        }

        @Override
        public void reloadConfigTree() {
            FormSetting.this.reloadConfigTree();
        }

        @Override
        public String errorDetail(Exception error) {
            return FormSetting.this.errorDetail(error);
        }
    }

    /**
     * Adapts editor state for {@link RingtoneSettingsController}.
     *
     * @author Konstantin A. Smirnov (ks@privatepractice.app)
     */
    private final class RingtoneHost implements RingtoneSettingsController.Host {

        @Override
        public DeviceState currentState() {
            return state;
        }

        @Override
        public TreeItem<ConfigTreeItem> currentEditorRoot() {
            return FormSetting.this.currentEditorRoot();
        }

        @Override
        public void refreshConfigTreeView() {
            FormSetting.this.refreshConfigTreeView();
        }
    }

    /**
     * Adapts editor state and window access for {@link ConfigSnapshotController}.
     *
     * @author Konstantin A. Smirnov (ks@privatepractice.app)
     */
    private final class ConfigSnapshotHost
        implements ConfigSnapshotController.Host
    {

        @Override
        public DeviceState state() {
            return state;
        }

        @Override
        public TreeItem<ConfigTreeItem> currentEditorRoot() {
            return FormSetting.this.currentEditorRoot();
        }

        @Override
        public List<ConfigProtos.Config> originalConfigs() {
            return originalConfigs;
        }

        @Override
        public List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs() {
            return originalModuleConfigs;
        }

        @Override
        public List<ChannelProtos.Channel> originalChannels() {
            return originalChannels;
        }

        @Override
        public List<ChannelProtos.Channel> workingChannels() {
            return workingChannels;
        }

        @Override
        public ConnectionEntry findActiveConnectionEntry() {
            return FormSetting.this.findActiveConnectionEntry();
        }

        @Override
        public boolean isConfigExchangeInProgress(ConnectionEntry entry) {
            return FormSetting.this.isConfigExchangeInProgress(entry);
        }

        @Override
        public void watchConfigExchangeCompletion(ConnectionEntry entry) {
            FormSetting.this.watchConfigExchangeCompletion(entry);
        }

        @Override
        public void reloadConfigTree() {
            FormSetting.this.reloadConfigTree();
        }

        @Override
        public Window currentWindow() {
            return getScene() != null ? getScene().getWindow() : null;
        }

        @Override
        public boolean hasPendingEditorChanges() {
            return FormSetting.this.hasPendingEditorChanges();
        }

        @Override
        public void applySnapshotToEditor(
            ConfigSnapshotService.ConfigSnapshot snapshot
        ) {
            FormSetting.this.applySnapshotToEditor(snapshot);
        }

        @Override
        public void refreshConfigTreeView() {
            FormSetting.this.refreshConfigTreeView();
        }

        @Override
        public void setSaveConfigButtonDisabled(boolean disabled) {
            if (saveConfigBtn != null) {
                saveConfigBtn.setDisable(disabled);
            }
        }

        @Override
        public void setStatus(String status) {
            if (configStatusLabel != null) {
                configStatusLabel.setText(status);
            }
        }
    }

    /**
     * Adapts editor state and UI operations for {@link ConfigSaveController}.
     *
     * @author Konstantin A. Smirnov (ks@privatepractice.app)
     */
    private final class ConfigSaveHost implements ConfigSaveController.Host {

        @Override
        public DeviceState state() {
            return state;
        }

        @Override
        public ProtocolHandler handler() {
            return handler;
        }

        @Override
        public ConnectionEntry findActiveConnectionEntry() {
            return FormSetting.this.findActiveConnectionEntry();
        }

        @Override
        public boolean isConfigExchangeInProgress(ConnectionEntry entry) {
            return FormSetting.this.isConfigExchangeInProgress(entry);
        }

        @Override
        public void watchConfigExchangeCompletion(ConnectionEntry entry) {
            FormSetting.this.watchConfigExchangeCompletion(entry);
        }

        @Override
        public TreeItem<ConfigTreeItem> currentEditorRoot() {
            return FormSetting.this.currentEditorRoot();
        }

        @Override
        public List<ConfigProtos.Config> originalConfigs() {
            return originalConfigs;
        }

        @Override
        public List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs() {
            return originalModuleConfigs;
        }

        @Override
        public List<ChannelProtos.Channel> collectModifiedChannels() {
            return FormSetting.this.collectModifiedChannels();
        }

        @Override
        public List<ChannelProtos.Channel> workingChannelsSnapshot() {
            return FormSetting.this.getWorkingChannelsSnapshot();
        }

        @Override
        public void setOriginalChannels(List<ChannelProtos.Channel> channels) {
            originalChannels = new ArrayList<>(channels);
        }

        @Override
        public void resetModifiedFlags(TreeItem<ConfigTreeItem> item) {
            FormSetting.this.resetModifiedFlags(item);
        }

        @Override
        public void setSaveConfigButtonDisabled(boolean disabled) {
            if (saveConfigBtn != null) {
                saveConfigBtn.setDisable(disabled);
            }
        }

        @Override
        public void setStatus(String status) {
            if (configStatusLabel != null) {
                configStatusLabel.setText(status);
            }
        }

        @Override
        public void clearConnectionContext(
            DeviceState expectedState,
            ProtocolHandler expectedHandler
        ) {
            FormSetting.this.clearConnectionContext(
                expectedState,
                expectedHandler
            );
        }

        @Override
        public void reloadConfigTree() {
            FormSetting.this.reloadConfigTree();
        }

        @Override
        public String errorDetail(Exception error) {
            return FormSetting.this.errorDetail(error);
        }
    }

    /**
     * Adapts form state and UI operations for
     * {@link DevicePowerActionController}.
     *
     * @author Konstantin A. Smirnov (ks@privatepractice.app)
     */
    private final class DevicePowerHost
        implements DevicePowerActionController.Host
    {

        @Override
        public void refreshConnection() {
            FormSetting.this.refreshConnection();
        }

        @Override
        public DeviceState state() {
            return state;
        }

        @Override
        public ProtocolHandler handler() {
            return handler;
        }

        @Override
        public ConnectionEntry findActiveConnectionEntry() {
            return FormSetting.this.findActiveConnectionEntry();
        }

        @Override
        public void setDevicePowerButtonsDisabled(boolean disabled) {
            FormSetting.this.setDevicePowerButtonsDisabled(disabled);
        }

        @Override
        public void setStatus(String status) {
            if (configStatusLabel != null) {
                configStatusLabel.setText(status);
            }
        }

        @Override
        public void clearConnectionContext(
            DeviceState expectedState,
            ProtocolHandler expectedHandler
        ) {
            FormSetting.this.clearConnectionContext(
                expectedState,
                expectedHandler
            );
        }

        @Override
        public void reloadConfigTree() {
            FormSetting.this.reloadConfigTree();
        }

        @Override
        public String errorDetail(Exception error) {
            return FormSetting.this.errorDetail(error);
        }
    }

    /**
     * Adapts form state and UI operations for {@link DeviceTimeSyncController}.
     *
     * @author Konstantin A. Smirnov (ks@privatepractice.app)
     */
    private final class TimeSyncHost implements DeviceTimeSyncController.Host {

        @Override
        public void refreshConnection() {
            FormSetting.this.refreshConnection();
        }

        @Override
        public DeviceState state() {
            return state;
        }

        @Override
        public ProtocolHandler handler() {
            return handler;
        }

        @Override
        public ConnectionEntry findActiveConnectionEntry() {
            return FormSetting.this.findActiveConnectionEntry();
        }

        @Override
        public boolean isConfigExchangeInProgress(ConnectionEntry entry) {
            return FormSetting.this.isConfigExchangeInProgress(entry);
        }

        @Override
        public void watchConfigExchangeCompletion(ConnectionEntry entry) {
            FormSetting.this.watchConfigExchangeCompletion(entry);
        }

        @Override
        public ConfigProtos.Config findLoadedDeviceConfig() {
            return FormSetting.this.findLoadedDeviceConfig();
        }

        @Override
        public boolean hasPendingEditorChanges() {
            return FormSetting.this.hasPendingEditorChanges();
        }

        @Override
        public void setSyncDateTimeButtonDisabled(boolean disabled) {
            FormSetting.this.setSyncDateTimeButtonDisabled(disabled);
        }

        @Override
        public void setStatus(String status) {
            if (configStatusLabel != null) {
                configStatusLabel.setText(status);
            }
        }

        @Override
        public void clearConnectionContext(
            DeviceState expectedState,
            ProtocolHandler expectedHandler
        ) {
            FormSetting.this.clearConnectionContext(
                expectedState,
                expectedHandler
            );
        }

        @Override
        public void reloadConfigTree() {
            FormSetting.this.reloadConfigTree();
        }

        @Override
        public String errorDetail(Exception error) {
            return FormSetting.this.errorDetail(error);
        }
    }

}
