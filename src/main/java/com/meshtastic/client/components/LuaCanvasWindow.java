package com.meshtastic.client.components;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.lua.LuaCanvasDrawCommand;
import com.meshtastic.client.lua.LuaCanvasEvent;
import com.meshtastic.client.lua.LuaCanvasKeyState;
import com.meshtastic.client.lua.LuaCanvasMouseState;
import com.meshtastic.client.lua.LuaCanvasOptions;
import com.meshtastic.client.lua.LuaCanvasSize;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.utils.AppPreferences;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Плавающее Canvas-окно, управляемое Lua-скриптом.
 *
 * <p>Окно не модальное, не регистрируется в боковом меню и не переключает
 * основную форму приложения.
 */
public final class LuaCanvasWindow {

    private static final double DEFAULT_WIDTH = 720;
    private static final double DEFAULT_HEIGHT = 520;
    private static final double MIN_WIDTH = 260;
    private static final double MIN_HEIGHT = 220;
    private static final double MAX_WIDTH = 1920;
    private static final double MAX_HEIGHT = 1080;
    private static final double RESIZE_EDGE_SIZE = 8;
    private static final double MOVE_ZONE_HEIGHT = 28;
    private static final double CLOSE_BUTTON_SIZE = 28;
    private static final double MINIMIZED_SIZE = 64;
    private static final double DRAW_FLUSH_DELAY_MS = 8;
    private static final long FX_WAIT_TIMEOUT_SECONDS = 2;
    private static final Map<Long, LuaCanvasWindow> OPEN_WINDOWS = new ConcurrentHashMap<>();
    private static final Map<Long, WindowPosition> MINIMIZED_POSITIONS = new ConcurrentHashMap<>();

    private final long scriptId;
    private final Consumer<LuaCanvasEvent> eventSink;
    private final Queue<LuaCanvasDrawCommand> drawQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drawFlushScheduled = new AtomicBoolean(false);
    private final Set<String> pressedCodes = new HashSet<>();

    private Stage stage;
    private Scene scene;
    private StackPane root;
    private Canvas canvas;
    private StackPane canvasHost;
    private StackPane closeButton;
    private StackPane minimizedBadge;
    private Label minimizedIconLabel;
    private String scriptIcon = LuaScript.DEFAULT_ICON;
    private PauseTransition drawFlushDelay;
    private Timeline frameTimer;
    private long lastFrameNanos;
    private volatile boolean active;
    private boolean minimized;
    private boolean resizeCanvasToHost;
    private boolean windowResizeEnabled;
    private boolean movingWindow;
    private ResizeMode resizeMode = ResizeMode.NONE;
    private WindowBounds restoreBounds;
    private WindowPosition minimizedPosition;
    private double dragOffsetX;
    private double dragOffsetY;
    private double resizeStartScreenX;
    private double resizeStartScreenY;
    private double resizeStartX;
    private double resizeStartY;
    private double resizeStartWidth;
    private double resizeStartHeight;
    private volatile LuaCanvasSize canvasSize = new LuaCanvasSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    private volatile LuaCanvasMouseState mouseState = LuaCanvasMouseState.empty();
    private volatile LuaCanvasKeyState keyState = LuaCanvasKeyState.empty();

    private LuaCanvasWindow(long scriptId, Consumer<LuaCanvasEvent> eventSink) {
        this.scriptId = scriptId;
        this.eventSink = eventSink;
        this.minimizedPosition = MINIMIZED_POSITIONS.get(scriptId);
    }

    public static void showWindow(long scriptId,
                                  String scriptName,
                                  String scriptIcon,
                                  LuaCanvasOptions options,
                                  Consumer<LuaCanvasEvent> eventSink) {
        runOnFxAndWait(() -> showWindowOnFx(
                scriptId,
                scriptName,
                LuaScript.normalizeIcon(scriptIcon),
                sanitizeOptions(options),
                eventSink));
    }

