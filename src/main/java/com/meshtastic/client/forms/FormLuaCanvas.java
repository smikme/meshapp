package com.meshtastic.client.forms;

import com.meshtastic.client.lua.LuaCanvasDrawCommand;
import com.meshtastic.client.lua.LuaCanvasEvent;
import com.meshtastic.client.lua.LuaCanvasKeyState;
import com.meshtastic.client.lua.LuaCanvasMouseState;
import com.meshtastic.client.lua.LuaCanvasOptions;
import com.meshtastic.client.lua.LuaCanvasSize;
import com.meshtastic.client.system.AllForms;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.system.FormManager;
import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Встроенная форма Canvas для Lua-скриптов.
 *
 * <p>Форма не регистрируется в боковом меню и показывается только через
 * {@code mesh.canvas.open(...)}.
 */
public final class FormLuaCanvas extends Form {

    private static final double DEFAULT_WIDTH = 640;
    private static final double DEFAULT_HEIGHT = 360;
    private static final double MIN_WIDTH = 240;
    private static final double MIN_HEIGHT = 160;
    private static final double MAX_WIDTH = 1920;
    private static final double MAX_HEIGHT = 1080;
    private static final long FX_WAIT_TIMEOUT_SECONDS = 2;
    private static volatile FormLuaCanvas instance;

    private final Queue<LuaCanvasDrawCommand> drawQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drawFlushScheduled = new AtomicBoolean(false);
    private final Set<String> pressedCodes = new HashSet<>();

    private Canvas canvas;
    private StackPane canvasHost;
    private Label subtitleLabel;
    private Label statusLabel;
    private Timeline frameTimer;
    private Form previousForm;
    private long lastFrameNanos;
    private volatile long activeScriptId;
    private Consumer<LuaCanvasEvent> eventSink;
    private boolean resizeCanvasToHost;
    private volatile LuaCanvasSize canvasSize = new LuaCanvasSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    private volatile LuaCanvasMouseState mouseState = LuaCanvasMouseState.empty();
    private volatile LuaCanvasKeyState keyState = LuaCanvasKeyState.empty();

    public FormLuaCanvas() {
        instance = this;
        init();
    }

    public static void showCanvas(long scriptId,
                                  String scriptName,
                                  LuaCanvasOptions options,
                                  Consumer<LuaCanvasEvent> eventSink) {
        runOnFxAndWait(() -> form().open(scriptId, scriptName, sanitizeOptions(options), eventSink));
    }

    public static void closeCanvas(long scriptId) {
        runOnFxAndWait(() -> {
            FormLuaCanvas form = form();
            if (form.activeScriptId == scriptId) {
                form.closeActive();
            }
        });
    }

    public static boolean enqueueDraw(long scriptId, LuaCanvasDrawCommand command) {
        FormLuaCanvas form = instance;
        if (form == null) {
            return false;
        }
        if (form.activeScriptId != scriptId || command == null) {
            return false;
        }
        form.enqueue(command);
        return true;
    }

    public static boolean setFrameRate(long scriptId, double fps) {
        FormLuaCanvas form = instance;
        if (form == null) {
            return false;
        }
        if (form.activeScriptId != scriptId) {
            return false;
        }
        runOnFxAndWait(() -> form.setFrameRateOnFx(fps));
        return true;
    }

    public static LuaCanvasMouseState mouseState(long scriptId) {
        FormLuaCanvas form = instance;
        if (form == null) {
            return LuaCanvasMouseState.empty();
        }
        return form.activeScriptId == scriptId ? form.mouseState : LuaCanvasMouseState.empty();
    }

    public static LuaCanvasKeyState keyState(long scriptId) {
        FormLuaCanvas form = instance;
        if (form == null) {
            return LuaCanvasKeyState.empty();
        }
        return form.activeScriptId == scriptId ? form.keyState : LuaCanvasKeyState.empty();
    }

    public static LuaCanvasSize size(long scriptId) {
        FormLuaCanvas form = instance;
        if (form == null) {
            return LuaCanvasSize.empty();
        }
        return form.activeScriptId == scriptId ? form.canvasSize : LuaCanvasSize.empty();
    }

    private static FormLuaCanvas form() {
        return (FormLuaCanvas) AllForms.getForm(FormLuaCanvas.class);
    }

