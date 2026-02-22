package com.meshtastic.client.forms;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.ProtobufTreeBuilder;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import java.util.ArrayList;
import java.util.List;

@SystemForm(name = "Настройки", description = "Настройки клиента", tags = {"settings", "options"})
public class FormSetting extends Form {

    // Owner info UI
    private TextField longNameField;
    private TextField shortNameField;
    private Button saveOwnerBtn;
    private Label ownerStatusLabel;
    private DeviceState state;
    private ProtocolHandler handler;
    private Runnable ownerInfoListener;

    // Cache tab
    private TableView<NodeData> cacheTable;
    private final ObservableList<NodeData> cacheData = FXCollections.observableArrayList();
    private Label cacheStatusLabel;
    private int cacheOffset = 0;
    private static final int PAGE_SIZE = 100;
    private int cacheTotalInDb = 0;

    private Tab cacheTab;

    // Config tab
    private TreeTableView<ConfigTreeItem> configTree;
    private Label configStatusLabel;
    private Button saveConfigBtn;
    private Button refreshConfigBtn;
    private Tab configTab;

    // Оригинальные protobuf-объекты для пересборки при сохранении
    private List<ConfigProtos.Config> originalConfigs = new ArrayList<>();
    private List<ModuleConfigProtos.ModuleConfig> originalModuleConfigs = new ArrayList<>();

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

        VBox generalTab = createGeneralPanel();

        cacheTab = new Tab("Кэш", createCachePanel());
        configTab = new Tab("Конфигурация", createConfigPanel());

        tabPane.getTabs().addAll(new Tab("Общие", generalTab), configTab, cacheTab);
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
    public void formRefresh() {
        refreshConnection();
        reloadCacheTable();
    }

    /**
     * Находит активное подключение и предзаполняет поля owner info.
     */
    private void refreshConnection() {
        // Снять предыдущий listener
        if (state != null && ownerInfoListener != null) {
            state.removeOwnerInfoListener(ownerInfoListener);
        }

        ConnectionManager mgr = ConnectionManager.getInstance();
        DeviceState newState = null;
        ProtocolHandler newHandler = null;
        for (ConnectionEntry entry : mgr.getEntries()) {
            if (entry.isConnected()) {
                newState = mgr.getDeviceState(entry.getId());
                newHandler = mgr.getProtocolHandler(entry.getId());
                if (newState != null) break;
            }
        }
        this.state = newState;
        this.handler = newHandler;

        boolean connected = state != null && handler != null;
        saveOwnerBtn.setDisable(!connected);

        if (connected) {
            // Предзаполнить из текущих данных nodeDb
            NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
            if (myNode != null) {
                if (longNameField.getText().isEmpty() && myNode.getLongName() != null) {
                    longNameField.setText(myNode.getLongName());
                }
                if (shortNameField.getText().isEmpty() && myNode.getShortName() != null) {
                    shortNameField.setText(myNode.getShortName());
                }
            }
            ownerStatusLabel.setText("");
        } else {
            ownerStatusLabel.setText("Нет подключения к радио");
        }
    }

    private VBox createGeneralPanel() {
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(15));
        panel.setAlignment(Pos.TOP_LEFT);

        // Секция: Имя ноды
        Label sectionLabel = new Label("Имя ноды");
        sectionLabel.setFont(Font.font("Roboto", FontWeight.BOLD, 14));

