package com.meshtastic.client.components;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.service.NodeCacheService;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Переиспользуемый компонент: AreaChart телеметрии + панель фильтра по периоду.
 * <p>
 * Используется в FormDashboard (телеметрия локальной ноды) и в FormNodes (телеметрия выбранной ноды).
 * Жизненный цикл: {@link #bind(DeviceState, String)} / {@link #unbind()}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class TelemetryChartPanel extends VBox {

    private static final DateTimeFormatter AXIS_FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private static final long PERIOD_1H = 3600;
    private static final long PERIOD_6H = 6L * 3600;
    private static final long PERIOD_12H = 12L * 3600;
    private static final long PERIOD_24H = 24L * 3600;
    private static final long PERIOD_48H = 48L * 3600;
    private static final long PERIOD_1W = 7L * 86400;
    private static final long PERIOD_MAX = 0;

    private static final List<PeriodOption> PERIOD_OPTIONS = List.of(
            new PeriodOption("1ч", PERIOD_1H),
            new PeriodOption("6ч", PERIOD_6H),
            new PeriodOption("12ч", PERIOD_12H),
            new PeriodOption("24ч", PERIOD_24H),
            new PeriodOption("48ч", PERIOD_48H),
            new PeriodOption("1нед", PERIOD_1W),
            new PeriodOption("Всё", PERIOD_MAX)
    );

    private final boolean basicOnly;
    private final AreaChart<Number, Number> chart;
    private final AreaChart<Number, Number> rxChart;
    private final AreaChart<Number, Number> rateChart;
    private final AreaChart<Number, Number> txChart;
    private final AreaChart<Number, Number> qualityChart;
    private final AreaChart<Number, Number> hopsChart;
    private final List<ChartBinding> chartBindings;
    private final ToggleGroup periodGroup = new ToggleGroup();

    private long selectedPeriodSeconds = PERIOD_6H;
    private BindingState bindingState = Unbound.INSTANCE;
    private final Runnable telemetryListener = () -> Platform.runLater(this::refresh);

    /** Callback, вызываемый после обновления данных (для синхронизации таблицы логов) */
    private Runnable onDataRefreshed = () -> {};

    /** Последние отфильтрованные записи (для использования в таблице логов) */
    private List<TelemetryEntry> filteredEntries = List.of();

    private record PeriodOption(String label, long seconds) {}

    private record ChartBinding(TelemetryChartDataBuilder.ChartKind kind, AreaChart<Number, Number> chart) {}

    private record InteractiveNodes(Region plotArea, Node chartContent) {}

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
        setSpacing(6);

        chart = createSizedChart(false);
        rxChart = createStyledChart(false, "/css/chart-green-red-yellow.css");
        rateChart = createStyledChart(true, "/css/chart-green-red-yellow.css");
        txChart = createStyledChart(true, "/css/chart-tx.css");
        qualityChart = createStyledChart(true, "/css/chart-quality.css");
        hopsChart = createStyledChart(true, "/css/chart-hops.css");
        chartBindings = basicOnly
                ? List.of(new ChartBinding(TelemetryChartDataBuilder.ChartKind.BASIC, chart))
                : List.of(
                new ChartBinding(TelemetryChartDataBuilder.ChartKind.BASIC, chart),
                new ChartBinding(TelemetryChartDataBuilder.ChartKind.RX, rxChart),
                new ChartBinding(TelemetryChartDataBuilder.ChartKind.RATE, rateChart),
                new ChartBinding(TelemetryChartDataBuilder.ChartKind.TX, txChart),
                new ChartBinding(TelemetryChartDataBuilder.ChartKind.QUALITY, qualityChart),
                new ChartBinding(TelemetryChartDataBuilder.ChartKind.HOPS, hopsChart)
        );

        getChildren().addAll(Stream.concat(createContent().stream(), Stream.of(createPeriodBar())).toList());
        updateChart(List.of(), List.of());
    }

    /**
     * Устанавливает callback, вызываемый после каждого обновления данных графика.
     * Используется FormDashboard для синхронизации таблицы логов.
     */
    public void setOnDataRefreshed(Runnable callback) {
        onDataRefreshed = Objects.requireNonNullElse(callback, () -> {});
    }

    /**
     * Привязать компонент к состоянию устройства и идентификатору ноды.
     * Подписывается на обновления телеметрии и загружает данные.
     * Если уже привязан к тому же state и nodeId — просто обновляет данные без переподписки.
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
     * Отвязать от текущего DeviceState и очистить график.
     */
    public void unbind() {
        currentBinding().ifPresent(bound -> bound.state().removeTelemetryListener(telemetryListener));
        bindingState = Unbound.INSTANCE;
        filteredEntries = List.of();
        updateChart(List.of(), List.of());
    }

    /**
     * Возвращает последние отфильтрованные записи телеметрии.
     * Используется FormDashboard для таблицы логов.
     */
    public List<TelemetryEntry> getFilteredEntries() {
        return filteredEntries;
    }

    private List<Node> createContent() {
        return basicOnly
                ? List.of(basicChartWrap())
                : List.of(
                createChartRow(chart, rxChart),
                createChartRow(rateChart, txChart),
                createChartRow(qualityChart, hopsChart)
        );
    }

    private StackPane basicChartWrap() {
        StackPane chartWrap = wrapWithOverlay(chart);
        VBox.setVgrow(chartWrap, Priority.ALWAYS);
        return chartWrap;
    }

    private void refresh() {
        switch (bindingState) {
            case Bound bound -> refresh(bound);
            case Unbound ignored -> {
                filteredEntries = List.of();
                updateChart(List.of(), List.of());
            }
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
            case Unbound ignored -> Optional.empty();
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

    private HBox createChartRow(AreaChart<Number, Number> leftChart, AreaChart<Number, Number> rightChart) {
        StackPane leftWrap = wrapWithOverlay(leftChart);
        StackPane rightWrap = wrapWithOverlay(rightChart);
        HBox.setHgrow(leftWrap, Priority.ALWAYS);
        HBox.setHgrow(rightWrap, Priority.ALWAYS);

        HBox row = new HBox(6, leftWrap, rightWrap);
        VBox.setVgrow(row, Priority.ALWAYS);
        return row;
    }

    static String formatSeriesValue(String seriesName, XYChart.Data<Number, Number> point) {
        if (TelemetryChartDataBuilder.SERIES_VOLTAGE.equals(seriesName)
                && point.getExtraValue() instanceof Number voltage) {
            return String.format("%.2fV", voltage.doubleValue());
        }
        return String.format("%.1f", point.getYValue().doubleValue());
    }

    private void updateChart(List<TelemetryEntry> entries, List<TelemetryEntry> qualityEntries) {
        TelemetryChartDataBuilder.PreparedCharts prepared = TelemetryChartDataBuilder.build(
                basicOnly,
                entries,
                qualityEntries,
                selectedPeriodSeconds
        );

        chartBindings.forEach(binding ->
                applyChart(binding.chart(), prepared.payload(binding.kind()), prepared.axisRange()));
    }

    private void applyChart(AreaChart<Number, Number> areaChart,
                            TelemetryChartDataBuilder.ChartPayload payload,
                            TelemetryChartDataBuilder.AxisRange axisRange) {
        setXAxisRange((NumberAxis) areaChart.getXAxis(), axisRange);
        areaChart.setTitle(payload.title());
        areaChart.getData().setAll(payload.series());
    }

    private void setXAxisRange(NumberAxis axis, TelemetryChartDataBuilder.AxisRange range) {
        axis.setAutoRanging(false);
        axis.setLowerBound(range.lowerBound());
        axis.setUpperBound(range.upperBound());
        axis.setTickUnit(range.tickUnit());
    }

    private HBox createPeriodBar() {
        List<ToggleButton> buttons = PERIOD_OPTIONS.stream()
                .map(this::periodButton)
                .toList();
        buttons.stream()
                .filter(button -> Objects.equals(button.getUserData(), PERIOD_6H))
                .findFirst()
                .ifPresent(button -> button.setSelected(true));

        HBox bar = new HBox(0);
        bar.getChildren().setAll(buttons);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-border-color: -color-border-default; -fx-border-radius: 6; -fx-background-radius: 6;");
        return bar;
    }

    private ToggleButton periodButton(PeriodOption option) {
        ToggleButton button = new ToggleButton(option.label());
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
     * Оборачивает AreaChart в StackPane и накладывает вертикальный курсор + label.
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
     * Устанавливает обработчики мыши на plot-area графика.
     * Используем Platform.runLater чтобы chart.lookup() работал после добавления в Scene Graph.
     */
    private void installCrosshair(AreaChart<Number, Number> areaChart,
                                  Line crosshair,
                                  Label valueLabel,
                                  StackPane wrapper) {
        areaChart.sceneProperty().addListener((obs, oldScene, newScene) ->
                Optional.ofNullable(newScene).ifPresent(scene -> Platform.runLater(() ->
                        resolveInteractiveNodes(areaChart)
                                .ifPresent(nodes -> installCrosshairHandlers(areaChart, crosshair, valueLabel, wrapper, nodes)))));
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
        nodes.chartContent().addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            Point2D local = nodes.plotArea().sceneToLocal(event.getSceneX(), event.getSceneY());
            if (nodes.plotArea().contains(local)) {
                showCrosshair(local.getX(), areaChart, crosshair, valueLabel, wrapper, nodes.plotArea());
            }
        });

        nodes.chartContent().addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            Point2D local = nodes.plotArea().sceneToLocal(event.getSceneX(), event.getSceneY());
            if (!nodes.plotArea().contains(local)) {
                crosshair.setVisible(false);
                valueLabel.setVisible(false);
            }
        });
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
     * Находит ближайшую по оси X точку в серии.
     */
    private static Optional<XYChart.Data<Number, Number>> findNearest(XYChart.Series<Number, Number> series,
                                                                      long targetEpoch) {
        return series.getData().stream()
                .min(Comparator.comparingLong(data ->
                        Math.abs(data.getXValue().longValue() - targetEpoch)));
    }
}