    private void init() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.getStyleClass().add("lua-canvas-form");

        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(2);
        Label title = new Label("Lua Canvas");
        title.getStyleClass().add("form-title");
        subtitleLabel = new Label("Нет активного скрипта");
        subtitleLabel.getStyleClass().add("muted-small-label");
        titleBox.getChildren().addAll(title, subtitleLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusLabel = new Label(sizeText(DEFAULT_WIDTH, DEFAULT_HEIGHT));
        statusLabel.getStyleClass().add("config-status-label");

        Button closeButton = new Button("Закрыть");
        closeButton.setOnAction(event -> closeActive());

        titleRow.getChildren().addAll(titleBox, spacer, statusLabel, closeButton);

        canvas = new Canvas(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        canvas.setFocusTraversable(true);
        installCanvasInputHandlers();
        canvas.widthProperty().addListener((observable, oldValue, newValue) -> emitResize());
        canvas.heightProperty().addListener((observable, oldValue, newValue) -> emitResize());

        canvasHost = new StackPane(canvas);
        canvasHost.getStyleClass().add("lua-canvas-host");
        canvasHost.setMinSize(MIN_WIDTH, MIN_HEIGHT);
        canvasHost.widthProperty().addListener((observable, oldValue, newValue) -> resizeCanvasToHost());
        canvasHost.heightProperty().addListener((observable, oldValue, newValue) -> resizeCanvasToHost());
        VBox.setVgrow(canvasHost, Priority.ALWAYS);

        content.getChildren().addAll(titleRow, new Separator(), canvasHost);
        getChildren().add(content);
    }

    private void open(long scriptId,
                      String scriptName,
                      LuaCanvasOptions options,
                      Consumer<LuaCanvasEvent> eventSink) {
        if (activeScriptId != 0 && activeScriptId != scriptId && this.eventSink != null) {
            this.eventSink.accept(LuaCanvasEvent.simple("closed", canvas.getWidth(), canvas.getHeight()));
        }
        Form current = FormManager.getCurrentForm();
        if (current != this) {
            previousForm = current;
        }
        activeScriptId = scriptId;
        this.eventSink = eventSink;
        pressedCodes.clear();
        keyState = LuaCanvasKeyState.empty();
        mouseState = LuaCanvasMouseState.empty();
        drawQueue.clear();

        subtitleLabel.setText(subtitle(scriptName, options));
        configureCanvas(options);
        applyInitialBackground(options);
        setFrameRateOnFx(options.fps());
        FormManager.showTransientForm(this);
        canvas.requestFocus();
        emit(LuaCanvasEvent.simple("opened", canvas.getWidth(), canvas.getHeight()));
    }

    private void configureCanvas(LuaCanvasOptions options) {
        canvas.widthProperty().unbind();
        canvas.heightProperty().unbind();
        resizeCanvasToHost = options.resizable();
        canvasHost.setMinSize(MIN_WIDTH, MIN_HEIGHT);
        canvasHost.setPrefSize(options.width(), options.height());
        canvasHost.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        if (options.resizable()) {
            canvas.setWidth(options.width());
            canvas.setHeight(options.height());
            resizeCanvasToHost();
        } else {
            canvas.setWidth(options.width());
            canvas.setHeight(options.height());
            canvasHost.setMinSize(options.width(), options.height());
            canvasHost.setPrefSize(options.width(), options.height());
            canvasHost.setMaxSize(options.width(), options.height());
        }
        updateStatus();
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
        stopFrameTimer();
        drawQueue.clear();
        long closingScriptId = activeScriptId;
        activeScriptId = 0;
        Consumer<LuaCanvasEvent> closingSink = eventSink;
        eventSink = null;
        if (closingSink != null && canvas != null) {
            closingSink.accept(LuaCanvasEvent.simple("closed", canvas.getWidth(), canvas.getHeight()));
        }
        if (previousForm != null && previousForm != this && FormManager.getCurrentForm() == this) {
            FormManager.showForm(previousForm);
        }
        previousForm = null;
        if (closingScriptId == 0) {
            clearCanvas();
        }
    }

    private void clearCanvas() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void enqueue(LuaCanvasDrawCommand command) {
        drawQueue.add(command);
        if (drawFlushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushDrawQueue);
        }
    }

    private void flushDrawQueue() {
        drawFlushScheduled.set(false);
        if (canvas == null || activeScriptId == 0) {
            drawQueue.clear();
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        LuaCanvasDrawCommand command;
        while ((command = drawQueue.poll()) != null) {
            command.draw(gc);
        }
        if (!drawQueue.isEmpty() && drawFlushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushDrawQueue);
        }
    }

    private void setFrameRateOnFx(double fps) {
        stopFrameTimer();
        if (fps <= 0 || canvas == null || activeScriptId == 0) {
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
        if (activeScriptId != 0 && canvas != null) {
            emit(LuaCanvasEvent.simple("resized", canvas.getWidth(), canvas.getHeight()));
        }
    }

    private void updateStatus() {
        if (statusLabel != null && canvas != null) {
            LuaCanvasSize size = new LuaCanvasSize(canvas.getWidth(), canvas.getHeight());
            canvasSize = size;
            statusLabel.setText(sizeText(size.width(), size.height()));
        }
    }

    private void applyInitialBackground(LuaCanvasOptions options) {
        clearCanvas();
        if (options.background() == null || options.background().isBlank()) {
            return;
        }
        try {
            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.setFill(Color.web(options.background()));
            gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        } catch (IllegalArgumentException ignored) {
            // Цвет валидируется Lua API.
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
        if (activeScriptId == 0) {
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
        if (activeScriptId == 0) {
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
        if (activeScriptId == 0) {
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
        Consumer<LuaCanvasEvent> sink = eventSink;
        if (sink != null) {
            sink.accept(event);
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

    private static String subtitle(String scriptName, LuaCanvasOptions options) {
        String script = scriptName != null && !scriptName.isBlank() ? scriptName : "Lua script";
        String title = options.title() != null && !options.title().isBlank() ? options.title() : "Canvas";
        return UnicodeTextUtils.sanitizeForJavaFxDisplay(script + " / " + title);
    }

    private static String sizeText(double width, double height) {
        return Math.round(width) + " x " + Math.round(height);
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