        Label longNameLabel = new Label("Длинное имя (до 40 символов):");
        longNameField = new TextField();
        longNameField.setPromptText("Например: Мой узел");
        longNameField.setMaxWidth(300);
        // Лимит 40 символов
        longNameField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.length() > 40) {
                longNameField.setText(oldVal);
            }
        });

        Label shortNameLabel = new Label("Короткое имя (до 4 символов):");
        shortNameField = new TextField();
        shortNameField.setPromptText("Например: МУ");
        shortNameField.setMaxWidth(100);
        // Лимит 4 символа
        shortNameField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.length() > 4) {
                shortNameField.setText(oldVal);
            }
        });

        ownerStatusLabel = new Label("");
        ownerStatusLabel.setStyle("-fx-opacity: 0.7;");

        saveOwnerBtn = new Button("Сохранить на радио");
        saveOwnerBtn.setDisable(true);
        saveOwnerBtn.setOnAction(e -> onSaveOwnerInfo());

        panel.getChildren().addAll(
                sectionLabel,
                longNameLabel, longNameField,
                shortNameLabel, shortNameField,
                saveOwnerBtn, ownerStatusLabel
        );
        return panel;
    }

    private void onSaveOwnerInfo() {
        if (state == null || handler == null) {
            ownerStatusLabel.setText("Нет подключения к радио");
            return;
        }

        String longName = longNameField.getText().trim();
        String shortName = shortNameField.getText().trim();

        if (longName.isEmpty()) {
            ownerStatusLabel.setText("Введите длинное имя");
            return;
        }
        if (shortName.isEmpty()) {
            ownerStatusLabel.setText("Введите короткое имя");
            return;
        }

        saveOwnerBtn.setDisable(true);
        ownerStatusLabel.setText("Запрос session key...");

        // 1. Запросить get_owner_request для получения session_passkey
        ownerInfoListener = () -> Platform.runLater(() -> {
            // 2. Получили ответ — отправляем set_owner
            state.removeOwnerInfoListener(ownerInfoListener);
            ownerStatusLabel.setText("Отправка имени...");

            MessageService.setOwnerInfo(handler, state, longName, shortName, state.getSessionPasskey());

            // Обновить локальные данные
            NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
            if (myNode != null) {
                myNode.setLongName(longName);
                myNode.setShortName(shortName);
                state.fireNodeUpdateListeners(state.getMyNodeNum());
            }

            ownerStatusLabel.setText("Имя установлено: " + longName + " (" + shortName + ")");
            saveOwnerBtn.setDisable(false);
        });
        state.addOwnerInfoListener(ownerInfoListener);

        // Таймаут — если ответ не пришёл за 5 секунд
        Thread timeoutThread = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) { return; }
            Platform.runLater(() -> {
                if (state != null && ownerInfoListener != null) {
                    state.removeOwnerInfoListener(ownerInfoListener);
                }
                if (saveOwnerBtn.isDisable()) {
                    // Попробовать без passkey (для локальных устройств может работать)
                    ownerStatusLabel.setText("Отправка без session key...");
                    MessageService.setOwnerInfo(handler, state, longName, shortName, null);

                    NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
                    if (myNode != null) {
                        myNode.setLongName(longName);
                        myNode.setShortName(shortName);
                        state.fireNodeUpdateListeners(state.getMyNodeNum());
                    }

                    ownerStatusLabel.setText("Имя отправлено: " + longName + " (" + shortName + ")");
                    saveOwnerBtn.setDisable(false);
                }
            });
        }, "owner-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        MessageService.requestOwnerInfo(handler, state);
    }

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

        // Scroll-based pagination
        cacheTable.setOnScroll(event -> {
            ScrollPane sp = (ScrollPane) cacheTable.lookup(".scroll-pane");
            if (sp != null && sp.getVvalue() > 0.9) {
                loadNextCachePage();
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
        if (cacheOffset >= cacheTotalInDb) return;
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

        // Кнопки
        HBox btnRow = new HBox(8);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        refreshConfigBtn = new Button("Обновить");
        refreshConfigBtn.setOnAction(e -> reloadConfigTree());

        saveConfigBtn = new Button("Сохранить на радио");
        saveConfigBtn.setDisable(true);
        saveConfigBtn.setOnAction(e -> onSaveConfig());

        configStatusLabel = new Label("");
        configStatusLabel.setStyle("-fx-opacity: 0.7;");

        btnRow.getChildren().addAll(refreshConfigBtn, saveConfigBtn, configStatusLabel);

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

        panel.getChildren().addAll(btnRow, configTree);
        return panel;
    }

    /**
     * Загружает конфигурацию из DeviceState и строит дерево.
     */
    private void reloadConfigTree() {
        // Обновить ссылку на state если не установлена
        if (state == null || handler == null) {
            refreshConnection();
        }

        if (state == null) {
            configStatusLabel.setText("Нет подключения к радио");
            saveConfigBtn.setDisable(true);
            configTree.setRoot(null);
            return;
        }

        // Сохраняем оригинальные protobuf для пересборки
        List<ConfigProtos.Config> stateConfigs;
        List<ModuleConfigProtos.ModuleConfig> stateModuleConfigs;
        synchronized (state.getConfigs()) {
            stateConfigs = new ArrayList<>(state.getConfigs());
        }
        synchronized (state.getModuleConfigs()) {
            stateModuleConfigs = new ArrayList<>(state.getModuleConfigs());
        }
        originalConfigs = stateConfigs;
        originalModuleConfigs = stateModuleConfigs;

        // Корневой элемент (невидимый)
        TreeItem<ConfigTreeItem> root = new TreeItem<>(new ConfigTreeItem("Корень", null, 0));
        root.setExpanded(true);

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

        configTree.setRoot(root);

        if (originalConfigs.isEmpty() && originalModuleConfigs.isEmpty()) {
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
        if (item.getValue() != null && item.getValue().isEditable()) count++;
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            count += countFields(child);
        }
        return count;
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

        TreeItem<ConfigTreeItem> root = configTree.getRoot();
        if (root == null) return;

        // Собрать изменённые секции
        List<ConfigProtos.Config> modifiedConfigs = new ArrayList<>();
        List<ModuleConfigProtos.ModuleConfig> modifiedModuleConfigs = new ArrayList<>();

        for (TreeItem<ConfigTreeItem> topLevel : root.getChildren()) {
            // topLevel = "Конфигурация устройства" или "Конфигурация модулей"
            for (TreeItem<ConfigTreeItem> section : topLevel.getChildren()) {
                if (!hasMoifiedFields(section)) continue;

                ConfigTreeItem sectionData = section.getValue();
                if ("config".equals(sectionData.getConfigType())) {
                    // Найти оригинальный Config по variantNumber
                    for (ConfigProtos.Config orig : originalConfigs) {
                        var oneofField = getActiveOneofFieldNumber(orig);
                        if (oneofField == sectionData.getConfigVariantNumber()) {
                            ConfigProtos.Config rebuilt = ProtobufTreeBuilder.rebuildConfig(section, orig);
                            if (rebuilt != null) modifiedConfigs.add(rebuilt);
                            break;
                        }
                    }
                } else if ("module_config".equals(sectionData.getConfigType())) {
                    for (ModuleConfigProtos.ModuleConfig orig : originalModuleConfigs) {
                        var oneofField = getActiveModuleOneofFieldNumber(orig);
                        if (oneofField == sectionData.getConfigVariantNumber()) {
                            ModuleConfigProtos.ModuleConfig rebuilt =
                                    ProtobufTreeBuilder.rebuildModuleConfig(section, orig);
                            if (rebuilt != null) modifiedModuleConfigs.add(rebuilt);
                            break;
                        }
                    }
                }
            }
        }

        if (modifiedConfigs.isEmpty() && modifiedModuleConfigs.isEmpty()) {
            configStatusLabel.setText("Нет изменений для сохранения");
            return;
        }

        int totalChanges = modifiedConfigs.size() + modifiedModuleConfigs.size();
        saveConfigBtn.setDisable(true);
        configStatusLabel.setText("Запрос session key...");

        // Запрашиваем session key → отправляем настройки
        Runnable configSaveListener = () -> Platform.runLater(() -> {
            if (state != null && ownerInfoListener != null) {
                state.removeOwnerInfoListener(ownerInfoListener);
            }
            sendConfigChanges(modifiedConfigs, modifiedModuleConfigs, totalChanges);
        });
        ownerInfoListener = configSaveListener;
        state.addOwnerInfoListener(configSaveListener);

        // Таймаут — отправить без passkey
        Thread timeoutThread = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
            Platform.runLater(() -> {
                if (state != null && ownerInfoListener == configSaveListener) {
                    state.removeOwnerInfoListener(configSaveListener);
                }
                if (saveConfigBtn.isDisable()) {
                    configStatusLabel.setText("Отправка без session key...");
                    sendConfigChanges(modifiedConfigs, modifiedModuleConfigs, totalChanges);
                }
            });
        }, "config-save-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        MessageService.requestOwnerInfo(handler, state);
    }

    /**
     * Отправляет изменённые конфигурации на устройство через begin/commit edit.
     */
    private void sendConfigChanges(List<ConfigProtos.Config> configs,
                                    List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
                                    int totalChanges) {
        configStatusLabel.setText("Отправка настроек...");

        // begin_edit_settings
        MessageService.beginEditSettings(handler, state);

        // Отправить каждую изменённую секцию
        for (ConfigProtos.Config c : configs) {
            MessageService.setConfig(handler, state, c);
        }
        for (ModuleConfigProtos.ModuleConfig mc : moduleConfigs) {
            MessageService.setModuleConfig(handler, state, mc);
        }

        // commit_edit_settings
        MessageService.commitEditSettings(handler, state);

        // Сбросить originalValue в дереве
        resetModifiedFlags(configTree.getRoot());

        saveConfigBtn.setDisable(false);
        configStatusLabel.setText("Отправлено секций: " + totalChanges + ". Устройство перезагрузится.");
    }

    /**
     * Проверяет, есть ли изменённые поля в секции.
     */
    private boolean hasMoifiedFields(TreeItem<ConfigTreeItem> item) {
        ConfigTreeItem data = item.getValue();
        if (data != null && data.isModified()) return true;
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            if (hasMoifiedFields(child)) return true;
        }
        return false;
    }

    /**
     * Сбрасывает флаги модификации после сохранения.
     */
    private void resetModifiedFlags(TreeItem<ConfigTreeItem> item) {
        if (item == null) return;
        if (item.getValue() != null) item.getValue().resetOriginal();
        for (TreeItem<ConfigTreeItem> child : item.getChildren()) {
            resetModifiedFlags(child);
        }
    }

    /**
     * Получает номер активного oneof-поля у Config.
     */
    private int getActiveOneofFieldNumber(ConfigProtos.Config config) {
        var oneof = config.getDescriptorForType().getOneofs().stream()
                .filter(o -> o.getName().equals("payload_variant"))
                .findFirst().orElse(null);
        if (oneof == null) return -1;
        var fd = config.getOneofFieldDescriptor(oneof);
        return fd != null ? fd.getNumber() : -1;
    }

    /**
     * Получает номер активного oneof-поля у ModuleConfig.
     */
    private int getActiveModuleOneofFieldNumber(ModuleConfigProtos.ModuleConfig mc) {
        var oneof = mc.getDescriptorForType().getOneofs().stream()
                .filter(o -> o.getName().equals("payload_variant"))
                .findFirst().orElse(null);
        if (oneof == null) return -1;
        var fd = mc.getOneofFieldDescriptor(oneof);
        return fd != null ? fd.getNumber() : -1;
    }

    // ==================== Config Value Cell ====================

    /**
     * Кастомная ячейка для колонки «Значение» в TreeTableView.
     * Отображает CheckBox для boolean, ComboBox для enum, TextField для строк/чисел.
     */
    private static class ConfigValueCell extends TreeTableCell<ConfigTreeItem, ConfigTreeItem> {

        @Override
        protected void updateItem(ConfigTreeItem item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            setGraphic(null);
            setStyle("");

            if (empty || item == null) return;

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
                    if (!isFocused) item.setValue(textField.getText());
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