    public static void closeWindow(long scriptId) {
        runOnFxAndWait(() -> {
            LuaCanvasWindow window = OPEN_WINDOWS.get(scriptId);
            if (window != null && window.stage != null) {
                window.stage.close();
            }
        });
    }

    public static boolean enqueueDraw(long scriptId, LuaCanvasDrawCommand command) {
        LuaCanvasWindow window = OPEN_WINDOWS.get(scriptId);
        if (window == null || !window.active || command == null) {
            return false;
        }
        window.enqueue(command);
        return true;
    }

    public static boolean setFrameRate(long scriptId, double fps) {
        LuaCanvasWindow window = OPEN_WINDOWS.get(scriptId);
        if (window == null || !window.active) {
            return false;
        }
        runOnFxAndWait(() -> window.setFrameRateOnFx(fps));
        return true;
    }

    public static LuaCanvasMouseState mouseState(long scriptId) {
        LuaCanvasWindow window = OPEN_WINDOWS.get(scriptId);
        return window != null && window.active ? window.mouseState : LuaCanvasMouseState.empty();
    }

    public static LuaCanvasKeyState keyState(long scriptId) {
        LuaCanvasWindow window = OPEN_WINDOWS.get(scriptId);
        return window != null && window.active ? window.keyState : LuaCanvasKeyState.empty();
    }

    public static LuaCanvasSize size(long scriptId) {
        LuaCanvasWindow window = OPEN_WINDOWS.get(scriptId);
        return window != null && window.active ? window.canvasSize : LuaCanvasSize.empty();
    }

    private static void showWindowOnFx(long scriptId,
                                       String scriptName,
                                       String scriptIcon,
                                       LuaCanvasOptions options,
                                       Consumer<LuaCanvasEvent> eventSink) {
        LuaCanvasWindow existing = OPEN_WINDOWS.get(scriptId);
        if (existing != null) {
            existing.update(scriptName, scriptIcon, options);
            existing.showStage();
            return;
        }

        LuaCanvasWindow window = new LuaCanvasWindow(scriptId, eventSink);
        OPEN_WINDOWS.put(scriptId, window);
        window.createStage(scriptName, scriptIcon, options);
        window.showStage();
    }

    private void createStage(String scriptName, String scriptIcon, LuaCanvasOptions options) {
        this.scriptIcon = LuaScript.normalizeIcon(scriptIcon);
        stage = new Stage();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle(windowTitle(scriptName, options));
        stage.setResizable(false);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        Stage owner = MeshApp.getPrimaryStage();
        if (owner != null) {
            stage.initOwner(owner);
            if (!owner.getIcons().isEmpty()) {
                stage.getIcons().setAll(owner.getIcons());
            }
        }

        root = new StackPane();
        root.getStyleClass().addAll("packet-monitor-root", "lua-canvas-root");

        canvas = new Canvas(options.width(), options.height());
        canvas.setFocusTraversable(true);
        installCanvasInputHandlers();
        canvas.widthProperty().addListener((observable, oldValue, newValue) -> emitResize());
        canvas.heightProperty().addListener((observable, oldValue, newValue) -> emitResize());

        canvasHost = new StackPane(canvas);
        canvasHost.getStyleClass().add("lua-canvas-host");
        canvasHost.setMinSize(MIN_WIDTH, MIN_HEIGHT);
        canvasHost.widthProperty().addListener((observable, oldValue, newValue) -> resizeCanvasToHost());
        canvasHost.heightProperty().addListener((observable, oldValue, newValue) -> resizeCanvasToHost());

        closeButton = createCloseButton();
        StackPane.setAlignment(closeButton, Pos.TOP_RIGHT);
        StackPane.setMargin(closeButton, new Insets(10, 10, 0, 0));

        minimizedBadge = createMinimizedBadge();
        minimizedBadge.setVisible(false);
        minimizedBadge.setManaged(false);

        root.getChildren().addAll(canvasHost, closeButton, minimizedBadge);

        scene = new Scene(root, options.width(), options.height());
        ThemeManager.applyTheme(scene, AppPreferences.isDarkMode());
        EmojiRenderingSupport.install(scene);
        installWindowChromeHandlers();
        stage.setScene(scene);
        stage.setOnShown(event -> {
            active = true;
            canvas.requestFocus();
            emit(LuaCanvasEvent.simple("opened", canvas.getWidth(), canvas.getHeight()));
        });
        stage.setOnHidden(event -> closeActive());

        configureCanvas(options);
        applyInitialBackground(options);
        active = true;
        setFrameRateOnFx(options.fps());
    }

