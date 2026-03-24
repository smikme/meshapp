package com.meshtastic.client.system;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.tray.AppTrayManager;
import com.meshtastic.client.utils.AppPreferences;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Корневая панель окна приложения с кастомным title bar.
 * <p>
 * Реализует кастомную оконную рамку (traffic light кнопки, перетаскивание,
 * resize по краям) для работы с {@code StageStyle.TRANSPARENT}.
 * Содержит {@link DrawerPane} (боковое меню), {@link MainForm} (область контента),
 * {@link com.meshtastic.client.modal.ModalPane} и overlay для toast-уведомлений.
 * <p>
 * На macOS resize делегируется нативному {@code NSWindowStyleMaskResizable}.
 * На Windows/Linux используются кастомные обработчики мыши по краям/углам окна.
 */
public class RootPane extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(RootPane.class);

    private static final double RESIZE_MARGIN = 8;
    private static final double CORNER_MARGIN = 16;

    private final DrawerPane drawerPane;
    private final MainForm mainForm;
    private final StackPane toastOverlay;

    private double dragStartX;
    private double dragStartY;
    private double stageStartX;
    private double stageStartY;
    private double stageStartW;
    private double stageStartH;
    private ResizeDirection resizeDir = ResizeDirection.NONE;

    private double dragOffsetX;
    private double dragOffsetY;
    private boolean dragging = false;

    /** Кастомный maximize (Windows/Linux): stage.setMaximized() при TRANSPARENT перекрывает панель задач */
    private boolean customMaximized = false;
    private double restoreX;
    private double restoreY;
    private double restoreW;
    private double restoreH;

    /** Узел, на котором мы принудительно подменили курсор; null если не подменяли */
    private Node cursorOverriddenNode;
    private Cursor cursorOverriddenOriginal;

    public RootPane() {
        drawerPane = new DrawerPane();
        mainForm = new MainForm();
        ModalPane modalPane = new ModalPane();
        toastOverlay = new StackPane();
        toastOverlay.setPickOnBounds(false);

        StackPane centerStack = new StackPane(mainForm, modalPane, toastOverlay);

        if (!AppPreferences.isDisableEffects()) {
            // Кастомный title bar — на всю ширину окна, прозрачный (frosted glass просвечивает)
            HBox titleBar = createTitleBar();
            setTop(titleBar);

            // Windows + StageStyle.TRANSPARENT: ОС пропускает mouse events сквозь
            // полностью прозрачные пиксели (hit-test по альфа-каналу).
            // Минимальный фон (alpha=1/255 ≈ 0.4%) невидим глазу, но делает
            // все пиксели RootPane «непрозрачными» для Windows hit-test.
            // На macOS это не нужно — NSVisualEffectView рисует backdrop под JavaFX.
            if (OsDetect.isWindows()) {
                setStyle("-fx-background-color: rgba(0,0,0,0.004);");
            }

            // На macOS ресайз обрабатывает нативный NSWindowStyleMaskResizable,
            // кастомные хэндлеры нужны только для Windows/Linux
            if (!OsDetect.isMacOs()) {
                initResizeHandlers();
            }
        }

        setLeft(drawerPane);
        setCenter(centerStack);

        Toast.setOverlay(toastOverlay);
        ModalPane.install(modalPane);
    }

    /**
     * Кастомный title bar: traffic lights слева, заголовок по центру, drag по всей ширине.
     */
    private HBox createTitleBar() {
        HBox bar = new HBox();
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.getStyleClass().add("custom-title-bar");
        // Перехватывать клики по всей площади bar, включая прозрачные spacers —
        // без этого на Windows клики проваливаются сквозь прозрачные Region
        bar.setPickOnBounds(true);

        // Traffic light кнопки
        Button closeBtn = createWindowButton("window-btn-close");
        closeBtn.setOnAction(e -> {
            Stage stage = MeshApp.getPrimaryStage();
            if (stage != null) { stage.close(); }
        });

        Button minimizeBtn = createWindowButton("window-btn-minimize");
        minimizeBtn.setOnAction(e -> AppTrayManager.getInstance().requestMinimize());

        Button maximizeBtn = createWindowButton("window-btn-maximize");
        maximizeBtn.setOnAction(e -> toggleMaximize());

        HBox buttons = new HBox(8, closeBtn, minimizeBtn, maximizeBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label titleLabel = new Label("MeshApp");
        titleLabel.getStyleClass().add("title-bar-label");

        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        bar.getChildren().addAll(buttons, spacer, titleLabel, spacer2);

        // Двойной клик по title bar — toggle maximize
        bar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !(event.getTarget() instanceof Button)) {
                toggleMaximize();
            }
        });

        // Перетаскивание окна за title bar (кроме зон resize по краям/углам)
        bar.setOnMousePressed(event -> {
            if (event.getTarget() instanceof Button) { return; }
            if (isInResizeZone(event)) { return; }
            Stage stage = MeshApp.getPrimaryStage();
            if (stage != null) {
                dragOffsetX = event.getScreenX() - stage.getX();
                dragOffsetY = event.getScreenY() - stage.getY();
                dragging = true;
            }
        });
        bar.setOnMouseDragged(event -> {
            if (!dragging) { return; }
            Stage stage = MeshApp.getPrimaryStage();
            if (stage != null) {
                // При перетаскивании из maximized — сначала восстанавливаем размер
                if (customMaximized) {
                    double relativeX = event.getScreenX() - stage.getX();
                    double proportion = relativeX / stage.getWidth();
                    restoreFromMaximize(stage);
                    // Пересчитываем offset чтобы курсор оставался пропорционально
                    dragOffsetX = restoreW * proportion;
                    dragOffsetY = event.getScreenY() - stage.getY();
                }
                stage.setX(event.getScreenX() - dragOffsetX);
                stage.setY(event.getScreenY() - dragOffsetY);
            }
        });
        bar.setOnMouseReleased(event -> dragging = false);

        return bar;
    }

    private Button createWindowButton(String styleClass) {
        Button btn = new Button();
        btn.getStyleClass().addAll("window-control-btn", styleClass);
        btn.setMinSize(13, 13);
        btn.setMaxSize(13, 13);
        btn.setPrefSize(13, 13);
        return btn;
    }

    // ==================== Maximize ====================

    private void toggleMaximize() {
        Stage stage = MeshApp.getPrimaryStage();
        if (stage == null) { return; }

        if (OsDetect.isMacOs()) {
            // На macOS нативный NSWindowStyleMaskResizable корректно обрабатывает maximize
            stage.setMaximized(!stage.isMaximized());
        } else {
            // Windows/Linux: stage.setMaximized() при TRANSPARENT перекрывает панель задач
            if (customMaximized) {
                restoreFromMaximize(stage);
            } else {
                maximizeToVisualBounds(stage);
            }
        }
    }

    private void maximizeToVisualBounds(Stage stage) {
        restoreX = stage.getX();
        restoreY = stage.getY();
        restoreW = stage.getWidth();
        restoreH = stage.getHeight();

        Rectangle2D bounds = Screen.getScreensForRectangle(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()
        ).getFirst().getVisualBounds();

        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        customMaximized = true;
    }

    private void restoreFromMaximize(Stage stage) {
        stage.setX(restoreX);
        stage.setY(restoreY);
        stage.setWidth(restoreW);
        stage.setHeight(restoreH);
        customMaximized = false;
    }

    // ==================== Resize ====================

    private void initResizeHandlers() {
        // Используем EventFilter (фаза захвата) вместо setOnMouse* (фаза всплытия),
        // чтобы resize работал поверх title bar и других дочерних элементов
        addEventFilter(MouseEvent.MOUSE_MOVED, this::updateResizeCursor);
        addEventFilter(MouseEvent.MOUSE_PRESSED, this::onResizeStart);
        addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onResizeDrag);
        addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (resizeDir != ResizeDirection.NONE) {
                resizeDir = ResizeDirection.NONE;
                e.consume();
            }
        });
    }

    private void updateResizeCursor(MouseEvent e) {
        if (customMaximized) {
            restoreCursorOverride();
            setCursor(Cursor.DEFAULT);
            return;
        }
        ResizeDirection dir = detectEdge(e);
        Node target = (e.getTarget() instanceof Node n) ? n : null;

        if (dir != ResizeDirection.NONE) {
            Cursor cursor = switch (dir) {
                case N, S -> Cursor.N_RESIZE;
                case E, W -> Cursor.E_RESIZE;
                case NE, SW -> Cursor.NE_RESIZE;
                case NW, SE -> Cursor.SE_RESIZE;
                default -> Cursor.DEFAULT;
            };
            // Восстановить предыдущий узел, если target сменился
            restoreCursorOverride();
            // Принудительно ставим resize-курсор на target-узел
            if (target != null) {
                cursorOverriddenOriginal = target.getCursor();
                cursorOverriddenNode = target;
                target.setCursor(cursor);
            }
            setCursor(cursor);
        } else {
            // Вне зоны resize — восстанавливаем оригинальный курсор
            restoreCursorOverride();
            setCursor(Cursor.DEFAULT);
        }
    }

    private void restoreCursorOverride() {
        if (cursorOverriddenNode != null) {
            cursorOverriddenNode.setCursor(cursorOverriddenOriginal);
            cursorOverriddenNode = null;
            cursorOverriddenOriginal = null;
        }
    }

    private void onResizeStart(MouseEvent e) {
        if (customMaximized) { return; }
        resizeDir = detectEdge(e);
        log.info("RESIZE_START: sceneX={} sceneY={} dir={} target={}",
                e.getSceneX(), e.getSceneY(), resizeDir, e.getTarget().getClass().getSimpleName());
        if (resizeDir != ResizeDirection.NONE) {
            Stage stage = (Stage) getScene().getWindow();
            dragStartX = e.getScreenX();
            dragStartY = e.getScreenY();
            stageStartX = stage.getX();
            stageStartY = stage.getY();
            stageStartW = stage.getWidth();
            stageStartH = stage.getHeight();
            e.consume();
        }
    }

    private void onResizeDrag(MouseEvent e) {
        if (resizeDir == ResizeDirection.NONE) { return; }
        log.info("RESIZE_DRAG: sceneX={} sceneY={} dir={}", e.getSceneX(), e.getSceneY(), resizeDir);
        Stage stage = (Stage) getScene().getWindow();
        double dx = e.getScreenX() - dragStartX;
        double dy = e.getScreenY() - dragStartY;
        double minW = stage.getMinWidth() > 0 ? stage.getMinWidth() : 400;
        double minH = stage.getMinHeight() > 0 ? stage.getMinHeight() : 300;

        if (resizeDir.isRight()) {
            stage.setWidth(Math.max(minW, stageStartW + dx));
        }
        if (resizeDir.isBottom()) {
            stage.setHeight(Math.max(minH, stageStartH + dy));
        }
        if (resizeDir.isLeft()) {
            double newW = Math.max(minW, stageStartW - dx);
            stage.setWidth(newW);
            stage.setX(stageStartX + stageStartW - newW);
        }
        if (resizeDir.isTop()) {
            double newH = Math.max(minH, stageStartH - dy);
            stage.setHeight(newH);
            stage.setY(stageStartY + stageStartH - newH);
        }
        e.consume();
    }

    private ResizeDirection detectEdge(MouseEvent e) {
        // Используем sceneX/sceneY — при EventFilter e.getX()/getY() относительны к target-узлу,
        // а не к RootPane. sceneX/sceneY совпадают с локальными координатами RootPane,
        // т.к. RootPane — корневой узел сцены.
        double x = e.getSceneX();
        double y = e.getSceneY();
        double w = getWidth();
        double h = getHeight();

        // Углы — увеличенная зона захвата
        boolean cornerTop = y < CORNER_MARGIN;
        boolean cornerBottom = y > h - CORNER_MARGIN;
        boolean cornerLeft = x < CORNER_MARGIN;
        boolean cornerRight = x > w - CORNER_MARGIN;

        if (cornerTop && cornerLeft) { return ResizeDirection.NW; }
        if (cornerTop && cornerRight) { return ResizeDirection.NE; }
        if (cornerBottom && cornerLeft) { return ResizeDirection.SW; }
        if (cornerBottom && cornerRight) { return ResizeDirection.SE; }

        // Стороны — стандартная зона
        boolean top = y < RESIZE_MARGIN;
        boolean bottom = y > h - RESIZE_MARGIN;
        boolean left = x < RESIZE_MARGIN;
        boolean right = x > w - RESIZE_MARGIN;

        if (top) { return ResizeDirection.N; }
        if (bottom) { return ResizeDirection.S; }
        if (left) { return ResizeDirection.W; }
        if (right) { return ResizeDirection.E; }
        return ResizeDirection.NONE;
    }

    /** Проверяет, находится ли курсор в зоне resize (края/углы окна) */
    private boolean isInResizeZone(MouseEvent event) {
        double sceneX = event.getSceneX();
        double sceneY = event.getSceneY();
        double w = getWidth();
        double h = getHeight();
        // Углы
        if ((sceneX < CORNER_MARGIN && sceneY < CORNER_MARGIN) ||
            (sceneX > w - CORNER_MARGIN && sceneY < CORNER_MARGIN) ||
            (sceneX < CORNER_MARGIN && sceneY > h - CORNER_MARGIN) ||
            (sceneX > w - CORNER_MARGIN && sceneY > h - CORNER_MARGIN)) { return true; }
        // Края
        return sceneX < RESIZE_MARGIN || sceneX > w - RESIZE_MARGIN ||
               sceneY < RESIZE_MARGIN || sceneY > h - RESIZE_MARGIN;
    }

    // ==================== Maximize state accessors ====================

    public boolean isCustomMaximized() { return customMaximized; }
    public double getRestoreX() { return restoreX; }
    public double getRestoreY() { return restoreY; }
    public double getRestoreW() { return restoreW; }
    public double getRestoreH() { return restoreH; }

    public void maximizeToVisualBounds() {
        Stage stage = MeshApp.getPrimaryStage();
        if (stage != null) { maximizeToVisualBounds(stage); }
    }

    public DrawerPane getDrawerPane() {
        return drawerPane;
    }

    public MainForm getMainForm() {
        return mainForm;
    }

    private enum ResizeDirection {
        NONE, N, S, E, W, NE, NW, SE, SW;

        boolean isTop() { return this == N || this == NE || this == NW; }
        boolean isBottom() { return this == S || this == SE || this == SW; }
        boolean isLeft() { return this == W || this == NW || this == SW; }
        boolean isRight() { return this == E || this == NE || this == SE; }
    }
}
