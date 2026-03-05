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
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SystemForm(name = "Настройки", description = "Настройки клиента", tags = {"settings", "options"})
public class FormSetting extends Form {

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

    // Config tab
    private TreeTableView<ConfigTreeItem> configTree;
    private TextField configSearchField;
    private TreeItem<ConfigTreeItem> fullConfigRoot;
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

        cacheTab = new Tab("Кэш", createCachePanel());
        configTab = new Tab("Конфигурация", createConfigPanel());

        tabPane.getTabs().addAll(configTab, cacheTab);
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

        panel.getChildren().addAll(configSearchField, btnRow, configTree);
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

        boolean connected = state != null && handler != null;

        if (!connected) {
            configStatusLabel.setText("Нет подключения к радио");
            saveConfigBtn.setDisable(true);
            configTree.setRoot(null);
            return;
        }

        NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());

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

        if (!ownerModified && !positionModified
                && modifiedConfigs.isEmpty() && modifiedModuleConfigs.isEmpty()) {
            configStatusLabel.setText("Нет изменений для сохранения");
            return;
        }

        int totalChanges = modifiedConfigs.size() + modifiedModuleConfigs.size()
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

        // Запрашиваем session key → отправляем настройки
        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () -> Platform.runLater(() -> {
            state.removeOwnerInfoListener(listenerHolder[0]);
            sendConfigChanges(modifiedConfigs, modifiedModuleConfigs,
                    fOwnerModified, fLongName, fShortName,
                    fPositionModified, fLat, fLon, fAlt,
                    totalChanges);
        });
        state.addOwnerInfoListener(listenerHolder[0]);

        // Таймаут — отправить без passkey
        Thread timeoutThread = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
            Platform.runLater(() -> {
                state.removeOwnerInfoListener(listenerHolder[0]);
                if (saveConfigBtn.isDisable()) {
                    configStatusLabel.setText("Отправка без session key...");
                    sendConfigChanges(modifiedConfigs, modifiedModuleConfigs,
                            fOwnerModified, fLongName, fShortName,
                            fPositionModified, fLat, fLon, fAlt,
                            totalChanges);
                }
            });
        }, "config-save-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        MessageService.requestOwnerInfo(handler, state);
    }

    /**
     * Отправляет изменённые конфигурации на устройство.
     * Виртуальные секции (имя, позиция) отправляются отдельными admin-сообщениями.
     * Protobuf-секции оборачиваются в begin/commit edit.
     */
    private void sendConfigChanges(List<ConfigProtos.Config> configs,
                                    List<ModuleConfigProtos.ModuleConfig> moduleConfigs,
                                    boolean ownerModified, String newLongName, String newShortName,
                                    boolean positionModified, double newLat, double newLon, int newAlt,
                                    int totalChanges) {
        configStatusLabel.setText("Отправка настроек...");

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
                NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
                if (myNode != null) {
                    myNode.setLatitude(newLat);
                    myNode.setLongitude(newLon);
                    myNode.setAltitude(newAlt);
                    state.fireNodeUpdateListeners(state.getMyNodeNum());
                }
            }
        }

        // Protobuf-секции — через begin/commit edit
        if (!configs.isEmpty() || !moduleConfigs.isEmpty()) {
            MessageService.beginEditSettings(handler, state);

            for (ConfigProtos.Config c : configs) {
                MessageService.setConfig(handler, state, c);
            }
            for (ModuleConfigProtos.ModuleConfig mc : moduleConfigs) {
                MessageService.setModuleConfig(handler, state, mc);
            }

            MessageService.commitEditSettings(handler, state);
        }

        // Сбросить originalValue в дереве
        resetModifiedFlags(fullConfigRoot != null ? fullConfigRoot : configTree.getRoot());

        saveConfigBtn.setDisable(false);
        configStatusLabel.setText("Отправлено секций: " + totalChanges + ". Устройство перезагрузится.");
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
