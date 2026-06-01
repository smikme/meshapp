package com.meshtastic.client.components;

import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.forms.FormMap;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.system.AllForms;
import com.meshtastic.client.system.FormManager;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import org.meshtastic.proto.MeshProtos;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Saved traceroute history for one node.
 *
 * <p>The component reads {@code traceroute_results}, rebuilds visuals from
 * protobuf {@link MeshProtos.RouteDiscovery} payloads or legacy text, and shows
 * the creation time of each trace.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class NodeTracerouteHistoryPanel extends BorderPane {

    private static final int PAGE_SIZE = 20;
    private static final double LOAD_MORE_SCROLL_THRESHOLD = 0.92;
    private static final DateTimeFormatter TRACE_DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Duration MODAL_CLOSE_DELAY = Duration.millis(260);

    private final DeviceState state;
    private final int nodeNum;
    private final long unsignedNodeNum;
    private final String nodeId;
    private final String targetName;
    private final Runnable onBeforeNavigate;
    private final DatePicker dateFilter;
    private final ScrollPane scrollPane;
    private final VBox resultBox;
    private final TracerouteView tracerouteView;
    private long nextBeforeTimestamp;
    private long nextBeforeId;
    private boolean loadingPage;
    private boolean allPagesLoaded;

    /**
     * Creates a traceroute history panel for the selected node.
     *
     * @param state active connection state, used for owner scope and node names
     * @param node node whose saved traces should be shown
     */
    public NodeTracerouteHistoryPanel(DeviceState state, NodeData node) {
        this(state, node, null);
    }

    /**
     * Creates a traceroute history panel for the selected node.
     *
     * @param state active connection state, used for owner scope and node names
     * @param node node whose saved traces should be shown
     * @param onBeforeNavigate action run before navigating to the map, such as closing a side panel
     */
    public NodeTracerouteHistoryPanel(DeviceState state, NodeData node, Runnable onBeforeNavigate) {
        this.state = state;
        this.nodeNum = node.getNodeNum();
        this.unsignedNodeNum = Integer.toUnsignedLong(node.getNodeNum());
        this.nodeId = Optional.ofNullable(node.getNodeId()).orElseGet(() -> nodeIdFromNum(node.getNodeNum()));
        this.targetName = nodeTitle(node);
        this.onBeforeNavigate = onBeforeNavigate;
        dateFilter = createDateFilter();

        resultBox = new VBox(12);
        resultBox.setPadding(new Insets(8, 0, 8, 0));
        resultBox.setMinHeight(Region.USE_PREF_SIZE);
        resultBox.getStyleClass().add("node-trace-history-list");

        tracerouteView = new TracerouteView(
                resultBox.widthProperty(),
                this::resolveNodeName,
                null,
                false);

        scrollPane = new ScrollPane(resultBox);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().addAll("edge-to-edge", "node-trace-history-scroll");
        scrollPane.vvalueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue.doubleValue() >= LOAD_MORE_SCROLL_THRESHOLD) {
                loadNextPage();
            }
        });

        setTop(createFilterBar());
        setCenter(scrollPane);
    }

    /**
     * Reloads saved traces from the database and rebuilds the visualization.
     */
    public void refresh() {
        resetPaging();
        loadNextPage();
    }

    private DatePicker createDateFilter() {
        DatePicker picker = new DatePicker();
        picker.setPromptText(I18n.t("node.trace.date.all"));
        picker.getStyleClass().add("node-trace-date-filter");
        picker.setTooltip(new Tooltip(I18n.t("node.trace.dateFilter.tooltip")));
        picker.valueProperty().addListener((observable, oldValue, newValue) -> refresh());
        return picker;
    }

    private HBox createFilterBar() {
        Label dateLabel = new Label(I18n.t("node.trace.date"));
        dateLabel.getStyleClass().add("muted-small-label");

        Button clearButton = new Button(I18n.t("common.reset"));
        clearButton.getStyleClass().add("node-trace-date-clear");
        clearButton.disableProperty().bind(dateFilter.valueProperty().isNull());
        clearButton.setOnAction(event -> dateFilter.setValue(null));

        HBox filterBar = new HBox(8, dateLabel, dateFilter, clearButton);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.getStyleClass().add("node-trace-filter-bar");
        return filterBar;
    }

    private void resetPaging() {
        nextBeforeTimestamp = 0;
        nextBeforeId = 0;
        loadingPage = false;
        allPagesLoaded = false;
        resultBox.getChildren().clear();
        scrollPane.setVvalue(0);
    }

    private void loadNextPage() {
        if (loadingPage || allPagesLoaded) {
            return;
        }

        loadingPage = true;
        try {
            boolean firstPage = nextBeforeTimestamp <= 0;
            String ownerNodeId = Optional.ofNullable(state)
                    .map(DeviceState::getOwnerNodeId)
                    .orElse("");
            TraceDateRange range = selectedDateRange();
            List<MessageDbService.TracerouteResultRecord> page = MessageDbService.getInstance()
                    .loadTracerouteResultsForNode(
                            ownerNodeId,
                            unsignedNodeNum,
                            nodeId,
                            range.startEpochSecond(),
                            range.endEpochSecond(),
                            PAGE_SIZE + 1,
                            nextBeforeTimestamp,
                            nextBeforeId);
            List<MessageDbService.TracerouteResultRecord> visibleRecords = page.stream()
                    .limit(PAGE_SIZE)
                    .toList();
            List<Node> traceNodes = visibleRecords
                    .stream()
                    .map(trace -> buildTraceNode(trace).map(traceNode -> (Node) wrapTrace(trace, traceNode)))
                    .flatMap(Optional::stream)
                    .toList();
            if (firstPage && traceNodes.isEmpty()) {
                resultBox.getChildren().setAll(emptyTraceLabel());
            } else {
                resultBox.getChildren().addAll(traceNodes);
            }
            visibleRecords.stream()
                    .reduce((previous, current) -> current)
                    .ifPresent(lastRecord -> {
                        nextBeforeTimestamp = lastRecord.timestamp();
                        nextBeforeId = lastRecord.id();
                    });
            allPagesLoaded = page.size() <= PAGE_SIZE;
        } finally {
            loadingPage = false;
        }
    }

    private TraceDateRange selectedDateRange() {
        return Optional.ofNullable(dateFilter.getValue())
                .map(NodeTracerouteHistoryPanel::dateRange)
                .orElseGet(TraceDateRange::all);
    }

    private static TraceDateRange dateRange(LocalDate date) {
        ZoneId zoneId = ZoneId.systemDefault();
        long start = date.atStartOfDay(zoneId).toEpochSecond();
        long end = date.plusDays(1).atStartOfDay(zoneId).toEpochSecond();
        return new TraceDateRange(start, end);
    }

    private VBox wrapTrace(MessageDbService.TracerouteResultRecord trace, Node traceNode) {
        Label dateLabel = new Label(I18n.t("node.trace.created", formatTraceTimestamp(trace.timestamp())));
        dateLabel.getStyleClass().add("node-trace-created-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, dateLabel, spacer, createMapButton(trace.id()));
        header.setAlignment(Pos.CENTER_LEFT);

        VBox wrapper = new VBox(6, header, traceNode);
        wrapper.getStyleClass().add("node-trace-record");
        return wrapper;
    }

    private Button createMapButton(long tracerouteResultId) {
        Button button = new Button();
        SVGPath icon = SvgIconLoader.load("/drawer/icon/map.svg", 16);
        if (icon != null) {
            button.setGraphic(icon);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            button.setText("?");
        }
        button.getStyleClass().addAll("map-icon-button", "node-trace-map-button");
        button.setTooltip(new Tooltip(I18n.t("node.trace.showOnMap")));
        button.setUserData(tracerouteResultId);
        button.setOnAction(event -> openTraceOnMap(tracerouteResultId));
        return button;
    }

    private void openTraceOnMap(long tracerouteResultId) {
        Optional.ofNullable(onBeforeNavigate).ifPresent(Runnable::run);
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane != null && modalPane.isVisible()) {
            PauseTransition delay = new PauseTransition(MODAL_CLOSE_DELAY);
            delay.setOnFinished(event -> showTraceOnMap(tracerouteResultId));
            delay.play();
            return;
        }
        showTraceOnMap(tracerouteResultId);
    }

    private void showTraceOnMap(long tracerouteResultId) {
        FormMap formMap = (FormMap) AllForms.getForm(FormMap.class);
        FormManager.showForm(formMap);
        formMap.showTracerouteResult(tracerouteResultId);
    }

    private Optional<Node> buildTraceNode(MessageDbService.TracerouteResultRecord trace) {
        Optional<MeshProtos.RouteDiscovery> route = parseRoute(trace.routeData());
        String displayTargetName = firstNonBlank(trace.targetName(), targetName, trace.targetNodeId(), nodeId);
        String text = firstNonBlank(
                trace.formattedText(),
                route.map(parsedRoute -> tracerouteView.formatText(displayTargetName, parsedRoute)).orElse(null));

        MeshMessage message = new MeshMessage(
                "!00000000",
                "!00000000",
                0,
                text,
                trace.timestamp(),
                false);
        message.setSystemMessage(true);

        return route.<Node>map(parsedRoute -> tracerouteView.buildFromProto(displayTargetName, parsedRoute, message))
                .or(() -> restoreTraceFromText(message, text))
                .or(this::fallbackTraceNode);
    }

    private Optional<Node> restoreTraceFromText(MeshMessage message, String text) {
        return Optional.ofNullable(text)
                .filter(value -> value.startsWith(TracerouteView.TRACEROUTE_PREFIX))
                .map(ignored -> tracerouteView.tryBuildFromText(message))
                .map(Node.class::cast);
    }

    private Optional<Node> fallbackTraceNode() {
        Label fallback = new Label(I18n.t("node.trace.renderFailed"));
        fallback.setWrapText(true);
        fallback.getStyleClass().add("config-status-label");
        return Optional.of(fallback);
    }

    private Optional<MeshProtos.RouteDiscovery> parseRoute(byte[] routeData) {
        return Optional.ofNullable(routeData)
                .filter(data -> data.length > 0)
                .flatMap(NodeTracerouteHistoryPanel::parseRouteBytes);
    }

    private static Optional<MeshProtos.RouteDiscovery> parseRouteBytes(byte[] routeData) {
        try {
            return Optional.of(MeshProtos.RouteDiscovery.parseFrom(routeData));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String resolveNodeName(int resolvedNodeNum) {
        return resolvedNodeNum == nodeNum
                ? targetName
                : Optional.ofNullable(NodeUtils.resolveNode(state, resolvedNodeNum))
                .map(NodeTracerouteHistoryPanel::nodeTitle)
                .orElseGet(() -> nodeIdFromNum(resolvedNodeNum));
    }

    /**
     * Formats trace creation time as full local date and time.
     *
     * @param epochSeconds Unix timestamp in seconds
     * @return {@code dd.MM.yyyy HH:mm} string, or empty string for invalid time
     */
    static String formatTraceTimestamp(long epochSeconds) {
        return epochSeconds <= 0
                ? ""
                : Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(TRACE_DATE_TIME);
    }

    private static String nodeTitle(NodeData node) {
        return Stream.of(node.getLongName(), node.getShortName(), node.getNodeId())
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .map(UnicodeTextUtils::sanitizeForJavaFxDisplay)
                .orElseGet(() -> nodeIdFromNum(node.getNodeNum()));
    }

    private static String nodeIdFromNum(int nodeNum) {
        return String.format("!%08x", nodeNum);
    }

    private static String firstNonBlank(String... values) {
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private Label emptyTraceLabel() {
        String text = dateFilter.getValue() == null
                ? I18n.t("node.trace.empty.all")
                : I18n.t("node.trace.empty.date");
        Label emptyLabel = new Label(text);
        emptyLabel.getStyleClass().add("form-placeholder-label");
        return emptyLabel;
    }

    private record TraceDateRange(long startEpochSecond, long endEpochSecond) {

        private static TraceDateRange all() {
            return new TraceDateRange(0, 0);
        }
    }
}
