package com.meshtastic.client.components;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.meshtastic.client.MeshApp;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.lua.LuaDebugSnapshot;
import com.meshtastic.client.lua.LuaDebugVariable;
import com.meshtastic.client.lua.LuaCompletionEngine;
import com.meshtastic.client.lua.LuaEditorIndentation;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptEvent;
import com.meshtastic.client.lua.LuaScriptRuntimeService;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.platform.NativeMacOsWindowControl;
import com.meshtastic.client.platform.NativeWindowHelper;
import com.meshtastic.client.platform.OsDetect;
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
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Screen;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.DoubleConsumer;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone MeshApp IDE window for editing, running, and debugging Lua scripts.
 * <p>
 * The window includes a highlighted editor, autocomplete, line numbers,
 * breakpoint markers, console, debug-variable table, and isolated KV storage
 * view for the selected script.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaDevWindow {

    private static final double DEFAULT_WINDOW_WIDTH = 1280;
    private static final double DEFAULT_WINDOW_HEIGHT = 860;
    private static final double MIN_WINDOW_WIDTH = 900;
    private static final double MIN_WINDOW_HEIGHT = 620;
    private static final double WINDOW_VISIBLE_MARGIN_X = 96;
    private static final double WINDOW_VISIBLE_MARGIN_Y = 72;
    private static final double COMPLETION_MIN_WIDTH = 260.0;
    private static final double COMPLETION_MIN_VISIBLE_HEIGHT = 36.0;
    private static final double COMPLETION_VERTICAL_GAP = 2.0;
    private static final int MAX_COMPLETIONS = 9;
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
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
    private static final Pattern JSON_HIGHLIGHT_PATTERN = Pattern.compile(
            "(?<KEY>\"(?:\\\\.|[^\"\\\\])*\")(?=\\s*:)"
                    + "|(?<STRING>\"(?:\\\\.|[^\"\\\\])*\")"
                    + "|(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)"
                    + "|(?<BOOLEAN>\\b(?:true|false)\\b)"
                    + "|(?<NULL>\\bnull\\b)"
    );

    private static LuaDevWindow instance;

    private final LuaScriptService scriptService = LuaScriptService.getInstance();
    private final LuaScriptRuntimeService runtimeService = LuaScriptRuntimeService.getInstance();
    private final LuaCompletionEngine completionEngine = new LuaCompletionEngine();
    private final ObservableList<KvRow> kvRows = FXCollections.observableArrayList();
    private final ObservableList<DebugVarRow> debugRows = FXCollections.observableArrayList();
    private static final Map<Long, Set<Integer>> BREAKPOINTS_BY_SCRIPT = new HashMap<>();

    private final VBox completionBox = new VBox();
    private final VBox completionRows = new VBox();
    private final ScrollPane completionScrollPane = new ScrollPane(completionRows);

    private Stage stage;
    private SplitPane mainSplit;
    private SplitPane editorSplit;
    private SplitPane infoSplit;
    private CodeArea codeArea;
    private StackPane editorStack;
    private TextArea consoleArea;
    private TableView<KvRow> kvTable;
    private TableView<DebugVarRow> debugTable;
    private Label scriptNameLabel;
    private Label statusLabel;
    private Button kvButton;
    private Button runButton;
    private Button debugButton;
    private Button continueButton;
    private Button stepButton;
    private Button stopButton;
    private IntFunction<Node> lineNumberFactory;
    private LuaScript currentScript;
    private boolean dirty;
    private boolean loadingScript;
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean draggingWindow;
    private boolean nativeEffectsApplied;
    private boolean closingWindow;
    private boolean restoreMaximizedOnShow;
    private boolean restoringDividerPositions;
    private boolean dividerPositionTrackingEnabled;
    private double normalWindowX = Double.NaN;
    private double normalWindowY = Double.NaN;
    private double normalWindowWidth = DEFAULT_WINDOW_WIDTH;
    private double normalWindowHeight = DEFAULT_WINDOW_HEIGHT;
    private List<LuaCompletionEngine.CompletionItem> visibleCompletions = List.of();
    private int completionReplaceStart;
    private int completionReplaceEnd;
    private int selectedCompletionIndex;
    private Set<Integer> currentBreakpoints = new TreeSet<>();
    private int currentDebugLine = -1;

    private LuaDevWindow() {
        configureCompletionOverlay();
        createStage();
    }

    public static void showWindow() {
        showWindow(0);
    }

    public static void showWindow(long scriptId) {
        if (Platform.isFxApplicationThread()) {
            showWindowInternal(scriptId);
        } else {
            Platform.runLater(() -> showWindowInternal(scriptId));
        }
    }

    /**
     * Saves IDE geometry and layout when the window exists in the current session.
     */
    public static void saveWindowStateIfOpen() {
        if (Platform.isFxApplicationThread()) {
            saveWindowStateIfOpenInternal();
        } else {
            Platform.runLater(LuaDevWindow::saveWindowStateIfOpenInternal);
        }
    }

    private static void showWindowInternal(long scriptId) {
        if (instance == null) {
            instance = new LuaDevWindow();
        }
        instance.showStage();
        instance.openScript(scriptId);
    }

    private static void saveWindowStateIfOpenInternal() {
        if (instance != null && instance.stage != null) {
            instance.saveWindowState();
        }
    }

    private void showStage() {
        boolean wasShowing = stage.isShowing();
        if (!wasShowing) {
            stage.show();
            restoreDividerPositionsAfterFirstLayout();
            if (restoreMaximizedOnShow) {
                Platform.runLater(() -> {
                    stage.setMaximized(true);
                    restoreDividerPositionsAfterFirstLayout();
                });
                restoreMaximizedOnShow = false;
            }
        }
        if (!wasShowing && useCustomFrame() && !nativeEffectsApplied) {
            NativeWindowHelper.applyNativeEffects(stage, AppPreferences.isDarkMode());
            nativeEffectsApplied = true;
            stage.setTitle(I18n.t("meshIde.title"));
        }
        stage.toFront();
        stage.requestFocus();
    }

    private void createStage() {
        stage = new Stage();
        if (useCustomFrame()) {
            NativeWindowHelper.prepareStage(stage);
        } else {
            stage.initStyle(StageStyle.DECORATED);
        }
        stage.setTitle(I18n.t("meshIde.title"));
        stage.setResizable(true);
        if (MeshApp.getPrimaryStage() != null && !MeshApp.getPrimaryStage().getIcons().isEmpty()) {
            stage.getIcons().setAll(MeshApp.getPrimaryStage().getIcons());
        }
        stage.setOnCloseRequest(event -> {
            event.consume();
            closeWindow();
        });

        VBox root = new VBox();
        root.getStyleClass().add("lua-dev-root");

        HBox body = new HBox();
        body.getStyleClass().add("lua-dev-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox content = new VBox(10);
        content.getStyleClass().add("lua-dev-content");
        content.setPadding(new Insets(12));
        HBox.setHgrow(content, Priority.ALWAYS);
        content.getChildren().addAll(createContent(), createStatusBar());

        body.getChildren().addAll(createSideMenu(), content);
        if (useCustomFrame()) {
            root.getChildren().add(createWindowTitleBar());
        }
        root.getChildren().add(body);

        Scene scene = new Scene(root, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
        if (useCustomFrame() && OsDetect.supportsSeamlessFrame()) {
            scene.setFill(Color.TRANSPARENT);
            root.pseudoClassStateChanged(NativeWindowHelper.SEAMLESS_FRAME, true);
            root.pseudoClassStateChanged(NativeWindowHelper.SEPARATE_FRAME, false);
        }
        ThemeManager.applyTheme(scene, AppPreferences.isDarkMode());
        EmojiRenderingSupport.install(scene);
        stage.setScene(scene);
        restoreWindowState();
        trackWindowBounds();
        stage.setOnHiding(event -> saveWindowState());
    }

    private boolean useCustomFrame() {
        return OsDetect.isMacOs() && !AppPreferences.isDisableEffectsEffective();
    }

    private HBox createWindowTitleBar() {
        HBox bar = new HBox();
        bar.getStyleClass().add("custom-title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setPickOnBounds(true);

        Button closeButton = createWindowButton("window-btn-close");
        closeButton.setOnAction(event -> closeWindow());

        Button minimizeButton = createWindowButton("window-btn-minimize");
        minimizeButton.setOnAction(event -> stage.setIconified(true));

        Button maximizeButton = createWindowButton("window-btn-maximize");
        maximizeButton.setOnAction(event -> toggleWindowMaximized());

        HBox buttons = new HBox(8, closeButton, minimizeButton, maximizeButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Region leftSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);
        leftSpacer.setMouseTransparent(true);

        Label title = new Label(I18n.t("meshIde.title"));
        title.getStyleClass().add("title-bar-label");
        title.setMinWidth(0);
        title.setMaxWidth(520);
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setMouseTransparent(true);

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);
        rightSpacer.setMouseTransparent(true);

        bar.getChildren().addAll(buttons, leftSpacer, title, rightSpacer);
        bar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !(event.getTarget() instanceof Button)) {
                toggleWindowMaximized();
            }
        });
        bar.setOnMousePressed(event -> {
            if (event.getTarget() instanceof Button) {
                return;
            }
            dragOffsetX = event.getScreenX() - stage.getX();
            dragOffsetY = event.getScreenY() - stage.getY();
            draggingWindow = true;
        });
        bar.setOnMouseDragged(event -> {
            if (!draggingWindow) {
                return;
            }
            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(event.getScreenY() - dragOffsetY);
        });
        bar.setOnMouseReleased(event -> draggingWindow = false);
        return bar;
    }

    private Button createWindowButton(String styleClass) {
        Button button = new Button();
        button.getStyleClass().addAll("window-control-btn", styleClass);
        button.setMinSize(13, 13);
        button.setMaxSize(13, 13);
        button.setPrefSize(13, 13);
        return button;
    }

    private void toggleWindowMaximized() {
        stage.setMaximized(!stage.isMaximized());
    }

    private void restoreWindowState() {
        if (!AppPreferences.hasLuaDevWindowBounds()) {
            normalWindowWidth = DEFAULT_WINDOW_WIDTH;
            normalWindowHeight = DEFAULT_WINDOW_HEIGHT;
            return;
        }

        WindowBounds bounds = resolveVisibleWindowBounds(
                AppPreferences.getLuaDevWindowX(),
                AppPreferences.getLuaDevWindowY(),
                AppPreferences.getLuaDevWindowWidth(),
                AppPreferences.getLuaDevWindowHeight());
        applyWindowBounds(bounds);
        restoreMaximizedOnShow = AppPreferences.isLuaDevWindowMaximized();
    }

    private void trackWindowBounds() {
        rememberNormalWindowBounds();
        stage.xProperty().addListener((obs, oldValue, newValue) -> rememberNormalWindowBounds());
        stage.yProperty().addListener((obs, oldValue, newValue) -> rememberNormalWindowBounds());
        stage.widthProperty().addListener((obs, oldValue, newValue) -> rememberNormalWindowBounds());
        stage.heightProperty().addListener((obs, oldValue, newValue) -> rememberNormalWindowBounds());
        stage.maximizedProperty().addListener((obs, oldValue, maximized) -> {
            if (!maximized) {
                Platform.runLater(this::rememberNormalWindowBounds);
            }
        });
    }

    private void rememberNormalWindowBounds() {
        if (stage == null || stage.isMaximized() || stage.isIconified()) {
            return;
        }
        double width = stage.getWidth();
        double height = stage.getHeight();
        if (!hasUsableWindowSize(width, height)) {
            return;
        }
        normalWindowX = stage.getX();
        normalWindowY = stage.getY();
        normalWindowWidth = width;
        normalWindowHeight = height;
    }

    private void saveWindowState() {
        if (stage == null) {
            return;
        }
        rememberNormalWindowBounds();
        boolean maximized = stage.isMaximized();
        WindowBounds visibleBounds = resolveVisibleWindowBounds(
                Double.isNaN(normalWindowX) ? stage.getX() : normalWindowX,
                Double.isNaN(normalWindowY) ? stage.getY() : normalWindowY,
                hasUsableWindowSize(normalWindowWidth, normalWindowHeight) ? normalWindowWidth : stage.getWidth(),
                hasUsableWindowSize(normalWindowWidth, normalWindowHeight) ? normalWindowHeight : stage.getHeight());
        saveDividerPositions();
        AppPreferences.saveLuaDevWindowBounds(
                visibleBounds.x(),
                visibleBounds.y(),
                visibleBounds.width(),
                visibleBounds.height(),
                maximized);
    }

    private void saveDividerPositions() {
        AppPreferences.saveLuaDevDividerPositions(
                dividerPosition(mainSplit, AppPreferences.getLuaDevMainDividerPos()),
                dividerPosition(editorSplit, AppPreferences.getLuaDevEditorDividerPos()),
                dividerPosition(infoSplit, AppPreferences.getLuaDevInfoDividerPos())
        );
    }

    private void restoreDividerPositionsAfterFirstLayout() {
        dividerPositionTrackingEnabled = false;
        Platform.runLater(() -> Platform.runLater(this::restoreDividerPositions));
    }

    private void restoreDividerPositions() {
        restoringDividerPositions = true;
        try {
            applyDividerPosition(mainSplit, AppPreferences.getLuaDevMainDividerPos());
            applyDividerPosition(editorSplit, AppPreferences.getLuaDevEditorDividerPos());
            applyDividerPosition(infoSplit, AppPreferences.getLuaDevInfoDividerPos());
        } finally {
            restoringDividerPositions = false;
            dividerPositionTrackingEnabled = true;
        }
    }

    private void configurePersistentDivider(SplitPane splitPane, double position, DoubleConsumer saveAction) {
        applyDividerPosition(splitPane, position);
        splitPane.getDividers().getFirst().positionProperty().addListener((obs, oldValue, newValue) -> {
            if (!dividerPositionTrackingEnabled || restoringDividerPositions || stage == null || !stage.isShowing()) {
                return;
            }
            saveAction.accept(normalizeDividerPosition(newValue.doubleValue(), oldValue.doubleValue()));
        });
    }

    private static void applyDividerPosition(SplitPane splitPane, double position) {
        if (splitPane != null && !splitPane.getDividers().isEmpty()) {
            splitPane.setDividerPositions(normalizeDividerPosition(position, 0.5));
        }
    }

    private static double dividerPosition(SplitPane splitPane, double fallback) {
        if (splitPane == null || splitPane.getDividers().isEmpty()) {
            return normalizeDividerPosition(fallback, 0.5);
        }
        return normalizeDividerPosition(splitPane.getDividers().getFirst().getPosition(), fallback);
    }

    private static double normalizeDividerPosition(double position, double fallback) {
        if (!Double.isFinite(position)) {
            return Double.isFinite(fallback) ? clamp(fallback, 0.0, 1.0) : 0.5;
        }
        return clamp(position, 0.0, 1.0);
    }

    private void applyWindowBounds(WindowBounds bounds) {
        if (bounds == null) {
            return;
        }
        stage.setX(bounds.x());
        stage.setY(bounds.y());
        stage.setWidth(bounds.width());
        stage.setHeight(bounds.height());
        normalWindowX = bounds.x();
        normalWindowY = bounds.y();
        normalWindowWidth = bounds.width();
        normalWindowHeight = bounds.height();
    }

    private static WindowBounds resolveVisibleWindowBounds(double x, double y, double width, double height) {
        List<Rectangle2D> visibleAreas = Screen.getScreens().stream()
                .map(Screen::getVisualBounds)
                .toList();
        Rectangle2D fallbackArea = visibleAreas.isEmpty()
                ? new Rectangle2D(0, 0, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT)
                : visibleAreas.getFirst();
        Rectangle2D targetArea = visibleAreas.stream()
                .filter(area -> isWindowVisibleOnArea(x, y, width, height, area))
                .findFirst()
                .orElse(fallbackArea);

        double safeWidth = clamp(
                Double.isFinite(width) && width > 0 ? width : DEFAULT_WINDOW_WIDTH,
                Math.min(MIN_WINDOW_WIDTH, targetArea.getWidth()),
                targetArea.getWidth());
        double safeHeight = clamp(
                Double.isFinite(height) && height > 0 ? height : DEFAULT_WINDOW_HEIGHT,
                Math.min(MIN_WINDOW_HEIGHT, targetArea.getHeight()),
                targetArea.getHeight());

        boolean invalidPosition = !Double.isFinite(x)
                || !Double.isFinite(y)
                || !isWindowVisibleOnArea(x, y, safeWidth, safeHeight, targetArea);
        double safeX = invalidPosition
                ? targetArea.getMinX() + Math.max(0, (targetArea.getWidth() - safeWidth) / 2.0)
                : clamp(x, targetArea.getMinX(), targetArea.getMaxX() - safeWidth);
        double safeY = invalidPosition
                ? targetArea.getMinY() + Math.max(0, (targetArea.getHeight() - safeHeight) / 2.0)
                : clamp(y, targetArea.getMinY(), targetArea.getMaxY() - safeHeight);
        return new WindowBounds(safeX, safeY, safeWidth, safeHeight);
    }

    private static boolean isWindowVisibleOnArea(double x, double y, double width, double height, Rectangle2D area) {
        if (area == null || !Double.isFinite(x) || !Double.isFinite(y)) {
            return false;
        }
        double safeWidth = Double.isFinite(width) && width > 0 ? width : DEFAULT_WINDOW_WIDTH;
        double safeHeight = Double.isFinite(height) && height > 0 ? height : DEFAULT_WINDOW_HEIGHT;
        return x + Math.min(WINDOW_VISIBLE_MARGIN_X, safeWidth) >= area.getMinX()
                && x <= area.getMaxX() - Math.min(WINDOW_VISIBLE_MARGIN_X, safeWidth)
                && y + Math.min(WINDOW_VISIBLE_MARGIN_Y, safeHeight) >= area.getMinY()
                && y <= area.getMaxY() - Math.min(WINDOW_VISIBLE_MARGIN_Y, safeHeight);
    }

    private static boolean hasUsableWindowSize(double width, double height) {
        return Double.isFinite(width) && Double.isFinite(height)
                && width >= MIN_WINDOW_WIDTH
                && height >= MIN_WINDOW_HEIGHT;
    }

    private static double clamp(double value, double min, double max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private record WindowBounds(double x, double y, double width, double height) {}

    private record CompletionPopupSize(double width, double height, double preferredHeight) {}

    private void closeWindow() {
        if (closingWindow) {
            return;
        }
        if (dirty && currentScript != null) {
            CloseChoice choice = promptSaveBeforeClose();
            if (choice == CloseChoice.CANCEL) {
                return;
            }
            if (choice == CloseChoice.SAVE && !saveCurrentScriptSafely()) {
                return;
            }
            if (choice == CloseChoice.DISCARD) {
                dirty = false;
            }
        }
        closingWindow = true;
        try {
            hideCompletion();
            if (stage != null) {
                ThemeManager.unregisterScene(stage.getScene());
                stage.hide();
            }
        } finally {
            nativeEffectsApplied = false;
            instance = null;
        }
    }

    private CloseChoice promptSaveBeforeClose() {
        ButtonType saveButton = new ButtonType(I18n.t("meshIde.dev.unsaved.save"), ButtonBar.ButtonData.YES);
        ButtonType discardButton = new ButtonType(I18n.t("meshIde.dev.unsaved.discard"), ButtonBar.ButtonData.NO);
        ButtonType cancelButton = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.t("meshIde.dev.unsaved.title"));
        alert.setHeaderText(I18n.t("meshIde.dev.unsaved.header"));
        String scriptName = currentScript.getName() == null || currentScript.getName().isBlank()
                ? I18n.t("meshIde.dev.unsaved.scriptFallback")
                : currentScript.getName();
        alert.setContentText(I18n.t("meshIde.dev.unsaved.content", scriptName));
        alert.getButtonTypes().setAll(saveButton, discardButton, cancelButton);
        if (stage != null) {
            alert.initOwner(stage);
        }

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == cancelButton) {
            return CloseChoice.CANCEL;
        }
        if (result.get() == saveButton) {
            return CloseChoice.SAVE;
        }
        return CloseChoice.DISCARD;
    }

    private void configureCompletionOverlay() {
        completionBox.getStyleClass().add("lua-completion-popup");
        completionBox.setManaged(false);
        completionBox.setVisible(false);
        completionScrollPane.getStyleClass().add("lua-completion-scroll-pane");
        completionScrollPane.setFitToWidth(true);
        completionScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        completionScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        completionScrollPane.setPannable(true);
        completionBox.getChildren().setAll(completionScrollPane);
        String appCss = LuaDevWindow.class.getResource("/css/app.css") != null
                ? LuaDevWindow.class.getResource("/css/app.css").toExternalForm()
                : null;
        if (appCss != null) {
            completionBox.getStylesheets().add(appCss);
        }
    }

    private HBox createStatusBar() {
        HBox statusBar = new HBox(8);
        statusBar.getStyleClass().add("lua-dev-status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        scriptNameLabel = new Label(I18n.t("meshIde.dev.scriptNotSelected"));
        scriptNameLabel.getStyleClass().add("lua-dev-script-name");
        statusLabel = new Label(I18n.t("meshIde.dev.status.ready"));
        statusLabel.getStyleClass().add("config-status-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        statusBar.getChildren().addAll(scriptNameLabel, spacer, statusLabel);
        return statusBar;
    }

    private VBox createSideMenu() {
        ToolBar toolbar = new ToolBar();
        toolbar.setOrientation(Orientation.VERTICAL);
        toolbar.getStyleClass().add("drawer-toolbar");

        Button saveButton = createSideMenuButton(
                I18n.t("meshIde.dev.tooltip.saveCode"), "/icons/ide-file-code.svg", this::saveCurrentScriptSafely);
        kvButton = createSideMenuButton(
                I18n.t("meshIde.dev.tooltip.kvEditor"), "/icons/database.svg", this::openCurrentScriptKvEditor);
        Button checkButton = createSideMenuButton(
                I18n.t("meshIde.dev.tooltip.checkSyntax"), "/icons/ide-code-check.svg", this::checkCurrentScript);
        runButton = createSideMenuButton(
                I18n.t("meshIde.dev.tooltip.runScript"), "/icons/ide-terminal-run.svg", this::runCurrentScript);
        debugButton = createSideMenuButton(
                I18n.t("meshIde.dev.tooltip.debug"), "/icons/ide-bug.svg", this::debugCurrentScript);
        continueButton = createSideMenuButton(
                I18n.t("meshIde.dev.tooltip.continue"), "/icons/ide-debug-continue.svg", this::continueDebuggee);
        stepButton = createSideMenuButton(
                I18n.t("meshIde.dev.tooltip.step"), "/icons/ide-debug-step-over.svg", this::stepDebuggee);
        stopButton = createSideMenuButton(
                I18n.t("meshIde.dev.tooltip.stop"), "/icons/ide-stop.svg", this::stopCurrentScript);
        Button clearConsoleButton = createSideMenuButton(
                I18n.t("meshIde.dev.tooltip.clearConsole"), "/icons/ide-console-clear.svg", () -> consoleArea.clear());

        toolbar.getItems().addAll(
                saveButton,
                kvButton,
                checkButton,
                runButton,
                debugButton,
                continueButton,
                stepButton,
                stopButton,
                new Separator(Orientation.HORIZONTAL),
                clearConsoleButton
        );

        VBox menu = new VBox(toolbar);
        menu.getStyleClass().add("lua-dev-side-menu");
        menu.setAlignment(Pos.TOP_CENTER);
        VBox.setVgrow(toolbar, Priority.ALWAYS);
        return menu;
    }

    private SplitPane createContent() {
        mainSplit = new SplitPane(createEditorPane(), createInfoPane());
        mainSplit.getStyleClass().add("lua-dev-split-pane");
        configurePersistentDivider(mainSplit, AppPreferences.getLuaDevMainDividerPos(),
                AppPreferences::setLuaDevMainDividerPos);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);
        return mainSplit;
    }

    private VBox createEditorPane() {
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

        VirtualizedScrollPane<CodeArea> codeScrollPane = new VirtualizedScrollPane<>(codeArea);
        editorStack = new StackPane(codeScrollPane, completionBox);
        editorStack.setPickOnBounds(false);
        StackPane.setAlignment(completionBox, Pos.TOP_LEFT);

        VBox editorBox = createPanel(null, editorStack);
        VBox.setVgrow(editorStack, Priority.ALWAYS);

        VBox consoleBox = createPanel(I18n.t("meshIde.dev.panel.console"), consoleArea);
        VBox.setVgrow(consoleArea, Priority.ALWAYS);

        StackPane editorSlot = createSplitSlot(editorBox, "lua-dev-code-slot");
        StackPane consoleSlot = createSplitSlot(consoleBox, "lua-dev-console-slot");

        editorSplit = new SplitPane(editorSlot, consoleSlot);
        editorSplit.getStyleClass().add("lua-dev-split-pane");
        editorSplit.setOrientation(Orientation.VERTICAL);
        configurePersistentDivider(editorSplit, AppPreferences.getLuaDevEditorDividerPos(),
                AppPreferences::setLuaDevEditorDividerPos);
        VBox.setVgrow(editorSplit, Priority.ALWAYS);

        return new VBox(editorSplit);
    }

    private SplitPane createInfoPane() {
        kvTable = new TableView<>(kvRows);
        kvTable.getStyleClass().add("lua-dev-table");
        kvTable.setPlaceholder(new Region());
        kvTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<KvRow, String> keyColumn = new TableColumn<>(I18n.t("meshIde.column.key"));
        keyColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().key()));
        keyColumn.setPrefWidth(110);
        TableColumn<KvRow, String> valueColumn = new TableColumn<>(I18n.t("meshIde.column.value"));
        valueColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().value()));
        valueColumn.setCellFactory(column -> createValueCell());
        kvTable.getColumns().add(keyColumn);
        kvTable.getColumns().add(valueColumn);

        VBox kvBox = createPanel(I18n.t("meshIde.dev.panel.kv"), kvTable);
        VBox.setVgrow(kvTable, Priority.ALWAYS);

        debugTable = createDebugTable();
        VBox debugBox = createPanel(I18n.t("meshIde.dev.panel.variables"), debugTable);
        VBox.setVgrow(debugTable, Priority.ALWAYS);

        infoSplit = new SplitPane(debugBox, kvBox);
        infoSplit.getStyleClass().add("lua-dev-split-pane");
        infoSplit.setOrientation(Orientation.VERTICAL);
        configurePersistentDivider(infoSplit, AppPreferences.getLuaDevInfoDividerPos(),
                AppPreferences::setLuaDevInfoDividerPos);
        VBox.setVgrow(infoSplit, Priority.ALWAYS);
        infoSplit.setMinWidth(260);
        infoSplit.setPrefWidth(300);
        return infoSplit;
    }

    private TableView<DebugVarRow> createDebugTable() {
        TableView<DebugVarRow> table = new TableView<>(debugRows);
        table.getStyleClass().add("lua-dev-table");
        table.setPlaceholder(new Region());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<DebugVarRow, String> scopeColumn = new TableColumn<>(I18n.t("meshIde.column.scope"));
        scopeColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().scope()));
        scopeColumn.setPrefWidth(68);
        TableColumn<DebugVarRow, String> nameColumn = new TableColumn<>(I18n.t("meshIde.column.name"));
        nameColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().name()));
        nameColumn.setPrefWidth(92);
        TableColumn<DebugVarRow, String> valueColumn = new TableColumn<>(I18n.t("meshIde.column.value"));
        valueColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().value()));
        valueColumn.setCellFactory(column -> createValueCell());
        table.getColumns().add(scopeColumn);
        table.getColumns().add(nameColumn);
        table.getColumns().add(valueColumn);
        return table;
    }

    private <S> TableCell<S, String> createValueCell() {
        return new TableCell<>() {
            private final Label valueLabel = new Label();
            private final Region spacer = new Region();
            private final Button detailsButton = new Button("...");
            private final HBox content = new HBox(6, valueLabel, spacer, detailsButton);

            {
                getStyleClass().add("lua-value-cell");
                valueLabel.getStyleClass().add("lua-value-cell-text");
                valueLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
                valueLabel.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(valueLabel, Priority.ALWAYS);
                HBox.setHgrow(spacer, Priority.ALWAYS);

                detailsButton.getStyleClass().add("lua-value-details-button");
                detailsButton.setFocusTraversable(false);
                detailsButton.setVisible(false);
                detailsButton.setManaged(false);
                detailsButton.setOnAction(event -> showValueToolWindow(getItem()));

                content.setAlignment(Pos.CENTER_LEFT);
                hoverProperty().addListener((obs, wasHover, isHover) -> updateButtonVisibility());
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    valueLabel.setText(null);
                    setGraphic(null);
                    setText(null);
                    detailsButton.setVisible(false);
                    detailsButton.setManaged(false);
                    return;
                }
                valueLabel.setText(item);
                setText(null);
                setGraphic(content);
                updateButtonVisibility();
            }

            private void updateButtonVisibility() {
                boolean show = isHover() && !isEmpty() && getItem() != null && !getItem().isBlank();
                detailsButton.setVisible(show);
                detailsButton.setManaged(show);
            }
        };
    }

    private void showValueToolWindow(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        CodeArea jsonView = createJsonCodeArea(formatJsonValue(value));
        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(jsonView);
        scrollPane.getStyleClass().add("lua-json-scroll");

        VBox root = new VBox(scrollPane);
        root.getStyleClass().add("lua-json-window-root");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        Scene scene = new Scene(root, 640, 420);
        ThemeManager.applyTheme(scene, AppPreferences.isDarkMode());
        EmojiRenderingSupport.install(scene);

        Stage valueStage = new Stage();
        valueStage.initStyle(StageStyle.UTILITY);
        valueStage.setResizable(true);
        if (stage != null && stage.isShowing()) {
            valueStage.initOwner(stage);
        }
        valueStage.setTitle(I18n.t("meshIde.dev.valueWindow.title"));
        valueStage.setScene(scene);
        valueStage.setOnHidden(event -> ThemeManager.unregisterScene(scene));
        valueStage.show();
        if (OsDetect.isMacOs()) {
            new NativeMacOsWindowControl(valueStage).hideMiniaturizeAndZoomButtons();
        }
        valueStage.toFront();
        Platform.runLater(jsonView::requestFocus);
    }

    private String formatJsonValue(String value) {
        try {
            JsonElement parsed = JsonParser.parseString(value);
            return PRETTY_GSON.toJson(parsed);
        } catch (JsonSyntaxException | IllegalStateException e) {
            return value;
        }
    }

    private CodeArea createJsonCodeArea(String text) {
        CodeArea jsonArea = new CodeArea();
        jsonArea.getStyleClass().add("lua-json-code-area");
        jsonArea.setEditable(false);
        jsonArea.replaceText(text);
        jsonArea.setStyleSpans(0, computeJsonHighlighting(text));
        jsonArea.moveTo(0);
        installJsonCopyActions(jsonArea);
        return jsonArea;
    }

    private void installJsonCopyActions(CodeArea jsonArea) {
        MenuItem copyItem = new MenuItem(I18n.t("common.copy"));
        copyItem.setOnAction(event -> copySelectedJsonText(jsonArea));
        MenuItem selectAllItem = new MenuItem(I18n.t("common.selectAll"));
        selectAllItem.setOnAction(event -> {
            jsonArea.requestFocus();
            jsonArea.selectAll();
        });
        ContextMenu contextMenu = new ContextMenu(copyItem, selectAllItem);

        jsonArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.A) {
                jsonArea.selectAll();
                event.consume();
                return;
            }
            if (event.isShortcutDown() && event.getCode() == KeyCode.C
                    && jsonArea.getSelection().getLength() > 0) {
                copySelectedJsonText(jsonArea);
                event.consume();
            }
        });
        jsonArea.setOnContextMenuRequested(event -> {
            copyItem.setDisable(jsonArea.getSelection().getLength() == 0);
            contextMenu.show(jsonArea, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        jsonArea.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                contextMenu.hide();
            }
        });
    }

    private void copySelectedJsonText(CodeArea jsonArea) {
        IndexRange selection = jsonArea.getSelection();
        if (selection.getLength() == 0) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(jsonArea.getText().substring(selection.getStart(), selection.getEnd()));
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static StyleSpans<Collection<String>> computeJsonHighlighting(String text) {
        Matcher matcher = JSON_HIGHLIGHT_PATTERN.matcher(text);
        int lastEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        while (matcher.find()) {
            String styleClass =
                    matcher.group("KEY") != null ? "json-key" :
                    matcher.group("STRING") != null ? "json-string" :
                    matcher.group("NUMBER") != null ? "json-number" :
                    matcher.group("BOOLEAN") != null ? "json-boolean" :
                    matcher.group("NULL") != null ? "json-null" :
                    null;
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastEnd);
            spansBuilder.add(styleClass == null ? Collections.emptyList() : Collections.singleton(styleClass),
                    matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastEnd);
        return spansBuilder.create();
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
        breakpoint.setTooltip(new Tooltip(I18n.t("meshIde.dev.breakpoint")));
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
            BREAKPOINTS_BY_SCRIPT.put(currentScript.getId(), new TreeSet<>(currentBreakpoints));
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

    private VBox createPanel(String title, Node content) {
        VBox panel = new VBox(6);
        panel.getStyleClass().add("lua-dev-panel");
        if (title != null && !title.isBlank()) {
            panel.getChildren().add(sectionTitle(title));
        }
        panel.getChildren().add(content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return panel;
    }

    private StackPane createSplitSlot(Node content, String styleClass) {
        StackPane slot = new StackPane(content);
        slot.getStyleClass().add(styleClass);
        return slot;
    }

    private Button createSideMenuButton(String tooltip, String iconPath, Runnable action) {
        Button button = new Button();
        button.getStyleClass().add("drawer-toolbar-button");
        button.setTooltip(new Tooltip(tooltip));
        SVGPath icon = SvgIconLoader.load(iconPath, 22);
        if (icon != null) {
            button.setGraphic(icon);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            button.setText("?");
        }
        button.setOnAction(event -> action.run());
        return button;
    }

    private void handleEditorKeyPressed(KeyEvent event) {
        if (isCompletionShowing()) {
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
            if (event.getCode() == KeyCode.ENTER || (event.getCode() == KeyCode.TAB && !event.isShiftDown())) {
                applySelectedCompletion();
                event.consume();
                return;
            }
            if (event.getCode() == KeyCode.ESCAPE) {
                hideCompletion();
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
        if (event.getCode() == KeyCode.TAB) {
            applyTabIndent(event.isShiftDown());
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.ENTER) {
            applyIndentedNewLine();
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.ESCAPE) {
            hideCompletion();
        }
    }

    private void applyIndentedNewLine() {
        IndexRange selection = codeArea.getSelection();
        applyEditorTextEdit(LuaEditorIndentation.newLineEdit(
                codeArea.getText(),
                selection.getStart(),
                selection.getEnd()
        ));
        hideCompletion();
    }

    private void applyTabIndent(boolean unindent) {
        IndexRange selection = codeArea.getSelection();
        applyEditorTextEdit(LuaEditorIndentation.tabEdit(
                codeArea.getText(),
                selection.getStart(),
                selection.getEnd(),
                unindent
        ));
        hideCompletion();
    }

    private void applyEditorTextEdit(LuaEditorIndentation.TextEdit edit) {
        codeArea.replaceText(edit.start(), edit.end(), edit.replacement());
        codeArea.selectRange(edit.selectionStart(), edit.selectionEnd());
    }

    private void showCompletion(boolean forced) {
        if (loadingScript || codeArea == null) {
            return;
        }
        LuaCompletionEngine.CompletionResult result =
                completionEngine.complete(codeArea.getText(), codeArea.getCaretPosition(), forced);
        if (result.isEmpty()) {
            hideCompletion();
            return;
        }

        visibleCompletions = result.items().stream()
                .limit(MAX_COMPLETIONS)
                .toList();
        completionReplaceStart = result.replaceStart();
        completionReplaceEnd = result.replaceEnd();
        selectedCompletionIndex = 0;
        rebuildCompletionRows();
        updateCompletionPopupTheme();

        positionCompletionOverlayAtCaret();
        Platform.runLater(this::positionCompletionOverlayAtCaret);
    }

    private void positionCompletionOverlayAtCaret() {
        if (codeArea == null || editorStack == null || visibleCompletions.isEmpty()) {
            return;
        }
        Optional<Point2D> anchor = completionAnchorPoint();
        if (anchor.isEmpty()) {
            hideCompletion();
            return;
        }

        CompletionPopupSize popupSize = applyCompletionBoxSize(Double.MAX_VALUE);
        double availableBelow = Math.max(0, editorStack.getHeight() - anchor.get().getY() - COMPLETION_VERTICAL_GAP);
        double availableAbove = Math.max(0, anchor.get().getY() - COMPLETION_VERTICAL_GAP);
        boolean openAbove = popupSize.preferredHeight() > availableBelow && availableAbove > availableBelow;
        double availableHeight = openAbove ? availableAbove : availableBelow;
        popupSize = applyCompletionBoxSize(Math.max(COMPLETION_MIN_VISIBLE_HEIGHT, availableHeight));
        double x = clamp(anchor.get().getX(), 0, Math.max(0, editorStack.getWidth() - popupSize.width()));
        double y = openAbove
                ? anchor.get().getY() - popupSize.height() - COMPLETION_VERTICAL_GAP
                : anchor.get().getY() + COMPLETION_VERTICAL_GAP;
        y = clamp(y, 0, Math.max(0, editorStack.getHeight() - popupSize.height()));
        completionBox.relocate(
                x,
                y
        );
        completionBox.toFront();
        completionBox.setVisible(true);
    }

    private CompletionPopupSize applyCompletionBoxSize(double maxHeight) {
        completionBox.setVisible(true);
        completionRows.applyCss();
        completionScrollPane.applyCss();
        completionBox.applyCss();
        double rowWidth = COMPLETION_MIN_WIDTH;
        double contentHeight = 0;
        for (Node child : completionRows.getChildren()) {
            if (child instanceof Region region) {
                rowWidth = Math.max(rowWidth, region.prefWidth(-1));
                contentHeight += region.prefHeight(rowWidth);
            } else {
                Bounds bounds = child.getLayoutBounds();
                rowWidth = Math.max(rowWidth, bounds.getWidth());
                contentHeight += bounds.getHeight();
            }
        }
        double verticalInsets = completionBox.getInsets().getTop() + completionBox.getInsets().getBottom();
        double horizontalInsets = completionBox.getInsets().getLeft() + completionBox.getInsets().getRight();
        double preferredHeight = verticalInsets + contentHeight;
        double height = Math.min(preferredHeight, Math.max(COMPLETION_MIN_VISIBLE_HEIGHT, maxHeight));
        double viewportHeight = Math.max(0, height - verticalInsets);
        completionRows.setMinWidth(rowWidth);
        completionRows.setPrefWidth(rowWidth);
        completionScrollPane.setMinWidth(rowWidth);
        completionScrollPane.setPrefWidth(rowWidth);
        completionScrollPane.setMaxWidth(rowWidth);
        completionScrollPane.setMinHeight(viewportHeight);
        completionScrollPane.setPrefHeight(viewportHeight);
        completionScrollPane.setMaxHeight(viewportHeight);
        double width = rowWidth + horizontalInsets;
        completionBox.setMinSize(width, height);
        completionBox.setPrefSize(width, height);
        completionBox.setMaxSize(width, height);
        completionBox.resize(width, height);
        return new CompletionPopupSize(width, height, preferredHeight);
    }

    private Optional<Point2D> completionAnchorPoint() {
        Optional<Point2D> caretAnchor = codeArea.getCaretBounds()
                .filter(bounds -> !bounds.isEmpty())
                .map(bounds -> editorStack.screenToLocal(bounds.getMinX(), bounds.getMaxY()))
                .map(this::snapPoint);
        if (caretAnchor.isPresent()) {
            return caretAnchor;
        }

        Optional<Point2D> characterAnchor = completionCharacterAnchorPoint();
        if (characterAnchor.isPresent()) {
            return characterAnchor;
        }

        return completionEstimatedAnchorPoint();
    }

    private Optional<Point2D> completionCharacterAnchorPoint() {
        int caretPosition = codeArea.getCaretPosition();
        int from = Math.max(0, caretPosition - 1);
        int to = Math.max(from, caretPosition);
        if (from == to) {
            return Optional.empty();
        }
        try {
            Optional<Bounds> bounds = codeArea.getCharacterBoundsOnScreen(from, to);
            if (bounds.isPresent()) {
                Bounds caretBounds = bounds.get();
                Point2D local = editorStack.screenToLocal(caretBounds.getMaxX(), caretBounds.getMaxY());
                return Optional.of(snapPoint(local));
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to estimated bounds.
        }
        return Optional.empty();
    }

    private Optional<Point2D> completionEstimatedAnchorPoint() {
        int currentParagraph = codeArea.getCurrentParagraph();
        Optional<Bounds> paragraphBounds = codeArea.getParagraphBoundsOnScreen(currentParagraph);
        if (paragraphBounds.isEmpty()) {
            return Optional.empty();
        }
        Bounds bounds = paragraphBounds.get();
        int lineCount = Math.max(1, codeArea.getParagraphLinesCount(currentParagraph));
        int lineIndex = Math.max(0, codeArea.lineIndex(currentParagraph, codeArea.getCaretColumn()));
        double lineHeight = bounds.getHeight() / lineCount;
        double x = bounds.getMinX() + approximateCaretColumnOffset();
        double y = bounds.getMinY() + ((lineIndex + 1) * lineHeight);
        return Optional.of(snapPoint(editorStack.screenToLocal(x, y)));
    }

    private double approximateCaretColumnOffset() {
        String paragraph = codeArea.getText(codeArea.getCurrentParagraph());
        int column = Math.max(0, Math.min(codeArea.getCaretColumn(), paragraph.length()));
        double charWidth = 9.6;
        return paragraph.substring(0, column).length() * charWidth;
    }

    private Point2D snapPoint(Point2D point) {
        return new Point2D(Math.floor(point.getX()), Math.floor(point.getY()));
    }

    private boolean isCompletionShowing() {
        return completionBox.isVisible();
    }

    private void hideCompletion() {
        visibleCompletions = List.of();
        selectedCompletionIndex = 0;
        completionRows.getChildren().clear();
        completionScrollPane.setVvalue(0);
        completionBox.setVisible(false);
    }

    private void rebuildCompletionRows() {
        completionRows.getChildren().setAll(java.util.stream.IntStream.range(0, visibleCompletions.size())
                .mapToObj(index -> createCompletionRow(visibleCompletions.get(index), index))
                .toList());
        completionScrollPane.setVvalue(0);
        updateSelectedCompletionRow();
    }

    private TextFlow createCompletionRow(LuaCompletionEngine.CompletionItem candidate, int index) {
        TextFlow row = new TextFlow();
        row.getStyleClass().add("lua-completion-row");
        row.setMinWidth(COMPLETION_MIN_WIDTH);
        row.setPrefWidth(COMPLETION_MIN_WIDTH);
        row.setUserData(index);
        addHighlightedCompletionText(row, candidate.displayText());
        row.setOnMouseEntered(event -> selectCompletionIndex(index));
        row.setOnMouseMoved(event -> selectCompletionIndex(index));
        row.setOnMousePressed(event -> applyCompletion(candidate));
        return row;
    }

    private void selectCompletionIndex(int index) {
        if (selectedCompletionIndex != index) {
            selectedCompletionIndex = index;
            updateSelectedCompletionRow();
        }
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
        for (javafx.scene.Node node : completionRows.getChildren()) {
            node.getStyleClass().remove("lua-completion-row-selected");
            if (node.getUserData() instanceof Integer index && index == selectedCompletionIndex) {
                node.getStyleClass().add("lua-completion-row-selected");
            }
        }
        scrollSelectedCompletionIntoView();
    }

    private void scrollSelectedCompletionIntoView() {
        Node selectedNode = completionRows.getChildren().stream()
                .filter(node -> node.getUserData() instanceof Integer index && index == selectedCompletionIndex)
                .findFirst()
                .orElse(null);
        if (selectedNode == null) {
            return;
        }
        completionRows.layout();
        completionScrollPane.layout();
        double viewportHeight = completionScrollPane.getViewportBounds().getHeight();
        double contentHeight = completionRows.getBoundsInLocal().getHeight();
        double scrollableHeight = contentHeight - viewportHeight;
        if (scrollableHeight <= 0) {
            return;
        }
        Bounds selectedBounds = selectedNode.getBoundsInParent();
        double currentTop = completionScrollPane.getVvalue() * scrollableHeight;
        double currentBottom = currentTop + viewportHeight;
        double targetTop = selectedBounds.getMinY();
        double targetBottom = selectedBounds.getMaxY();
        double newTop = currentTop;
        if (targetTop < currentTop) {
            newTop = targetTop;
        } else if (targetBottom > currentBottom) {
            newTop = targetBottom - viewportHeight;
        }
        completionScrollPane.setVvalue(clamp(newTop / scrollableHeight, 0, 1));
    }

    private void applySelectedCompletion() {
        if (visibleCompletions.isEmpty() || selectedCompletionIndex < 0
                || selectedCompletionIndex >= visibleCompletions.size()) {
            return;
        }
        applyCompletion(visibleCompletions.get(selectedCompletionIndex));
    }

    private void updateCompletionPopupTheme() {
        completionBox.getStyleClass().remove("light-theme");
        if (!AppPreferences.isDarkMode()) {
            completionBox.getStyleClass().add("light-theme");
        }
    }

    private void applyCompletion(LuaCompletionEngine.CompletionItem candidate) {
        int start = Math.max(0, Math.min(completionReplaceStart, codeArea.getLength()));
        int end = Math.max(start, Math.min(completionReplaceEnd, codeArea.getLength()));
        codeArea.replaceText(start, end, candidate.insertText());
        codeArea.requestFocus();
        hideCompletion();
    }

    private void openScript(long scriptId) {
        if (dirty && currentScript != null) {
            saveCurrentScriptSafely();
        }
        LuaScript script = scriptId > 0
                ? scriptService.findScript(scriptId).orElse(null)
                : scriptService.listScripts().stream().findFirst().orElse(null);
        if (script == null) {
            script = scriptService.createScript();
        }
        if (currentScript == null || currentScript.getId() != script.getId()) {
            loadScript(script);
        }
    }

    private void loadScript(LuaScript script) {
        loadingScript = true;
        currentScript = script;
        hideCompletion();
        currentBreakpoints = new TreeSet<>(BREAKPOINTS_BY_SCRIPT.computeIfAbsent(script.getId(), ignored -> new TreeSet<>()));
        clearDebugState();
        updateScriptNameLabel();
        codeArea.replaceText(script.getCode() != null ? script.getCode() : "");
        codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText()));
        dirty = false;
        loadingScript = false;
        refreshKvRows();
        recreateLineGraphics();
        updateButtons();
        setStatus(runtimeService.isRunning(script.getId())
                ? I18n.t("meshIde.dev.status.scriptRunning")
                : I18n.t("meshIde.dev.status.ready"));
    }

    private boolean saveCurrentScriptSafely() {
        if (currentScript == null) {
            return false;
        }
        try {
            LuaScript saved = scriptService.saveScript(
                    currentScript.getId(),
                    currentScript.getName(),
                    codeArea.getText(),
                    currentScript.isEnabled());
            currentScript = saved;
            updateScriptNameLabel();
            dirty = false;
            refreshKvRows();
            updateButtons();
            setStatus(I18n.t("meshIde.dev.status.savedToDb"));
            appendConsole(I18n.t("meshIde.dev.status.saved", saved.getName()));
            return true;
        } catch (Exception e) {
            setStatus(I18n.t("meshIde.dev.status.saveError"));
            appendConsole("ERROR " + e.getMessage());
            return false;
        }
    }

    private void checkCurrentScript() {
        if (currentScript == null) {
            return;
        }
        saveCurrentScriptSafely();
        String error = runtimeService.checkSyntax(codeArea.getText(), currentScript.getName());
        if (error == null) {
            setStatus(I18n.t("meshIde.dev.status.syntaxOk"));
            appendConsole(I18n.t("meshIde.dev.status.syntaxOk"));
        } else {
            setStatus(I18n.t("meshIde.dev.status.syntaxError"));
            appendConsole("SYNTAX ERROR " + error);
        }
    }

    private void openCurrentScriptKvEditor() {
        if (currentScript == null) {
            return;
        }
        LuaKvEditorWindow.showWindow(currentScript);
    }

    private void runCurrentScript() {
        if (currentScript == null) {
            return;
        }
        saveCurrentScriptSafely();
        consoleArea.clear();
        clearDebugState();
        appendConsole(I18n.t("meshIde.dev.status.run", currentScript.getName()));
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
        appendConsole(I18n.t("meshIde.dev.status.debug", currentScript.getName()));
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
        scriptService.findScript(currentScript.getId()).ifPresent(script -> currentScript = script);
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
                case UI_BOT_NOTICE -> "UI";
            };
            appendConsole(prefix + " " + event.message());
            if (event.type() == LuaScriptEvent.Type.ERROR) {
                setStatus(I18n.t("meshIde.dev.status.executionError"));
            } else if (event.type() == LuaScriptEvent.Type.STARTED) {
                setStatus(I18n.t("meshIde.dev.status.scriptRunning"));
            } else if (event.type() == LuaScriptEvent.Type.DEBUG_PAUSED) {
                runtimeService.debugSnapshot(event.scriptId()).ifPresent(this::showDebugSnapshot);
                setStatus(I18n.t("meshIde.dev.status.debugPaused"));
            } else if (event.type() == LuaScriptEvent.Type.DEBUG_RESUMED) {
                currentDebugLine = -1;
                recreateLineGraphics();
                setStatus(I18n.t("meshIde.dev.status.debugRunning"));
            } else if (event.type() == LuaScriptEvent.Type.STOPPED) {
                setStatus(I18n.t("meshIde.dev.status.scriptStopped"));
                clearDebugState();
            }
            updateButtons();
            refreshKvRows();
        });
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
        setStatus(I18n.t("meshIde.dev.status.dirty"));
    }

    private void updateButtons() {
        boolean hasScript = currentScript != null;
        boolean running = hasScript && runtimeService.isRunning(currentScript.getId());
        boolean paused = hasScript && runtimeService.isPaused(currentScript.getId());
        kvButton.setDisable(!hasScript);
        runButton.setDisable(!hasScript || running);
        debugButton.setDisable(!hasScript || running);
        continueButton.setDisable(!paused);
        stepButton.setDisable(!paused);
        stopButton.setDisable(!hasScript || !running);
    }

    private void updateScriptNameLabel() {
        if (scriptNameLabel == null) {
            return;
        }
        String name = currentScript != null ? currentScript.getName() : null;
        scriptNameLabel.setText(name == null || name.isBlank()
                ? I18n.t("meshIde.dev.scriptNotSelected")
                : I18n.t("meshIde.dev.scriptVersion", name, Long.toString(currentScript.getVersion())));
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

    private enum CloseChoice {
        SAVE,
        DISCARD,
        CANCEL
    }

    private record DebugVarRow(String scope, String name, String value) {
        static DebugVarRow from(LuaDebugVariable variable) {
            return new DebugVarRow(variable.scope(), variable.name(), variable.value());
        }
    }
}
