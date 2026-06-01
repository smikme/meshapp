package com.meshtastic.client.components;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.meshtastic.proto.MeshProtos;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Отдельное окно live traceroute до выбранной ноды.
 *
 * <p>Окно запускает новый traceroute-запрос при открытии или повторе,
 * показывает полученные результаты без очистки предыдущих запусков и сохраняет
 * успешные ответы в {@code traceroute_results} для вкладки истории ноды.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class NodeTracerouteWindow {

    private static final int TIMEOUT_SECONDS = 360;
    private static final double DEFAULT_WIDTH = 640;
    private static final double DEFAULT_HEIGHT = 360;
    private static final double MIN_WIDTH = 420;
    private static final double MIN_HEIGHT = 240;
    private static final double WINDOW_MARGIN = 48;
    private static final double TRACE_BUBBLE_HORIZONTAL_PADDING = 24;
    private static final double TRACE_NODE_WIDTH = 80;
    private static final double TRACE_LINK_WIDTH = 48;
    private static final double TRACE_CONTENT_EXTRA_WIDTH = 36;
    private static final double TRACE_CONTENT_EXTRA_HEIGHT = 48;
    private static final double TRACE_ROUTE_HEIGHT = 86;
    private static final double TRACE_REVERSE_ROUTE_EXTRA_HEIGHT = 74;
    private static final Map<Integer, NodeTracerouteWindow> OPEN_WINDOWS = new ConcurrentHashMap<>();
    private static final Set<NodeTracerouteWindow> ACTIVE_TRACE_WINDOWS = ConcurrentHashMap.newKeySet();

    private final DeviceState state;
    private final ProtocolHandler handler;
    private final int nodeNum;
    private final String targetNodeId;
    private final String targetName;
    private final BiConsumer<Integer, MeshProtos.RouteDiscovery> tracerouteListener = this::handleTracerouteResult;

    private Stage stage;
    private HBox headerBar;
    private Separator separator;
    private VBox resultBox;
    private Label statusLabel;
    private ProgressIndicator progressIndicator;
    private Button retryButton;
    private Button closeButton;
    private Timeline timeoutTimer;
    private ChangeListener<Number> stageBoundsListener;
    private ChangeListener<Number> ownerBoundsListener;
    private double widestTraceWidth;
    private int remainingSeconds;
    private volatile boolean traceInProgress;
    private volatile long activeRequestSerial;
    private long nextRequestSerial;
    private boolean clampingBounds;
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean draggingWindow;

    private NodeTracerouteWindow(DeviceState state, NodeData node, ProtocolHandler handler) {
        this.state = state;
        this.handler = handler;
        this.nodeNum = node.getNodeNum();
        this.targetNodeId = node.getNodeId() != null ? node.getNodeId() : nodeIdFromNum(node.getNodeNum());
        this.targetName = nodeTitle(node);
    }

    /**
     * Открывает окно traceroute для указанной ноды и сразу запускает запрос.
     *
     * <p>Для одной ноды переиспользуется уже открытое окно с тем же состоянием
     * подключения; при вызове из фонового потока открытие переносится в JavaFX thread.
     *
     * @param state   состояние активного подключения
     * @param node    целевая нода
     * @param handler протокольный обработчик для отправки traceroute-запроса
     */
    public static void showWindow(DeviceState state, NodeData node, ProtocolHandler handler) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showWindow(state, node, handler));
            return;
        }
        if (node == null) {
            return;
        }

        NodeTracerouteWindow existing = OPEN_WINDOWS.get(node.getNodeNum());
        if (existing != null) {
            if (existing.matches(state, handler)) {
                existing.showStage();
                existing.restartTrace();
                return;
            }
            existing.stage.hide();
        }

        NodeTracerouteWindow window = new NodeTracerouteWindow(state, node, handler);
        OPEN_WINDOWS.put(node.getNodeNum(), window);
        window.createStage();
        window.showStage();
        window.restartTrace();
    }

    private void createStage() {
        stage = new Stage();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle(I18n.t("node.trace.window.title", targetName));
        stage.setResizable(false);
        Stage owner = MeshApp.getPrimaryStage();
        if (owner != null) {
            stage.initOwner(owner);
            if (!owner.getIcons().isEmpty()) {
                stage.getIcons().setAll(owner.getIcons());
            }
        }

        VBox root = new VBox(10);
        root.getStyleClass().add("packet-monitor-root");
        root.setPadding(new Insets(12));

        headerBar = createHeader();
        separator = new Separator();
        resultBox = new VBox(10);
        resultBox.setMinHeight(Region.USE_PREF_SIZE);

        ScrollPane scrollPane = new ScrollPane(resultBox);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        root.getChildren().addAll(headerBar, separator, scrollPane);

        Scene scene = new Scene(root, preferredWidth(owner), preferredHeight(owner));
        ThemeManager.applyTheme(scene, AppPreferences.isDarkMode());
        EmojiRenderingSupport.install(scene);
        installEscapeCloseHandler(scene);
        stage.setScene(scene);
        installOwnerBoundsGuard(owner);
        stage.setOnHidden(event -> {
            cleanup();
            OPEN_WINDOWS.remove(nodeNum, this);
            ThemeManager.unregisterScene(scene);
        });
    }

    private void installEscapeCloseHandler(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                cancelTraceAndClose();
                event.consume();
            }
        });
    }

    private HBox createHeader() {
        VBox titleBox = new VBox(2);
        Label title = new Label(I18n.t("node.trace.window.heading"));
        title.getStyleClass().add("form-title");
        Label subtitle = new Label(UnicodeTextUtils.sanitizeForJavaFxDisplay(targetName));
        subtitle.getStyleClass().add("muted-small-label");
        titleBox.getChildren().addAll(title, subtitle);

        progressIndicator = new ProgressIndicator();
        progressIndicator.setPrefSize(22, 22);
        progressIndicator.setMaxSize(22, 22);

        statusLabel = new Label();
        statusLabel.getStyleClass().add("config-status-label");

        retryButton = new Button(I18n.t("node.trace.window.retry"));
        retryButton.getStyleClass().add("accent");
        retryButton.setOnAction(event -> restartTrace());

        closeButton = new Button(I18n.t("node.trace.window.close"));
        closeButton.setOnAction(event -> {
            if (traceInProgress) {
                cancelTrace();
            } else {
                stage.hide();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(10, titleBox, spacer, progressIndicator, statusLabel, retryButton, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);
        installHeaderDrag(header);
        return header;
    }

    private void installHeaderDrag(HBox header) {
        header.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY || isInsideButton(event.getTarget())) {
                draggingWindow = false;
                return;
            }
            dragOffsetX = event.getScreenX() - stage.getX();
            dragOffsetY = event.getScreenY() - stage.getY();
            draggingWindow = true;
        });
        header.setOnMouseDragged(event -> {
            if (!draggingWindow) {
                return;
            }
            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(event.getScreenY() - dragOffsetY);
            clampToOwnerBounds();
        });
        header.setOnMouseReleased(event -> draggingWindow = false);
    }

    private boolean isInsideButton(Object target) {
        if (!(target instanceof Node node)) {
            return false;
        }
        for (Node current = node; current != null; current = current.getParent()) {
            if (current instanceof Button) {
                return true;
            }
            if (current == headerBar) {
                return false;
            }
        }
        return false;
    }

    private void showStage() {
        if (!stage.isShowing()) {
            stage.show();
        }
        centerRelativeToOwner();
        stage.toFront();
        stage.requestFocus();
    }

    private double preferredWidth(Stage owner) {
        if (owner == null || owner.getWidth() <= 0) {
            return DEFAULT_WIDTH;
        }
        return Math.min(DEFAULT_WIDTH, Math.max(MIN_WIDTH, owner.getWidth() - WINDOW_MARGIN));
    }

    private double preferredHeight(Stage owner) {
        if (owner == null || owner.getHeight() <= 0) {
            return DEFAULT_HEIGHT;
        }
        return Math.min(DEFAULT_HEIGHT, Math.max(MIN_HEIGHT, owner.getHeight() - WINDOW_MARGIN));
    }

    private void installOwnerBoundsGuard(Stage owner) {
        stageBoundsListener = (observable, oldValue, newValue) -> clampToOwnerBounds();
        stage.xProperty().addListener(stageBoundsListener);
        stage.yProperty().addListener(stageBoundsListener);
        stage.widthProperty().addListener(stageBoundsListener);
        stage.heightProperty().addListener(stageBoundsListener);

        if (owner == null) {
            return;
        }
        ownerBoundsListener = (observable, oldValue, newValue) -> {
            centerRelativeToOwner();
            clampToOwnerBounds();
        };
        owner.xProperty().addListener(ownerBoundsListener);
        owner.yProperty().addListener(ownerBoundsListener);
        owner.widthProperty().addListener(ownerBoundsListener);
        owner.heightProperty().addListener(ownerBoundsListener);
    }

    private void centerRelativeToOwner() {
        Stage owner = MeshApp.getPrimaryStage();
        if (owner == null || owner.getWidth() <= 0 || owner.getHeight() <= 0) {
            stage.centerOnScreen();
            return;
        }
        clampingBounds = true;
        try {
            stage.setX(owner.getX() + Math.max(0, (owner.getWidth() - stage.getWidth()) / 2.0));
            stage.setY(owner.getY() + Math.max(0, (owner.getHeight() - stage.getHeight()) / 2.0));
        } finally {
            clampingBounds = false;
        }
        clampToOwnerBounds();
    }

    private void clampToOwnerBounds() {
        if (clampingBounds || stage == null || !stage.isShowing()) {
            return;
        }
        Stage owner = MeshApp.getPrimaryStage();
        if (owner == null || owner.getWidth() <= 0 || owner.getHeight() <= 0) {
            return;
        }

        double targetWidth = Math.min(stage.getWidth(), owner.getWidth());
        double targetHeight = Math.min(stage.getHeight(), owner.getHeight());
        double minX = owner.getX();
        double minY = owner.getY();
        double maxX = Math.max(minX, owner.getX() + owner.getWidth() - targetWidth);
        double maxY = Math.max(minY, owner.getY() + owner.getHeight() - targetHeight);
        double targetX = clamp(stage.getX(), minX, maxX);
        double targetY = clamp(stage.getY(), minY, maxY);

        if (Double.compare(targetX, stage.getX()) == 0
                && Double.compare(targetY, stage.getY()) == 0
                && Double.compare(targetWidth, stage.getWidth()) == 0
                && Double.compare(targetHeight, stage.getHeight()) == 0) {
            return;
        }
        clampingBounds = true;
        try {
            stage.setWidth(targetWidth);
            stage.setHeight(targetHeight);
            stage.setX(targetX);
            stage.setY(targetY);
        } finally {
            clampingBounds = false;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean matches(DeviceState otherState, ProtocolHandler otherHandler) {
        return state == otherState && handler == otherHandler;
    }

    private void restartTrace() {
        finishActiveTrace();

        if (state == null || handler == null || nodeNum == 0) {
            showFailure(I18n.t("node.trace.window.unavailable"));
            return;
        }

        traceInProgress = true;
        activeRequestSerial = ++nextRequestSerial;
        ACTIVE_TRACE_WINDOWS.add(this);
        syncTraceControls();

        state.addTracerouteListener(tracerouteListener);
        remainingSeconds = TIMEOUT_SECONDS;
        statusLabel.setText(I18n.t("node.trace.window.waiting", remainingSeconds));
        timeoutTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> tickTimeout()));
        timeoutTimer.setCycleCount(TIMEOUT_SECONDS);
        timeoutTimer.setOnFinished(event -> {
            if (traceInProgress) {
                showFailure(I18n.t("node.trace.window.timeout"));
            }
        });
        timeoutTimer.play();

        try {
            MessageService.requestTraceroute(handler, state, nodeNum);
        } catch (Throwable error) {
            showFailure(I18n.t("node.trace.window.sendFailed", errorMessage(error)));
        }
    }

    private void tickTimeout() {
        if (timeoutTimer == null || !traceInProgress) {
            return;
        }
        remainingSeconds = Math.max(0, remainingSeconds - 1);
        statusLabel.setText(I18n.t("node.trace.window.waiting", remainingSeconds));
    }

    private void handleTracerouteResult(int fromNodeNum, MeshProtos.RouteDiscovery route) {
        if (!traceInProgress || !matchesTracerouteResponse(fromNodeNum)) {
            return;
        }
        long requestSerial = activeRequestSerial;
        Platform.runLater(() -> showResult(requestSerial, fromNodeNum, route));
    }

    private boolean matchesTracerouteResponse(int fromNodeNum) {
        if (fromNodeNum == nodeNum) {
            return true;
        }
        return ACTIVE_TRACE_WINDOWS.size() == 1;
    }

    private void showResult(long requestSerial, int fromNodeNum, MeshProtos.RouteDiscovery route) {
        if (!traceInProgress || requestSerial != activeRequestSerial) {
            return;
        }
        finishActiveTrace();

        statusLabel.setText(I18n.t("node.trace.window.ready"));

        MeshProtos.RouteDiscovery safeRoute = route != null
                ? route
                : MeshProtos.RouteDiscovery.newBuilder().build();
        TracerouteView view = new TracerouteView(
                resultBox.widthProperty(),
                this::resolveNodeName,
                null,
                false);
        long timestamp = System.currentTimeMillis() / 1000;
        String formattedText = view.formatText(targetName, safeRoute);
        MeshMessage message = new MeshMessage(
                "!00000000",
                "!00000000",
                0,
                formattedText,
                timestamp,
                false);
        message.setSystemMessage(true);
        persistTraceResult(fromNodeNum, safeRoute, formattedText, timestamp);
        HBox traceRow = view.buildFromProto(targetName, safeRoute, message);
        resultBox.getChildren().add(traceRow);
        Platform.runLater(() -> resizeToTrace(traceRow, safeRoute));
    }

    private void persistTraceResult(int fromNodeNum,
                                    MeshProtos.RouteDiscovery route,
                                    String formattedText,
                                    long timestamp) {
        if (state == null || route == null) {
            return;
        }
        String ownerNodeId = state.getOwnerNodeId() != null ? state.getOwnerNodeId() : "";
        MessageDbService.getInstance().saveTracerouteResult(
                ownerNodeId,
                "",
                "",
                "java.node.traceroute",
                "node:" + timestamp + ":" + UUID.randomUUID(),
                0,
                Integer.toUnsignedLong(nodeNum),
                targetNodeId,
                targetName,
                fromNodeNum != 0 ? Integer.toUnsignedLong(fromNodeNum) : 0,
                fromNodeNum != 0 ? nodeIdFromNum(fromNodeNum) : null,
                route.toByteArray(),
                formattedText,
                timestamp);
    }

    private void resizeToTrace(HBox traceRow, MeshProtos.RouteDiscovery route) {
        if (stage == null || stage.getScene() == null || traceRow == null) {
            return;
        }

        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        traceRow.applyCss();
        traceRow.autosize();

        double measuredWidth = Math.max(traceRow.prefWidth(-1), traceRow.getLayoutBounds().getWidth());
        double measuredHeight = Math.max(traceRow.prefHeight(measuredWidth), traceRow.getLayoutBounds().getHeight());
        widestTraceWidth = Math.max(widestTraceWidth, Math.max(measuredWidth, estimatedTraceWidth(route)));
        double measuredResultsHeight = Math.max(
                resultBox.prefHeight(Math.max(MIN_WIDTH, widestTraceWidth)),
                resultBox.getLayoutBounds().getHeight());
        double targetSceneWidth = Math.max(
                MIN_WIDTH,
                Math.max(headerPreferredWidth(), widestTraceWidth));
        double targetSceneHeight = Math.max(
                MIN_HEIGHT,
                headerPreferredHeight()
                        + separatorPreferredHeight()
                        + Math.max(measuredResultsHeight, estimatedTraceHeight(route))
                        + TRACE_CONTENT_EXTRA_HEIGHT);

        resizeWindowToSceneSize(targetSceneWidth, targetSceneHeight);
    }

    private double estimatedTraceWidth(MeshProtos.RouteDiscovery route) {
        int forwardNodes = route.getRouteCount() + 2;
        int reverseNodes = hasReverseRoute(route) ? route.getRouteBackCount() + 2 : 0;
        int nodes = Math.max(forwardNodes, reverseNodes);
        double routeWidth = nodes * TRACE_NODE_WIDTH
                + Math.max(0, nodes - 1) * TRACE_LINK_WIDTH;
        return TRACE_BUBBLE_HORIZONTAL_PADDING
                + routeWidth
                + TRACE_CONTENT_EXTRA_WIDTH;
    }

    private double estimatedTraceHeight(MeshProtos.RouteDiscovery route) {
        return TRACE_ROUTE_HEIGHT + (hasReverseRoute(route) ? TRACE_REVERSE_ROUTE_EXTRA_HEIGHT : 0);
    }

    private double headerPreferredWidth() {
        return headerBar != null ? headerBar.prefWidth(-1) + 24 : 0;
    }

    private double headerPreferredHeight() {
        return headerBar != null ? headerBar.prefHeight(-1) : 0;
    }

    private double separatorPreferredHeight() {
        return separator != null ? separator.prefHeight(-1) : 0;
    }

    private void resizeWindowToSceneSize(double requestedSceneWidth, double requestedSceneHeight) {
        Stage owner = MeshApp.getPrimaryStage();
        double decorationWidth = Math.max(0, stage.getWidth() - stage.getScene().getWidth());
        double decorationHeight = Math.max(0, stage.getHeight() - stage.getScene().getHeight());
        double maxStageWidth = owner != null && owner.getWidth() > 0 ? owner.getWidth() : Double.MAX_VALUE;
        double maxStageHeight = owner != null && owner.getHeight() > 0 ? owner.getHeight() : Double.MAX_VALUE;
        double targetWidth = Math.min(requestedSceneWidth + decorationWidth, maxStageWidth);
        double targetHeight = Math.min(requestedSceneHeight + decorationHeight, maxStageHeight);

        clampingBounds = true;
        try {
            stage.setWidth(targetWidth);
            stage.setHeight(targetHeight);
        } finally {
            clampingBounds = false;
        }
        centerRelativeToOwner();
    }

    private static boolean hasReverseRoute(MeshProtos.RouteDiscovery route) {
        return route.getRouteBackCount() > 0 || route.getSnrBackCount() > 0;
    }

    private void showFailure(String message) {
        finishActiveTrace();
        statusLabel.setText(I18n.t("node.trace.window.error"));
        resultBox.getChildren().add(wrappedLabel(message));
        Platform.runLater(this::resizeToResults);
    }

    private void cancelTrace() {
        if (!traceInProgress) {
            return;
        }
        finishActiveTrace();
        statusLabel.setText(I18n.t("node.trace.window.cancelled"));
    }

    private void cancelTraceAndClose() {
        if (traceInProgress) {
            cancelTrace();
        }
        stage.hide();
    }

    private void resizeToResults() {
        if (stage == null || stage.getScene() == null || resultBox == null) {
            return;
        }
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        double measuredResultsWidth = Math.max(resultBox.prefWidth(-1), resultBox.getLayoutBounds().getWidth());
        double measuredResultsHeight = Math.max(
                resultBox.prefHeight(Math.max(MIN_WIDTH, measuredResultsWidth)),
                resultBox.getLayoutBounds().getHeight());
        widestTraceWidth = Math.max(widestTraceWidth, measuredResultsWidth);
        resizeWindowToSceneSize(
                Math.max(MIN_WIDTH, Math.max(headerPreferredWidth(), widestTraceWidth)),
                Math.max(MIN_HEIGHT,
                        headerPreferredHeight()
                                + separatorPreferredHeight()
                                + measuredResultsHeight
                                + TRACE_CONTENT_EXTRA_HEIGHT));
    }

    private Label wrappedLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("config-status-label");
        return label;
    }

    private void cleanup() {
        finishActiveTrace();
        uninstallOwnerBoundsGuard();
    }

    private void finishActiveTrace() {
        traceInProgress = false;
        ACTIVE_TRACE_WINDOWS.remove(this);
        stopTimer();
        if (state != null) {
            state.removeTracerouteListener(tracerouteListener);
        }
        syncTraceControls();
    }

    private void syncTraceControls() {
        if (retryButton != null) {
            retryButton.setDisable(traceInProgress);
        }
        if (progressIndicator != null) {
            progressIndicator.setVisible(traceInProgress);
            progressIndicator.setManaged(traceInProgress);
        }
        if (closeButton != null) {
            closeButton.setText(I18n.t(traceInProgress
                    ? "node.trace.window.cancel"
                    : "node.trace.window.close"));
        }
    }

    private void uninstallOwnerBoundsGuard() {
        if (stage != null && stageBoundsListener != null) {
            stage.xProperty().removeListener(stageBoundsListener);
            stage.yProperty().removeListener(stageBoundsListener);
            stage.widthProperty().removeListener(stageBoundsListener);
            stage.heightProperty().removeListener(stageBoundsListener);
            stageBoundsListener = null;
        }

        Stage owner = MeshApp.getPrimaryStage();
        if (owner != null && ownerBoundsListener != null) {
            owner.xProperty().removeListener(ownerBoundsListener);
            owner.yProperty().removeListener(ownerBoundsListener);
            owner.widthProperty().removeListener(ownerBoundsListener);
            owner.heightProperty().removeListener(ownerBoundsListener);
            ownerBoundsListener = null;
        }
    }

    private void stopTimer() {
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }

    private String resolveNodeName(int resolvedNodeNum) {
        NodeData resolved = NodeUtils.resolveNode(state, resolvedNodeNum);
        return resolved != null ? nodeTitle(resolved) : String.format("!%08x", resolvedNodeNum);
    }

    private static String nodeIdFromNum(int nodeNum) {
        return String.format("!%08x", nodeNum);
    }

    private static String nodeTitle(NodeData node) {
        if (node.getLongName() != null && !node.getLongName().isBlank()) {
            return UnicodeTextUtils.sanitizeForJavaFxDisplay(node.getLongName());
        }
        if (node.getShortName() != null && !node.getShortName().isBlank()) {
            return UnicodeTextUtils.sanitizeForJavaFxDisplay(node.getShortName());
        }
        if (node.getNodeId() != null && !node.getNodeId().isBlank()) {
            return UnicodeTextUtils.sanitizeForJavaFxDisplay(node.getNodeId());
        }
        return String.format("!%08x", node.getNodeNum());
    }

    private static String errorMessage(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return message != null && !message.isBlank() ? message : error.getClass().getSimpleName();
    }
}