    private void update(String scriptName, String scriptIcon, LuaCanvasOptions options) {
        this.scriptIcon = LuaScript.normalizeIcon(scriptIcon);
        if (minimizedIconLabel != null) {
            minimizedIconLabel.setText(this.scriptIcon);
        }
        if (minimized) {
            restoreFromMinimized(false);
        }
        stage.setTitle(windowTitle(scriptName, options));
        stage.setResizable(false);
        pressedCodes.clear();
        keyState = LuaCanvasKeyState.empty();
        mouseState = LuaCanvasMouseState.empty();
        drawQueue.clear();
        configureCanvas(options);
        applyInitialBackground(options);
        active = true;
        setFrameRateOnFx(options.fps());
        emit(LuaCanvasEvent.simple("opened", canvas.getWidth(), canvas.getHeight()));
    }

    private void showStage() {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        if (minimized && minimizedBadge != null) {
            minimizedBadge.requestFocus();
        } else {
            canvas.requestFocus();
        }
    }

    private void configureCanvas(LuaCanvasOptions options) {
        resizeCanvasToHost = options.resizable();
        windowResizeEnabled = options.resizable();
        canvasHost.setMinSize(MIN_WIDTH, MIN_HEIGHT);
        canvasHost.setPrefSize(options.width(), options.height());
        canvasHost.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        canvas.setWidth(options.width());
        canvas.setHeight(options.height());
        if (!options.resizable()) {
            canvasHost.setMinSize(options.width(), options.height());
            canvasHost.setPrefSize(options.width(), options.height());
            canvasHost.setMaxSize(options.width(), options.height());
        }
        updateStatus();
        if (stage != null && !minimized) {
            stage.sizeToScene();
        }
    }

