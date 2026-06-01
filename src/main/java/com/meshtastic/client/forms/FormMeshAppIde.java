package com.meshtastic.client.forms;

import com.meshtastic.client.components.LuaDevWindow;
import com.meshtastic.client.components.LuaKvEditorWindow;
import com.meshtastic.client.components.LuaScriptSettingsForm;
import com.meshtastic.client.components.LuaScriptStoreForm;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptEvent;
import com.meshtastic.client.lua.LuaScriptRuntimeService;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.SystemForm;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Main MeshApp IDE form showing the user's Lua scripts.
 * <p>
 * Displays script settings, runtime state, and control actions. The editor
 * window opens only when the user explicitly chooses to edit a selected script.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@SystemForm(name = "MeshApp IDE", description = "Скрипты и автоматизация", tags = {"lua", "scripts", "automation"})
public class FormMeshAppIde extends Form {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final LuaScriptService scriptService = LuaScriptService.getInstance();
    private final LuaScriptRuntimeService runtimeService = LuaScriptRuntimeService.getInstance();

    private VBox cardsBox;
    private Label emptyLabel;

    public FormMeshAppIde() {
        init();
    }

    private void init() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(I18n.t("meshIde.title"));
        title.getStyleClass().add("form-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("ide-toolbar");

        Button refreshButton = createToolbarButton(
                I18n.t("meshIde.action.refresh"),
                I18n.t("meshIde.tooltip.refresh"),
                "/icons/refresh.svg",
                this::rebuildCards);

        Button importButton = createToolbarButton(
                I18n.t("meshIde.action.import"),
                I18n.t("meshIde.tooltip.import"),
                "/icons/load-config.svg",
                this::importScript);

        Button storeButton = createStoreToolbarButton(
                I18n.t("meshIde.action.store"),
                I18n.t("meshIde.tooltip.store"),
                this::showScriptStore);

        Button createButton = createToolbarButton(
                I18n.t("meshIde.action.newScript"),
                I18n.t("meshIde.tooltip.newScript"),
                "/icons/add.svg",
                this::createScript);

        actionToolbar.getItems().addAll(
                refreshButton,
                new Separator(Orientation.VERTICAL),
                storeButton,
                importButton,
                createButton
        );

        titleRow.getChildren().addAll(title, spacer, actionToolbar);

        emptyLabel = new Label(I18n.t("meshIde.empty.noScripts"));
        emptyLabel.setStyle("-fx-opacity: 0.65;");

        cardsBox = new VBox(10);

        ScrollPane scrollPane = new ScrollPane(cardsBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        content.getChildren().addAll(titleRow, scrollPane);
        getChildren().add(content);
    }

    @Override
    public void formOpen() {
        rebuildCards();
    }

    @Override
    public void formRefresh() {
        rebuildCards();
    }

    private void rebuildCards() {
        List<LuaScript> scripts = scriptService.listScripts();
        cardsBox.getChildren().clear();
        if (scripts.isEmpty()) {
            VBox emptyCard = new VBox(emptyLabel);
            emptyCard.setPadding(new Insets(15));
            emptyCard.getStyleClass().add("connection-card");
            cardsBox.getChildren().add(emptyCard);
            return;
        }
        for (LuaScript script : scripts) {
            cardsBox.getChildren().add(createScriptCard(script));
        }
    }

    private VBox createScriptCard(LuaScript script) {
        boolean running = runtimeService.isRunning(script.getId());
        VBox card = new VBox(8);
        card.setPadding(new Insets(15));
        card.getStyleClass().add("connection-card");
        if (running) {
            card.setStyle("-fx-border-color: #1EA97C; -fx-border-width: 0 0 0 4; -fx-background-radius: 20; -fx-border-radius: 20;");
        } else if (!script.isEnabled()) {
            card.setStyle("-fx-border-color: #9CA3AF; -fx-border-width: 0 0 0 4; -fx-background-radius: 20; -fx-border-radius: 20;");
        }

        HBox topRow = new HBox(10);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label indicator = new Label("\u25CF");
        indicator.setStyle("-fx-text-fill: " + indicatorColor(script, running) + "; -fx-font-weight: bold;");

        Label icon = new Label(script.getIcon());
        icon.setStyle("-fx-font-size: 18px;");

        Label name = new Label(script.getName());
        name.getStyleClass().add("connection-card-name");

        Label status = new Label(statusText(script, running));
        status.setStyle("-fx-text-fill: " + indicatorColor(script, running) + "; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar actionToolbar = createScriptActionToolbar(script, running);

        topRow.getChildren().addAll(indicator, icon, name, status, spacer, actionToolbar);

        Label params = new Label(scriptSummary(script));
        params.setWrapText(true);
        params.setStyle("-fx-opacity: 0.68;");

        card.getChildren().addAll(topRow, params);
        if (script.getDescription() != null && !script.getDescription().isBlank()) {
            Label description = new Label(truncate(script.getDescription(), 360));
            description.setWrapText(true);
            description.setStyle("-fx-opacity: 0.78;");
            card.getChildren().add(description);
        }
        if (script.getLastError() != null && !script.getLastError().isBlank()) {
            Label error = new Label(I18n.t("meshIde.error.prefix", truncate(script.getLastError(), 240)));
            error.setWrapText(true);
            error.setStyle("-fx-text-fill: #EF4444;");
            card.getChildren().add(error);
        }
        return card;
    }

    private ToolBar createScriptActionToolbar(LuaScript script, boolean running) {
        ToolBar actionToolbar = new ToolBar();
        actionToolbar.getStyleClass().add("ide-toolbar");

        Button runButton = createToolbarButton(
                running ? I18n.t("meshIde.action.stop") : I18n.t("meshIde.action.run"),
                running ? I18n.t("meshIde.tooltip.stop") : I18n.t("meshIde.tooltip.run"),
                running ? "/icons/ide-stop.svg" : "/icons/ide-terminal-run.svg",
                () -> {
                    if (running) {
                        stopScript(script);
                    } else {
                        runScript(script);
                    }
                });

        Button autostartButton = createToolbarButton(
                script.isAutostart()
                        ? I18n.t("meshIde.action.disableAutostart")
                        : I18n.t("meshIde.action.enableAutostart"),
                script.isAutostart()
                        ? I18n.t("meshIde.tooltip.disableAutostart")
                        : I18n.t("meshIde.tooltip.enableAutostart"),
                "/icons/autoplay.svg",
                () -> toggleAutostart(script));

        Button ideButton = createToolbarButton(
                I18n.t("meshIde.action.openIde"),
                I18n.t("meshIde.tooltip.openIde"),
                "/icons/ide-file-code.svg",
                () -> LuaDevWindow.showWindow(script.getId()));

        Button kvButton = createToolbarButton(
                "KV",
                I18n.t("meshIde.tooltip.kvEditor"),
                "/icons/database.svg",
                () -> LuaKvEditorWindow.showWindow(script));

        Button exportButton = createToolbarButton(
                I18n.t("meshIde.action.export"),
                I18n.t("meshIde.tooltip.export"),
                "/icons/export.svg",
                () -> exportScript(script));

        Button editButton = createToolbarButton(
                I18n.t("meshIde.action.settings"),
                I18n.t("meshIde.tooltip.settings"),
                "/drawer/icon/setting.svg",
                () -> showSettingsDialog(script));

        Button deleteButton = createToolbarButton(
                I18n.t("common.delete"),
                I18n.t("meshIde.tooltip.delete"),
                "/drawer/icon/delete-node.svg",
                () -> deleteScript(script));

        actionToolbar.getItems().addAll(
                runButton,
                autostartButton,
                new Separator(Orientation.VERTICAL),
                ideButton,
                kvButton,
                exportButton,
                editButton,
                new Separator(Orientation.VERTICAL),
                deleteButton
        );
        return actionToolbar;
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

    private Button createStoreToolbarButton(String title, String description, Runnable action) {
        Button button = new Button("🛍️");
        button.getStyleClass().addAll("ide-toolbar-button", "script-store-toolbar-button");
        button.setMinSize(34, 34);
        button.setPrefSize(34, 34);
        button.setMaxSize(34, 34);
        button.setFocusTraversable(false);
        button.setAccessibleText(title);
        button.setContentDisplay(ContentDisplay.TEXT_ONLY);
        button.setStyle("-fx-font-size: 17px;");
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

    private void createScript() {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            return;
        }
        LuaScript draftScript = scriptService.createDraftScript();
        LuaScriptSettingsForm form = new LuaScriptSettingsForm(draftScript);
        form.setOnSave(draft -> {
            try {
                LuaScript created = scriptService.createScript(
                        draft.name(),
                        draftScript.getCode(),
                        draft.autostart(),
                        draft.icon(),
                        draft.nodeId(),
                        draft.botType(),
                        draft.automationName(),
                        draft.description(),
                        draft.author());
                modalPane.hide();
                rebuildCards();
                Toast.show(Toast.Type.SUCCESS, I18n.t("meshIde.toast.created", created.getName()));
            } catch (Exception e) {
                Toast.show(Toast.Type.ERROR, I18n.t("meshIde.toast.createFailed", userMessage(e)));
            }
        });
        modalPane.show(form);
        modalPane.setOnHidden(form::dispose);
    }

    private void showScriptStore() {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            return;
        }
        LuaScriptStoreForm form = new LuaScriptStoreForm(this::rebuildCards);
        modalPane.show(form);
        modalPane.setOnHidden(form::dispose);
    }

    private void importScript() {
        FileChooser chooser = createScriptJsonChooser(I18n.t("meshIde.file.importTitle"), false, null);
        File source = chooser.showOpenDialog(currentWindow());
        if (source == null) {
            return;
        }
        try {
            LuaScriptService.ScriptImportResult result = scriptService.importScript(source.toPath());
            rebuildCards();
            Toast.show(
                    Toast.Type.SUCCESS,
                    I18n.t(result.updated() ? "meshIde.toast.updated" : "meshIde.toast.imported",
                            result.script().getName()));
        } catch (Exception e) {
            Toast.show(Toast.Type.ERROR, I18n.t("meshIde.toast.importFailed", userMessage(e)));
        }
    }

    private void exportScript(LuaScript script) {
        FileChooser chooser = createScriptJsonChooser(I18n.t("meshIde.file.exportTitle"), true, script);
        File target = chooser.showSaveDialog(currentWindow());
        if (target == null) {
            return;
        }
        File outputFile = ensureJsonExtension(target);
        try {
            scriptService.exportScript(script.getId(), outputFile.toPath());
            Toast.show(Toast.Type.SUCCESS, I18n.t("meshIde.toast.exported", outputFile.getName()));
        } catch (IOException e) {
            Toast.show(Toast.Type.ERROR, I18n.t("meshIde.toast.exportFailed", userMessage(e)));
        } catch (Exception e) {
            Toast.show(Toast.Type.ERROR, I18n.t("meshIde.toast.exportFailed", userMessage(e)));
        }
    }

    private void toggleAutostart(LuaScript script) {
        try {
            scriptService.saveScript(script.getId(), script.getName(), script.getCode(), !script.isAutostart());
            rebuildCards();
        } catch (Exception e) {
            Toast.show(Toast.Type.ERROR, I18n.t("meshIde.toast.stateChangeFailed", userMessage(e)));
        }
    }

    private void showSettingsDialog(LuaScript script) {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            return;
        }
        LuaScriptSettingsForm form = new LuaScriptSettingsForm(script);
        form.setOnSave(draft -> {
            try {
                LuaScript saved = scriptService.saveScriptSettings(
                        script.getId(),
                        draft.name(),
                        draft.autostart(),
                        draft.icon(),
                        draft.nodeId(),
                        draft.botType(),
                        draft.automationName(),
                        draft.description(),
                        draft.author());
                modalPane.hide();
                rebuildCards();
                Toast.show(Toast.Type.SUCCESS, I18n.t("meshIde.toast.saved", saved.getName()));
            } catch (Exception e) {
                Toast.show(Toast.Type.ERROR, I18n.t("meshIde.toast.saveFailed", userMessage(e)));
            }
        });
        modalPane.show(form);
        modalPane.setOnHidden(form::dispose);
    }

    private void runScript(LuaScript script) {
        runtimeService.runScript(script, this::handleRuntimeEvent);
        rebuildCards();
        Toast.show(Toast.Type.INFO, I18n.t("meshIde.toast.run", script.getName()));
    }

    private void stopScript(LuaScript script) {
        runtimeService.stopScript(script.getId(), this::handleRuntimeEvent);
        rebuildCards();
        Toast.show(Toast.Type.INFO, I18n.t("meshIde.toast.stopped", script.getName()));
    }

    private void deleteScript(LuaScript script) {
        ModalPane.showConfirm(
                I18n.t("meshIde.confirm.delete.title"),
                I18n.t("meshIde.confirm.delete.message", script.getName()),
                confirmed -> {
                    if (!confirmed) {
                        return;
                    }
                    runtimeService.stopScript(script.getId(), this::handleRuntimeEvent);
                    scriptService.deleteScript(script.getId());
                    rebuildCards();
                    Toast.show(Toast.Type.SUCCESS, I18n.t("meshIde.toast.deleted", script.getName()));
                });
    }

    private void handleRuntimeEvent(LuaScriptEvent event) {
        Platform.runLater(() -> {
            rebuildCards();
            if (event.type() == LuaScriptEvent.Type.ERROR) {
                Toast.show(Toast.Type.ERROR, event.message());
            }
        });
    }

    private String scriptSummary(LuaScript script) {
        List<String> parts = new ArrayList<>();
        parts.add(I18n.t("meshIde.summary.version", Long.toString(script.getVersion())));
        String author = authorSummary(script);
        if (!author.isBlank()) {
            parts.add(author);
        }
        parts.add(I18n.t(script.isAutostart()
                ? "meshIde.summary.autostartOn"
                : "meshIde.summary.autostartOff"));
        parts.add(script.getBotType().getDisplayName());
        String automation = automationSummary(script);
        if (!automation.isBlank()) {
            parts.add(automation);
        }
        parts.add(nodeSummary(script));
        parts.add(I18n.t("meshIde.summary.lines", Integer.toString(lineCount(script.getCode()))));
        parts.add(I18n.t("meshIde.summary.updated", formatTime(script.getUpdatedAt())));
        parts.add(I18n.t("meshIde.summary.lastRun", formatLastRun(script.getLastRunAt())));
        return String.join(" · ", parts);
    }

    private String statusText(LuaScript script, boolean running) {
        if (running) {
            return I18n.t("meshIde.status.running");
        }
        String status = script.getLastStatus();
        if (status == null || status.isBlank() || "NEW".equalsIgnoreCase(status)) {
            return I18n.t("meshIde.status.new");
        }
        if ("ERROR".equalsIgnoreCase(status)) {
            return I18n.t("meshIde.status.error");
        }
        if ("STOPPED".equalsIgnoreCase(status)) {
            return I18n.t("meshIde.status.stopped");
        }
        if ("OK".equalsIgnoreCase(status)) {
            return I18n.t("meshIde.status.ok");
        }
        return status.toUpperCase(Locale.ROOT);
    }

    private String indicatorColor(LuaScript script, boolean running) {
        if (running) {
            return "#1EA97C";
        }
        if (!script.isAutostart()) {
            return "#9CA3AF";
        }
        String status = script.getLastStatus();
        if ("ERROR".equalsIgnoreCase(status)) {
            return "#EF4444";
        }
        return "#60A5FA";
    }

    private String automationSummary(LuaScript script) {
        if (script.getBotType() != LuaScript.BotType.AUTOMATION_BOT) {
            return "";
        }
        String automationName = script.getAutomationName();
        return automationName == null || automationName.isBlank()
                ? I18n.t("meshIde.summary.automationMissing")
                : automationName;
    }

    private String authorSummary(LuaScript script) {
        String author = script.getAuthor();
        return author == null || author.isBlank() ? "" : I18n.t("meshIde.summary.author", author);
    }

    private String nodeSummary(LuaScript script) {
        if (script.getBotType() == LuaScript.BotType.AUTOMATION_BOT) {
            return I18n.t("meshIde.summary.connectionCurrent");
        }
        String nodeId = script.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            return I18n.t("meshIde.summary.nodeMissing");
        }
        ConnectionManager manager = ConnectionManager.getInstance();
        for (ConnectionEntry entry : manager.getEntries()) {
            String entryNodeId = firstNonBlank(manager.getOwnerNodeId(entry.getId()), entry.getNodeId());
            if (nodeId.equalsIgnoreCase(entryNodeId)) {
                return I18n.t("meshIde.summary.nodeWithName", entry.getName(), nodeId);
            }
        }
        return I18n.t("meshIde.summary.node", nodeId);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second != null ? second.trim() : "";
    }

