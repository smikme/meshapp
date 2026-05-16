package com.meshtastic.client.components;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.lua.LuaDebugSnapshot;
import com.meshtastic.client.lua.LuaDebugVariable;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptEvent;
import com.meshtastic.client.lua.LuaScriptRuntimeService;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.SvgIconLoader;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LuaDevWindow {

    private static final double DEFAULT_WINDOW_WIDTH = 1280;
    private static final double DEFAULT_WINDOW_HEIGHT = 860;
    private static final int MAX_COMPLETIONS = 9;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Pattern LUA_HIGHLIGHT_PATTERN = Pattern.compile(
            "(?<COMMENT>--\\[\\[[\\s\\S]*?\\]\\]|--[^\\n]*)"
                    + "|(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
                    + "|(?<NUMBER>\\b\\d+(?:\\.\\d+)?\\b)"
                    + "|(?<API>\\bmesh(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)"
                    + "|(?<KEYWORD>\\b(?:and|break|do|else|elseif|end|false|for|function|if|in|local|nil|not|or|repeat|return|then|true|until|while)\\b)"
                    + "|(?<BUILTIN>\\b(?:assert|error|ipairs|next|pairs|pcall|select|tonumber|tostring|type|xpcall|string|table|math|coroutine)\\b)"
    );

    private static final List<String> COMPLETIONS = List.of(
            "function", "local", "return", "if", "then", "else", "elseif", "end",
            "for", "in", "pairs", "ipairs", "while", "do", "repeat", "until",
            "true", "false", "nil",
            "on_message(msg)", "mesh.log(", "mesh.now()", "mesh.owner()",
            "mesh.chat.send_channel(", "mesh.chat.send_dm(", "mesh.chat.recent(",
            "mesh.chat.nodes()", "mesh.chat.channels()",
            "mesh.kv.get(", "mesh.kv.set(", "mesh.kv.delete(", "mesh.kv.list()", "mesh.kv.clear()"
    );

    private static final String API_REFERENCE = """
            Разрешенный Lua API

            print(...) / mesh.log(text)
            mesh.now() -> epoch seconds
            mesh.owner() -> { node_id, node_num, connection_id }

            mesh.chat.send_channel(channel, text) -> message
            mesh.chat.send_dm(node_id, text) -> message
            mesh.chat.recent(chat_type, chat_key, limit) -> { messages }
            mesh.chat.nodes() -> { nodes }
            mesh.chat.channels() -> { channels }

            mesh.kv.get(key) -> string | nil
            mesh.kv.set(key, value) -> true
            mesh.kv.delete(key) -> boolean
            mesh.kv.list() -> table
            mesh.kv.clear() -> true

            Если объявить function on_message(msg), скрипт останется запущенным
            и будет получать новые сообщения выбранного подключения.

            В песочнице нет io, os, debug, package, require, luajava,
            dofile/loadfile и collectgarbage.
            """;

    private static LuaDevWindow instance;

    private final LuaScriptService scriptService = LuaScriptService.getInstance();
    private final LuaScriptRuntimeService runtimeService = LuaScriptRuntimeService.getInstance();
    private final ObservableList<LuaScript> scripts = FXCollections.observableArrayList();
    private final ObservableList<KvRow> kvRows = FXCollections.observableArrayList();
    private final ObservableList<DebugVarRow> debugRows = FXCollections.observableArrayList();
    private final Map<Long, Set<Integer>> breakpointsByScript = new HashMap<>();
    private final Popup completionPopup = new Popup();
    private final VBox completionBox = new VBox();

    private Stage stage;
    private ListView<LuaScript> scriptListView;
    private TextField nameField;
    private CheckBox enabledCheck;
    private CodeArea codeArea;
    private TextArea consoleArea;
    private TableView<KvRow> kvTable;
    private TableView<DebugVarRow> debugTable;
    private Label statusLabel;
    private Button runButton;
    private Button debugButton;
    private Button continueButton;
    private Button stepButton;
    private Button stopButton;
    private IntFunction<Node> lineNumberFactory;
    private LuaScript currentScript;
    private boolean dirty;
    private boolean loadingScript;
    private List<String> visibleCompletions = List.of();
    private String visibleCompletionPrefix = "";
    private int selectedCompletionIndex;
    private Set<Integer> currentBreakpoints = new TreeSet<>();
    private int currentDebugLine = -1;

    private LuaDevWindow() {
        configureCompletionPopup();
        createStage();
        reloadScripts(0);
    }

    public static void showWindow() {
        if (Platform.isFxApplicationThread()) {
            showWindowInternal();
        } else {
            Platform.runLater(LuaDevWindow::showWindowInternal);
        }
    }

    private static void showWindowInternal() {
        if (instance == null) {
            instance = new LuaDevWindow();
        }
        instance.showStage();
    }

    private void showStage() {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        stage.requestFocus();
    }

    private void createStage() {
        VBox root = new VBox(10);
        root.getStyleClass().add("lua-dev-root");
        root.setPadding(new Insets(12));
        root.getChildren().addAll(createHeader(), createContent());

        Scene scene = new Scene(root, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
        ThemeManager.applyTheme(scene, AppPreferences.isDarkMode());
        EmojiRenderingSupport.install(scene);

        stage = new Stage();
        stage.initStyle(StageStyle.DECORATED);
        stage.setTitle("Lua среда разработки");
        stage.setResizable(true);
        if (MeshApp.getPrimaryStage() != null && !MeshApp.getPrimaryStage().getIcons().isEmpty()) {
            stage.getIcons().setAll(MeshApp.getPrimaryStage().getIcons());
        }
        stage.setScene(scene);
    }

    private void configureCompletionPopup() {
        completionBox.getStyleClass().add("lua-completion-popup");
        String appCss = LuaDevWindow.class.getResource("/css/app.css") != null
                ? LuaDevWindow.class.getResource("/css/app.css").toExternalForm()
                : null;
        if (appCss != null) {
            completionBox.getStylesheets().add(appCss);
        }
        completionPopup.setAutoHide(true);
        completionPopup.setHideOnEscape(true);
        completionPopup.getContent().add(completionBox);
    }

    private HBox createHeader() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Lua среда разработки");
        title.getStyleClass().add("form-title");

        statusLabel = new Label("Готово");
        statusLabel.getStyleClass().add("config-status-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar toolbar = new ToolBar();
        toolbar.getStyleClass().add("logs-toolbar");

        Button newButton = createToolbarButton("Новый", "Создать скрипт", "/icons/java.svg", this::createScript);
        Button saveButton = createToolbarButton("Сохранить", "Сохранить код в БД", "/icons/save-text.svg", this::saveCurrentScriptSafely);
        Button deleteButton = createToolbarButton("Удалить", "Удалить скрипт и его KV-хранилище", "/icons/clear.svg", this::deleteCurrentScript);
        Button checkButton = createToolbarButton("Проверить", "Проверить синтаксис Lua", "/icons/eye.svg", this::checkCurrentScript);
        runButton = createToolbarButton("Запустить", "Запустить скрипт", "/icons/play.svg", this::runCurrentScript);
        debugButton = createToolbarButton("Отладка", "Запустить с остановкой на первой строке и breakpoints", "/icons/eye.svg", this::debugCurrentScript);
        continueButton = createToolbarButton("Продолжить", "Продолжить выполнение после паузы", "/icons/play.svg", this::continueDebuggee);
        stepButton = createToolbarButton("Шаг", "Выполнить одну строку", "/icons/refresh.svg", this::stepDebuggee);
        stopButton = createToolbarButton("Остановить", "Остановить скрипт", "/icons/pause.svg", this::stopCurrentScript);
        Button clearConsoleButton = createToolbarButton("Очистить", "Очистить консоль", "/icons/clear.svg", () -> consoleArea.clear());

        toolbar.getItems().addAll(
                newButton,
                saveButton,
                deleteButton,
                new Separator(Orientation.VERTICAL),
                checkButton,
                runButton,
                debugButton,
                continueButton,
                stepButton,
                stopButton,
                new Separator(Orientation.VERTICAL),
                clearConsoleButton
        );

        header.getChildren().addAll(title, statusLabel, spacer, toolbar);
        return header;
    }

    private SplitPane createContent() {
        SplitPane splitPane = new SplitPane(createScriptListPane(), createEditorPane(), createInfoPane());
        splitPane.setDividerPositions(0.20, 0.78);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        return splitPane;
    }

    private VBox createScriptListPane() {
        Label label = new Label("Скрипты");
        label.getStyleClass().add("packet-monitor-section-title");

        scriptListView = new ListView<>(scripts);
        scriptListView.getStyleClass().add("lua-script-list");
        scriptListView.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(LuaScript item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                String status = runtimeService.isRunning(item.getId())
                        ? "RUNNING"
                        : Optional.ofNullable(item.getLastStatus()).orElse("NEW");
                setText(item.getName() + "\n" + status);
                setTooltip(new Tooltip(item.getName()));
            }
        });
        scriptListView.getSelectionModel().selectedItemProperty().addListener((obs, oldScript, newScript) -> {
            if (newScript == null || newScript == currentScript) {
                return;
            }
            if (dirty && currentScript != null) {
                saveCurrentScriptSafely();
            }
            loadScript(newScript);
        });

        VBox pane = new VBox(8, label, scriptListView);
        pane.getStyleClass().add("packet-monitor-section");
        pane.setMinWidth(220);
        pane.setPrefWidth(260);
        VBox.setVgrow(scriptListView, Priority.ALWAYS);
        return pane;
    }

    private VBox createEditorPane() {
        nameField = new TextField();
        nameField.setPromptText("Название скрипта");
        nameField.textProperty().addListener((obs, oldValue, newValue) -> markDirty());

        enabledCheck = new CheckBox("Включен");
        enabledCheck.selectedProperty().addListener((obs, oldValue, newValue) -> markDirty());

        HBox metaRow = new HBox(10, new Label("Имя"), nameField, enabledCheck);
        metaRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameField, Priority.ALWAYS);

        codeArea = new CodeArea();
        codeArea.getStyleClass().add("lua-code-area");
        installEditorGutter();
        codeArea.multiPlainChanges()
                .successionEnds(java.time.Duration.ofMillis(120))
                .subscribe(ignore -> codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText())));
        codeArea.textProperty().addListener((obs, oldValue, newValue) -> {
            markDirty();
            showCompletion(false);
        });
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handleEditorKeyPressed);

        consoleArea = new TextArea();
        consoleArea.getStyleClass().add("lua-console");
        consoleArea.setEditable(false);
        consoleArea.setWrapText(false);
        consoleArea.setPrefRowCount(9);

        VBox editorBox = new VBox(8, metaRow, new VirtualizedScrollPane<>(codeArea));
        VBox.setVgrow(editorBox.getChildren().get(1), Priority.ALWAYS);

        VBox consoleBox = new VBox(6, sectionTitle("Консоль"), consoleArea);
        VBox.setVgrow(consoleArea, Priority.ALWAYS);

        SplitPane editorSplit = new SplitPane(editorBox, consoleBox);
        editorSplit.setOrientation(Orientation.VERTICAL);
        editorSplit.setDividerPositions(0.72);
        VBox.setVgrow(editorSplit, Priority.ALWAYS);

        VBox pane = new VBox(8, editorSplit);
        pane.getStyleClass().add("packet-monitor-section");
        VBox.setVgrow(editorSplit, Priority.ALWAYS);
        return pane;
    }

    private VBox createInfoPane() {
        TextArea apiArea = new TextArea(API_REFERENCE);
        apiArea.getStyleClass().add("lua-api-reference");
        apiArea.setEditable(false);
        apiArea.setWrapText(true);

        kvTable = new TableView<>(kvRows);
        kvTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<KvRow, String> keyColumn = new TableColumn<>("Ключ");
        keyColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().key()));
        keyColumn.setPrefWidth(110);
        TableColumn<KvRow, String> valueColumn = new TableColumn<>("Значение");
        valueColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().value()));
        kvTable.getColumns().add(keyColumn);
        kvTable.getColumns().add(valueColumn);

        VBox apiBox = new VBox(6, sectionTitle("API"), apiArea);
        VBox kvBox = new VBox(6, sectionTitle("KV выбранного скрипта"), kvTable);
        VBox.setVgrow(apiArea, Priority.ALWAYS);
        VBox.setVgrow(kvTable, Priority.ALWAYS);

        debugTable = createDebugTable();
        VBox debugBox = new VBox(6, sectionTitle("Переменные"), debugTable);
        VBox.setVgrow(debugTable, Priority.ALWAYS);

        SplitPane bottomSplit = new SplitPane(debugBox, kvBox);
        bottomSplit.setOrientation(Orientation.VERTICAL);
        bottomSplit.setDividerPositions(0.55);
        VBox.setVgrow(bottomSplit, Priority.ALWAYS);

        SplitPane split = new SplitPane(apiBox, bottomSplit);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.42);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox pane = new VBox(split);
        pane.getStyleClass().add("packet-monitor-section");
        pane.setMinWidth(260);
        pane.setPrefWidth(300);
        VBox.setVgrow(split, Priority.ALWAYS);
        return pane;
    }

    private TableView<DebugVarRow> createDebugTable() {
        TableView<DebugVarRow> table = new TableView<>(debugRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<DebugVarRow, String> scopeColumn = new TableColumn<>("Scope");
        scopeColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().scope()));
        scopeColumn.setPrefWidth(68);
        TableColumn<DebugVarRow, String> nameColumn = new TableColumn<>("Имя");
        nameColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().name()));
        nameColumn.setPrefWidth(92);
        TableColumn<DebugVarRow, String> valueColumn = new TableColumn<>("Значение");
        valueColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().value()));
        table.getColumns().add(scopeColumn);
        table.getColumns().add(nameColumn);
        table.getColumns().add(valueColumn);
        return table;
    }

    private void installEditorGutter() {
        lineNumberFactory = LineNumberFactory.get(codeArea);
        codeArea.setParagraphGraphicFactory(this::createLineGraphic);
    }

    private Node createLineGraphic(int paragraphIndex) {
        int line = paragraphIndex + 1;
        boolean hasBreakpoint = currentBreakpoints.contains(line);

        Label breakpoint = new Label(hasBreakpoint ? "●" : " ");
        breakpoint.getStyleClass().add("lua-breakpoint-marker");
        if (hasBreakpoint) {
            breakpoint.getStyleClass().add("lua-breakpoint-marker-active");
        }
        breakpoint.setTooltip(new Tooltip("Breakpoint"));
        breakpoint.setOnMouseClicked(event -> toggleBreakpoint(line));

        HBox graphic = new HBox(4, breakpoint, lineNumberFactory.apply(paragraphIndex));
        graphic.getStyleClass().add("lua-gutter-line");
        if (currentDebugLine == line) {
            graphic.getStyleClass().add("lua-gutter-line-current");
        }
        return graphic;
    }

    private void toggleBreakpoint(int line) {
        if (line <= 0) {
            return;
        }
        if (currentBreakpoints.contains(line)) {
            currentBreakpoints.remove(line);
        } else {
            currentBreakpoints.add(line);
        }
        if (currentScript != null) {
            breakpointsByScript.put(currentScript.getId(), new TreeSet<>(currentBreakpoints));
        }
        recreateLineGraphics();
    }

    private void recreateLineGraphics() {
        if (codeArea == null) {
            return;
        }
        for (int i = 0; i < codeArea.getParagraphs().size(); i++) {
            codeArea.recreateParagraphGraphic(i);
        }
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("packet-monitor-section-title");
        return label;
    }

    private Button createToolbarButton(String text, String tooltip, String iconPath, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("config-toolbar-button", "packet-monitor-toolbar-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setContentDisplay(ContentDisplay.LEFT);
        SVGPath icon = SvgIconLoader.load(iconPath, 16);
        if (icon != null) {
            button.setGraphic(icon);
        }
        button.setOnAction(event -> action.run());
        return button;
    }

    private void handleEditorKeyPressed(KeyEvent event) {
        if (completionPopup.isShowing()) {
            if (event.getCode() == KeyCode.DOWN) {
                selectCompletionOffset(1);
                event.consume();
                return;
            }
            if (event.getCode() == KeyCode.UP) {
                selectCompletionOffset(-1);
                event.consume();
                return;
            }
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.TAB) {
                applySelectedCompletion();
                event.consume();
                return;
            }
            if (event.getCode() == KeyCode.ESCAPE) {
                completionPopup.hide();
                event.consume();
                return;
            }
        }
        if (event.isShortcutDown() && event.getCode() == KeyCode.S) {
            saveCurrentScriptSafely();
            event.consume();
            return;
        }
        if (event.isShortcutDown() && event.getCode() == KeyCode.ENTER) {
            runCurrentScript();
            event.consume();
            return;
        }
        if (event.isControlDown() && event.getCode() == KeyCode.SPACE) {
            showCompletion(true);
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.ESCAPE) {
            completionPopup.hide();
        }
    }

    private void showCompletion(boolean forced) {
        if (loadingScript || codeArea == null) {
            return;
        }
        String prefix = completionPrefix();
        if (!forced && prefix.length() < 2 && !prefix.contains(".")) {
            completionPopup.hide();
            return;
        }
        String query = prefix.toLowerCase(Locale.ROOT);
        List<String> candidates = COMPLETIONS.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(query)
                        || candidate.toLowerCase(Locale.ROOT).contains("." + query))
                .limit(MAX_COMPLETIONS)
                .toList();
        if (candidates.isEmpty()) {
            completionPopup.hide();
            return;
        }

        visibleCompletions = candidates;
        visibleCompletionPrefix = prefix;
        selectedCompletionIndex = 0;
        rebuildCompletionRows();
        updateCompletionPopupTheme();

        Optional<Bounds> caretBounds = codeArea.getCaretBounds();
        if (caretBounds.isPresent()) {
            Bounds screenBounds = caretBounds.get();
            double x = screenBounds.getMaxX();
            double y = screenBounds.getMaxY() + 3;
            if (completionPopup.isShowing()) {
                completionPopup.setX(x);
                completionPopup.setY(y);
            } else {
                completionPopup.show(codeArea, x, y);
            }
        }
    }

    private void rebuildCompletionRows() {
        completionBox.getChildren().setAll(java.util.stream.IntStream.range(0, visibleCompletions.size())
                .mapToObj(index -> createCompletionRow(visibleCompletions.get(index), index))
                .toList());
        updateSelectedCompletionRow();
    }

    private TextFlow createCompletionRow(String candidate, int index) {
        TextFlow row = new TextFlow();
        row.getStyleClass().add("lua-completion-row");
        row.setMinWidth(260);
        row.setUserData(index);
        addHighlightedCompletionText(row, candidate);
        row.setOnMouseEntered(event -> {
            selectedCompletionIndex = index;
            updateSelectedCompletionRow();
        });
        row.setOnMousePressed(event -> applyCompletion(visibleCompletionPrefix, candidate));
        return row;
    }

    private void addHighlightedCompletionText(TextFlow row, String candidate) {
        Matcher matcher = LUA_HIGHLIGHT_PATTERN.matcher(candidate);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                row.getChildren().add(new Text(candidate.substring(lastEnd, matcher.start())));
            }
            Text text = new Text(candidate.substring(matcher.start(), matcher.end()));
            String styleClass =
                    matcher.group("COMMENT") != null ? "lua-comment" :
                    matcher.group("STRING") != null ? "lua-string" :
                    matcher.group("NUMBER") != null ? "lua-number" :
                    matcher.group("API") != null ? "lua-api" :
                    matcher.group("KEYWORD") != null ? "lua-keyword" :
                    matcher.group("BUILTIN") != null ? "lua-builtin" :
                    null;
            if (styleClass != null) {
                text.getStyleClass().add(styleClass);
            }
            row.getChildren().add(text);
            lastEnd = matcher.end();
        }
        if (lastEnd < candidate.length()) {
            row.getChildren().add(new Text(candidate.substring(lastEnd)));
        }
    }

    private void selectCompletionOffset(int offset) {
        if (visibleCompletions.isEmpty()) {
            return;
        }
        selectedCompletionIndex = Math.floorMod(selectedCompletionIndex + offset, visibleCompletions.size());
        updateSelectedCompletionRow();
    }

    private void updateSelectedCompletionRow() {
        for (javafx.scene.Node node : completionBox.getChildren()) {
            node.getStyleClass().remove("lua-completion-row-selected");
            if (node.getUserData() instanceof Integer index && index == selectedCompletionIndex) {
                node.getStyleClass().add("lua-completion-row-selected");
            }
        }
    }

    private void applySelectedCompletion() {
        if (visibleCompletions.isEmpty() || selectedCompletionIndex < 0
                || selectedCompletionIndex >= visibleCompletions.size()) {
            return;
        }
        applyCompletion(visibleCompletionPrefix, visibleCompletions.get(selectedCompletionIndex));
    }

    private void updateCompletionPopupTheme() {
        completionBox.getStyleClass().remove("light-theme");
        if (!AppPreferences.isDarkMode()) {
            completionBox.getStyleClass().add("light-theme");
        }
    }

    private String completionPrefix() {
        int caret = codeArea.getCaretPosition();
        String text = codeArea.getText();
        int start = caret;
        while (start > 0) {
            char ch = text.charAt(start - 1);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '.') {
                start--;
            } else {
                break;
            }
        }
        return text.substring(start, caret);
    }

    private void applyCompletion(String prefix, String candidate) {
        int caret = codeArea.getCaretPosition();
        int start = Math.max(0, caret - prefix.length());
        codeArea.replaceText(start, caret, candidate);
        codeArea.requestFocus();
        completionPopup.hide();
    }

    private void createScript() {
        try {
            LuaScript script = scriptService.createScript();
            reloadScripts(script.getId());
            appendConsole("Создан скрипт " + script.getName());
        } catch (Exception e) {
            setStatus("Не удалось создать скрипт");
            appendConsole("ERROR " + e.getMessage());
        }
    }

    private void loadScript(LuaScript script) {
        loadingScript = true;
        currentScript = script;
        completionPopup.hide();
        currentBreakpoints = new TreeSet<>(breakpointsByScript.computeIfAbsent(script.getId(), ignored -> new TreeSet<>()));
        clearDebugState();
        nameField.setText(script.getName());
        enabledCheck.setSelected(script.isEnabled());
        codeArea.replaceText(script.getCode() != null ? script.getCode() : "");
        codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText()));
        dirty = false;
        loadingScript = false;
        refreshKvRows();
        recreateLineGraphics();
        updateButtons();
        setStatus(runtimeService.isRunning(script.getId()) ? "Скрипт запущен" : "Готово");
    }

    private void saveCurrentScriptSafely() {
        if (currentScript == null) {
            return;
        }
        try {
            LuaScript saved = scriptService.saveScript(
                    currentScript.getId(),
                    nameField.getText(),
                    codeArea.getText(),
                    enabledCheck.isSelected());
            currentScript = saved;
            dirty = false;
            reloadScripts(saved.getId());
            setStatus("Сохранено в БД");
            appendConsole("Сохранено: " + saved.getName());
        } catch (Exception e) {
            setStatus("Ошибка сохранения");
            appendConsole("ERROR " + e.getMessage());
        }
    }

    private void deleteCurrentScript() {
        if (currentScript == null) {
            return;
        }
        long deletedId = currentScript.getId();
        runtimeService.stopScript(deletedId, this::handleRuntimeEvent);
        scriptService.deleteScript(deletedId);
        currentScript = null;
        dirty = false;
        reloadScripts(0);
        appendConsole("Скрипт удален");
    }

    private void checkCurrentScript() {
        if (currentScript == null) {
            return;
        }
        saveCurrentScriptSafely();
        String error = runtimeService.checkSyntax(codeArea.getText(), nameField.getText());
        if (error == null) {
            setStatus("Синтаксис OK");
            appendConsole("Синтаксис OK");
        } else {
            setStatus("Ошибка синтаксиса");
            appendConsole("SYNTAX ERROR " + error);
        }
    }

    private void runCurrentScript() {
        if (currentScript == null) {
            return;
        }
        saveCurrentScriptSafely();
        consoleArea.clear();
        clearDebugState();
        appendConsole("Запуск " + currentScript.getName());
        runtimeService.runScript(currentScript, this::handleRuntimeEvent);
        updateButtons();
    }

    private void debugCurrentScript() {
        if (currentScript == null) {
            return;
        }
        saveCurrentScriptSafely();
        consoleArea.clear();
        clearDebugState();
        appendConsole("Отладка " + currentScript.getName());
        runtimeService.debugScript(currentScript, new HashSet<>(currentBreakpoints), this::handleRuntimeEvent);
        updateButtons();
    }

    private void continueDebuggee() {
        if (currentScript == null) {
            return;
        }
        runtimeService.debugContinue(currentScript.getId());
        updateButtons();
    }

    private void stepDebuggee() {
        if (currentScript == null) {
            return;
        }
        runtimeService.debugStep(currentScript.getId());
        updateButtons();
    }

    private void stopCurrentScript() {
        if (currentScript == null) {
            return;
        }
        runtimeService.stopScript(currentScript.getId(), this::handleRuntimeEvent);
        clearDebugState();
        updateButtons();
        reloadScripts(currentScript.getId());
    }

    private void handleRuntimeEvent(LuaScriptEvent event) {
        Platform.runLater(() -> {
            String prefix = switch (event.type()) {
                case STARTED -> "START";
                case STOPPED -> "STOP";
                case ERROR -> "ERROR";
                case DEBUG_PAUSED -> "PAUSE";
                case DEBUG_RESUMED -> "DEBUG";
                case WARNING -> "WARN";
                case OUTPUT -> "OUT";
                case INFO -> "INFO";
            };
            appendConsole(prefix + " " + event.message());
            if (event.type() == LuaScriptEvent.Type.ERROR) {
                setStatus("Ошибка выполнения");
            } else if (event.type() == LuaScriptEvent.Type.STARTED) {
                setStatus("Скрипт запущен");
            } else if (event.type() == LuaScriptEvent.Type.DEBUG_PAUSED) {
                runtimeService.debugSnapshot(event.scriptId()).ifPresent(this::showDebugSnapshot);
                setStatus("Пауза отладки");
            } else if (event.type() == LuaScriptEvent.Type.DEBUG_RESUMED) {
                currentDebugLine = -1;
                recreateLineGraphics();
                setStatus("Отладка выполняется");
            } else if (event.type() == LuaScriptEvent.Type.STOPPED) {
                setStatus("Скрипт остановлен");
                clearDebugState();
            }
            updateButtons();
            refreshKvRows();
            if (scriptListView != null) {
                scriptListView.refresh();
            }
        });
    }

    private void reloadScripts(long selectId) {
        List<LuaScript> loaded = scriptService.listScripts();
        if (loaded.isEmpty()) {
            loaded = List.of(scriptService.createScript());
        }
        scripts.setAll(loaded);
        long targetId = selectId > 0
                ? selectId
                : (currentScript != null ? currentScript.getId() : loaded.getFirst().getId());
        scripts.stream()
                .filter(script -> script.getId() == targetId)
                .findFirst()
                .or(() -> scripts.stream().findFirst())
                .ifPresent(script -> {
                    scriptListView.getSelectionModel().select(script);
                    if (currentScript == null || currentScript.getId() != script.getId()) {
                        loadScript(script);
                    }
                });
        scriptListView.refresh();
    }

    private void refreshKvRows() {
        if (currentScript == null) {
            kvRows.clear();
            return;
        }
        kvRows.setAll(scriptService.listKv(currentScript.getId()).entrySet().stream()
                .map(entry -> new KvRow(entry.getKey(), entry.getValue()))
                .toList());
    }

    private void showDebugSnapshot(LuaDebugSnapshot snapshot) {
        currentDebugLine = snapshot.line();
        debugRows.setAll(snapshot.variables().stream()
                .map(DebugVarRow::from)
                .toList());
        recreateLineGraphics();
        if (codeArea != null && !codeArea.getParagraphs().isEmpty()) {
            int paragraph = Math.max(0, Math.min(codeArea.getParagraphs().size() - 1, snapshot.line() - 1));
            codeArea.showParagraphAtCenter(paragraph);
        }
    }

    private void clearDebugState() {
        currentDebugLine = -1;
        debugRows.clear();
        recreateLineGraphics();
    }

    private void markDirty() {
        if (loadingScript || currentScript == null) {
            return;
        }
        dirty = true;
        setStatus("Есть несохраненные изменения");
    }

    private void updateButtons() {
        boolean hasScript = currentScript != null;
        boolean running = hasScript && runtimeService.isRunning(currentScript.getId());
        boolean paused = hasScript && runtimeService.isPaused(currentScript.getId());
        runButton.setDisable(!hasScript || running);
        debugButton.setDisable(!hasScript || running);
        continueButton.setDisable(!paused);
        stepButton.setDisable(!paused);
        stopButton.setDisable(!hasScript || !running);
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private void appendConsole(String line) {
        String time = TIME_FORMAT.format(Instant.now());
        consoleArea.appendText("[" + time + "] " + line + System.lineSeparator());
    }

    private static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = LUA_HIGHLIGHT_PATTERN.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        while (matcher.find()) {
            String styleClass =
                    matcher.group("COMMENT") != null ? "lua-comment" :
                    matcher.group("STRING") != null ? "lua-string" :
                    matcher.group("NUMBER") != null ? "lua-number" :
                    matcher.group("API") != null ? "lua-api" :
                    matcher.group("KEYWORD") != null ? "lua-keyword" :
                    matcher.group("BUILTIN") != null ? "lua-builtin" :
                    null;
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
            spansBuilder.add(styleClass == null ? Collections.emptyList() : Collections.singleton(styleClass),
                    matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
        return spansBuilder.create();
    }

    private record KvRow(String key, String value) {}

    private record DebugVarRow(String scope, String name, String value) {
        static DebugVarRow from(LuaDebugVariable variable) {
            return new DebugVarRow(variable.scope(), variable.name(), variable.value());
        }
    }
}
