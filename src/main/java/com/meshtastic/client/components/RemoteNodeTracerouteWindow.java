package com.meshtastic.client.components;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.MeshApp;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.rpc.RemoteNodeJson;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.rpc.RpcEventListener;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.meshtastic.proto.MeshProtos;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side live traceroute window backed by a remote RPC host.
 *
 * <p>The remote host owns the radio connection and persists traceroute history.
 * This window only starts a request and renders the pushed result event.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RemoteNodeTracerouteWindow {

    private static final int TIMEOUT_SECONDS = 360;
    private static final double DEFAULT_WIDTH = 640;
    private static final double DEFAULT_HEIGHT = 360;
    private static final double MIN_WIDTH = 420;
    private static final double MIN_HEIGHT = 240;
    private static final java.time.Duration RPC_START_TIMEOUT = java.time.Duration.ofSeconds(15);
    private static final Map<String, RemoteNodeTracerouteWindow> OPEN_WINDOWS = new ConcurrentHashMap<>();

    private final RemoteRpcState rpcState;
    private final NodeData node;
    private final int nodeNum;
    private final String targetNodeId;
    private final String targetName;
    private final String windowKey;
    private final Map<Integer, String> nodeNames = new ConcurrentHashMap<>();
    private final RpcEventListener eventListener = this::handleRpcEvent;

    private Stage stage;
    private VBox resultBox;
    private Label statusLabel;
    private ProgressIndicator progressIndicator;
    private Button retryButton;
    private Button closeButton;
    private Timeline timeoutTimer;
    private boolean listenerAttached;
    private boolean traceInProgress;
    private long activeRequestSerial;
    private long nextRequestSerial;
    private int remainingSeconds;
    private String activeRequestId;

    private RemoteNodeTracerouteWindow(RemoteRpcState rpcState, NodeData node) {
        this.rpcState = Objects.requireNonNull(rpcState, "rpcState");
        this.node = Objects.requireNonNull(node, "node");
        this.nodeNum = node.getNodeNum();
        this.targetNodeId = node.getNodeId() != null ? node.getNodeId() : nodeIdFromNum(node.getNodeNum());
        this.targetName = nodeTitle(node);
        this.windowKey = System.identityHashCode(rpcState.client()) + ":" + nodeNum;
        nodeNames.put(nodeNum, targetName);
    }

    public static void showWindow(RemoteRpcState rpcState, NodeData node) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showWindow(rpcState, node));
            return;
        }
        if (rpcState == null || node == null) {
            return;
        }

        String key = System.identityHashCode(rpcState.client()) + ":" + node.getNodeNum();
        RemoteNodeTracerouteWindow existing = OPEN_WINDOWS.get(key);
        if (existing != null) {
            existing.showStage();
            existing.restartTrace();
            return;
        }

        RemoteNodeTracerouteWindow window = new RemoteNodeTracerouteWindow(rpcState, node);
        OPEN_WINDOWS.put(key, window);
        window.createStage();
        window.showStage();
        window.restartTrace();
    }

    private void createStage() {
        stage = new Stage();
        stage.setTitle(I18n.t("node.trace.window.title", targetName));
        stage.setResizable(true);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        Stage owner = MeshApp.getPrimaryStage();
        if (owner != null) {
            stage.initOwner(owner);
            if (!owner.getIcons().isEmpty()) {
                stage.getIcons().setAll(owner.getIcons());
            }
        }

        VBox root = new VBox(10);
        root.getStyleClass().addAll("packet-monitor-root", "traceroute-window-root");
        root.setPadding(new Insets(12));

        HBox headerBar = createHeader();
        resultBox = new VBox(10);
        resultBox.setMinHeight(Region.USE_PREF_SIZE);

        ScrollPane scrollPane = new ScrollPane(resultBox);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        root.getChildren().addAll(headerBar, new Separator(), scrollPane);

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        ThemeManager.applyTheme(scene, AppPreferences.isDarkMode());
        EmojiRenderingSupport.install(scene);
        stage.setScene(scene);
        stage.setOnHidden(event -> {
            cleanup();
            OPEN_WINDOWS.remove(windowKey, this);
            ThemeManager.unregisterScene(scene);
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
        return header;
    }

    private void showStage() {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        stage.requestFocus();
    }

    private void restartTrace() {
        finishActiveTrace();
        resultBox.getChildren().clear();

        if (rpcState.client() == null || !rpcState.client().isOpen() || nodeNum == 0) {
            showFailure(I18n.t("node.trace.window.unavailable"));
            return;
        }

        traceInProgress = true;
        activeRequestId = UUID.randomUUID().toString();
        long requestSerial = ++nextRequestSerial;
        activeRequestSerial = requestSerial;
        attachListener();
        startTimer();
        syncTraceControls();

        JsonObject params = RemoteNodeJson.nodeParams(node);
        params.addProperty("requestId", activeRequestId);
        rpcState.client()
                .call("node.traceroute", params, RPC_START_TIMEOUT)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (!traceInProgress || requestSerial != activeRequestSerial) {
                        return;
                    }
                    if (error != null) {
                        showFailure(I18n.t("node.trace.window.sendFailed", errorMessage(error)));
                        return;
                    }
                    JsonObject object = objectOrEmpty(result);
                    String acceptedRequestId = stringField(object, "requestId");
                    updateNodeNames(object);
                    if (!activeRequestId.equals(acceptedRequestId)) {
                        showFailure(I18n.t("node.trace.window.sendFailed", "missing request id"));
                    }
                }));
    }

    private void startTimer() {
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
    }

    private void tickTimeout() {
        if (timeoutTimer == null || !traceInProgress) {
            return;
        }
        remainingSeconds = Math.max(0, remainingSeconds - 1);
        statusLabel.setText(I18n.t("node.trace.window.waiting", remainingSeconds));
    }

    private void handleRpcEvent(String event, JsonElement payload) {
        if (!"node.traceroute.result".equals(event)) {
            return;
        }
        JsonObject object = objectOrEmpty(payload);
        String requestId = stringField(object, "requestId");
        Platform.runLater(() -> handleTracerouteEvent(requestId, object));
    }

    private void handleTracerouteEvent(String requestId, JsonObject object) {
        if (!traceInProgress || activeRequestId == null || !activeRequestId.equals(requestId)) {
            return;
        }
        updateNodeNames(object);
        String status = stringField(object, "status");
        if ("error".equals(status)) {
            showFailure(firstText(stringField(object, "message"), I18n.t("node.trace.window.error")));
            return;
        }

        MeshProtos.RouteDiscovery route;
        try {
            route = parseRoute(stringField(object, "routeData"));
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            showFailure(I18n.t("node.trace.renderFailed"));
            return;
        }
        showResult(activeRequestSerial, route, longField(object, "timestamp"));
    }

    private MeshProtos.RouteDiscovery parseRoute(String encodedRoute) throws InvalidProtocolBufferException {
        if (encodedRoute == null || encodedRoute.isBlank()) {
            return MeshProtos.RouteDiscovery.newBuilder().build();
        }
        return MeshProtos.RouteDiscovery.parseFrom(Base64.getDecoder().decode(encodedRoute));
    }

    private void showResult(long requestSerial, MeshProtos.RouteDiscovery route, long timestamp) {
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
        long messageTimestamp = timestamp > 0 ? timestamp : System.currentTimeMillis() / 1000;
        MeshMessage message = new MeshMessage(
                "!00000000",
                "!00000000",
                0,
                view.formatText(targetName, safeRoute),
                messageTimestamp,
                false);
        message.setSystemMessage(true);
        resultBox.getChildren().add(view.buildFromProto(targetName, safeRoute, message));
    }

    private void showFailure(String message) {
        finishActiveTrace();
        statusLabel.setText(I18n.t("node.trace.window.error"));
        Label label = new Label(message);
        label.setWrapText(true);
        label.getStyleClass().add("config-status-label");
        resultBox.getChildren().add(label);
    }

    private void cancelTrace() {
        if (!traceInProgress) {
            return;
        }
        finishActiveTrace();
        statusLabel.setText(I18n.t("node.trace.window.cancelled"));
    }

    private void cleanup() {
        finishActiveTrace();
    }

    private void finishActiveTrace() {
        traceInProgress = false;
        activeRequestId = null;
        stopTimer();
        detachListener();
        syncTraceControls();
    }

    private void attachListener() {
        if (!listenerAttached) {
            rpcState.client().addEventListener(eventListener);
            listenerAttached = true;
        }
    }

    private void detachListener() {
        if (listenerAttached) {
            rpcState.client().removeEventListener(eventListener);
            listenerAttached = false;
        }
    }

    private void stopTimer() {
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
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

    private void updateNodeNames(JsonObject object) {
        JsonElement namesElement = object != null ? object.get("nodeNames") : null;
        if (namesElement == null || !namesElement.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : namesElement.getAsJsonObject().entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) {
                continue;
            }
            try {
                int nodeKey = (int) Long.parseUnsignedLong(entry.getKey());
                String name = entry.getValue().getAsString();
                if (name != null && !name.isBlank()) {
                    nodeNames.put(nodeKey, name.trim());
                }
            } catch (NumberFormatException ignored) {
                // Ignore names from newer hosts using an unknown key format.
            }
        }
    }

    private String resolveNodeName(int resolvedNodeNum) {
        String name = nodeNames.get(resolvedNodeNum);
        return name != null && !name.isBlank() ? name : nodeIdFromNum(resolvedNodeNum);
    }

    private static JsonObject objectOrEmpty(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String stringField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString();
        return value == null ? "" : value.trim();
    }

    private static long longField(JsonObject object, String field) {
        JsonElement element = object != null ? object.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsLong()
                : 0L;
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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
        return nodeIdFromNum(node.getNodeNum());
    }

    private static String errorMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        String message = current != null ? current.getMessage() : null;
        return message == null || message.isBlank() ? String.valueOf(error) : message;
    }
}