    private String formatLastRun(long epochSeconds) {
        return epochSeconds > 0 ? formatTime(epochSeconds) : I18n.t("meshIde.summary.never");
    }

    private String formatTime(long epochSeconds) {
        return epochSeconds > 0 ? TIME_FORMAT.format(Instant.ofEpochSecond(epochSeconds)) : "-";
    }

    private int lineCount(String code) {
        if (code == null || code.isEmpty()) {
            return 0;
        }
        return code.split("\\R", -1).length;
    }

    private FileChooser createScriptJsonChooser(String title, boolean saveMode, LuaScript script) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(I18n.t("meshIde.file.type"), "*.json"));
        if (saveMode && script != null) {
            chooser.setInitialFileName(suggestedExportFileName(script));
        }
        return chooser;
    }

    private String suggestedExportFileName(LuaScript script) {
        String name = script.getName() != null ? script.getName().trim() : "";
        if (name.isBlank()) {
            name = "lua-script";
        }
        String safeName = name.replaceAll("[^\\p{L}\\p{N}._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (safeName.isBlank()) {
            safeName = "lua-script";
        }
        return safeName + ".meshapp-script.json";
    }

    private File ensureJsonExtension(File file) {
        String name = file.getName();
        if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return file;
        }
        File parent = file.getParentFile();
        return new File(parent != null ? parent : new File("."), name + ".json");
    }

    private Window currentWindow() {
        return getScene() != null ? getScene().getWindow() : null;
    }

    private String userMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? I18n.t("meshIde.operationFailed") : message;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)) + "...";
    }
}
