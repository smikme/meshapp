package com.meshtastic.client.components;

import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptEvent;
import com.meshtastic.client.lua.LuaScriptRuntimeService;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.lua.LuaScriptStoreService;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Боковая форма магазина Lua-скриптов MeshApp.
 */
public final class LuaScriptStoreForm extends VBox {

    private final LuaScriptService scriptService = LuaScriptService.getInstance();
    private final LuaScriptRuntimeService runtimeService = LuaScriptRuntimeService.getInstance();
    private final LuaScriptStoreService storeService;
    private final Runnable onScriptsChanged;
    private final VBox cardsBox = new VBox(10);
    private final Label statusLabel = new Label();
    private final Button refreshButton = new Button("Обновить");
    private final ComboBox<ScriptTypeFilter> typeFilter = new ComboBox<>(
            FXCollections.observableArrayList(ScriptTypeFilter.values()));

    private List<LuaScriptStoreService.StoreScript> currentScripts = List.of();
    private boolean disposed;

    public LuaScriptStoreForm(Runnable onScriptsChanged) {
        this(new LuaScriptStoreService(), onScriptsChanged);
    }

    LuaScriptStoreForm(LuaScriptStoreService storeService, Runnable onScriptsChanged) {
        this.storeService = storeService;
        this.onScriptsChanged = onScriptsChanged;
        configureLayout();
        loadScripts();
    }

    public void dispose() {
        disposed = true;
    }

