package com.meshtastic.client.components;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.Rectangle;
import software.coley.bentofx.Bento;
import software.coley.bentofx.building.ControlsBuilding;
import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.control.Header;
import software.coley.bentofx.control.HeaderPane;
import software.coley.bentofx.control.Headers;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.dockable.DockableCloseListener;
import software.coley.bentofx.dockable.DockableDragDropBehavior;
import software.coley.bentofx.event.DockEvent;
import software.coley.bentofx.layout.DockContainer;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.bentofx.layout.container.DockContainerRootBranch;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Reusable telemetry component containing an {@code AreaChart} and a period filter.
 * <p>
 * Used by the dashboard for the local node and by node details for a selected
 * node. The lifecycle is {@link #bind(DeviceState, String)} followed by
 * {@link #unbind()}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class TelemetryChartPanel extends VBox {

    private static final DateTimeFormatter AXIS_FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final Object CROSSHAIR_FILTERS_KEY = new Object();
    private static final Gson GSON = new Gson();
    private static final int DOCK_LAYOUT_VERSION = 1;
    private static final double NODE_DETAIL_LEFT_AXIS_WIDTH = 44;
    private static final double NODE_DETAIL_RIGHT_AXIS_WIDTH = 38;
    private static final double NODE_DETAIL_TITLE_HEIGHT = 38;
    private static final double NODE_DETAIL_LEGEND_HEIGHT = 48;
    private static final double NODE_DETAIL_CHART_GAP = 6;
    private static final List<String> BASIC_CHART_COLORS = List.of("#f3622d", "#fba71b", "#57b757", "#41a9c9");
    private static final List<String> ENVIRONMENT_CHART_COLORS = List.of("#ff7043", "#29b6f6", "#ab47bc", "#c0ca33");

    private static final long PERIOD_1H = 3600;
    private static final long PERIOD_2H = 2L * 3600;
    private static final long PERIOD_6H = 6L * 3600;
    private static final long PERIOD_12H = 12L * 3600;
    private static final long PERIOD_24H = 24L * 3600;
    private static final long PERIOD_48H = 48L * 3600;
    private static final long PERIOD_1W = 7L * 86400;
    private static final long PERIOD_MAX = 0;

    private static final List<PeriodOption> PERIOD_OPTIONS = List.of(
            new PeriodOption("telemetry.period.1h", PERIOD_1H),
            new PeriodOption("telemetry.period.2h", PERIOD_2H),
            new PeriodOption("telemetry.period.6h", PERIOD_6H),
            new PeriodOption("telemetry.period.12h", PERIOD_12H),
            new PeriodOption("telemetry.period.24h", PERIOD_24H),
            new PeriodOption("telemetry.period.48h", PERIOD_48H),
            new PeriodOption("telemetry.period.1w", PERIOD_1W),
            new PeriodOption("telemetry.period.all", PERIOD_MAX)
    );

    private final boolean basicOnly;
    private final Label nodeDetailBasicTitleLabel;
    private final Label nodeDetailEnvironmentTitleLabel;
    private final HBox nodeDetailBasicLegend;
    private final HBox nodeDetailEnvironmentLegend;
    private final AreaChart<Number, Number> chart;
    private final AreaChart<Number, Number> environmentMetricsChart;
    private final Pane environmentPressureOverlay;
    private final NumberAxis environmentPressureAxis;
    private final Pane environmentPressurePlotLayer;
    private final Rectangle environmentPressureClip;
    private final Path environmentPressureArea;
    private final Path environmentPressureLine;
    private final AreaChart<Number, Number> rxChart;
    private final AreaChart<Number, Number> rateChart;
    private final AreaChart<Number, Number> txChart;
    private final AreaChart<Number, Number> qualityChart;
    private final AreaChart<Number, Number> hopsChart;
    private final AreaChart<Number, Number> temperatureChart;
    private final AreaChart<Number, Number> humidityChart;
    private final AreaChart<Number, Number> pressureChart;
    private final AreaChart<Number, Number> radiationChart;
    private final List<ChartBinding> chartBindings;
    private final Region dockRoot;
    private final ToggleGroup periodGroup = new ToggleGroup();
    private DockBuilding dockBuilder;
    private DockContainerBranch dockWorkspace;
    private HBox dockMinimizedBar;
    private StackPane dockOverlayRoot;

    private long selectedPeriodSeconds = PERIOD_6H;
    private BindingState bindingState = Unbound.INSTANCE;
    private final AtomicBoolean refreshQueued = new AtomicBoolean(false);
    private final AtomicBoolean dockLayoutSaveQueued = new AtomicBoolean(false);
    private final Map<Dockable, DockableCloseListener> dockableCloseListeners = new IdentityHashMap<>();
    private DockMaximizeState dockMaximizeState;
    private List<XYChart.Data<Number, Number>> environmentPressureData = List.of();
    private final Runnable telemetryListener = this::queueRefresh;

    /** Callback invoked after data refresh, used to synchronize the log table. */
    private Runnable onDataRefreshed = () -> {};

    /** Last filtered entries, reused by the log table. */
    private List<TelemetryEntry> filteredEntries = List.of();

    private record PeriodOption(String labelKey, long seconds) {}

    private record ChartBinding(TelemetryChartDataBuilder.ChartKind kind,
                                AreaChart<Number, Number> chart,
                                Dockable dockable) {}

    private record ChartSpec(TelemetryChartDataBuilder.ChartKind kind,
                             AreaChart<Number, Number> chart) {}

    private record DockLayout(Region root,
                              List<ChartBinding> bindings) {}

    private record DockMaximizeState(Dockable dockable,
                                     Node contentNode,
                                     Region overlayNode,
                                     boolean closable) {}

    private record InteractiveNodes(Region plotArea, Node chartContent) {}

    private record CrosshairFilters(Node chartContent,
                                    EventHandler<MouseEvent> clickHandler,
                                    EventHandler<MouseEvent> moveHandler) {
        private void detach() {
            chartContent.removeEventFilter(MouseEvent.MOUSE_CLICKED, clickHandler);
            chartContent.removeEventFilter(MouseEvent.MOUSE_MOVED, moveHandler);
        }
    }

    private sealed interface BindingState permits Bound, Unbound {}

    private record Bound(DeviceState state, String nodeId) implements BindingState {}

    private enum Unbound implements BindingState {
        INSTANCE
    }

    public TelemetryChartPanel() {
        this(false);
    }

    public TelemetryChartPanel(boolean basicOnly) {
        this.basicOnly = basicOnly;
        setSpacing(NODE_DETAIL_CHART_GAP);

        nodeDetailBasicTitleLabel = createNodeDetailChartTitle();
        nodeDetailEnvironmentTitleLabel = createNodeDetailChartTitle();
        nodeDetailBasicLegend = createNodeDetailChartLegend();
        nodeDetailEnvironmentLegend = createNodeDetailChartLegend();
        chart = createSizedChart(false);
        environmentMetricsChart = createStyledChart(true, "/css/chart-environment.css");
        environmentMetricsChart.setPadding(new Insets(0, NODE_DETAIL_RIGHT_AXIS_WIDTH, 0, 0));
        environmentPressureAxis = createEnvironmentPressureAxis();
        environmentPressureClip = new Rectangle();
        environmentPressureArea = new Path();
        environmentPressureArea.getStyleClass().add("telemetry-environment-pressure-area");
        environmentPressureArea.setMouseTransparent(true);
        environmentPressureLine = new Path();
        environmentPressureLine.getStyleClass().add("telemetry-environment-pressure-line");
        environmentPressureLine.setMouseTransparent(true);
        environmentPressurePlotLayer = new Pane(environmentPressureArea, environmentPressureLine);
        environmentPressurePlotLayer.getStyleClass().add("telemetry-environment-pressure-plot-layer");
        environmentPressurePlotLayer.setMouseTransparent(true);
        environmentPressurePlotLayer.setClip(environmentPressureClip);
        environmentPressureOverlay = createEnvironmentPressureOverlay();
        if (basicOnly) {
            alignNodeDetailPlotAreas();
        }
        rxChart = createStyledChart(false, "/css/chart-green-red-yellow.css");
        rateChart = createStyledChart(true, "/css/chart-green-red-yellow.css");
        txChart = createStyledChart(true, "/css/chart-tx.css");
        qualityChart = createStyledChart(true, "/css/chart-quality.css");
        hopsChart = createStyledChart(true, "/css/chart-hops.css");
        temperatureChart = createStyledChart(true, "/css/chart-temperature.css");
        humidityChart = createStyledChart(true, "/css/chart-humidity.css");
        pressureChart = createStyledChart(true, "/css/chart-pressure.css");
        radiationChart = createStyledChart(true, "/css/chart-radiation.css");
        DockLayout dockLayout = basicOnly ? null : createDockLayout();
        dockRoot = dockLayout != null ? dockLayout.root() : null;
        chartBindings = basicOnly
                ? List.of(new ChartBinding(TelemetryChartDataBuilder.ChartKind.BASIC, chart, null))
                : dockLayout.bindings();

        getChildren().addAll(Stream.concat(createContent().stream(), Stream.of(createPeriodBar())).toList());
        updateChart(List.of(), List.of());
    }

    /**
     * Sets the callback invoked after each chart data refresh.
     * The dashboard uses it to synchronize its log table.
     */
    public void setOnDataRefreshed(Runnable callback) {
        onDataRefreshed = Objects.requireNonNullElse(callback, () -> {});
    }

    /**
     * Binds the component to a device state and node id.
     * It subscribes to telemetry updates and loads data. If the same state and
     * node id are already bound, data is refreshed without resubscribing.
     */
    public void bind(DeviceState state, String nodeId) {
        Bound nextBinding = new Bound(
                Objects.requireNonNull(state, "state"),
                Objects.requireNonNull(nodeId, "nodeId")
        );

        if (currentBinding().filter(nextBinding::equals).isPresent()) {
            refresh();
            return;
        }

        currentBinding().ifPresent(bound -> bound.state().removeTelemetryListener(telemetryListener));
        bindingState = nextBinding;
        nextBinding.state().addTelemetryListener(telemetryListener);
        refresh();
    }

    /**
     * Unbinds the current device state and clears the chart.
     */
    public void unbind() {
        currentBinding().ifPresent(bound -> bound.state().removeTelemetryListener(telemetryListener));
        bindingState = Unbound.INSTANCE;
        filteredEntries = List.of();
        updateChart(List.of(), List.of());
    }

    /**
     * Returns the latest filtered telemetry entries for the dashboard log table.
     */
    public List<TelemetryEntry> getFilteredEntries() {
        return filteredEntries;
    }

    private List<Node> createContent() {
        return basicOnly
                ? List.of(nodeDetailCharts())
                : List.of(dockRoot);
    }

    private Region nodeDetailCharts() {
        Region basicFrame = nodeDetailChartFrame(nodeDetailBasicTitleLabel, basicChartWrap(), nodeDetailBasicLegend);
        Region environmentFrame = nodeDetailChartFrame(
                nodeDetailEnvironmentTitleLabel,
                environmentChartWrap(),
                nodeDetailEnvironmentLegend
        );

        NodeDetailChartsPane pane = new NodeDetailChartsPane(basicFrame, environmentFrame);
        pane.getStyleClass().add("telemetry-node-detail-charts");
        pane.setSnapToPixel(false);
        pane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(pane, Priority.ALWAYS);
        return pane;
    }

    private Region nodeDetailChartFrame(Label titleLabel, StackPane chartWrap, HBox legend) {
        VBox frame = new VBox(0, titleLabel, chartWrap, legend);
        frame.getStyleClass().add("telemetry-node-detail-chart-frame");
        frame.setSnapToPixel(false);
        titleLabel.setSnapToPixel(false);
        chartWrap.setSnapToPixel(false);
        legend.setSnapToPixel(false);
        frame.setFillWidth(true);
        frame.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(chartWrap, Priority.ALWAYS);
        return frame;
    }

    private StackPane basicChartWrap() {
        StackPane chartWrap = wrapWithOverlay(chart);
        VBox.setVgrow(chartWrap, Priority.ALWAYS);
        return chartWrap;
    }

    private StackPane environmentChartWrap() {
        StackPane chartWrap = wrapWithOverlay(environmentMetricsChart);
        chartWrap.getChildren().add(1, environmentPressureOverlay);
        environmentPressureOverlay.prefWidthProperty().bind(chartWrap.widthProperty());
        environmentPressureOverlay.prefHeightProperty().bind(chartWrap.heightProperty());
        VBox.setVgrow(chartWrap, Priority.ALWAYS);
        return chartWrap;
    }

    private void refresh() {
        switch (bindingState) {
            case Bound bound -> refresh(bound);
            case Unbound _ -> {
                filteredEntries = List.of();
                updateChart(List.of(), List.of());
            }
        }
    }

    private void queueRefresh() {
        if (!refreshQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            Platform.runLater(() -> {
                refreshQueued.set(false);
                refresh();
            });
        } catch (IllegalStateException e) {
            refreshQueued.set(false);
        }
    }

    private void refresh(Bound bound) {
        long now = System.currentTimeMillis() / 1000;
        long sinceEpoch = selectedPeriodSeconds > 0 ? now - selectedPeriodSeconds : 0;
        long maxTs = now + 300;
        String ownerNodeId = ownerNodeId(bound.state());

        filteredEntries = NodeCacheService.getInstance()
                .loadTelemetryForNode(bound.nodeId(), sinceEpoch, maxTs, ownerNodeId);

        List<TelemetryEntry> qualityEntries = basicOnly
                ? List.of()
                : NodeCacheService.getInstance().loadTelemetryQuality(sinceEpoch, maxTs, ownerNodeId);

        updateChart(filteredEntries, qualityEntries);
        onDataRefreshed.run();
    }

    private String ownerNodeId(DeviceState state) {
        String ownerNodeId = state.getOwnerNodeId();
        if (ownerNodeId != null && !ownerNodeId.isBlank()) {
            return ownerNodeId;
        }
        return state.getMyNodeNum() == 0 ? "" : String.format("!%08x", state.getMyNodeNum());
    }

    private Optional<Bound> currentBinding() {
        return switch (bindingState) {
            case Bound bound -> Optional.of(bound);
            case Unbound _ -> Optional.empty();
        };
    }

    private AreaChart<Number, Number> createSizedChart(boolean autoRangeY) {
        AreaChart<Number, Number> areaChart = createChart(autoRangeY);
        areaChart.setPrefHeight(200);
        areaChart.setMinHeight(120);
        return areaChart;
    }

    private AreaChart<Number, Number> createStyledChart(boolean autoRangeY, String stylesheetResource) {
        AreaChart<Number, Number> areaChart = createSizedChart(autoRangeY);
        areaChart.getStylesheets().add(getClass().getResource(stylesheetResource).toExternalForm());
        return areaChart;
    }

    private Label createNodeDetailChartTitle() {
        Label label = new Label();
        label.getStyleClass().add("telemetry-node-detail-chart-title");
        label.setAlignment(Pos.CENTER);
        label.setMinHeight(NODE_DETAIL_TITLE_HEIGHT);
        label.setPrefHeight(NODE_DETAIL_TITLE_HEIGHT);
        label.setMaxHeight(NODE_DETAIL_TITLE_HEIGHT);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private HBox createNodeDetailChartLegend() {
        HBox legend = new HBox(12);
        legend.getStyleClass().add("telemetry-node-detail-chart-legend");
        legend.setAlignment(Pos.CENTER);
        legend.setMinHeight(NODE_DETAIL_LEGEND_HEIGHT);
        legend.setPrefHeight(NODE_DETAIL_LEGEND_HEIGHT);
        legend.setMaxHeight(NODE_DETAIL_LEGEND_HEIGHT);
        legend.setMaxWidth(Double.MAX_VALUE);
        return legend;
    }

    private void alignNodeDetailPlotAreas() {
        alignNodeDetailPlotArea(chart);
        alignNodeDetailPlotArea(environmentMetricsChart);
        environmentPressureAxis.setMinWidth(NODE_DETAIL_RIGHT_AXIS_WIDTH);
        environmentPressureAxis.setPrefWidth(NODE_DETAIL_RIGHT_AXIS_WIDTH);
        environmentPressureAxis.setMaxWidth(NODE_DETAIL_RIGHT_AXIS_WIDTH);
    }

    private void alignNodeDetailPlotArea(AreaChart<Number, Number> areaChart) {
        areaChart.getStyleClass().add("telemetry-node-detail-chart");
        areaChart.setSnapToPixel(false);
        areaChart.setLegendVisible(false);
        areaChart.setPadding(new Insets(0, NODE_DETAIL_RIGHT_AXIS_WIDTH, 0, 0));
        if (areaChart.getXAxis() instanceof Region xAxisRegion) {
            xAxisRegion.setSnapToPixel(false);
        }
        Node yAxis = areaChart.getYAxis();
        if (yAxis instanceof Region yAxisRegion) {
            yAxisRegion.setSnapToPixel(false);
            yAxisRegion.setMinWidth(NODE_DETAIL_LEFT_AXIS_WIDTH);
            yAxisRegion.setPrefWidth(NODE_DETAIL_LEFT_AXIS_WIDTH);
            yAxisRegion.setMaxWidth(NODE_DETAIL_LEFT_AXIS_WIDTH);
        }
    }

    private NumberAxis createEnvironmentPressureAxis() {
        NumberAxis axis = new NumberAxis();
        axis.setSide(Side.RIGHT);
        axis.setForceZeroInRange(false);
        axis.setAutoRanging(false);
        axis.setLabel("");
        axis.setTickLabelFormatter(new NumberAxis.DefaultFormatter(axis) {
            @Override
            public String toString(Number object) {
                return String.format(I18n.locale(), "%.0f", object.doubleValue());
            }
        });
        axis.getStyleClass().add("telemetry-environment-pressure-axis");
        return axis;
    }

    private Pane createEnvironmentPressureOverlay() {
        Pane overlay = new Pane() {
            @Override
            protected void layoutChildren() {
                super.layoutChildren();
                layoutEnvironmentPressureOverlay(this);
            }
        };
        overlay.getStyleClass().add("telemetry-environment-pressure-overlay");
        overlay.getStylesheets().add(getClass().getResource("/css/chart-environment-pressure.css").toExternalForm());
        overlay.setMouseTransparent(true);
        overlay.getChildren().addAll(environmentPressurePlotLayer, environmentPressureAxis);
        return overlay;
    }

    private AreaChart<Number, Number> createChart(boolean autoRangeY) {
        AreaChart<Number, Number> areaChart = new AreaChart<>(createTimeAxis(), createYAxis(autoRangeY));
        areaChart.setAnimated(false);
        areaChart.setCreateSymbols(false);
        areaChart.setLegendVisible(true);
        areaChart.setTitle(null);
        return areaChart;
    }

    private NumberAxis createTimeAxis() {
        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("");
        xAxis.setForceZeroInRange(false);
        xAxis.setAutoRanging(true);
        xAxis.setTickLabelFormatter(new NumberAxis.DefaultFormatter(xAxis) {
            @Override
            public String toString(Number object) {
                long epoch = object.longValue();
                return epoch <= 0
                        ? ""
                        : Instant.ofEpochSecond(epoch)
                        .atZone(ZoneId.systemDefault())
                        .format(AXIS_FMT);
            }
        });
        return xAxis;
    }

    private NumberAxis createYAxis(boolean autoRangeY) {
        NumberAxis yAxis = autoRangeY ? new NumberAxis() : new NumberAxis(0, 100, 25);
        yAxis.setLabel("");
        yAxis.setAutoRanging(autoRangeY);
        yAxis.setForceZeroInRange(autoRangeY);
        return yAxis;
    }

    private DockLayout createDockLayout() {
        Bento bento = new TelemetryBento();
        DockBuilding builder = bento.dockBuilding();
        DockContainerRootBranch workspace = builder.root("telemetry-dock-workspace");
        HBox minimizedBar = createMinimizedBar();

        workspace.getStyleClass().add("telemetry-dock");
        workspace.setOrientation(Orientation.VERTICAL);
        workspace.setPruneWhenEmpty(false);
        dockBuilder = builder;
        dockWorkspace = workspace;
        dockMinimizedBar = minimizedBar;

        List<ChartBinding> bindings = createDockChartBindings(builder, workspace, minimizedBar);

        if (!restoreDockLayout(builder, workspace, minimizedBar, bindings)) {
            applyDefaultDockLayout(builder, workspace, minimizedBar, bindings);
        }

        BorderPane shell = new BorderPane(workspace);
        shell.setId("telemetry-dock-shell");
        shell.getStyleClass().add("telemetry-dock-shell");
        shell.setBottom(minimizedBar);
        installDockLayoutPersistence(bento, shell, workspace, minimizedBar, bindings);

        StackPane root = new StackPane(shell);
        root.getStyleClass().add("telemetry-dock-root");
        dockOverlayRoot = root;
        VBox.setVgrow(root, Priority.ALWAYS);

        return new DockLayout(root, bindings);
    }

    private List<ChartBinding> createDockChartBindings(DockBuilding builder,
                                                       DockContainerBranch workspace,
                                                       HBox minimizedBar) {
        return Stream.of(
                        new ChartSpec(TelemetryChartDataBuilder.ChartKind.BASIC, chart),
                        new ChartSpec(TelemetryChartDataBuilder.ChartKind.RX, rxChart),
                        new ChartSpec(TelemetryChartDataBuilder.ChartKind.RATE, rateChart),
                        new ChartSpec(TelemetryChartDataBuilder.ChartKind.TX, txChart),
                        new ChartSpec(TelemetryChartDataBuilder.ChartKind.QUALITY, qualityChart),
                        new ChartSpec(TelemetryChartDataBuilder.ChartKind.HOPS, hopsChart),
                        new ChartSpec(TelemetryChartDataBuilder.ChartKind.TEMPERATURE, temperatureChart),
                        new ChartSpec(TelemetryChartDataBuilder.ChartKind.HUMIDITY, humidityChart),
                        new ChartSpec(TelemetryChartDataBuilder.ChartKind.PRESSURE, pressureChart),
                        new ChartSpec(TelemetryChartDataBuilder.ChartKind.RADIATION, radiationChart)
                )
                .map(spec -> dockChart(builder, workspace, minimizedBar, spec.kind(), spec.chart()))
                .toList();
    }

    private void applyDefaultDockLayout(DockBuilding builder,
                                        DockContainerBranch workspace,
                                        HBox minimizedBar,
                                        List<ChartBinding> bindings) {
        Map<TelemetryChartDataBuilder.ChartKind, ChartBinding> bindingsByKind = bindingsByKind(bindings);
        bindings.stream()
                .map(ChartBinding::dockable)
                .forEach(dockable -> {
                    dockable.setClosable(true);
                    addMinimizeCloseListener(builder, workspace, minimizedBar, dockable);
                });
        addDefaultDockLayout(
                builder,
                workspace,
                binding(bindingsByKind, TelemetryChartDataBuilder.ChartKind.BASIC),
                binding(bindingsByKind, TelemetryChartDataBuilder.ChartKind.RX),
                binding(bindingsByKind, TelemetryChartDataBuilder.ChartKind.RATE),
                binding(bindingsByKind, TelemetryChartDataBuilder.ChartKind.TX),
                binding(bindingsByKind, TelemetryChartDataBuilder.ChartKind.QUALITY),
                binding(bindingsByKind, TelemetryChartDataBuilder.ChartKind.HOPS)
        );
        addDefaultMinimizedDockables(
                builder,
                workspace,
                minimizedBar,
                binding(bindingsByKind, TelemetryChartDataBuilder.ChartKind.TEMPERATURE),
                binding(bindingsByKind, TelemetryChartDataBuilder.ChartKind.HUMIDITY),
                binding(bindingsByKind, TelemetryChartDataBuilder.ChartKind.PRESSURE),
                binding(bindingsByKind, TelemetryChartDataBuilder.ChartKind.RADIATION)
        );
    }

    private void resetDockLayout() {
        if (!isDockLayoutAvailable()) {
            return;
        }

        restoreMaximizedDockLayout();
        AppPreferences.saveTelemetryDockLayout(null);
        clearDockWorkspace(dockWorkspace);
        dockMinimizedBar.getChildren().clear();
        applyDefaultDockLayout(dockBuilder, dockWorkspace, dockMinimizedBar, chartBindings);
        saveDockLayout(dockWorkspace, dockMinimizedBar, chartBindings);
    }

    private void toggleDockMaximize(AreaChart<Number, Number> areaChart) {
        if (!isDockLayoutAvailable()) {
            return;
        }
        if (dockMaximizeState != null) {
            restoreMaximizedDockLayout();
            return;
        }

        chartBindings.stream()
                .filter(binding -> binding.chart() == areaChart)
                .map(ChartBinding::dockable)
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(this::maximizeDockable);
    }

    private boolean isDockLayoutAvailable() {
        return !basicOnly && dockBuilder != null && dockWorkspace != null && dockMinimizedBar != null;
    }

    private void maximizeDockable(Dockable dockable) {
        if (dockable.getContainer() == null || dockOverlayRoot == null || dockable.getNode() == null) {
            return;
        }

        Node contentNode = dockable.getNode();
        StackPane placeholder = new StackPane();
        placeholder.setMinSize(0, 0);
        placeholder.getStyleClass().add("telemetry-dock-maximized-placeholder");

        boolean closable = dockable.isClosable();
        dockable.setNode(placeholder);

        BorderPane overlay = new BorderPane(contentNode);
        overlay.getStyleClass().add("telemetry-dock-maximized-overlay");
        overlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        StackPane.setAlignment(overlay, Pos.CENTER);

        dockMaximizeState = new DockMaximizeState(dockable, contentNode, overlay, closable);
        dockable.setClosable(false);
        dockOverlayRoot.getChildren().add(overlay);
    }

    private void restoreMaximizedDockLayout() {
        Optional.ofNullable(dockMaximizeState).ifPresent(state -> {
            dockMaximizeState = null;
            if (state.overlayNode() instanceof BorderPane overlay) {
                overlay.setCenter(null);
            }
            dockOverlayRoot.getChildren().remove(state.overlayNode());
            state.dockable().setNode(state.contentNode());
            state.dockable().setClosable(state.closable());
        });
    }

    private void addDefaultDockLayout(DockBuilding builder,
                                      DockContainerBranch workspace,
                                      ChartBinding basicBinding,
                                      ChartBinding rxBinding,
                                      ChartBinding rateBinding,
                                      ChartBinding txBinding,
                                      ChartBinding qualityBinding,
                                      ChartBinding hopsBinding) {
        DockContainerBranch firstRow = chartRow(builder, "telemetry-dock-row-1", basicBinding, rxBinding);
        DockContainerBranch secondRow = chartRow(builder, "telemetry-dock-row-2", rateBinding, txBinding);
        DockContainerBranch thirdRow = chartRow(builder, "telemetry-dock-row-3", qualityBinding, hopsBinding);
        workspace.addContainers(
                firstRow,
                secondRow,
                thirdRow
        );
        applyDefaultDockDividerPositions(workspace, firstRow, secondRow, thirdRow);
        Platform.runLater(() -> {
            applyDefaultDockDividerPositions(workspace, firstRow, secondRow, thirdRow);
            Platform.runLater(() -> applyDefaultDockDividerPositions(workspace, firstRow, secondRow, thirdRow));
        });
    }

    private void applyDefaultDockDividerPositions(DockContainerBranch workspace, DockContainerBranch... rows) {
        workspace.setDividerPositions(1.0 / 3.0, 2.0 / 3.0);
        Arrays.stream(rows).forEach(row -> row.setDividerPositions(0.5));
    }

    private void addDefaultMinimizedDockables(DockBuilding builder,
                                              DockContainerBranch workspace,
                                              HBox minimizedBar,
                                              ChartBinding... bindings) {
        Arrays.stream(bindings)
                .map(ChartBinding::dockable)
                .forEach(dockable -> {
                    dockable.setClosable(false);
                    addMinimizedDockableButton(builder, workspace, minimizedBar, dockable);
                });
        updateMinimizedBarVisibility(minimizedBar);
    }

    private HBox createMinimizedBar() {
        HBox bar = new HBox(4);
        bar.setId("telemetry-dock-minimized-bar");
        bar.getStyleClass().add("telemetry-dock-minimized-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setVisible(false);
        bar.managedProperty().bind(bar.visibleProperty());
        return bar;
    }

    private DockContainerBranch chartRow(DockBuilding builder,
                                         String identifier,
                                         ChartBinding leftBinding,
                                         ChartBinding rightBinding) {
        DockContainerBranch row = builder.branch(identifier);
        row.setOrientation(Orientation.HORIZONTAL);
        row.addContainers(
                chartLeaf(builder, identifier + "-left", leftBinding),
                chartLeaf(builder, identifier + "-right", rightBinding)
        );
        return row;
    }

    private DockContainerLeaf chartLeaf(DockBuilding builder, String identifier, ChartBinding binding) {
        DockContainerLeaf leaf = builder.leaf(identifier);
        leaf.addDockable(binding.dockable());
        return leaf;
    }

    private ChartBinding dockChart(DockBuilding builder,
                                   DockContainerBranch workspace,
                                   HBox minimizedBar,
                                   TelemetryChartDataBuilder.ChartKind kind,
                                   AreaChart<Number, Number> areaChart) {
        Dockable dockable = builder.dockable("telemetry-chart-" + kind.name().toLowerCase());
        dockable.setNode(wrapWithOverlay(areaChart));
        dockable.setCanBeDroppedToNewWindow(false);
        addMinimizeCloseListener(builder, workspace, minimizedBar, dockable);
        return new ChartBinding(kind, areaChart, dockable);
    }

    private void addMinimizeCloseListener(DockBuilding builder,
                                          DockContainerBranch workspace,
                                          HBox minimizedBar,
                                          Dockable dockable) {
        Optional.ofNullable(dockableCloseListeners.remove(dockable))
                .ifPresent(dockable::removeCloseListener);

        DockableCloseListener listener = (path, closedDockable) -> {
            dockableCloseListeners.remove(closedDockable);
            Platform.runLater(() -> minimizeDockable(builder, workspace, minimizedBar, closedDockable));
        };
        dockableCloseListeners.put(dockable, listener);
        dockable.addCloseListener(listener);
    }

    private void minimizeDockable(DockBuilding builder,
                                  DockContainerBranch workspace,
                                  HBox minimizedBar,
                                  Dockable dockable) {
        if (minimizedBar.getChildren().stream().anyMatch(node -> node.getUserData() == dockable)) {
            return;
        }

        Optional.ofNullable(dockable.getContainer())
                .ifPresent(container -> container.removeDockable(dockable));
        dockable.setClosable(false);

        addMinimizedDockableButton(builder, workspace, minimizedBar, dockable);
        updateMinimizedBarVisibility(minimizedBar);
        normalizeSingleOpenDockable(workspace);
    }

    private void addMinimizedDockableButton(DockBuilding builder,
                                            DockContainerBranch workspace,
                                            HBox minimizedBar,
                                            Dockable dockable) {
        Button restoreButton = new Button();
        restoreButton.getStyleClass().add("telemetry-dock-minimized-button");
        restoreButton.textProperty().bind(dockable.titleProperty());
        restoreButton.setUserData(dockable);
        restoreButton.setOnAction(event ->
                restoreDockable(builder, workspace, minimizedBar, dockable, restoreButton));
        minimizedBar.getChildren().add(restoreButton);
    }

    private void restoreDockable(DockBuilding builder,
                                 DockContainerBranch workspace,
                                 HBox minimizedBar,
                                 Dockable dockable,
                                 Button restoreButton) {
        minimizedBar.getChildren().remove(restoreButton);
        updateMinimizedBarVisibility(minimizedBar);
        if (dockable.getContainer() != null) {
            return;
        }

        dockable.setClosable(true);
        addMinimizeCloseListener(builder, workspace, minimizedBar, dockable);

        DockContainerLeaf leaf = builder.leaf("telemetry-dock-restored-" + dockable.getIdentifier() + "-" + System.nanoTime());
        leaf.addDockable(dockable);
        workspace.addContainer(leaf);
        normalizeSingleOpenDockable(workspace);
    }

    private void updateMinimizedBarVisibility(HBox minimizedBar) {
        minimizedBar.setVisible(!minimizedBar.getChildren().isEmpty());
    }

    private void installDockLayoutPersistence(Bento bento,
                                              BorderPane shell,
                                              DockContainerBranch workspace,
                                              HBox minimizedBar,
                                              List<ChartBinding> bindings) {
        bento.events().addEventListener(event -> {
            if (event instanceof DockEvent.ContainerChildAdded
                    || event instanceof DockEvent.ContainerChildRemoved
                    || event instanceof DockEvent.DockableAdded
                    || event instanceof DockEvent.DockableRemoved) {
                queueDockLayoutSave(workspace, minimizedBar, bindings);
            }
        });
        shell.addEventFilter(MouseEvent.MOUSE_RELEASED, event ->
                queueDockLayoutSave(workspace, minimizedBar, bindings));
    }

    private void queueDockLayoutSave(DockContainerBranch workspace,
                                     HBox minimizedBar,
                                     List<ChartBinding> bindings) {
        if (!dockLayoutSaveQueued.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            Platform.runLater(() -> {
                dockLayoutSaveQueued.set(false);
                saveDockLayout(workspace, minimizedBar, bindings);
            });
        });
    }

    private void saveDockLayout(DockContainerBranch workspace,
                                HBox minimizedBar,
                                List<ChartBinding> bindings) {
        serializeContainer(workspace)
                .flatMap(workspaceState -> serializeMinimizedDockables(minimizedBar, bindings)
                        .filter(minimizedState -> isCompleteDockLayoutState(workspaceState, minimizedState))
                        .map(minimizedState -> dockLayoutState(workspaceState, minimizedState)))
                .ifPresent(layout -> AppPreferences.saveTelemetryDockLayout(GSON.toJson(layout)));
    }

    private JsonObject dockLayoutState(JsonObject workspaceState, JsonArray minimizedState) {
        JsonObject layout = new JsonObject();
        layout.addProperty("version", DOCK_LAYOUT_VERSION);
        layout.add("workspace", workspaceState);
        layout.add("minimized", minimizedState);
        return layout;
    }

    private Optional<JsonArray> serializeMinimizedDockables(HBox minimizedBar, List<ChartBinding> bindings) {
        List<TelemetryChartDataBuilder.ChartKind> minimizedKinds = minimizedBar.getChildren().stream()
                .map(node -> dockableFromNodeData(node).flatMap(dockable -> kindForDockable(dockable, bindings)))
                .flatMap(Optional::stream)
                .toList();
        if (minimizedKinds.size() != minimizedBar.getChildren().size()) {
            return Optional.empty();
        }
        return Optional.of(jsonStringArray(minimizedKinds.stream().map(Enum::name)));
    }

    private Optional<Dockable> dockableFromNodeData(Node node) {
        return Optional.ofNullable(node.getUserData())
                .filter(Dockable.class::isInstance)
                .map(Dockable.class::cast);
    }

    private Optional<JsonObject> serializeContainer(DockContainer container) {
        if (container instanceof DockContainerBranch branch) {
            return serializeBranch(branch);
        }
        if (container instanceof DockContainerLeaf leaf) {
            return serializeLeaf(leaf);
        }
        return Optional.empty();
    }

    private Optional<JsonObject> serializeBranch(DockContainerBranch branch) {
        List<JsonObject> children = branch.getChildContainers().stream()
                .map(this::serializeContainer)
                .flatMap(Optional::stream)
                .toList();
        if (children.size() != branch.getChildContainers().size()) {
            return Optional.empty();
        }

        JsonObject state = new JsonObject();
        state.addProperty("type", "branch");
        state.addProperty("orientation", branch.getOrientation().name());
        state.add("dividers", jsonNumberArray(branch.getDividerPositions()));
        state.add("children", jsonObjectArray(children.stream()));
        return Optional.of(state);
    }

    private Optional<JsonObject> serializeLeaf(DockContainerLeaf leaf) {
        if (leaf.getDockables().size() != 1) {
            return Optional.empty();
        }
        return kindForDockable(leaf.getDockables().getFirst(), chartBindings)
                .map(kind -> {
                    JsonObject state = new JsonObject();
                    state.addProperty("type", "leaf");
                    state.addProperty("dockable", kind.name());
                    return state;
                });
    }

    private boolean restoreDockLayout(DockBuilding builder,
                                      DockContainerBranch workspace,
                                      HBox minimizedBar,
                                      List<ChartBinding> bindings) {
        return savedDockLayout()
                .map(raw -> restoreDockLayout(builder, workspace, minimizedBar, bindings, raw))
                .orElse(false);
    }

    private Optional<String> savedDockLayout() {
        return Optional.ofNullable(AppPreferences.getTelemetryDockLayout())
                .filter(raw -> !raw.isBlank());
    }

    private boolean restoreDockLayout(DockBuilding builder,
                                      DockContainerBranch workspace,
                                      HBox minimizedBar,
                                      List<ChartBinding> bindings,
                                      String raw) {
        try {
            JsonObject layout = JsonParser.parseString(raw).getAsJsonObject();
            if (layout.get("version").getAsInt() != DOCK_LAYOUT_VERSION) {
                return false;
            }
            JsonObject workspaceState = layout.getAsJsonObject("workspace");
            JsonArray minimizedState = layout.has("minimized")
                    ? layout.getAsJsonArray("minimized")
                    : new JsonArray();
            if (!isCompleteDockLayoutState(workspaceState, minimizedState)) {
                return false;
            }

            Map<TelemetryChartDataBuilder.ChartKind, ChartBinding> bindingsByKind = bindingsByKind(bindings);
            int[] nextId = {0};
            restoreBranchChildren(builder, workspace, workspaceState, bindingsByKind, nextId);
            jsonElements(minimizedState)
                    .map(JsonElement::getAsString)
                    .map(TelemetryChartDataBuilder.ChartKind::valueOf)
                    .map(bindingsByKind::get)
                    .map(ChartBinding::dockable)
                    .forEach(dockable -> {
                        dockable.setClosable(false);
                        addMinimizedDockableButton(builder, workspace, minimizedBar, dockable);
                    });
            updateMinimizedBarVisibility(minimizedBar);
            return true;
        } catch (IllegalStateException | JsonParseException | NullPointerException | IllegalArgumentException ignored) {
            clearDockWorkspace(workspace);
            minimizedBar.getChildren().clear();
            updateMinimizedBarVisibility(minimizedBar);
            return false;
        }
    }

    private void clearDockWorkspace(DockContainerBranch workspace) {
        List.copyOf(workspace.getDockables()).forEach(dockable ->
                Optional.ofNullable(dockable.getContainer())
                        .ifPresent(container -> container.removeDockable(dockable)));
        List.copyOf(workspace.getChildContainers()).forEach(workspace::removeContainer);
    }

    private void restoreBranchChildren(DockBuilding builder,
                                       DockContainerBranch branch,
                                       JsonObject branchState,
                                       Map<TelemetryChartDataBuilder.ChartKind, ChartBinding> bindingsByKind,
                                       int[] nextId) {
        branch.setOrientation(readOrientation(branchState));
        jsonElements(branchState.getAsJsonArray("children"))
                .map(JsonElement::getAsJsonObject)
                .map(childState -> restoreContainer(builder, childState, bindingsByKind, nextId))
                .forEach(branch::addContainer);
        applyDividerPositions(branch, branchState.getAsJsonArray("dividers"));
        reapplyDividerPositionsAfterLayout(branch, branchState.getAsJsonArray("dividers"));
    }

    private DockContainer restoreContainer(DockBuilding builder,
                                           JsonObject containerState,
                                           Map<TelemetryChartDataBuilder.ChartKind, ChartBinding> bindingsByKind,
                                           int[] nextId) {
        String type = containerState.get("type").getAsString();
        if ("leaf".equals(type)) {
            TelemetryChartDataBuilder.ChartKind kind =
                    TelemetryChartDataBuilder.ChartKind.valueOf(containerState.get("dockable").getAsString());
            DockContainerLeaf leaf = builder.leaf("telemetry-dock-restored-" + kind.name().toLowerCase());
            leaf.addDockable(binding(bindingsByKind, kind).dockable());
            return leaf;
        }

        DockContainerBranch branch = builder.branch("telemetry-dock-restored-branch-" + nextId[0]++);
        restoreBranchChildren(builder, branch, containerState, bindingsByKind, nextId);
        return branch;
    }

    private Orientation readOrientation(JsonObject branchState) {
        return "HORIZONTAL".equals(branchState.get("orientation").getAsString())
                ? Orientation.HORIZONTAL
                : Orientation.VERTICAL;
    }

    private void applyDividerPositions(DockContainerBranch branch, JsonArray dividers) {
        double[] positions = dividerPositions(dividers);
        if (positions.length == 0) {
            return;
        }
        branch.setDividerPositions(positions);
    }

    private void reapplyDividerPositionsAfterLayout(DockContainerBranch branch, JsonArray dividers) {
        double[] positions = dividerPositions(dividers);
        if (positions.length == 0) {
            return;
        }
        Platform.runLater(() -> {
            branch.setDividerPositions(positions);
            Platform.runLater(() -> branch.setDividerPositions(positions));
        });
    }

    private double[] dividerPositions(JsonArray dividers) {
        if (dividers == null || dividers.isEmpty()) {
            return new double[0];
        }
        return jsonElements(dividers)
                .mapToDouble(JsonElement::getAsDouble)
                .toArray();
    }

    private boolean isCompleteDockLayoutState(JsonObject workspaceState, JsonArray minimizedState) {
        EnumSet<TelemetryChartDataBuilder.ChartKind> seen = EnumSet.noneOf(TelemetryChartDataBuilder.ChartKind.class);
        if (!collectDockKinds(workspaceState, seen)) {
            return false;
        }
        boolean minimizedKindsAreComplete = jsonElements(minimizedState)
                .map(element -> kindFromName(element.getAsString()))
                .allMatch(kind -> kind.filter(seen::add).isPresent());
        if (!minimizedKindsAreComplete) {
            return false;
        }
        return seen.equals(EnumSet.allOf(TelemetryChartDataBuilder.ChartKind.class));
    }

    private boolean collectDockKinds(JsonObject containerState, Set<TelemetryChartDataBuilder.ChartKind> seen) {
        String type = containerState.get("type").getAsString();
        if ("leaf".equals(type)) {
            Optional<TelemetryChartDataBuilder.ChartKind> kind = kindFromName(containerState.get("dockable").getAsString());
            return kind.isPresent() && seen.add(kind.get());
        }
        if (!"branch".equals(type)) {
            return false;
        }

        return jsonElements(containerState.getAsJsonArray("children"))
                .allMatch(child -> collectDockKinds(child.getAsJsonObject(), seen));
    }

    private Map<TelemetryChartDataBuilder.ChartKind, ChartBinding> bindingsByKind(List<ChartBinding> bindings) {
        return bindings.stream()
                .collect(Collectors.toMap(
                        ChartBinding::kind,
                        binding -> binding,
                        (first, second) -> second,
                        () -> new EnumMap<>(TelemetryChartDataBuilder.ChartKind.class)
                ));
    }

    private ChartBinding binding(Map<TelemetryChartDataBuilder.ChartKind, ChartBinding> bindingsByKind,
                                 TelemetryChartDataBuilder.ChartKind kind) {
        return Objects.requireNonNull(bindingsByKind.get(kind), "Missing chart binding for " + kind);
    }

    private Stream<JsonElement> jsonElements(JsonArray array) {
        return StreamSupport.stream(array.spliterator(), false);
    }

    private JsonArray jsonStringArray(Stream<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private JsonArray jsonNumberArray(double[] values) {
        JsonArray array = new JsonArray();
        Arrays.stream(values).forEach(array::add);
        return array;
    }

    private JsonArray jsonObjectArray(Stream<JsonObject> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private Optional<TelemetryChartDataBuilder.ChartKind> kindForDockable(Dockable dockable,
                                                                          List<ChartBinding> bindings) {
        return bindings.stream()
                .filter(binding -> binding.dockable() == dockable)
                .map(ChartBinding::kind)
                .findFirst();
    }

    private Optional<TelemetryChartDataBuilder.ChartKind> kindFromName(String name) {
        try {
            return Optional.of(TelemetryChartDataBuilder.ChartKind.valueOf(name));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static final class NodeDetailChartsPane extends Region {
        private final Region first;
        private final Region second;

        private NodeDetailChartsPane(Region first, Region second) {
            this.first = first;
            this.second = second;
            setMinSize(0, 0);
            setSnapToPixel(false);
            getChildren().setAll(first, second);
        }

        @Override
        protected void layoutChildren() {
            double width = getWidth();
            double rowHeight = Math.max(0, (getHeight() - NODE_DETAIL_CHART_GAP) / 2.0);
            first.resizeRelocate(0, 0, width, rowHeight);
            second.resizeRelocate(0, rowHeight + NODE_DETAIL_CHART_GAP, width, rowHeight);
        }

        @Override
        protected double computeMinWidth(double height) {
            return Math.max(first.minWidth(height), second.minWidth(height));
        }

        @Override
        protected double computePrefWidth(double height) {
            return Math.max(first.prefWidth(height), second.prefWidth(height));
        }

        @Override
        protected double computeMinHeight(double width) {
            return first.minHeight(width) + NODE_DETAIL_CHART_GAP + second.minHeight(width);
        }

        @Override
        protected double computePrefHeight(double width) {
            return first.prefHeight(width) + NODE_DETAIL_CHART_GAP + second.prefHeight(width);
        }
    }

    private static final class TelemetryBento extends Bento {
        @Override
        protected ControlsBuilding newControlsBuilding() {
            ControlsBuilding controls = new ControlsBuilding();
            controls.setHeadersFactory(TelemetryHeaders::new);
            controls.setHeaderFactory((dockable, parentPane) -> {
                Header header = new TelemetryHeader(dockable, parentPane).withDragDrop();
                header.prefWidthProperty().bind(parentPane.widthProperty());
                expandHeaderChrome(header);
                header.setOnDragOver(event -> event.consume());
                header.setOnDragDropped(event -> event.consume());
                return header;
            });
            return controls;
        }

        @Override
        protected DockableDragDropBehavior newDragDropBehavior() {
            return new DockableDragDropBehavior() {
                @Override
                public boolean canReceiveDockable(DockContainerLeaf targetContainer, Side targetSide, Dockable dockable) {
                    return targetSide != null;
                }
            };
        }

        private static void expandHeaderChrome(Header header) {
            BorderPane wrapper = header.getChildrenUnmodifiable().stream()
                    .filter(BorderPane.class::isInstance)
                    .map(BorderPane.class::cast)
                    .findFirst()
                    .orElse(null);
            if (wrapper == null || !(wrapper.getCenter() instanceof GridPane grid)) {
                return;
            }

            Node closeWrapper = grid.getChildren().stream()
                    .filter(TelemetryBento::containsCloseButton)
                    .findFirst()
                    .orElse(null);
            List<Node> titleNodes = grid.getChildren().stream()
                    .filter(node -> node != closeWrapper)
                    .toList();
            grid.getChildren().removeAll(titleNodes);
            if (closeWrapper != null) {
                grid.getChildren().remove(closeWrapper);
            }

            HBox titleBox = new HBox(6);
            titleBox.setAlignment(Pos.CENTER);
            titleBox.setMaxWidth(Double.MAX_VALUE);
            titleBox.getChildren().addAll(titleNodes);

            BorderPane titleBar = new BorderPane();
            titleBar.getStyleClass().add("telemetry-dock-titlebar");
            titleBar.setCenter(titleBox);
            BorderPane.setAlignment(titleBox, Pos.CENTER);
            if (closeWrapper != null) {
                Region leftSpacer = new Region();
                if (closeWrapper instanceof Region closeRegion) {
                    leftSpacer.prefWidthProperty().bind(closeRegion.widthProperty());
                }
                titleBar.setLeft(leftSpacer);
                titleBar.setRight(closeWrapper);
                BorderPane.setAlignment(closeWrapper, Pos.CENTER_RIGHT);
            }
            titleBar.prefWidthProperty().bind(header.widthProperty());
            titleBar.prefHeightProperty().bind(header.heightProperty());
            wrapper.setCenter(titleBar);
        }

        private static boolean containsCloseButton(Node node) {
            return node instanceof Pane pane
                    && pane.getChildrenUnmodifiable().stream().anyMatch(Button.class::isInstance);
        }

        private static final class TelemetryHeader extends Header {
            private TelemetryHeader(Dockable dockable, HeaderPane parentPane) {
                super(dockable, parentPane);
            }

            @Override
            protected void layoutChildren() {
                super.layoutChildren();
                getChildren().forEach(child -> child.resizeRelocate(0, 0, getWidth(), getHeight()));
            }
        }

        private static final class TelemetryHeaders extends Headers {
            private TelemetryHeaders(DockContainerLeaf container, Orientation orientation, Side side) {
                super(container, orientation, side);
            }

            @Override
            protected void layoutHorizontal() {
                if (layoutSingleHeader(true)) {
                    return;
                }
                super.layoutHorizontal();
            }

            @Override
            protected void layoutVertical() {
                if (layoutSingleHeader(false)) {
                    return;
                }
                super.layoutVertical();
            }

            private boolean layoutSingleHeader(boolean horizontal) {
                if (getChildren().size() != 1) {
                    return false;
                }

                Node child = getChildren().getFirst();
                if (!child.visibleProperty().isBound()) {
                    child.setManaged(true);
                    child.setVisible(true);
                }

                double width = horizontal ? getWidth() : Math.max(getWidth(), child.getLayoutBounds().getWidth());
                double height = horizontal ? Math.max(getHeight(), child.getLayoutBounds().getHeight()) : getHeight();
                layoutInArea(child, 0, 0, width, height,
                        0, Insets.EMPTY, true, true,
                        HPos.LEFT, VPos.TOP);
                overflowingProperty().set(false);
                return true;
            }
        }
    }

    private void normalizeSingleOpenDockable(DockContainerBranch workspace) {
        List<Dockable> openDockables = workspace.getDockables();
        if (openDockables.size() != 1) {
            return;
        }

        DockContainerLeaf remainingLeaf = openDockables.getFirst().getContainer();
        if (remainingLeaf == null) {
            return;
        }
        if (workspace.getChildContainers().size() == 1
                && workspace.getChildContainers().contains(remainingLeaf)) {
            return;
        }

        DockContainerBranch parent = remainingLeaf.getParentContainer();
        if (parent != null) {
            parent.removeContainer(remainingLeaf);
        }
        if (!workspace.getChildContainers().contains(remainingLeaf)) {
            workspace.addContainer(remainingLeaf);
        }
    }

    static String formatSeriesValue(String seriesName, XYChart.Data<Number, Number> point) {
        if (point.getExtraValue() instanceof Number voltage) {
            String formattedVoltage = String.format(I18n.locale(), "%.2f", voltage.doubleValue());
            return I18n.t("telemetry.chart.value.voltage", formattedVoltage);
        }
        return String.format(I18n.locale(), "%.1f", point.getYValue().doubleValue());
    }

    private void updateChart(List<TelemetryEntry> entries, List<TelemetryEntry> qualityEntries) {
        TelemetryChartDataBuilder.PreparedCharts prepared = TelemetryChartDataBuilder.build(
                basicOnly,
                entries,
                qualityEntries,
                selectedPeriodSeconds
        );

        chartBindings.forEach(binding ->
                applyChart(binding, prepared.payload(binding.kind()), prepared.axisRange(binding.kind())));
        if (basicOnly) {
            TelemetryChartDataBuilder.AxisRange axisRange = prepared.axisRange(TelemetryChartDataBuilder.ChartKind.BASIC);
            applyStandaloneChart(
                    environmentMetricsChart,
                    TelemetryChartDataBuilder.buildEnvironmentMetricsChart(entries),
                    axisRange
            );
            updateEnvironmentPressureOverlay(TelemetryChartDataBuilder.buildEnvironmentPressureChart(entries));
        }
    }

    private void updateEnvironmentPressureOverlay(TelemetryChartDataBuilder.ChartPayload payload) {
        environmentPressureData = payload.series().isEmpty()
                ? List.of()
                : List.copyOf(payload.series().getFirst().getData());
        setPressureAxisRange(environmentPressureData);
        environmentPressureOverlay.requestLayout();
        Platform.runLater(environmentPressureOverlay::requestLayout);
    }

    private void setPressureAxisRange(List<XYChart.Data<Number, Number>> pressureData) {
        if (pressureData.isEmpty()) {
            environmentPressureAxis.setLowerBound(0);
            environmentPressureAxis.setUpperBound(1);
            environmentPressureAxis.setTickUnit(1);
            return;
        }

        DoubleSummaryStatistics pressureStats = pressureData.stream()
                .collect(Collectors.summarizingDouble(data -> data.getYValue().doubleValue()));
        double min = pressureStats.getMin();
        double max = pressureStats.getMax();
        double range = Math.max(max - min, 1.0);
        double padding = Math.max(range * 0.08, 1.0);
        double lower = Math.max(0, Math.floor(min - padding));
        double upper = Math.ceil(max + padding);
        double tickUnit = nicePressureTickUnit((upper - lower) / 5.0);
        lower = Math.floor(lower / tickUnit) * tickUnit;
        upper = Math.ceil(upper / tickUnit) * tickUnit;
        environmentPressureAxis.setLowerBound(lower);
        environmentPressureAxis.setUpperBound(upper);
        environmentPressureAxis.setTickUnit(tickUnit);
    }

    private double nicePressureTickUnit(double rawTickUnit) {
        if (rawTickUnit <= 1) {
            return 1;
        }
        double exponent = Math.pow(10, Math.floor(Math.log10(rawTickUnit)));
        double fraction = rawTickUnit / exponent;
        double niceFraction;
        if (fraction <= 1) {
            niceFraction = 1;
        } else if (fraction <= 2) {
            niceFraction = 2;
        } else if (fraction <= 5) {
            niceFraction = 5;
        } else {
            niceFraction = 10;
        }
        return niceFraction * exponent;
    }

    private void layoutEnvironmentPressureOverlay(Pane overlay) {
        plotArea(environmentMetricsChart)
                .map(plotArea -> overlay.sceneToLocal(plotArea.localToScene(plotArea.getBoundsInLocal())))
                .filter(bounds -> bounds.getWidth() > 0 && bounds.getHeight() > 0)
                .ifPresentOrElse(this::layoutEnvironmentPressurePlot, this::clearEnvironmentPressurePaths);
    }

    private void layoutEnvironmentPressurePlot(Bounds plotBounds) {
        environmentPressurePlotLayer.resizeRelocate(
                plotBounds.getMinX(),
                plotBounds.getMinY(),
                plotBounds.getWidth(),
                plotBounds.getHeight()
        );
        environmentPressureClip.setWidth(plotBounds.getWidth());
        environmentPressureClip.setHeight(plotBounds.getHeight());
        environmentPressureAxis.resizeRelocate(
                plotBounds.getMaxX(),
                plotBounds.getMinY(),
                NODE_DETAIL_RIGHT_AXIS_WIDTH,
                plotBounds.getHeight()
        );
        drawEnvironmentPressurePaths(plotBounds.getHeight());
    }

    private void drawEnvironmentPressurePaths(double plotHeight) {
        clearEnvironmentPressurePaths();
        if (environmentPressureData.isEmpty()) {
            return;
        }

        NumberAxis xAxis = (NumberAxis) environmentMetricsChart.getXAxis();
        List<Point2D> points = environmentPressureData.stream()
                .map(data -> new Point2D(
                        xAxis.getDisplayPosition(data.getXValue()),
                        environmentPressureAxis.getDisplayPosition(data.getYValue())
                ))
                .filter(point -> Double.isFinite(point.getX()) && Double.isFinite(point.getY()))
                .toList();
        if (points.isEmpty()) {
            return;
        }

        Point2D first = points.getFirst();
        Point2D last = points.getLast();
        environmentPressureLine.getElements().add(new MoveTo(first.getX(), first.getY()));
        environmentPressureArea.getElements().add(new MoveTo(first.getX(), plotHeight));
        environmentPressureArea.getElements().add(new LineTo(first.getX(), first.getY()));
        for (int index = 1; index < points.size(); index++) {
            Point2D point = points.get(index);
            environmentPressureLine.getElements().add(new LineTo(point.getX(), point.getY()));
            environmentPressureArea.getElements().add(new LineTo(point.getX(), point.getY()));
        }
        environmentPressureArea.getElements().add(new LineTo(last.getX(), plotHeight));
        environmentPressureArea.getElements().add(new ClosePath());
    }

    private void clearEnvironmentPressurePaths() {
        environmentPressureLine.getElements().clear();
        environmentPressureArea.getElements().clear();
    }

    private void applyChart(ChartBinding binding,
                            TelemetryChartDataBuilder.ChartPayload payload,
                            TelemetryChartDataBuilder.AxisRange axisRange) {
        AreaChart<Number, Number> areaChart = binding.chart();
        applyStandaloneChart(areaChart, payload, axisRange);
        if (binding.dockable() != null) {
            areaChart.setTitle(null);
            binding.dockable().setTitle(payload.title());
        }
    }

    private void applyStandaloneChart(AreaChart<Number, Number> areaChart,
                                      TelemetryChartDataBuilder.ChartPayload payload,
                                      TelemetryChartDataBuilder.AxisRange axisRange) {
        setXAxisRange((NumberAxis) areaChart.getXAxis(), axisRange);
        applyStandaloneChartTitle(areaChart, payload.title());
        areaChart.getData().setAll(payload.series());
        updateNodeDetailLegend(areaChart, payload.series());
    }

    private void applyStandaloneChartTitle(AreaChart<Number, Number> areaChart, String title) {
        if (basicOnly && areaChart == chart) {
            nodeDetailBasicTitleLabel.setText(title);
            areaChart.setTitle(null);
        } else if (basicOnly && areaChart == environmentMetricsChart) {
            nodeDetailEnvironmentTitleLabel.setText(title);
            areaChart.setTitle(null);
        } else {
            areaChart.setTitle(title);
        }
    }

    private void updateNodeDetailLegend(AreaChart<Number, Number> areaChart,
                                        List<XYChart.Series<Number, Number>> series) {
        if (!basicOnly) {
            return;
        }
        if (areaChart == chart) {
            updateNodeDetailLegend(nodeDetailBasicLegend, series, BASIC_CHART_COLORS);
        } else if (areaChart == environmentMetricsChart) {
            updateNodeDetailLegend(nodeDetailEnvironmentLegend, series, ENVIRONMENT_CHART_COLORS);
        }
    }

    private void updateNodeDetailLegend(HBox legend,
                                        List<XYChart.Series<Number, Number>> series,
                                        List<String> colors) {
        legend.getChildren().clear();
        for (int index = 0; index < series.size(); index++) {
            String color = colors.get(Math.min(index, colors.size() - 1));
            legend.getChildren().add(nodeDetailLegendItem(series.get(index).getName(), color));
        }
    }

    private Node nodeDetailLegendItem(String text, String color) {
        Region symbol = new Region();
        symbol.getStyleClass().add("telemetry-node-detail-chart-legend-symbol");
        symbol.setStyle("-fx-background-color: " + color + ";");

        Label label = new Label(text);
        label.getStyleClass().add("telemetry-node-detail-chart-legend-label");

        HBox item = new HBox(4, symbol, label);
        item.getStyleClass().add("telemetry-node-detail-chart-legend-item");
        item.setAlignment(Pos.CENTER);
        return item;
    }

    private void setXAxisRange(NumberAxis axis, TelemetryChartDataBuilder.AxisRange range) {
        axis.setAutoRanging(false);
        axis.setLowerBound(range.lowerBound());
        axis.setUpperBound(range.upperBound());
        axis.setTickUnit(range.tickUnit());
    }

    private Region createPeriodBar() {
        List<ToggleButton> buttons = PERIOD_OPTIONS.stream()
                .map(this::periodButton)
                .toList();
        buttons.stream()
                .filter(button -> Objects.equals(button.getUserData(), PERIOD_6H))
                .findFirst()
                .ifPresent(button -> button.setSelected(true));

        HBox periodButtons = new HBox(0);
        periodButtons.getStyleClass().add("telemetry-period-buttons");
        periodButtons.getChildren().setAll(buttons);
        periodButtons.setAlignment(Pos.CENTER_LEFT);

        BorderPane bar = new BorderPane();
        bar.getStyleClass().add("telemetry-period-bar");
        bar.setLeft(periodButtons);
        if (!basicOnly) {
            bar.setRight(createResetLayoutButton());
        }
        bar.setMaxWidth(Double.MAX_VALUE);
        return bar;
    }

    private Button createResetLayoutButton() {
        Button button = new Button(I18n.t("telemetry.dock.resetLayout"));
        button.getStyleClass().add("telemetry-dock-reset-layout-button");
        button.setTooltip(new Tooltip(I18n.t("telemetry.dock.resetLayout.tooltip")));
        button.setPadding(new Insets(6, 16, 6, 16));
        button.setOnAction(event -> resetDockLayout());
        return button;
    }

    private ToggleButton periodButton(PeriodOption option) {
        ToggleButton button = new ToggleButton(I18n.t(option.labelKey()));
        button.setToggleGroup(periodGroup);
        button.setUserData(option.seconds());
        button.setPadding(new Insets(6, 16, 6, 16));
        button.setStyle("-fx-background-radius: 0; -fx-font-size: 12px;");
        button.setOnAction(event -> {
            selectedPeriodSeconds = option.seconds();
            refresh();
        });
        return button;
    }

    /**
     * Wraps the AreaChart in a StackPane and overlays the vertical cursor and label.
     */
    private StackPane wrapWithOverlay(AreaChart<Number, Number> areaChart) {
        Line crosshair = new Line();
        crosshair.setStyle("-fx-stroke: white; -fx-opacity: 0.6; -fx-stroke-width: 1;");
        crosshair.setMouseTransparent(true);
        crosshair.setVisible(false);
        crosshair.setManaged(false);

        Label valueLabel = new Label();
        valueLabel.setStyle("-fx-background-color: rgba(0,0,0,0.85); -fx-text-fill: white; "
                + "-fx-padding: 6; -fx-font-size: 11px; -fx-font-family: monospace; -fx-background-radius: 4;");
        valueLabel.setMouseTransparent(true);
        valueLabel.setVisible(false);
        valueLabel.setManaged(false);

        StackPane wrapper = new StackPane(areaChart, crosshair, valueLabel);
        StackPane.setAlignment(crosshair, Pos.TOP_LEFT);
        StackPane.setAlignment(valueLabel, Pos.TOP_LEFT);

        installCrosshair(areaChart, crosshair, valueLabel, wrapper);
        return wrapper;
    }

    /**
     * Installs mouse handlers on the chart plot area.
     * {@link Platform#runLater(Runnable)} is used so {@code chart.lookup()} runs
     * after the chart is attached to the scene graph.
     */
    private void installCrosshair(AreaChart<Number, Number> areaChart,
                                  Line crosshair,
                                  Label valueLabel,
                                  StackPane wrapper) {
        areaChart.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                clearCrosshairHandlers(areaChart);
                return;
            }
            Platform.runLater(() -> {
                if (areaChart.getScene() != newScene) {
                    return;
                }
                resolveInteractiveNodes(areaChart)
                        .ifPresent(nodes -> installCrosshairHandlers(areaChart, crosshair, valueLabel, wrapper, nodes));
            });
        });
    }

    private Optional<InteractiveNodes> resolveInteractiveNodes(AreaChart<Number, Number> areaChart) {
        return plotArea(areaChart)
                .flatMap(plotArea -> chartContent(areaChart).map(chartContent -> new InteractiveNodes(plotArea, chartContent)));
    }

    private Optional<Region> plotArea(AreaChart<Number, Number> areaChart) {
        return Optional.ofNullable(areaChart.lookup(".chart-plot-background"))
                .filter(Region.class::isInstance)
                .map(Region.class::cast);
    }

    private Optional<Node> chartContent(AreaChart<Number, Number> areaChart) {
        return Optional.ofNullable(areaChart.lookup(".chart-content"));
    }

    private void installCrosshairHandlers(AreaChart<Number, Number> areaChart,
                                          Line crosshair,
                                          Label valueLabel,
                                          StackPane wrapper,
                                          InteractiveNodes nodes) {
        clearCrosshairHandlers(areaChart);

        EventHandler<MouseEvent> clickHandler = event -> {
            Point2D local = nodes.plotArea().sceneToLocal(event.getSceneX(), event.getSceneY());
            if (nodes.plotArea().contains(local)) {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    toggleDockMaximize(areaChart);
                    event.consume();
                    return;
                }
                showCrosshair(local.getX(), areaChart, crosshair, valueLabel, wrapper, nodes.plotArea());
            }
        };

        EventHandler<MouseEvent> moveHandler = event -> {
            Point2D local = nodes.plotArea().sceneToLocal(event.getSceneX(), event.getSceneY());
            if (!nodes.plotArea().contains(local)) {
                crosshair.setVisible(false);
                valueLabel.setVisible(false);
            }
        };

        nodes.chartContent().addEventFilter(MouseEvent.MOUSE_CLICKED, clickHandler);
        nodes.chartContent().addEventFilter(MouseEvent.MOUSE_MOVED, moveHandler);
        areaChart.getProperties().put(CROSSHAIR_FILTERS_KEY,
                new CrosshairFilters(nodes.chartContent(), clickHandler, moveHandler));
    }

    private static void clearCrosshairHandlers(AreaChart<Number, Number> areaChart) {
        Object previous = areaChart.getProperties().remove(CROSSHAIR_FILTERS_KEY);
        if (previous instanceof CrosshairFilters filters) {
            filters.detach();
        }
    }

    private void showCrosshair(double localX,
                               AreaChart<Number, Number> areaChart,
                               Line crosshair,
                               Label valueLabel,
                               StackPane wrapper,
                               Region plotArea) {
        boolean hasData = areaChart.getData().stream().anyMatch(series -> !series.getData().isEmpty());
        if (!hasData) {
            crosshair.setVisible(false);
            valueLabel.setVisible(false);
            return;
        }

        NumberAxis xAxis = (NumberAxis) areaChart.getXAxis();
        long epoch = xAxis.getValueForDisplay(localX).longValue();
        String timeStr = Instant.ofEpochSecond(epoch)
                .atZone(ZoneId.systemDefault())
                .format(AXIS_FMT);

        String valuesText = areaChart.getData().stream()
                .map(series -> nearestPointLabel(series, epoch))
                .flatMap(Optional::stream)
                .collect(Collectors.joining("\n"));
        valueLabel.setText(Stream.of(timeStr, valuesText)
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n")));

        Bounds plotBounds = plotArea.localToScene(plotArea.getBoundsInLocal());
        Bounds wrapperBounds = wrapper.sceneToLocal(plotBounds);

        double xInWrapper = wrapperBounds.getMinX() + localX;
        double plotTop = wrapperBounds.getMinY();
        double plotHeight = wrapperBounds.getHeight();

        crosshair.setStartX(xInWrapper);
        crosshair.setStartY(plotTop);
        crosshair.setEndX(xInWrapper);
        crosshair.setEndY(plotTop + plotHeight);
        crosshair.setVisible(true);

        double labelX = xInWrapper + 8;
        double wrapperWidth = wrapper.getWidth();
        valueLabel.autosize();
        if (labelX + valueLabel.prefWidth(-1) > wrapperWidth - 10) {
            labelX = xInWrapper - valueLabel.prefWidth(-1) - 8;
        }
        valueLabel.setTranslateX(labelX);
        valueLabel.setTranslateY(plotTop + 10);
        valueLabel.setVisible(true);
    }

    private static Optional<String> nearestPointLabel(XYChart.Series<Number, Number> series, long targetEpoch) {
        return findNearest(series, targetEpoch)
                .map(nearest -> series.getName() + ": " + formatSeriesValue(series.getName(), nearest));
    }

    /**
     * Finds the closest point in a series along the X axis.
     */
    private static Optional<XYChart.Data<Number, Number>> findNearest(XYChart.Series<Number, Number> series,
                                                                      long targetEpoch) {
        return series.getData().stream()
                .min(Comparator.comparingLong(data ->
                        Math.abs(data.getXValue().longValue() - targetEpoch)));
    }
}