    private void installWindowChromeHandlers() {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (movingWindow || resizeMode != ResizeMode.NONE) {
                return;
            }
            if (minimized) {
                scene.setCursor(Cursor.MOVE);
                return;
            }
            if (isCloseButtonTarget(event.getTarget())) {
                scene.setCursor(Cursor.DEFAULT);
                return;
            }
            ResizeMode hoverMode = resizeModeFor(event);
            scene.setCursor(cursorFor(hoverMode, isMoveZone(event)));
        });
        scene.addEventFilter(MouseEvent.MOUSE_EXITED, event -> {
            if (!movingWindow && resizeMode == ResizeMode.NONE) {
                scene.setCursor(Cursor.DEFAULT);
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (minimized) {
                if (isMinimizedBadgeTarget(event.getTarget()) && event.getClickCount() >= 2) {
                    restoreFromMinimized(true);
                } else {
                    startMove(event);
                }
                event.consume();
                return;
            }
            if (isCloseButtonTarget(event.getTarget())) {
                return;
            }
            if (event.getClickCount() >= 2 && isMoveZone(event)) {
                minimizeToBadge();
                event.consume();
                return;
            }
            ResizeMode pressedResizeMode = resizeModeFor(event);
            if (pressedResizeMode != ResizeMode.NONE) {
                startResize(pressedResizeMode, event);
                event.consume();
                return;
            }
            if (isMoveZone(event)) {
                startMove(event);
                event.consume();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (resizeMode != ResizeMode.NONE) {
                resizeWindow(event);
                event.consume();
                return;
            }
            if (movingWindow) {
                stage.setX(event.getScreenX() - dragOffsetX);
                stage.setY(event.getScreenY() - dragOffsetY);
                if (minimized) {
                    rememberMinimizedPosition();
                }
                event.consume();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (resizeMode == ResizeMode.NONE && !movingWindow) {
                return;
            }
            boolean movedMinimizedBadge = minimized && movingWindow;
            resizeMode = ResizeMode.NONE;
            movingWindow = false;
            if (movedMinimizedBadge) {
                rememberMinimizedPosition();
            }
            scene.setCursor(cursorFor(resizeModeFor(event), isMoveZone(event)));
            event.consume();
        });
    }

    private void startMove(MouseEvent event) {
        dragOffsetX = event.getScreenX() - stage.getX();
        dragOffsetY = event.getScreenY() - stage.getY();
        movingWindow = true;
        scene.setCursor(Cursor.MOVE);
    }

    private void startResize(ResizeMode mode, MouseEvent event) {
        resizeMode = mode;
        resizeStartScreenX = event.getScreenX();
        resizeStartScreenY = event.getScreenY();
        resizeStartX = stage.getX();
        resizeStartY = stage.getY();
        resizeStartWidth = stage.getWidth();
        resizeStartHeight = stage.getHeight();
        scene.setCursor(cursorFor(mode, false));
    }

    private void resizeWindow(MouseEvent event) {
        double dx = event.getScreenX() - resizeStartScreenX;
        double dy = event.getScreenY() - resizeStartScreenY;
        double x = resizeStartX;
        double y = resizeStartY;
        double width = resizeStartWidth;
        double height = resizeStartHeight;

        if (resizeMode.east) {
            width = clamp(resizeStartWidth + dx, MIN_WIDTH, MAX_WIDTH);
        }
        if (resizeMode.south) {
            height = clamp(resizeStartHeight + dy, MIN_HEIGHT, MAX_HEIGHT);
        }
        if (resizeMode.west) {
            width = clamp(resizeStartWidth - dx, MIN_WIDTH, MAX_WIDTH);
            x = resizeStartX + resizeStartWidth - width;
        }
        if (resizeMode.north) {
            height = clamp(resizeStartHeight - dy, MIN_HEIGHT, MAX_HEIGHT);
            y = resizeStartY + resizeStartHeight - height;
        }

        stage.setX(x);
        stage.setY(y);
        stage.setWidth(width);
        stage.setHeight(height);
    }

    private void minimizeToBadge() {
        if (minimized || stage == null) {
            return;
        }
        restoreBounds = new WindowBounds(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        WindowPosition badgePosition = minimizedPosition != null
                ? minimizedPosition
                : new WindowPosition(stage.getX(), stage.getY());
        movingWindow = false;
        resizeMode = ResizeMode.NONE;
        minimized = true;

        root.getStyleClass().add("lua-canvas-minimized-root");
        canvasHost.setVisible(false);
        canvasHost.setManaged(false);
        closeButton.setVisible(false);
        closeButton.setManaged(false);
        minimizedIconLabel.setText(scriptIcon);
        minimizedBadge.setManaged(true);
        minimizedBadge.setVisible(true);

        stage.setMinWidth(MINIMIZED_SIZE);
        stage.setMinHeight(MINIMIZED_SIZE);
        stage.setOpacity(0.72);
        stage.setX(badgePosition.x());
        stage.setY(badgePosition.y());
        stage.setWidth(MINIMIZED_SIZE);
        stage.setHeight(MINIMIZED_SIZE);
        rememberMinimizedPosition();
        scene.setCursor(Cursor.MOVE);
        minimizedBadge.requestFocus();
    }

    private void restoreFromMinimized(boolean focusCanvas) {
        if (!minimized || stage == null) {
            return;
        }
        rememberMinimizedPosition();
        WindowBounds bounds = restoreBounds != null
                ? restoreBounds
                : new WindowBounds(stage.getX(), stage.getY(), DEFAULT_WIDTH, DEFAULT_HEIGHT);
        movingWindow = false;
        resizeMode = ResizeMode.NONE;
        minimized = false;

        root.getStyleClass().remove("lua-canvas-minimized-root");
        minimizedBadge.setVisible(false);
        minimizedBadge.setManaged(false);
        canvasHost.setManaged(true);
        canvasHost.setVisible(true);
        closeButton.setManaged(true);
        closeButton.setVisible(true);

        stage.setOpacity(1.0);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setX(bounds.x());
        stage.setY(bounds.y());
        stage.setWidth(Math.max(MIN_WIDTH, bounds.width()));
        stage.setHeight(Math.max(MIN_HEIGHT, bounds.height()));
        scene.setCursor(Cursor.DEFAULT);
        if (focusCanvas && canvas != null) {
            canvas.requestFocus();
        }
    }

    private void rememberMinimizedPosition() {
        if (stage == null) {
            return;
        }
        minimizedPosition = new WindowPosition(stage.getX(), stage.getY());
        MINIMIZED_POSITIONS.put(scriptId, minimizedPosition);
    }

    private ResizeMode resizeModeFor(MouseEvent event) {
        if (!windowResizeEnabled || scene == null) {
            return ResizeMode.NONE;
        }
        double x = event.getSceneX();
        double y = event.getSceneY();
        double width = scene.getWidth();
        double height = scene.getHeight();
        boolean west = x >= 0 && x <= RESIZE_EDGE_SIZE;
        boolean east = x >= width - RESIZE_EDGE_SIZE && x <= width;
        boolean north = y >= 0 && y <= RESIZE_EDGE_SIZE;
        boolean south = y >= height - RESIZE_EDGE_SIZE && y <= height;
        return ResizeMode.of(north, east, south, west);
    }

    private boolean isMoveZone(MouseEvent event) {
        return event.getSceneY() > RESIZE_EDGE_SIZE
                && event.getSceneY() <= MOVE_ZONE_HEIGHT
                && event.getSceneX() > RESIZE_EDGE_SIZE
                && event.getSceneX() < scene.getWidth() - RESIZE_EDGE_SIZE;
    }

    private static Cursor cursorFor(ResizeMode mode, boolean moveZone) {
        if (mode != ResizeMode.NONE) {
            return mode.cursor;
        }
        return moveZone ? Cursor.MOVE : Cursor.DEFAULT;
    }

    private StackPane createCloseButton() {
        Label icon = new Label("✕");
        icon.getStyleClass().add("lua-canvas-close-icon");

        StackPane button = new StackPane(icon);
        button.getStyleClass().add("lua-canvas-close-button");
        button.setMinSize(CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE);
        button.setPrefSize(CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE);
        button.setMaxSize(CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE);
        button.setFocusTraversable(false);
        button.setCursor(Cursor.HAND);
        Tooltip.install(button, new Tooltip("Закрыть"));
        button.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                requestCloseFromUser();
                event.consume();
            }
        });
        return button;
    }

    private StackPane createMinimizedBadge() {
        minimizedIconLabel = new Label(scriptIcon);
        minimizedIconLabel.getStyleClass().add("lua-canvas-minimized-icon");

        StackPane badge = new StackPane(minimizedIconLabel);
        badge.getStyleClass().add("lua-canvas-minimized-badge");
        badge.setMinSize(MINIMIZED_SIZE, MINIMIZED_SIZE);
        badge.setPrefSize(MINIMIZED_SIZE, MINIMIZED_SIZE);
        badge.setMaxSize(MINIMIZED_SIZE, MINIMIZED_SIZE);
        badge.setFocusTraversable(true);
        badge.setCursor(Cursor.MOVE);
        Tooltip.install(badge, new Tooltip("Двойной клик - восстановить"));
        return badge;
    }

    private void requestCloseFromUser() {
        if (confirmUserClose()) {
            stage.close();
        } else if (canvas != null) {
            canvas.requestFocus();
        }
    }

    private boolean confirmUserClose() {
        ButtonType cancelButton = new ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType closeButtonType = new ButtonType("Закрыть", ButtonBar.ButtonData.OK_DONE);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Закрыть форму");
        alert.setHeaderText("Закрыть Canvas-форму?");
        alert.setContentText("Текущее состояние формы может быть потеряно.");
        alert.getButtonTypes().setAll(cancelButton, closeButtonType);
        if (stage != null) {
            alert.initOwner(stage);
        }

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == closeButtonType;
    }

    private boolean isCloseButtonTarget(Object target) {
        return isTargetInside(target, closeButton);
    }

    private boolean isMinimizedBadgeTarget(Object target) {
        return isTargetInside(target, minimizedBadge);
    }

    private boolean isTargetInside(Object target, Node ancestor) {
        if (!(target instanceof Node node) || ancestor == null) {
            return false;
        }
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private void resizeCanvasToHost() {
        if (!resizeCanvasToHost || canvas == null || canvasHost == null) {
            return;
        }
        if (canvasHost.getWidth() > 0) {
            canvas.setWidth(canvasHost.getWidth());
        }
        if (canvasHost.getHeight() > 0) {
            canvas.setHeight(canvasHost.getHeight());
        }
    }

    private void closeActive() {
        if (!active) {
            return;
        }
        if (minimized) {
            rememberMinimizedPosition();
        }
        active = false;
        stopFrameTimer();
        if (drawFlushDelay != null) {
            drawFlushDelay.stop();
            drawFlushDelay = null;
        }
        drawQueue.clear();
        OPEN_WINDOWS.remove(scriptId, this);
        if (scene != null) {
            ThemeManager.unregisterScene(scene);
        }
        emit(LuaCanvasEvent.simple("closed", canvas.getWidth(), canvas.getHeight()));
    }

    private void enqueue(LuaCanvasDrawCommand command) {
        drawQueue.add(command);
        if (drawFlushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::scheduleDrawFlush);
        }
    }

    private void scheduleDrawFlush() {
        if (drawFlushDelay == null) {
            drawFlushDelay = new PauseTransition(Duration.millis(DRAW_FLUSH_DELAY_MS));
            drawFlushDelay.setOnFinished(event -> flushDrawQueue());
        }
        drawFlushDelay.playFromStart();
    }

    private void flushDrawQueue() {
        drawFlushScheduled.set(false);
        if (canvas == null || !active) {
            drawQueue.clear();
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        LuaCanvasDrawCommand command;
        while ((command = drawQueue.poll()) != null) {
            command.draw(gc);
        }
        if (!drawQueue.isEmpty() && drawFlushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::scheduleDrawFlush);
        }
    }

    private void setFrameRateOnFx(double fps) {
        stopFrameTimer();
        if (fps <= 0 || canvas == null || !active) {
            return;
        }
        double intervalMs = 1000.0 / clamp(fps, 1, 120);
        lastFrameNanos = System.nanoTime();
        frameTimer = new Timeline(new KeyFrame(Duration.millis(intervalMs), event -> emitFrame()));
        frameTimer.setCycleCount(Timeline.INDEFINITE);
        frameTimer.play();
    }

    private void emitFrame() {
        long now = System.nanoTime();
        double deltaSeconds = Math.max(0, (now - lastFrameNanos) / 1_000_000_000.0);
        lastFrameNanos = now;
        emit(LuaCanvasEvent.frame(canvas.getWidth(), canvas.getHeight(), deltaSeconds));
    }

    private void stopFrameTimer() {
        if (frameTimer != null) {
            frameTimer.stop();
            frameTimer = null;
        }
    }

    private void emitResize() {
        updateStatus();
        if (active && canvas != null) {
            emit(LuaCanvasEvent.simple("resized", canvas.getWidth(), canvas.getHeight()));
        }
    }

    private void updateStatus() {
        if (canvas != null) {
            canvasSize = new LuaCanvasSize(canvas.getWidth(), canvas.getHeight());
        }
    }

    private void applyInitialBackground(LuaCanvasOptions options) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (options.background() == null || options.background().isBlank()) {
            return;
        }
        try {
            gc.setFill(Color.web(options.background()));
            gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        } catch (IllegalArgumentException ignored) {
            // Color is validated by Lua API where possible.
        }
    }

    private void installCanvasInputHandlers() {
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, event -> handleMouse("mouse_moved", event));
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            canvas.requestFocus();
            handleMouse("mouse_pressed", event);
        });
        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> handleMouse("mouse_released", event));
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> handleMouse("mouse_clicked", event));
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> handleMouse("mouse_dragged", event));
        canvas.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> handleMouse("mouse_entered", event));
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED, event -> handleMouse("mouse_exited", event));
        canvas.addEventHandler(ScrollEvent.SCROLL, this::handleScroll);
        canvas.addEventHandler(KeyEvent.KEY_PRESSED, event -> handleKey("key_pressed", event));
        canvas.addEventHandler(KeyEvent.KEY_RELEASED, event -> handleKey("key_released", event));
        canvas.addEventHandler(KeyEvent.KEY_TYPED, event -> handleKey("key_typed", event));
    }

    private void handleMouse(String type, MouseEvent event) {
        if (!active) {
            return;
        }
        boolean over = !"mouse_exited".equals(type);
        boolean pressed = event.isPrimaryButtonDown() || event.isMiddleButtonDown() || event.isSecondaryButtonDown();
        String button = buttonName(event.getButton());
        mouseState = new LuaCanvasMouseState(
                event.getX(),
                event.getY(),
                event.getScreenX(),
                event.getScreenY(),
                over,
                pressed,
                event.isPrimaryButtonDown(),
                event.isMiddleButtonDown(),
                event.isSecondaryButtonDown(),
                button,
                event.getClickCount(),
                0,
                0,
                type,
                System.currentTimeMillis() / 1000.0);
        emit(LuaCanvasEvent.mouse(
                type,
                event.getX(),
                event.getY(),
                event.getScreenX(),
                event.getScreenY(),
                button,
                event.getClickCount(),
                event.isPrimaryButtonDown(),
                event.isMiddleButtonDown(),
                event.isSecondaryButtonDown(),
                canvas.getWidth(),
                canvas.getHeight()));
    }

    private void handleScroll(ScrollEvent event) {
        if (!active) {
            return;
        }
        mouseState = new LuaCanvasMouseState(
                event.getX(),
                event.getY(),
                event.getScreenX(),
                event.getScreenY(),
                true,
                false,
                false,
                false,
                false,
                "",
                0,
                event.getDeltaX(),
                event.getDeltaY(),
                "scroll",
                System.currentTimeMillis() / 1000.0);
        emit(LuaCanvasEvent.scroll(
                event.getX(),
                event.getY(),
                event.getScreenX(),
                event.getScreenY(),
                event.getDeltaX(),
                event.getDeltaY(),
                canvas.getWidth(),
                canvas.getHeight()));
    }

    private void handleKey(String type, KeyEvent event) {
        if (!active) {
            return;
        }
        String code = event.getCode() != null ? event.getCode().getName() : "";
        if ("key_pressed".equals(type) && !code.isBlank()) {
            pressedCodes.add(code);
        } else if ("key_released".equals(type) && !code.isBlank()) {
            pressedCodes.remove(code);
        }
        keyState = new LuaCanvasKeyState(
                Set.copyOf(pressedCodes),
                type,
                code,
                event.getCode() != null ? event.getCode().getName() : "",
                event.getCharacter() != null ? event.getCharacter() : "",
                event.isShiftDown(),
                event.isControlDown(),
                event.isAltDown(),
                event.isMetaDown(),
                System.currentTimeMillis() / 1000.0);
        emit(LuaCanvasEvent.key(
                type,
                code,
                event.getCode() != null ? event.getCode().getName() : "",
                event.getCharacter() != null ? event.getCharacter() : "",
                event.isShiftDown(),
                event.isControlDown(),
                event.isAltDown(),
                event.isMetaDown(),
                canvas.getWidth(),
                canvas.getHeight()));
    }

    private void emit(LuaCanvasEvent event) {
        if (eventSink != null) {
            eventSink.accept(event);
        }
    }

    private static LuaCanvasOptions sanitizeOptions(LuaCanvasOptions options) {
        LuaCanvasOptions source = options != null
                ? options
                : new LuaCanvasOptions("", DEFAULT_WIDTH, DEFAULT_HEIGHT, "", true, 0);
        return new LuaCanvasOptions(
                source.title() != null ? source.title() : "",
                clamp(source.width() > 0 ? source.width() : DEFAULT_WIDTH, MIN_WIDTH, MAX_WIDTH),
                clamp(source.height() > 0 ? source.height() : DEFAULT_HEIGHT, MIN_HEIGHT, MAX_HEIGHT),
                source.background() != null ? source.background() : "",
                source.resizable(),
                clamp(source.fps(), 0, 120));
    }

    private static String windowTitle(String scriptName, LuaCanvasOptions options) {
        String title = options.title() != null && !options.title().isBlank()
                ? options.title()
                : "Lua Canvas";
        if (scriptName != null && !scriptName.isBlank()) {
            return title + " - " + scriptName;
        }
        return title;
    }

    private static String buttonName(javafx.scene.input.MouseButton button) {
        if (button == null) {
            return "";
        }
        return switch (button) {
            case PRIMARY -> "primary";
            case MIDDLE -> "middle";
            case SECONDARY -> "secondary";
            case BACK -> "back";
            case FORWARD -> "forward";
            case NONE -> "";
            default -> button.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private record WindowBounds(double x, double y, double width, double height) {}

    private record WindowPosition(double x, double y) {}

    private enum ResizeMode {
        NONE(false, false, false, false, Cursor.DEFAULT),
        NORTH(true, false, false, false, Cursor.N_RESIZE),
        NORTH_EAST(true, true, false, false, Cursor.NE_RESIZE),
        EAST(false, true, false, false, Cursor.E_RESIZE),
        SOUTH_EAST(false, true, true, false, Cursor.SE_RESIZE),
        SOUTH(false, false, true, false, Cursor.S_RESIZE),
        SOUTH_WEST(false, false, true, true, Cursor.SW_RESIZE),
        WEST(false, false, false, true, Cursor.W_RESIZE),
        NORTH_WEST(true, false, false, true, Cursor.NW_RESIZE);

        private final boolean north;
        private final boolean east;
        private final boolean south;
        private final boolean west;
        private final Cursor cursor;

        ResizeMode(boolean north, boolean east, boolean south, boolean west, Cursor cursor) {
            this.north = north;
            this.east = east;
            this.south = south;
            this.west = west;
            this.cursor = cursor;
        }

        private static ResizeMode of(boolean north, boolean east, boolean south, boolean west) {
            if (north && east) {
                return NORTH_EAST;
            }
            if (south && east) {
                return SOUTH_EAST;
            }
            if (south && west) {
                return SOUTH_WEST;
            }
            if (north && west) {
                return NORTH_WEST;
            }
            if (north) {
                return NORTH;
            }
            if (east) {
                return EAST;
            }
            if (south) {
                return SOUTH;
            }
            if (west) {
                return WEST;
            }
            return NONE;
        }
    }

    private static void runOnFxAndWait(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RuntimeException> errorRef = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (RuntimeException error) {
                errorRef.set(error);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(FX_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX thread did not respond");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lua Canvas operation interrupted", e);
        }
        RuntimeException error = errorRef.get();
        if (error != null) {
            throw error;
        }
    }
}