    private void configureLayout() {
        setSpacing(10);
        setPadding(new Insets(20, 30, 20, 30));
        setPrefWidth(520);
        setMaxWidth(520);
        setMaxHeight(Double.MAX_VALUE);
        getStyleClass().add("modal-side-panel");

        Label title = new Label("Магазин скриптов");
        title.getStyleClass().add("dialog-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        refreshButton.setOnAction(event -> loadScripts());

        HBox titleRow = new HBox(10, title, spacer, refreshButton);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        HBox filterBar = createFilterBar();

        statusLabel.getStyleClass().add("muted-small-label");
        statusLabel.setWrapText(true);

        cardsBox.setFillWidth(true);

        ScrollPane scrollPane = new ScrollPane(cardsBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        Button closeButton = new Button("Закрыть");
        closeButton.setOnAction(event -> closeModal());
        HBox actions = new HBox(closeButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(10, 0, 0, 0));

        getChildren().addAll(titleRow, new Separator(), filterBar, statusLabel, scrollPane, actions);
    }

    private HBox createFilterBar() {
        Label typeLabel = new Label("Тип");
        typeLabel.getStyleClass().add("packet-monitor-filter-label");

        typeFilter.getSelectionModel().select(ScriptTypeFilter.ALL);
        typeFilter.setMinWidth(180);
        typeFilter.setPrefWidth(180);
        typeFilter.valueProperty().addListener((obs, oldValue, newValue) -> rebuildCards());

        HBox filterBar = new HBox(10, typeLabel, typeFilter);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.getStyleClass().add("packet-monitor-filter-bar");
        return filterBar;
    }

    private void loadScripts() {
        refreshButton.setDisable(true);
        statusLabel.setText("Загрузка магазина...");
        cardsBox.getChildren().setAll(placeholder("Загрузка..."));

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return storeService.fetchScripts();
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(e);
                    }
                })
                .whenComplete((scripts, error) -> Platform.runLater(() -> {
                    if (disposed) {
                        return;
                    }
                    refreshButton.setDisable(false);
                    if (error != null) {
                        showLoadError(error);
                        return;
                    }
                    currentScripts = scripts != null ? scripts : List.of();
                    rebuildCards();
                }));
    }

    private void showLoadError(Throwable error) {
        String message = userMessage(error);
        statusLabel.setText("Не удалось загрузить магазин: " + message);

        Button retryButton = new Button("Повторить");
        retryButton.getStyleClass().add("accent");
        retryButton.setOnAction(event -> loadScripts());

        VBox box = new VBox(10, placeholder("Магазин недоступен"), retryButton);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(15));
        box.getStyleClass().add("connection-card");
        cardsBox.getChildren().setAll(box);
    }

    private void rebuildCards() {
        Map<String, LuaScript> installedByGuid = installedScriptsByGuid();
        cardsBox.getChildren().clear();
        if (currentScripts.isEmpty()) {
            statusLabel.setText("В магазине нет доступных скриптов");
            cardsBox.getChildren().setAll(placeholder("Список пуст"));
            return;
        }

        List<LuaScriptStoreService.StoreScript> visibleScripts = currentScripts.stream()
                .filter(this::matchesTypeFilter)
                .toList();
        if (visibleScripts.isEmpty()) {
            statusLabel.setText("Скриптов выбранного типа нет");
            cardsBox.getChildren().setAll(placeholder("Список пуст"));
            return;
        }

        statusLabel.setText(statusText(visibleScripts.size(), currentScripts.size()));
        for (LuaScriptStoreService.StoreScript storeScript : visibleScripts) {
            LuaScript installed = installedByGuid.get(storeScript.guid());
            cardsBox.getChildren().add(createStoreCard(storeScript, installed));
        }
    }

    private VBox createStoreCard(LuaScriptStoreService.StoreScript storeScript, LuaScript installed) {
        boolean updateAvailable = installed != null && storeScript.version() > installed.getVersion();

        VBox card = new VBox(8);
        card.setPadding(new Insets(15));
        card.getStyleClass().add("connection-card");

        HBox topRow = new HBox(10);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label icon = new Label(storeScript.icon());
        icon.setStyle("-fx-font-size: 22px;");

        Label name = new Label(storeScript.name());
        name.getStyleClass().add("connection-card-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMaxWidth(Double.MAX_VALUE);

        Label version = new Label(versionText(storeScript, installed));
        version.getStyleClass().add("muted-small-label");

        Label type = new Label("Тип: " + scriptTypeText(storeScript.botType()));
        type.getStyleClass().add("muted-small-label");

        Label author = new Label(authorText(storeScript.author()));
        author.getStyleClass().add("muted-small-label");

        VBox titleBox = new VBox(2, name, author, type, version);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        HBox actions = createActions(storeScript, installed, updateAvailable);

        topRow.getChildren().addAll(icon, titleBox, actions);
        card.getChildren().add(topRow);

        if (storeScript.description() != null && !storeScript.description().isBlank()) {
            Label description = new Label(truncate(storeScript.description(), 420));
            description.setWrapText(true);
            description.setStyle("-fx-opacity: 0.78;");
            card.getChildren().add(description);
        }

        return card;
    }

    private HBox createActions(LuaScriptStoreService.StoreScript storeScript,
                               LuaScript installed,
                               boolean updateAvailable) {
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);

        if (installed == null) {
            Button installButton = new Button("Установить");
            installButton.getStyleClass().add("accent");
            installButton.setOnAction(event -> installOrUpdate(storeScript, null));
            actions.getChildren().add(installButton);
            return actions;
        }

        if (updateAvailable) {
            Button updateButton = new Button("Обновить");
            updateButton.getStyleClass().add("accent");
            updateButton.setOnAction(event -> installOrUpdate(storeScript, installed));
            actions.getChildren().add(updateButton);
        }

        Button deleteButton = new Button("Удалить");
        deleteButton.setOnAction(event -> deleteInstalledScript(installed));
        actions.getChildren().add(deleteButton);
        return actions;
    }

    private void installOrUpdate(LuaScriptStoreService.StoreScript storeScript, LuaScript installed) {
        try {
            if (installed != null && runtimeService.isRunning(installed.getId())) {
                runtimeService.stopScript(installed.getId(), this::ignoreRuntimeEvent);
            }
            LuaScriptService.ScriptImportResult result = scriptService.importScriptExport(storeScript.exportFile());
            notifyScriptsChanged();
            rebuildCards();
            Toast.show(
                    Toast.Type.SUCCESS,
                    (result.updated() ? "Обновлен скрипт: " : "Установлен скрипт: ")
                            + result.script().getName());
        } catch (Exception e) {
            Toast.show(Toast.Type.ERROR, "Ошибка установки: " + userMessage(e));
        }
    }

    private void deleteInstalledScript(LuaScript installed) {
        if (installed == null || !confirmDelete(installed)) {
            return;
        }
        runtimeService.stopScript(installed.getId(), this::ignoreRuntimeEvent);
        scriptService.deleteScript(installed.getId());
        notifyScriptsChanged();
        rebuildCards();
        Toast.show(Toast.Type.SUCCESS, "Удален скрипт: " + installed.getName());
    }

    private boolean confirmDelete(LuaScript script) {
        ButtonType deleteButton = new ButtonType("Удалить", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Удалить скрипт");
        alert.setHeaderText("Удалить скрипт \"" + script.getName() + "\"?");
        alert.setContentText("Скрипт будет удален из локального списка MeshApp IDE.");
        alert.getButtonTypes().setAll(cancelButton, deleteButton);
        if (getScene() != null && getScene().getWindow() != null) {
            alert.initOwner(getScene().getWindow());
        }

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == deleteButton;
    }

    private Map<String, LuaScript> installedScriptsByGuid() {
        Map<String, LuaScript> result = new HashMap<>();
        for (LuaScript script : scriptService.listScripts()) {
            String guid = normalizeGuidKey(script.getGuid());
            if (!guid.isBlank()) {
                result.put(guid, script);
            }
        }
        return result;
    }

    private String versionText(LuaScriptStoreService.StoreScript storeScript, LuaScript installed) {
        if (installed == null) {
            return "v" + storeScript.version();
        }
        if (storeScript.version() > installed.getVersion()) {
            return "v" + storeScript.version() + " доступна, установлена v" + installed.getVersion();
        }
        if (storeScript.version() == installed.getVersion()) {
            return "v" + storeScript.version() + " установлена";
        }
        return "v" + storeScript.version() + " в магазине, установлена v" + installed.getVersion();
    }

    private boolean matchesTypeFilter(LuaScriptStoreService.StoreScript script) {
        ScriptTypeFilter filter = typeFilter.getValue();
        return filter == null || filter.botType() == null || script.botType() == filter.botType();
    }

    private String statusText(int visibleCount, int totalCount) {
        ScriptTypeFilter filter = typeFilter.getValue();
        if (filter == null || filter == ScriptTypeFilter.ALL) {
            return "Доступно скриптов: " + totalCount;
        }
        return "Показано: " + visibleCount + " из " + totalCount;
    }

    private static String scriptTypeText(LuaScript.BotType botType) {
        return botType == LuaScript.BotType.AUTOMATION_BOT ? "Автоматизация" : "Бот";
    }

    private static String authorText(String author) {
        String value = author == null ? "" : author.trim();
        return value.isBlank() ? "Автор: не указан" : "Автор: " + value;
    }

    private void notifyScriptsChanged() {
        if (onScriptsChanged != null) {
            onScriptsChanged.run();
        }
    }

    private void ignoreRuntimeEvent(LuaScriptEvent event) {
        // Store actions only need to stop the old runtime session before changing local script data.
    }

    private void closeModal() {
        ModalPane pane = ModalPane.getInstance();
        if (pane != null) {
            pane.hide();
        }
    }

    private Label placeholder(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-opacity: 0.65;");
        return label;
    }

    private static String normalizeGuidKey(String guid) {
        return guid == null ? "" : guid.trim().toLowerCase(Locale.ROOT);
    }

    private static String userMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "операция не выполнена" : message;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)) + "...";
    }

    private enum ScriptTypeFilter {
        ALL("Все типы", null),
        BOT("Бот", LuaScript.BotType.AIR_BOT),
        AUTOMATION("Автоматизация", LuaScript.BotType.AUTOMATION_BOT);

        private final String label;
        private final LuaScript.BotType botType;

        ScriptTypeFilter(String label, LuaScript.BotType botType) {
            this.label = label;
            this.botType = botType;
        }

        private LuaScript.BotType botType() {
            return botType;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
