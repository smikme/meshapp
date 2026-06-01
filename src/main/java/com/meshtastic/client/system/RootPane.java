package com.meshtastic.client.system;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.tray.AppTrayManager;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
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
 * Root pane for the application window with a custom title bar.
 * <p>
 * Implements a custom window frame for {@code StageStyle.TRANSPARENT}: traffic
 * light buttons, window dragging, and edge resizing. Contains {@link DrawerPane}
 * for navigation, {@link MainForm} for content, {@link com.meshtastic.client.modal.ModalPane},
 * and a toast overlay.
 * <p>
 * On macOS, resizing is delegated to native {@code NSWindowStyleMaskResizable}.
 * On Windows/Linux, custom mouse handlers handle edges and corners.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class RootPane extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(RootPane.class);

    private static final double RESIZE_MARGIN = 8;
    private static final double CORNER_MARGIN = 16;
    /**
     * A transparent/layered Windows window needs a minimal alpha value; otherwise
     * hit-testing passes through fully transparent pixels. Keep the value barely
     * visible so it does not harm the backdrop.
     */
    private static final String WINDOWS_HIT_TEST_BACKGROUND = "-fx-background-color: rgba(0,0,0,0.004);";

    private final DrawerPane drawerPane;
    private final MainForm mainForm;
    private final ConnectionTabsPane connectionTabsPane;
    private final StackPane toastOverlay;
    private final Runnable connectionTitleListener = () -> Platform.runLater(this::updateWindowTitle);

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

    /** Custom maximize for Windows/Linux: stage.setMaximized() with TRANSPARENT overlaps the taskbar. */
    private boolean customMaximized = false;
    private double restoreX;
    private double restoreY;
    private double restoreW;
    private double restoreH;

    /** Node whose cursor was forcibly overridden; null when none was changed. */
    private Node cursorOverriddenNode;
    private Cursor cursorOverriddenOriginal;
    private Label titleLabel;

    public RootPane() {
        drawerPane = new DrawerPane();
        mainForm = new MainForm();
        connectionTabsPane = new ConnectionTabsPane();
        ModalPane modalPane = new ModalPane();
        toastOverlay = new StackPane();
        toastOverlay.setPickOnBounds(false);

        StackPane centerStack = new StackPane(mainForm, modalPane, toastOverlay);

        if (!AppPreferences.isDisableEffectsEffective()) {
        // Custom title bar spans the full window width and remains transparent so frosted glass shows through.
            HBox titleBar = createTitleBar();
            setTop(titleBar);

        // Windows + StageStyle.TRANSPARENT lets mouse events pass through fully
        // transparent pixels because hit-testing uses alpha. Fully transparent
        // pixels do not participate in Windows hit-testing, so root gets a nearly
        // invisible alpha layer for resize. macOS does not need this because
        // NSVisualEffectView draws the backdrop under JavaFX.
            if (OsDetect.isWindows()) {
                setPickOnBounds(true);
                setStyle(WINDOWS_HIT_TEST_BACKGROUND);
            }

        // On macOS, resize is handled by native NSWindowStyleMaskResizable.
        // Custom handlers are needed only for Windows/Linux.
            if (!OsDetect.isMacOs()) {
                initResizeHandlers();
            }
        }

        setLeft(drawerPane);
        setCenter(centerStack);
        setBottom(connectionTabsPane);

        Toast.setOverlay(toastOverlay);
        ModalPane.install(modalPane);

        ConnectionManager.getInstance().addListener(connectionTitleListener);
        updateWindowTitle();
    }

    /**
     * Custom title bar: traffic lights on the left, centered title, and full-width dragging.
     */
    private HBox createTitleBar() {
        HBox bar = new HBox();
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.getStyleClass().add("custom-title-bar");
        // Capture clicks across the whole bar, including transparent spacers;
        // otherwise Windows lets clicks pass through transparent Region nodes.
        bar.setPickOnBounds(true);
        if (OsDetect.isWindows()) {
        // For a transparent/layered window, Windows evaluates alpha in the top
        // titlebar layer, so make the bar itself hit-testable, not only root.
            bar.setStyle(WINDOWS_HIT_TEST_BACKGROUND);
        }

        // Traffic light buttons.
        Button closeBtn = createWindowButton("window-btn-close");
        closeBtn.setOnAction(e -> AppTrayManager.getInstance().exitApplication());

        Button minimizeBtn = createWindowButton("window-btn-minimize");
        minimizeBtn.setOnAction(e -> AppTrayManager.getInstance().requestMinimize());

        Button maximizeBtn = createWindowButton("window-btn-maximize");
        maximizeBtn.setOnAction(e -> toggleMaximize());

        HBox buttons = new HBox(8, closeBtn, minimizeBtn, maximizeBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        spacer.setMouseTransparent(true);

        titleLabel = new Label(applicationTitle());
        titleLabel.getStyleClass().add("title-bar-label");
        titleLabel.setMinWidth(0);
        titleLabel.setMaxWidth(520);
        titleLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        titleLabel.setMouseTransparent(true);

        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);
        spacer2.setMouseTransparent(true);

        bar.getChildren().addAll(buttons, spacer, titleLabel, spacer2);

        // Double-click on title bar toggles maximize.
        bar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !(event.getTarget() instanceof Button)) {
                toggleMaximize();
            }
        });

        // Drag the window by the title bar, except over edge/corner resize zones.
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
                    // When dragging from maximized state, restore size first.
                if (customMaximized) {
                    double relativeX = event.getScreenX() - stage.getX();
                    double proportion = relativeX / stage.getWidth();
                    restoreFromMaximize(stage);
                    // Recalculate offset so the cursor keeps the same relative position.
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

    private void updateWindowTitle() {
        String title = buildWindowTitle();
        if (titleLabel != null) {
            titleLabel.setText(title);
        }
        Stage stage = MeshApp.getPrimaryStage();
        if (stage != null) {
            stage.setTitle(title);
        }
    }

    private String buildWindowTitle() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = findCurrentConnection(manager);
        if (entry == null) {
            return applicationTitle();
        }

        String nodeTitle = resolveNodeTitle(manager, entry);
        if (entry.isReconnecting() && !entry.isConnected()) {
            nodeTitle += " " + I18n.t("connection.windowTitle.reconnectingSuffix");
        }
        return I18n.t("app.windowTitle.withConnection", applicationTitle(), nodeTitle);
    }

    private static String applicationTitle() {
        return I18n.t("app.title");
    }

    private static ConnectionEntry findCurrentConnection(ConnectionManager manager) {
        return manager.getSelectedConnectionEntry();
    }

    private static String resolveNodeTitle(ConnectionManager manager, ConnectionEntry entry) {
        NodeData node = findLocalNode(manager, entry);
        String nodeName = firstText(
                node != null ? node.getLongName() : null,
                node != null ? node.getShortName() : null,
                entry.getName()
        );
        String nodeId = firstText(
                node != null ? node.getNodeId() : null,
                manager.getOwnerNodeId(entry.getId()),
                entry.getNodeId()
        );

        if (nodeId == null || nodeId.equals(nodeName)) {
            return nodeName != null ? nodeName : entry.getName();
        }
        return (nodeName != null ? nodeName : entry.getName()) + " (" + nodeId + ")";
    }

    private static NodeData findLocalNode(ConnectionManager manager, ConnectionEntry entry) {
        DeviceState state = manager.getDeviceState(entry.getId());
        if (state == null || state.getMyNodeNum() == 0) {
            return null;
        }
        return state.getNodeDb().get(state.getMyNodeNum());
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"?".equals(value.trim())) {
                return value.trim();
            }
        }
        return null;
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

        if (usesNativeMaximize()) {
            stage.setMaximized(!stage.isMaximized());
            return;
        }

        // In seamless mode, do not use stage.setMaximized(): on macOS, after
        // restoring maximized state it can lose the correct restore frame and
        // move a transparent window off-screen during unmaximize.
        if (customMaximized) {
            restoreFromMaximize(stage);
        } else {
            maximizeToVisualBounds(stage);
        }
    }

    private boolean usesNativeMaximize() {
        return OsDetect.isMacOs() && AppPreferences.isDisableEffectsEffective();
    }

    private void maximizeToVisualBounds(Stage stage) {
        if (hasValidStageBounds(stage)) {
            restoreX = stage.getX();
            restoreY = stage.getY();
            restoreW = stage.getWidth();
            restoreH = stage.getHeight();
        }

        Rectangle2D bounds = resolveVisualBounds(stage);

        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        customMaximized = true;
    }

    private void restoreFromMaximize(Stage stage) {
        ensureRestoreBounds(stage);
        stage.setX(restoreX);
        stage.setY(restoreY);
        stage.setWidth(restoreW);
        stage.setHeight(restoreH);
        customMaximized = false;
    }

    // ==================== Resize ====================

    private void initResizeHandlers() {
        // Use EventFilter, the capture phase, instead of setOnMouse*, the bubble
        // phase, so resize works above the title bar and other child elements.
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
                // Restore the previous node if the target changed.
            restoreCursorOverride();
                // Force the resize cursor onto the target node.
            if (target != null) {
                cursorOverriddenOriginal = target.getCursor();
                cursorOverriddenNode = target;
                target.setCursor(cursor);
            }
            setCursor(cursor);
        } else {
                // Outside resize zones, restore the original cursor.
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
        // Use sceneX/sceneY: with EventFilter, e.getX()/getY() are relative to the
        // target node, not RootPane. sceneX/sceneY match RootPane local coordinates
        // because RootPane is the scene root.
        double x = e.getSceneX();
        double y = e.getSceneY();
        double w = getWidth();
        double h = getHeight();

        // Corners use an enlarged grab zone.
        boolean cornerTop = y < CORNER_MARGIN;
        boolean cornerBottom = y > h - CORNER_MARGIN;
        boolean cornerLeft = x < CORNER_MARGIN;
        boolean cornerRight = x > w - CORNER_MARGIN;

        if (cornerTop && cornerLeft) { return ResizeDirection.NW; }
        if (cornerTop && cornerRight) { return ResizeDirection.NE; }
        if (cornerBottom && cornerLeft) { return ResizeDirection.SW; }
        if (cornerBottom && cornerRight) { return ResizeDirection.SE; }

        // Edges use the standard zone.
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

    /** Checks whether the cursor is in a resize zone: window edges or corners. */
    private boolean isInResizeZone(MouseEvent event) {
        double sceneX = event.getSceneX();
        double sceneY = event.getSceneY();
        double w = getWidth();
        double h = getHeight();
        // Corners.
        if ((sceneX < CORNER_MARGIN && sceneY < CORNER_MARGIN) ||
            (sceneX > w - CORNER_MARGIN && sceneY < CORNER_MARGIN) ||
            (sceneX < CORNER_MARGIN && sceneY > h - CORNER_MARGIN) ||
            (sceneX > w - CORNER_MARGIN && sceneY > h - CORNER_MARGIN)) { return true; }
        // Edges.
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

    private Rectangle2D resolveVisualBounds(Stage stage) {
        var screens = hasValidStageBounds(stage)
                ? Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight())
                : Screen.getScreens();
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.getFirst();
        return screen.getVisualBounds();
    }

    private boolean hasValidStageBounds(Stage stage) {
        return Double.isFinite(stage.getX())
                && Double.isFinite(stage.getY())
                && Double.isFinite(stage.getWidth())
                && Double.isFinite(stage.getHeight())
                && stage.getWidth() > 0
                && stage.getHeight() > 0;
    }

    private void ensureRestoreBounds(Stage stage) {
        if (Double.isFinite(restoreX)
                && Double.isFinite(restoreY)
                && Double.isFinite(restoreW)
                && Double.isFinite(restoreH)
                && restoreW > 0
                && restoreH > 0) {
            return;
        }

        Rectangle2D bounds = resolveVisualBounds(stage);
        double minW = stage.getMinWidth() > 0 ? stage.getMinWidth() : 800;
        double minH = stage.getMinHeight() > 0 ? stage.getMinHeight() : 600;
        restoreW = Math.max(minW, Math.min(bounds.getWidth() * 0.85, bounds.getWidth()));
        restoreH = Math.max(minH, Math.min(bounds.getHeight() * 0.85, bounds.getHeight()));
        restoreX = bounds.getMinX() + (bounds.getWidth() - restoreW) / 2.0;
        restoreY = bounds.getMinY() + (bounds.getHeight() - restoreH) / 2.0;
    }

    private enum ResizeDirection {
        NONE, N, S, E, W, NE, NW, SE, SW;

        boolean isTop() { return this == N || this == NE || this == NW; }
        boolean isBottom() { return this == S || this == SE || this == SW; }
        boolean isLeft() { return this == W || this == NW || this == SW; }
        boolean isRight() { return this == E || this == NE || this == SE; }
    }
}
