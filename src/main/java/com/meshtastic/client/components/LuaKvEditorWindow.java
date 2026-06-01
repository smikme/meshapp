package com.meshtastic.client.components;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.SvgIconLoader;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Самостоятельное окно редактирования KV-хранилища Lua-скрипта.
 * <p>
 * Окно открывается из списка скриптов MeshApp IDE и работает только с
 * key-value данными конкретного скрипта. Позволяет просматривать всю KV-базу,
 * фильтровать записи по ключу и значению, добавлять новые пары, изменять
 * существующие ключи/значения и удалять записи.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaKvEditorWindow {

    private static final double DEFAULT_WIDTH = 980;
    private static final double DEFAULT_HEIGHT = 720;
    private static final int MAX_KEY_LENGTH = 200;
    private static final Map<Long, LuaKvEditorWindow> OPEN_WINDOWS = new HashMap<>();

    private final LuaScriptService scriptService = LuaScriptService.getInstance();
    private final long scriptId;
    private final ObservableList<KvEntryRow> rows = FXCollections.observableArrayList();
    private final FilteredList<KvEntryRow> filteredRows = new FilteredList<>(rows, row -> true);

    private String scriptName;
    private Stage stage;
    private Label scriptLabel;
    private Label statusLabel;
    private TableView<KvEntryRow> table;
    private TextField searchField;
    private TextField keyField;
    private TextArea valueArea;
    private Button deleteButton;

    private LuaKvEditorWindow(LuaScript script) {
        this.scriptId = script.getId();
        this.scriptName = displayScriptName(script);
        createStage();
        refreshRows(null);
    }

    /**
     * Открывает самостоятельное окно KV-редактора для указанного Lua-скрипта.
     * <p>
     * Если окно для этого скрипта уже открыто, оно обновляет заголовок/данные
     * и выводится на передний план вместо создания второго экземпляра.
     *
     * @param script скрипт, KV-хранилище которого нужно открыть
     */
    public static void showWindow(LuaScript script) {
        if (script == null || script.getId() <= 0) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            showWindowInternal(script);
        } else {
            Platform.runLater(() -> showWindowInternal(script));
        }
    }

    private static void showWindowInternal(LuaScript script) {
        LuaKvEditorWindow existing = OPEN_WINDOWS.get(script.getId());
        if (existing != null) {
            existing.updateScript(script);
            existing.showStage();
            return;
        }
        LuaKvEditorWindow window = new LuaKvEditorWindow(script);
        OPEN_WINDOWS.put(script.getId(), window);
        window.showStage();
    }

    private void updateScript(LuaScript script) {
        scriptName = displayScriptName(script);
        updateTitle();
        refreshRows(selectedKey());
    }

    private void showStage() {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        stage.requestFocus();
    }

    private void createStage() {
        stage = new Stage();
        stage.initStyle(StageStyle.DECORATED);
        stage.setTitle(windowTitle());
        stage.setResizable(true);
        if (MeshApp.getPrimaryStage() != null && !MeshApp.getPrimaryStage().getIcons().isEmpty()) {
            stage.getIcons().setAll(MeshApp.getPrimaryStage().getIcons());
        }

        VBox root = new VBox();
        root.getStyleClass().addAll("lua-dev-root", "lua-kv-editor-root");

        VBox content = new VBox(10);
        content.getStyleClass().add("lua-dev-content");
        content.setPadding(new Insets(12));
        VBox.setVgrow(content, Priority.ALWAYS);

        SplitPane splitPane = new SplitPane(createTablePanel(), createEditorPanel());
        splitPane.getStyleClass().add("lua-dev-split-pane");
        splitPane.setOrientation(Orientation.VERTICAL);
        splitPane.setDividerPositions(0.62);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        content.getChildren().addAll(createHeader(), splitPane, createStatusBar());
        root.getChildren().add(content);

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        ThemeManager.applyTheme(scene, AppPreferences.isDarkMode());
        EmojiRenderingSupport.install(scene);
        stage.setScene(scene);
        stage.setOnHidden(event -> {
            OPEN_WINDOWS.remove(scriptId, this);
            ThemeManager.unregisterScene(scene);
        });
        updateTitle();
    }

    private HBox createHeader() {
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(2);
        Label titleLabel = new Label(I18n.t("meshIde.kv.title"));
        titleLabel.getStyleClass().add("form-title");
        scriptLabel = new Label(scriptName);
        scriptLabel.getStyleClass().add("muted-small-label");
        titleBox.getChildren().addAll(titleLabel, scriptLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        searchField = new TextField();
        searchField.getStyleClass().add("lua-kv-search-field");
        searchField.setPromptText(I18n.t("meshIde.kv.searchPrompt"));
        searchField.textProperty().addListener((obs, oldValue, newValue) -> applyFilter());

        ToolBar toolbar = new ToolBar();
        toolbar.getStyleClass().add("ide-toolbar");
        Button refreshButton = createToolbarButton(
                I18n.t("meshIde.action.refresh"),
                I18n.t("meshIde.kv.tooltip.refresh"),
                "/icons/refresh.svg",
                () -> {
                    refreshRows(selectedKey());
                    setStatus(I18n.t("meshIde.kv.status.refreshed", countText()));
                });
        Button newButton = createToolbarButton(
                I18n.t("meshIde.action.newEntry"),
                I18n.t("meshIde.kv.tooltip.newEntry"),
                "/icons/add.svg",
                this::beginNewEntry);
        toolbar.getItems().addAll(refreshButton, new Separator(Orientation.VERTICAL), newButton);

        header.getChildren().addAll(titleBox, spacer, searchField, toolbar);
        return header;
    }

    private VBox createTablePanel() {
        table = new TableView<>(filteredRows);
        table.getStyleClass().addAll("lua-dev-table", "lua-kv-table");
        table.setEditable(false);
        table.setPlaceholder(new Label(I18n.t("meshIde.kv.empty")));
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<KvEntryRow, String> keyColumn = new TableColumn<>(I18n.t("meshIde.column.key"));
        keyColumn.setCellValueFactory(data -> data.getValue().keyProperty());
        keyColumn.setPrefWidth(260);

        TableColumn<KvEntryRow, String> valueColumn = new TableColumn<>(I18n.t("meshIde.column.value"));
        valueColumn.setCellValueFactory(data -> data.getValue().valueProperty());
        valueColumn.setCellFactory(column -> createValueCell());
        valueColumn.setPrefWidth(520);

        table.getColumns().add(keyColumn);
        table.getColumns().add(valueColumn);
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> showEntry(newRow));

        VBox panel = createPanel(I18n.t("meshIde.kv.panel.database"), table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return panel;
    }

    private VBox createEditorPanel() {
        keyField = new TextField();
        keyField.setPromptText(I18n.t("meshIde.column.key"));
        keyField.setMaxWidth(Double.MAX_VALUE);

        valueArea = new TextArea();
        valueArea.getStyleClass().add("lua-kv-value-area");
        valueArea.setPromptText(I18n.t("meshIde.column.value"));
        valueArea.setWrapText(false);
        valueArea.setPrefRowCount(7);
        valueArea.setMaxWidth(Double.MAX_VALUE);

        Button newButton = new Button(I18n.t("meshIde.action.newEntry"));
        newButton.setOnAction(event -> beginNewEntry());

        deleteButton = new Button(I18n.t("common.delete"));
        deleteButton.setDisable(true);
        deleteButton.setOnAction(event -> deleteSelectedEntry());

        Button saveButton = new Button(I18n.t("common.save"));
        saveButton.getStyleClass().add("accent");
        saveButton.setOnAction(event -> saveCurrentEntry());

        HBox actions = new HBox(10, newButton, deleteButton, saveButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox editor = new VBox(7,
                fieldLabel(I18n.t("meshIde.column.key")),
                keyField,
                fieldLabel(I18n.t("meshIde.column.value")),
                valueArea,
                actions);
        VBox.setVgrow(valueArea, Priority.ALWAYS);

        VBox panel = createPanel(I18n.t("meshIde.kv.panel.editor"), editor);
        VBox.setVgrow(editor, Priority.ALWAYS);
        return panel;
    }

    private HBox createStatusBar() {
        HBox statusBar = new HBox(8);
        statusBar.getStyleClass().add("lua-dev-status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusLabel = new Label(I18n.t("meshIde.dev.status.ready"));
        statusLabel.getStyleClass().add("config-status-label");
        statusBar.getChildren().add(statusLabel);
        return statusBar;
    }

    private VBox createPanel(String title, Node content) {
        VBox panel = new VBox(6);
        panel.getStyleClass().add("lua-dev-panel");
        if (title != null && !title.isBlank()) {
            panel.getChildren().add(fieldLabel(title));
        }
        panel.getChildren().add(content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return panel;
    }

    private Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("packet-monitor-section-title");
        return label;
    }

    private TableCell<KvEntryRow, String> createValueCell() {
        return new TableCell<>() {
            private final Label label = new Label();

            {
                label.getStyleClass().add("lua-value-cell-text");
                label.setMaxWidth(Double.MAX_VALUE);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                label.setText(item != null ? item : "");
                setText(null);
                setGraphic(label);
            }
        };
    }

    private Button createToolbarButton(String title, String description, String iconPath, Runnable action) {
        Button button = new Button();
        button.getStyleClass().add("ide-toolbar-button");
        button.setMinSize(34, 34);
        button.setPrefSize(34, 34);
        button.setMaxSize(34, 34);
        button.setFocusTraversable(false);
        button.setAccessibleText(title);
        setToolbarButtonGraphic(button, iconPath, title);
        button.setTooltip(new Tooltip(title + "\n" + description));
        button.setOnAction(event -> action.run());
        return button;
    }

    private void setToolbarButtonGraphic(Button button, String iconPath, String fallbackText) {
        SVGPath icon = SvgIconLoader.load(iconPath, 18);
        if (icon != null) {
            button.setGraphic(icon);
            button.setText(null);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            button.setGraphic(null);
            button.setText(fallbackText);
            button.setContentDisplay(ContentDisplay.TEXT_ONLY);
        }
    }

    private void refreshRows(String selectKey) {
        rows.setAll(scriptService.listKv(scriptId).entrySet().stream()
                .map(entry -> new KvEntryRow(entry.getKey(), entry.getValue()))
                .toList());
        applyFilter();
        selectKey(selectKey);
        if (table.getSelectionModel().getSelectedItem() == null) {
            clearEditorFields();
        }
        setStatus(countText());
    }

    private void applyFilter() {
        String query = normalizedSearchQuery();
        filteredRows.setPredicate(row -> matchesSearch(row.key(), row.value(), query));
        KvEntryRow selected = table != null ? table.getSelectionModel().getSelectedItem() : null;
        if (selected != null && !filteredRows.contains(selected)) {
            table.getSelectionModel().clearSelection();
            clearEditorFields();
        }
        setStatus(countText());
    }

    private void showEntry(KvEntryRow row) {
        if (row == null) {
            clearEditorFields();
            return;
        }
        keyField.setText(row.key());
        valueArea.setText(row.value());
        deleteButton.setDisable(false);
    }

    private void beginNewEntry() {
        table.getSelectionModel().clearSelection();
        clearEditorFields();
        setStatus(I18n.t("meshIde.kv.status.newEntry"));
        Platform.runLater(keyField::requestFocus);
    }

    private void clearEditorFields() {
        if (keyField != null) {
            keyField.clear();
        }
        if (valueArea != null) {
            valueArea.clear();
        }
        if (deleteButton != null) {
            deleteButton.setDisable(true);
        }
    }

    private void saveCurrentEntry() {
        String key = keyField.getText() != null ? keyField.getText().trim() : "";
        if (key.isEmpty()) {
            setStatus(I18n.t("meshIde.kv.status.enterKey"));
            keyField.requestFocus();
            return;
        }
        if (key.length() > MAX_KEY_LENGTH) {
            setStatus(I18n.t("meshIde.kv.status.keyTooLong", Integer.toString(MAX_KEY_LENGTH)));
            keyField.requestFocus();
            return;
        }

        KvEntryRow selected = table.getSelectionModel().getSelectedItem();
        String originalKey = selected != null ? selected.key() : null;
        boolean keyChanged = originalKey != null && !originalKey.equals(key);
        if ((originalKey == null || keyChanged) && keyExists(key)) {
            setStatus(I18n.t("meshIde.kv.status.keyExists", key));
            keyField.requestFocus();
            return;
        }

        String value = valueArea.getText() != null ? valueArea.getText() : "";
        try {
            if (keyChanged) {
                scriptService.deleteKv(scriptId, originalKey);
            }
            scriptService.setKv(scriptId, key, value);
            refreshRows(key);
            setStatus(I18n.t("meshIde.kv.status.saved", key));
        } catch (Exception e) {
            setStatus(I18n.t("meshIde.kv.status.saveError", e.getMessage()));
        }
    }

    private void deleteSelectedEntry() {
        KvEntryRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus(I18n.t("meshIde.kv.status.selectForDelete"));
            return;
        }
        if (!confirmDelete(selected.key())) {
            return;
        }
        boolean deleted = scriptService.deleteKv(scriptId, selected.key());
        refreshRows(null);
        beginNewEntry();
        setStatus(I18n.t(deleted ? "meshIde.kv.status.deleted" : "meshIde.kv.status.notFound", selected.key()));
    }

    private boolean confirmDelete(String key) {
        ButtonType deleteType = new ButtonType(I18n.t("common.delete"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.t("meshIde.kv.delete.title"));
        alert.setHeaderText(I18n.t("meshIde.kv.delete.header", key));
        alert.setContentText(I18n.t("meshIde.kv.delete.content"));
        alert.getButtonTypes().setAll(cancelType, deleteType);
        alert.initOwner(stage);
        return alert.showAndWait().orElse(cancelType) == deleteType;
    }

    private boolean keyExists(String key) {
        return rows.stream().anyMatch(row -> row.key().equals(key));
    }

    private void selectKey(String key) {
        if (key == null || key.isBlank()) {
            table.getSelectionModel().clearSelection();
            return;
        }
        filteredRows.stream()
                .filter(row -> row.key().equals(key))
                .findFirst()
                .ifPresentOrElse(
                        row -> table.getSelectionModel().select(row),
                        () -> table.getSelectionModel().clearSelection());
    }

    private String selectedKey() {
        KvEntryRow selected = table != null ? table.getSelectionModel().getSelectedItem() : null;
        return selected != null ? selected.key() : null;
    }

    private String normalizedSearchQuery() {
        String value = searchField != null ? searchField.getText() : "";
        return normalizeSearchQuery(value);
    }

    private String countText() {
        if (normalizedSearchQuery().isBlank()) {
            return I18n.t("meshIde.kv.count.total", Integer.toString(rows.size()));
        }
        return I18n.t("meshIde.kv.count.filtered",
                Integer.toString(filteredRows.size()),
                Integer.toString(rows.size()));
    }

    private void setStatus(String text) {
        if (statusLabel != null) {
            statusLabel.setText(text != null ? text : "");
        }
    }

    private void updateTitle() {
        stage.setTitle(windowTitle());
        if (scriptLabel != null) {
            scriptLabel.setText(scriptName);
        }
    }

    private String windowTitle() {
        return I18n.t("meshIde.kv.windowTitle", scriptName);
    }

    private static String displayScriptName(LuaScript script) {
        String name = script.getName();
        return name == null || name.isBlank()
                ? I18n.t("meshIde.kv.scriptFallback", Long.toString(script.getId()))
                : name;
    }

    static boolean matchesSearch(String key, String value, String query) {
        String normalizedQuery = normalizeSearchQuery(query);
        if (normalizedQuery.isBlank()) {
            return true;
        }
        return normalizeSearchQuery(key).contains(normalizedQuery)
                || normalizeSearchQuery(value).contains(normalizedQuery);
    }

    private static String normalizeSearchQuery(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class KvEntryRow {
        private final StringProperty key;
        private final StringProperty value;

        private KvEntryRow(String key, String value) {
            this.key = new SimpleStringProperty(key != null ? key : "");
            this.value = new SimpleStringProperty(value != null ? value : "");
        }

        private String key() {
            return key.get();
        }

        private String value() {
            return value.get();
        }

        private StringProperty keyProperty() {
            return key;
        }

        private StringProperty valueProperty() {
            return value;
        }
    }
}
