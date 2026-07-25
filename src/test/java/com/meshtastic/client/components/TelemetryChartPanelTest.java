package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.forms.FormDashboard;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.meshtastic.proto.MeshProtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.bentofx.control.Header;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerBranch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class TelemetryChartPanelTest {
    private static final List<TelemetryChartDataBuilder.ChartKind> DEFAULT_OPEN_CHARTS = List.of(
            TelemetryChartDataBuilder.ChartKind.BASIC,
            TelemetryChartDataBuilder.ChartKind.RX,
            TelemetryChartDataBuilder.ChartKind.RATE,
            TelemetryChartDataBuilder.ChartKind.TX,
            TelemetryChartDataBuilder.ChartKind.QUALITY,
            TelemetryChartDataBuilder.ChartKind.HOPS
    );
    private static final List<TelemetryChartDataBuilder.ChartKind> DEFAULT_MINIMIZED_CHARTS = List.of(
            TelemetryChartDataBuilder.ChartKind.TEMPERATURE,
            TelemetryChartDataBuilder.ChartKind.HUMIDITY,
            TelemetryChartDataBuilder.ChartKind.PRESSURE,
            TelemetryChartDataBuilder.ChartKind.RADIATION
    );
    private static final long RX_NORMALIZATION_SAMPLE_TIME = 1_700_000_000L;
    private static final long RX_NORMALIZATION_NEXT_SAMPLE_TIME = 1_700_000_060L;
    private static final int LOG_RX_RECEIVED = 10;
    private static final int LOG_RX_BAD = 15;
    private static final int LOG_RX_DUPLICATE = 5;
    private static final int CHART_RX_PREVIOUS_RECEIVED = 100;
    private static final int CHART_RX_PREVIOUS_BAD = 20;
    private static final int CHART_RX_PREVIOUS_DUPLICATE = 10;
    private static final int CHART_RX_CURRENT_RECEIVED = 110;
    private static final int CHART_RX_CURRENT_BAD = 35;
    private static final int CHART_RX_CURRENT_DUPLICATE = 15;
    private static final int NO_TX_COUNTER = 0;
    private static final double ZERO_PERCENT = 0.0;
    private static final double EXPECTED_RX_BAD_PERCENT = 75.0;
    private static final double EXPECTED_RX_DUPLICATE_PERCENT = 25.0;

    private record NodeDetailLayout(Bounds basicPlot,
                                    Bounds environmentPlot,
                                    Bounds basicTitle,
                                    Bounds environmentTitle,
                                    Bounds basicFrame,
                                    Bounds environmentFrame) {}

    private static String originalTelemetryDockLayout;

    @TempDir
    private static Path tempHome;

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.ensureJavaFxStarted();
        originalTelemetryDockLayout = AppPreferences.getTelemetryDockLayout();
        AppPreferences.saveTelemetryDockLayout(null);
    }

    @BeforeEach
    void clearTelemetryDockLayout() {
        AppPreferences.saveTelemetryDockLayout(null);
    }

    @AfterAll
    static void restoreTelemetryDockLayout() {
        AppPreferences.saveTelemetryDockLayout(originalTelemetryDockLayout);
    }

    @Test
    void initializesAllTelemetryChartsBeforeAnyDataArrives() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));

        AreaChart<Number, Number> basicChart = chartField(panel, "chart");
        AreaChart<Number, Number> rxChart = chartField(panel, "rxChart");
        AreaChart<Number, Number> rateChart = chartField(panel, "rateChart");
        AreaChart<Number, Number> txChart = chartField(panel, "txChart");
        AreaChart<Number, Number> qualityChart = chartField(panel, "qualityChart");
        AreaChart<Number, Number> hopsChart = chartField(panel, "hopsChart");
        AreaChart<Number, Number> temperatureChart = chartField(panel, "temperatureChart");
        AreaChart<Number, Number> humidityChart = chartField(panel, "humidityChart");
        AreaChart<Number, Number> pressureChart = chartField(panel, "pressureChart");
        AreaChart<Number, Number> radiationChart = chartField(panel, "radiationChart");

        assertNull(basicChart.getTitle());
        assertEquals(t("telemetry.chart.title.basic"),
                dockTitle(panel, TelemetryChartDataBuilder.ChartKind.BASIC));
        assertEquals(labels(
                "telemetry.chart.series.voltage",
                "telemetry.chart.series.battery",
                "telemetry.chart.series.chUtil",
                "telemetry.chart.series.airUtil"), seriesNames(basicChart));

        assertNull(rxChart.getTitle());
        assertEquals(t("telemetry.chart.title.rx"),
                dockTitle(panel, TelemetryChartDataBuilder.ChartKind.RX));
        assertEquals(labels(
                "telemetry.chart.series.goodRx",
                "telemetry.chart.series.badRx",
                "telemetry.chart.series.dupeRx"), seriesNames(rxChart));

        assertNull(rateChart.getTitle());
        assertEquals(t("telemetry.chart.title.rate"),
                dockTitle(panel, TelemetryChartDataBuilder.ChartKind.RATE));
        assertEquals(labels(
                "telemetry.chart.series.packetsReceived",
                "telemetry.chart.series.badPackets",
                "telemetry.chart.series.duplicates"), seriesNames(rateChart));

        assertNull(txChart.getTitle());
        assertEquals(t("telemetry.chart.title.tx"),
                dockTitle(panel, TelemetryChartDataBuilder.ChartKind.TX));
        assertEquals(labels(
                "telemetry.chart.series.packetsTransmitted",
                "telemetry.chart.series.dropped",
                "telemetry.chart.series.relayed",
                "telemetry.chart.series.relayCanceled"), seriesNames(txChart));

        assertNull(qualityChart.getTitle());
        assertEquals(t("telemetry.chart.title.quality"),
                dockTitle(panel, TelemetryChartDataBuilder.ChartKind.QUALITY));
        assertEquals(labels(
                "telemetry.chart.series.snr",
                "telemetry.chart.series.rssi"), seriesNames(qualityChart));

        assertNull(hopsChart.getTitle());
        assertEquals(t("telemetry.chart.title.hops"),
                dockTitle(panel, TelemetryChartDataBuilder.ChartKind.HOPS));
        assertEquals(labels(
                "telemetry.chart.series.hopsMax",
                "telemetry.chart.series.hopsMin",
                "telemetry.chart.series.hopsAvg"), seriesNames(hopsChart));

        assertNull(temperatureChart.getTitle());
        assertEquals(t("telemetry.chart.title.temperature"),
                dockTitle(panel, TelemetryChartDataBuilder.ChartKind.TEMPERATURE));
        assertEquals(labels("telemetry.chart.series.temperature"), seriesNames(temperatureChart));

        assertNull(humidityChart.getTitle());
        assertEquals(t("telemetry.chart.title.humidity"),
                dockTitle(panel, TelemetryChartDataBuilder.ChartKind.HUMIDITY));
        assertEquals(labels("telemetry.chart.series.humidity"), seriesNames(humidityChart));

        assertNull(pressureChart.getTitle());
        assertEquals(t("telemetry.chart.title.pressure"),
                dockTitle(panel, TelemetryChartDataBuilder.ChartKind.PRESSURE));
        assertEquals(labels("telemetry.chart.series.pressure"), seriesNames(pressureChart));

        assertNull(radiationChart.getTitle());
        assertEquals(t("telemetry.chart.title.radiation"),
                dockTitle(panel, TelemetryChartDataBuilder.ChartKind.RADIATION));
        assertEquals(labels("telemetry.chart.series.radiation"), seriesNames(radiationChart));

        assertFalse(((NumberAxis) basicChart.getXAxis()).isAutoRanging());
        assertFalse(((NumberAxis) rxChart.getXAxis()).isAutoRanging());
        assertFalse(((NumberAxis) qualityChart.getXAxis()).isAutoRanging());
        assertFalse(((NumberAxis) temperatureChart.getXAxis()).isAutoRanging());
    }

    @Test
    void dockedTelemetryChartsStartAsSeparateWindowsAndRejectTabDrops() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));
        Dockable basic = dockable(panel, TelemetryChartDataBuilder.ChartKind.BASIC);
        Dockable rx = dockable(panel, TelemetryChartDataBuilder.ChartKind.RX);

        assertNotSame(basic.getContainer(), rx.getContainer());
        for (TelemetryChartDataBuilder.ChartKind kind : DEFAULT_OPEN_CHARTS) {
            Dockable dockable = dockable(panel, kind);
            assertNotNull(dockable.getContainer());
            Header header = dockable.getContainer().getHeader(dockable);
            assertNotNull(header);
            assertTrue(header.prefWidthProperty().isBound());
            assertTrue(header.getClass().getName().contains("TelemetryHeader"));
            assertTrue(header.getParent().getClass().getName().contains("TelemetryHeaders"));
            BorderPane wrapper = assertInstanceOf(BorderPane.class, header.getChildrenUnmodifiable().getFirst());
            BorderPane titleBar = assertInstanceOf(BorderPane.class, wrapper.getCenter());
            assertNotNull(titleBar.getLeft());
            assertNotNull(titleBar.getRight());
            assertEquals(1, dockable.getContainer().getDockables().size());
        }
        for (TelemetryChartDataBuilder.ChartKind kind : DEFAULT_MINIMIZED_CHARTS) {
            assertNull(dockable(panel, kind).getContainer());
        }
        assertEquals(DEFAULT_MINIMIZED_CHARTS.size(), minimizedBar(panel).getChildren().size());
        assertFalse(rx.getContainer().canReceiveDockable(basic, null));
        assertTrue(rx.getContainer().canReceiveDockable(basic, Side.RIGHT));
    }

    @Test
    void defaultDockLayoutUsesEqualChartSizes() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));
        onFxThread(() -> null);
        onFxThread(() -> null);

        DockContainerBranch workspace = workspace(panel);
        assertDividerPositions(new double[] {1.0 / 3.0, 2.0 / 3.0}, workspace.getDividerPositions());
        for (var row : workspace.getChildContainers()) {
            DockContainerBranch rowBranch = assertInstanceOf(DockContainerBranch.class, row);
            assertDividerPositions(new double[] {0.5}, rowBranch.getDividerPositions());
        }
    }

    @Test
    void closingDockedTelemetryChartMovesItToMinimizedBar() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));
        Dockable dockable = dockable(panel, TelemetryChartDataBuilder.ChartKind.BASIC);

        onFxThread(() -> {
            dockable.getContainer().closeDockable(dockable);
            return null;
        });
        onFxThread(() -> null);

        HBox minimizedBar = minimizedBar(panel);
        Button restoreButton = restoreButtonFor(minimizedBar, dockable);
        assertNull(dockable.getContainer());
        assertFalse(dockable.isClosable());
        assertTrue(minimizedBar.isVisible());
        assertSame(dockable, restoreButton.getUserData());
        assertEquals(DEFAULT_MINIMIZED_CHARTS.size() + 1, minimizedBar.getChildren().size());
    }

    @Test
    void defaultMinimizedTelemetryChartCanBeRestoredAsDockWindow() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));
        Dockable dockable = dockable(panel, TelemetryChartDataBuilder.ChartKind.TEMPERATURE);
        HBox minimizedBar = minimizedBar(panel);

        assertNull(dockable.getContainer());

        onFxThread(() -> {
            restoreButtonFor(minimizedBar, dockable).fire();
            return null;
        });

        assertNotNull(dockable.getContainer());
        assertTrue(dockable.isClosable());
        assertEquals(1, dockable.getContainer().getDockables().size());
        assertEquals(DEFAULT_MINIMIZED_CHARTS.size() - 1, minimizedBar.getChildren().size());
    }

    @Test
    void minimizedTelemetryChartCanBeRestoredAndClosedAgain() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));
        Dockable dockable = dockable(panel, TelemetryChartDataBuilder.ChartKind.BASIC);
        HBox minimizedBar = minimizedBar(panel);

        onFxThread(() -> {
            dockable.getContainer().closeDockable(dockable);
            return null;
        });
        onFxThread(() -> null);

        onFxThread(() -> {
            restoreButtonFor(minimizedBar, dockable).fire();
            return null;
        });

        assertNotNull(dockable.getContainer());
        assertTrue(dockable.isClosable());
        assertEquals(1, dockable.getContainer().getDockables().size());
        assertEquals(DEFAULT_MINIMIZED_CHARTS.size(), minimizedBar.getChildren().size());
        assertTrue(minimizedBar.isVisible());

        onFxThread(() -> {
            dockable.getContainer().closeDockable(dockable);
            return null;
        });
        onFxThread(() -> null);

        assertNull(dockable.getContainer());
        assertEquals(DEFAULT_MINIMIZED_CHARTS.size() + 1, minimizedBar.getChildren().size());
    }

    @Test
    void dockCloseListenersStaySingleAcrossLayoutResetsAndRestoreCycles() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));
        Dockable dockable = dockable(panel, TelemetryChartDataBuilder.ChartKind.BASIC);
        HBox minimizedBar = minimizedBar(panel);

        assertEquals(1, closeListenerCount(dockable));
        assertEquals(DEFAULT_OPEN_CHARTS.size() + DEFAULT_MINIMIZED_CHARTS.size(),
                dockCloseListenerRegistrationCount(panel));

        onFxThread(() -> {
            resetLayoutButton(panel).fire();
            resetLayoutButton(panel).fire();
            return null;
        });

        assertEquals(1, closeListenerCount(dockable));
        assertEquals(DEFAULT_OPEN_CHARTS.size() + DEFAULT_MINIMIZED_CHARTS.size(),
                dockCloseListenerRegistrationCount(panel));

        onFxThread(() -> {
            dockable.getContainer().closeDockable(dockable);
            return null;
        });
        onFxThread(() -> null);

        assertEquals(0, closeListenerCount(dockable));
        assertEquals(DEFAULT_OPEN_CHARTS.size() + DEFAULT_MINIMIZED_CHARTS.size() - 1,
                dockCloseListenerRegistrationCount(panel));

        onFxThread(() -> {
            restoreButtonFor(minimizedBar, dockable).fire();
            resetLayoutButton(panel).fire();
            return null;
        });

        assertEquals(1, closeListenerCount(dockable));
        assertEquals(DEFAULT_OPEN_CHARTS.size() + DEFAULT_MINIMIZED_CHARTS.size(),
                dockCloseListenerRegistrationCount(panel));
    }

    @Test
    void lastOpenTelemetryChartOccupiesWorkspace() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));
        Dockable remaining = dockable(panel, TelemetryChartDataBuilder.ChartKind.HOPS);

        onFxThread(() -> {
            dockable(panel, TelemetryChartDataBuilder.ChartKind.BASIC).getContainer()
                    .closeDockable(dockable(panel, TelemetryChartDataBuilder.ChartKind.BASIC));
            dockable(panel, TelemetryChartDataBuilder.ChartKind.RX).getContainer()
                    .closeDockable(dockable(panel, TelemetryChartDataBuilder.ChartKind.RX));
            dockable(panel, TelemetryChartDataBuilder.ChartKind.RATE).getContainer()
                    .closeDockable(dockable(panel, TelemetryChartDataBuilder.ChartKind.RATE));
            dockable(panel, TelemetryChartDataBuilder.ChartKind.TX).getContainer()
                    .closeDockable(dockable(panel, TelemetryChartDataBuilder.ChartKind.TX));
            dockable(panel, TelemetryChartDataBuilder.ChartKind.QUALITY).getContainer()
                    .closeDockable(dockable(panel, TelemetryChartDataBuilder.ChartKind.QUALITY));
            return null;
        });
        onFxThread(() -> null);

        DockContainerBranch workspace = remaining.getContainer().getParentContainer();
        assertEquals("telemetry-dock-workspace", workspace.getIdentifier());
        assertEquals(1, workspace.getChildContainers().size());
        assertTrue(workspace.getChildContainers().contains(remaining.getContainer()));
        assertEquals(DEFAULT_MINIMIZED_CHARTS.size() + 5, minimizedBar(panel).getChildren().size());
    }

    @Test
    void restoresSavedTelemetryDockLayoutWithMinimizedWindows() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));

        onFxThread(() -> {
            dockable(panel, TelemetryChartDataBuilder.ChartKind.BASIC).getContainer()
                    .closeDockable(dockable(panel, TelemetryChartDataBuilder.ChartKind.BASIC));
            dockable(panel, TelemetryChartDataBuilder.ChartKind.RX).getContainer()
                    .closeDockable(dockable(panel, TelemetryChartDataBuilder.ChartKind.RX));
            return null;
        });
        onFxThread(() -> null);
        onFxThread(() -> null);

        assertFalse(AppPreferences.getTelemetryDockLayout().isBlank());

        TelemetryChartPanel restored = onFxThread(() -> new TelemetryChartPanel(false));
        assertNull(dockable(restored, TelemetryChartDataBuilder.ChartKind.BASIC).getContainer());
        assertNull(dockable(restored, TelemetryChartDataBuilder.ChartKind.RX).getContainer());
        for (TelemetryChartDataBuilder.ChartKind kind : DEFAULT_MINIMIZED_CHARTS) {
            assertNull(dockable(restored, kind).getContainer());
        }
        assertNotNull(dockable(restored, TelemetryChartDataBuilder.ChartKind.RATE).getContainer());
        assertEquals(DEFAULT_MINIMIZED_CHARTS.size() + 2, minimizedBar(restored).getChildren().size());
    }

    @Test
    void restoresSavedTelemetryDockDividerPositions() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));
        onFxThread(() -> null);
        onFxThread(() -> null);

        onFxThread(() -> {
            workspace(panel).setDividerPositions(0.21, 0.79);
            dockShell(panel).fireEvent(mouseReleased());
            return null;
        });
        onFxThread(() -> null);
        onFxThread(() -> null);

        TelemetryChartPanel restored = onFxThread(() -> new TelemetryChartPanel(false));
        double[] restoredDividers = workspace(restored).getDividerPositions();
        assertEquals(2, restoredDividers.length);
        assertEquals(0.21, restoredDividers[0], 0.0001);
        assertEquals(0.79, restoredDividers[1], 0.0001);
    }

    @Test
    void resetLayoutButtonRestoresDefaultDockLayout() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));
        Dockable basic = dockable(panel, TelemetryChartDataBuilder.ChartKind.BASIC);
        Dockable temperature = dockable(panel, TelemetryChartDataBuilder.ChartKind.TEMPERATURE);

        onFxThread(() -> {
            basic.getContainer().closeDockable(basic);
            return null;
        });
        onFxThread(() -> null);
        onFxThread(() -> {
            restoreButtonFor(minimizedBar(panel), temperature).fire();
            workspace(panel).setDividerPositions(0.28, 0.72);
            return null;
        });

        assertNull(basic.getContainer());
        assertNotNull(temperature.getContainer());

        onFxThread(() -> {
            resetLayoutButton(panel).fire();
            return null;
        });

        for (TelemetryChartDataBuilder.ChartKind kind : DEFAULT_OPEN_CHARTS) {
            assertNotNull(dockable(panel, kind).getContainer());
        }
        for (TelemetryChartDataBuilder.ChartKind kind : DEFAULT_MINIMIZED_CHARTS) {
            assertNull(dockable(panel, kind).getContainer());
        }
        assertEquals(DEFAULT_MINIMIZED_CHARTS.size(), minimizedBar(panel).getChildren().size());
    }

    @Test
    void doubleClickMaximizesDockWindowAndRestoresPreviousDockState() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));
        Dockable basic = dockable(panel, TelemetryChartDataBuilder.ChartKind.BASIC);
        Dockable rx = dockable(panel, TelemetryChartDataBuilder.ChartKind.RX);
        AreaChart<Number, Number> rxChart = chartField(panel, "rxChart");

        onFxThread(() -> {
            basic.getContainer().closeDockable(basic);
            return null;
        });
        onFxThread(() -> null);
        onFxThread(() -> {
            workspace(panel).setDividerPositions(0.28, 0.72);
            return null;
        });

        assertNull(basic.getContainer());
        assertNotNull(rx.getContainer());
        assertEquals(DEFAULT_MINIMIZED_CHARTS.size() + 1, minimizedBar(panel).getChildren().size());
        int workspaceChildrenBefore = workspace(panel).getChildContainers().size();
        double[] dividersBefore = workspace(panel).getDividerPositions();

        onFxThread(() -> {
            invokeToggleDockMaximize(panel, rxChart);
            return null;
        });

        assertNotNull(rx.getContainer());
        assertFalse(rx.isClosable());
        assertEquals(2, dockRootStack(panel).getChildren().size());
        assertEquals(workspaceChildrenBefore, workspace(panel).getChildContainers().size());
        assertDividerPositions(dividersBefore, workspace(panel).getDividerPositions());
        assertEquals(DEFAULT_MINIMIZED_CHARTS.size() + 1, minimizedBar(panel).getChildren().size());

        onFxThread(() -> {
            invokeToggleDockMaximize(panel, rxChart);
            return null;
        });
        onFxThread(() -> null);
        onFxThread(() -> null);

        assertNull(basic.getContainer());
        assertNotNull(rx.getContainer());
        assertTrue(rx.isClosable());
        assertEquals(1, dockRootStack(panel).getChildren().size());
        assertDividerPositions(dividersBefore, workspace(panel).getDividerPositions());
        assertEquals(DEFAULT_MINIMIZED_CHARTS.size() + 1, minimizedBar(panel).getChildren().size());
        for (TelemetryChartDataBuilder.ChartKind kind : List.of(
                TelemetryChartDataBuilder.ChartKind.RX,
                TelemetryChartDataBuilder.ChartKind.RATE,
                TelemetryChartDataBuilder.ChartKind.TX,
                TelemetryChartDataBuilder.ChartKind.QUALITY,
                TelemetryChartDataBuilder.ChartKind.HOPS)) {
            assertNotNull(dockable(panel, kind).getContainer());
        }
    }

    @Test
    void initializesPlaceholderSeriesInBasicNodeMode() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(true));
        AreaChart<Number, Number> basicChart = chartField(panel, "chart");
        AreaChart<Number, Number> environmentChart = chartField(panel, "environmentMetricsChart");
        Label basicTitle = nodeDetailTitleLabel(panel, "nodeDetailBasicTitleLabel");
        Label environmentTitle = nodeDetailTitleLabel(panel, "nodeDetailEnvironmentTitleLabel");
        NumberAxis pressureAxis = pressureAxis(panel);

        assertNull(basicChart.getTitle());
        assertEquals(t("telemetry.chart.title.basic"), basicTitle.getText());
        assertEquals(labels(
                "telemetry.chart.series.voltage",
                "telemetry.chart.series.battery",
                "telemetry.chart.series.chUtil",
                "telemetry.chart.series.airUtil"), seriesNames(basicChart));
        assertNull(environmentChart.getTitle());
        assertEquals(t("telemetry.chart.title.environment"), environmentTitle.getText());
        assertEquals(labels(
                "telemetry.chart.series.temperature",
                "telemetry.chart.series.humidity",
                "telemetry.chart.series.pressure",
                "telemetry.chart.series.radiation"), seriesNames(environmentChart));
        assertFalse(((NumberAxis) basicChart.getXAxis()).isAutoRanging());
        assertFalse(((NumberAxis) environmentChart.getXAxis()).isAutoRanging());
        assertEquals(Side.RIGHT, pressureAxis.getSide());
    }

    @Test
    void periodFilterIncludesTwoHourOption() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(true));
        HBox periodButtons = periodButtons(panel);

        assertTrue(periodButtons.getChildren().stream()
                .filter(ToggleButton.class::isInstance)
                .map(ToggleButton.class::cast)
                .anyMatch(button -> t("telemetry.period.2h").equals(button.getText())
                        && Long.valueOf(2L * 3600).equals(button.getUserData())));
        ToggleButton selected = periodButtons.getChildren().stream()
                .filter(ToggleButton.class::isInstance)
                .map(ToggleButton.class::cast)
                .filter(ToggleButton::isSelected)
                .findFirst()
                .orElseThrow();
        assertEquals(t("telemetry.period.6h"), selected.getText());
    }

    @Test
    void storesActualVoltageForDisplayInsteadOfScaledPercent() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(true));
        TelemetryEntry entry = new TelemetryEntry(1_700_000_000L, "!test");
        entry.setVoltage(4.0f);

        onFxThread(() -> {
            invokeUpdateChart(panel, List.of(entry), List.of());
            return null;
        });

        AreaChart<Number, Number> basicChart = chartField(panel, "chart");
        XYChart.Series<Number, Number> voltageSeries = basicChart.getData().stream()
                .filter(series -> t("telemetry.chart.series.voltage").equals(series.getName()))
                .findFirst()
                .orElseThrow();
        XYChart.Data<Number, Number> point = voltageSeries.getData().getFirst();

        assertEquals(83.0, point.getYValue().doubleValue(), 0.0001);
        Number actualVoltage = assertInstanceOf(Number.class, point.getExtraValue());
        assertEquals(4.0, actualVoltage.doubleValue(), 0.0001);
        String expectedVoltage = I18n.t("telemetry.chart.value.voltage",
                String.format(I18n.locale(), "%.2f", 4.0));
        assertEquals(expectedVoltage, TelemetryChartPanel.formatSeriesValue(voltageSeries.getName(), point));

        assertSeriesValues(basicChart, t("telemetry.chart.series.battery"), 83.0);
    }

    @Test
    void plotsMeshtasticBatteryPercentWhenDeviceMetricsProvideLevel() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(true));
        TelemetryEntry entry = new TelemetryEntry(1_700_000_000L, "!test");
        entry.setBatteryLevel(77);

        onFxThread(() -> {
            invokeUpdateChart(panel, List.of(entry), List.of());
            return null;
        });

        AreaChart<Number, Number> basicChart = chartField(panel, "chart");
        assertSeriesValues(basicChart, t("telemetry.chart.series.battery"), 77.0);
    }

    @Test
    void plotsPoweredFlagBatteryFromVoltageEstimateWithoutUsingFlagAsPercent() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(true));
        TelemetryEntry entry = new TelemetryEntry(1_700_000_000L, "!test");
        entry.setExternallyPowered(true);
        entry.setVoltage(4.0f);

        onFxThread(() -> {
            invokeUpdateChart(panel, List.of(entry), List.of());
            return null;
        });

        AreaChart<Number, Number> basicChart = chartField(panel, "chart");
        assertSeriesValues(basicChart, t("telemetry.chart.series.battery"), 83.0);
    }

    @Test
    void usesDeviceStateOwnerIdForTelemetryHistoryQueries() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(true));
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x1234ABCD);
        state.setOwnerInfo(MeshProtos.User.newBuilder()
                .setId("!1234abcd")
                .build());

        assertEquals("!1234abcd", invokeOwnerNodeId(panel, state));
    }

    @Test
    void telemetryLogRowShowsDerivedBatteryPercentAndOwnerNameForMeshtasticVoltageOnlyEntry() {
        DeviceState state = new DeviceState();
        state.setOwnerInfo(MeshProtos.User.newBuilder()
                .setId("!1234abcd")
                .setLongName("Meshtastic Radio")
                .build());
        TelemetryEntry entry = new TelemetryEntry(1_700_000_000L, "!1234abcd");
        entry.setVoltage(4.0f);

        FormDashboard.TelemetryLogRow row = new FormDashboard.TelemetryLogRow(entry, state);

        assertEquals("83%", row.getBattery());
        assertEquals("Meshtastic Radio (!1234abcd)", row.getNode());
    }

    @Test
    void telemetryLogRowDoesNotMixPoweredFlagIntoBatteryPercent() {
        TelemetryEntry entry = new TelemetryEntry(1_700_000_000L, "!1234abcd");
        entry.setExternallyPowered(true);
        entry.setVoltage(4.0f);

        FormDashboard.TelemetryLogRow row = new FormDashboard.TelemetryLogRow(entry, null);

        assertEquals("83%", row.getBattery());
    }

    @Test
    void telemetryLogRowUsesShortNameWhenLongNameIsMissing() {
        DeviceState state = new DeviceState();
        NodeData node = state.getOrCreateNode(0x1234ABCD);
        node.setShortName("MESH");
        TelemetryEntry entry = new TelemetryEntry(1_700_000_000L, "!1234abcd");
        entry.setBatteryLevel(77);

        FormDashboard.TelemetryLogRow row = new FormDashboard.TelemetryLogRow(entry, state);

        assertEquals("MESH (!1234abcd)", row.getNode());
    }

    @Test
    void telemetryLogRowNormalizesInconsistentRxCounters() {
        TelemetryEntry entry = new TelemetryEntry(RX_NORMALIZATION_SAMPLE_TIME, "!1234abcd");
        entry.setNumPacketsRx(LOG_RX_RECEIVED);
        entry.setNumPacketsRxBad(LOG_RX_BAD);
        entry.setNumRxDupe(LOG_RX_DUPLICATE);

        FormDashboard.TelemetryLogRow row = new FormDashboard.TelemetryLogRow(entry, null);

        assertEquals(formatPercent(ZERO_PERCENT), normalizePercent(row.getGoodRx()));
        assertEquals(formatPercent(EXPECTED_RX_BAD_PERCENT), normalizePercent(row.getBadRx()));
        assertEquals(formatPercent(EXPECTED_RX_DUPLICATE_PERCENT), normalizePercent(row.getDupeRx()));
    }

    @Test
    void telemetryLogNodeColumnUsesExplicitValueFactoryAfterRowsAreRebuilt() {
        FormDashboard dashboard = onFxThread(FormDashboard::new);
        TableColumn<FormDashboard.TelemetryLogRow, String> nodeColumn = telemetryNodeColumn(dashboard);

        DeviceState state = new DeviceState();
        NodeData node = state.getOrCreateNode(0x1234ABCD);
        node.setLongName("Meshtastic Radio");

        TelemetryEntry firstEntry = new TelemetryEntry(1_700_000_000L, "!1234abcd");
        TelemetryEntry secondEntry = new TelemetryEntry(1_700_000_060L, "!1234abcd");
        FormDashboard.TelemetryLogRow firstRow = new FormDashboard.TelemetryLogRow(firstEntry, state);
        FormDashboard.TelemetryLogRow secondRow = new FormDashboard.TelemetryLogRow(secondEntry, state);

        assertEquals("Meshtastic Radio (!1234abcd)",
                nodeColumn.getCellObservableValue(firstRow).getValue());
        assertEquals("Meshtastic Radio (!1234abcd)",
                nodeColumn.getCellObservableValue(secondRow).getValue());
    }

    @Test
    void coalescesQueuedTelemetryRefreshesBeforeFxThreadRuns() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(true));
        AreaChart<Number, Number> basicChart = chartField(panel, "chart");
        AtomicInteger chartDataChanges = new AtomicInteger();
        basicChart.getData().addListener((ListChangeListener<XYChart.Series<Number, Number>>) change ->
                chartDataChanges.incrementAndGet());

        CountDownLatch fxBlocked = new CountDownLatch(1);
        CountDownLatch releaseFx = new CountDownLatch(1);
        Platform.runLater(() -> {
            fxBlocked.countDown();
            try {
                if (!releaseFx.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release FX thread");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while blocking FX thread", e);
            }
        });
        await(fxBlocked);

        Runnable listener = telemetryListener(panel);
        for (int i = 0; i < 10; i++) {
            listener.run();
        }
        assertTrue(refreshQueued(panel).get());

        releaseFx.countDown();
        onFxThread(() -> null);

        assertEquals(1, chartDataChanges.get());
        assertFalse(refreshQueued(panel).get());
    }

    @Test
    void dashboardCloseUnbindsTelemetryChart() {
        FormDashboard dashboard = onFxThread(FormDashboard::new);
        TrackingTelemetryChartPanel replacement = onFxThread(TrackingTelemetryChartPanel::new);
        replaceChartPanel(dashboard, replacement);

        onFxThread(() -> {
            dashboard.formClose();
            return null;
        });

        assertEquals(1, replacement.unbindCount.get());
    }

    @Test
    void derivesRateAndTxSeriesFromCounterDeltasIncludingCounterReset() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));

        TelemetryEntry first = telemetryEntry(1_700_000_000L, 10, 1, 2, 20, 2, 3, 1);
        TelemetryEntry second = telemetryEntry(1_700_000_060L, 25, 4, 3, 32, 5, 7, 2);
        TelemetryEntry third = telemetryEntry(1_700_000_120L, 5, 1, 0, 6, 1, 1, 0);

        onFxThread(() -> {
            invokeUpdateChart(panel, List.of(first, second, third), List.of());
            return null;
        });

        AreaChart<Number, Number> rxChart = chartField(panel, "rxChart");
        AreaChart<Number, Number> rateChart = chartField(panel, "rateChart");
        AreaChart<Number, Number> txChart = chartField(panel, "txChart");

        assertSeriesValues(rateChart, t("telemetry.chart.series.packetsReceived"), 15.0, 5.0);
        assertSeriesValues(rateChart, t("telemetry.chart.series.badPackets"), 3.0, 1.0);
        assertSeriesValues(rateChart, t("telemetry.chart.series.duplicates"), 1.0, 0.0);

        assertSeriesValues(rxChart, t("telemetry.chart.series.goodRx"), 73.33333333333333, 80.0);
        assertSeriesValues(rxChart, t("telemetry.chart.series.badRx"), 20.0, 20.0);
        assertSeriesValues(rxChart, t("telemetry.chart.series.dupeRx"), 6.666666666666667, 0.0);

        assertSeriesValues(txChart, t("telemetry.chart.series.packetsTransmitted"), 12.0, 6.0);
        assertSeriesValues(txChart, t("telemetry.chart.series.dropped"), 3.0, 1.0);
        assertSeriesValues(txChart, t("telemetry.chart.series.relayed"), 4.0, 1.0);
        assertSeriesValues(txChart, t("telemetry.chart.series.relayCanceled"), 1.0, 0.0);
    }

    @Test
    void normalizesRxPercentagesWhenBadAndDuplicateDeltasExceedReceivedDelta() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));

        TelemetryEntry first = telemetryEntry(
                RX_NORMALIZATION_SAMPLE_TIME,
                CHART_RX_PREVIOUS_RECEIVED,
                CHART_RX_PREVIOUS_BAD,
                CHART_RX_PREVIOUS_DUPLICATE,
                NO_TX_COUNTER, NO_TX_COUNTER, NO_TX_COUNTER, NO_TX_COUNTER);
        TelemetryEntry second = telemetryEntry(
                RX_NORMALIZATION_NEXT_SAMPLE_TIME,
                CHART_RX_CURRENT_RECEIVED,
                CHART_RX_CURRENT_BAD,
                CHART_RX_CURRENT_DUPLICATE,
                NO_TX_COUNTER, NO_TX_COUNTER, NO_TX_COUNTER, NO_TX_COUNTER);

        onFxThread(() -> {
            invokeUpdateChart(panel, List.of(first, second), List.of());
            return null;
        });

        AreaChart<Number, Number> rxChart = chartField(panel, "rxChart");
        AreaChart<Number, Number> rateChart = chartField(panel, "rateChart");

        assertSeriesValues(rxChart, t("telemetry.chart.series.goodRx"), ZERO_PERCENT);
        assertSeriesValues(rxChart, t("telemetry.chart.series.badRx"), EXPECTED_RX_BAD_PERCENT);
        assertSeriesValues(rxChart, t("telemetry.chart.series.dupeRx"), EXPECTED_RX_DUPLICATE_PERCENT);
        assertSeriesValues(rateChart, t("telemetry.chart.series.packetsReceived"), chartRxReceivedDelta());
        assertSeriesValues(rateChart, t("telemetry.chart.series.badPackets"), chartRxBadDelta());
        assertSeriesValues(rateChart, t("telemetry.chart.series.duplicates"), chartRxDuplicateDelta());
    }

    @Test
    void ignoresInvalidHopPairsWhenBuildingHopsSeries() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));

        TelemetryEntry first = hopEntry(1_700_000_000L, 7, 5);
        TelemetryEntry invalid = hopEntry(1_700_000_060L, 4, 6);
        TelemetryEntry second = hopEntry(1_700_000_120L, 7, 4);

        onFxThread(() -> {
            invokeUpdateChart(panel, List.of(), List.of(first, invalid, second));
            return null;
        });

        AreaChart<Number, Number> hopsChart = chartField(panel, "hopsChart");
        assertSeriesValues(hopsChart, t("telemetry.chart.series.hopsMax"), 2.0, 3.0);
        assertSeriesValues(hopsChart, t("telemetry.chart.series.hopsMin"), 2.0, 3.0);
        assertSeriesValues(hopsChart, t("telemetry.chart.series.hopsAvg"), 2.0, 3.0);
    }

    @Test
    void plotsEnvironmentTelemetrySeries() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(false));

        TelemetryEntry first = environmentEntry(1_700_000_000L, 21.5f, 45.0f, 1012.4f, 0.12f);
        TelemetryEntry second = environmentEntry(1_700_000_060L, 22.0f, 46.5f, 1013.1f, 0.15f);

        onFxThread(() -> {
            invokeUpdateChart(panel, List.of(first, second), List.of());
            return null;
        });

        assertSeriesValues(chartField(panel, "temperatureChart"),
                t("telemetry.chart.series.temperature"), 21.5, 22.0);
        assertSeriesValues(chartField(panel, "humidityChart"),
                t("telemetry.chart.series.humidity"), 45.0, 46.5);
        assertSeriesValues(chartField(panel, "pressureChart"),
                t("telemetry.chart.series.pressure"), 1012.4, 1013.1);
        assertSeriesValues(chartField(panel, "radiationChart"),
                t("telemetry.chart.series.radiation"), 0.12, 0.15);
    }

    @Test
    void plotsCombinedEnvironmentMetricsInBasicNodeMode() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(true));

        TelemetryEntry first = environmentEntry(1_700_000_000L, 21.5f, 45.0f, 1012.4f, 0.12f);
        TelemetryEntry second = environmentEntry(1_700_000_060L, 22.0f, 46.5f, 1013.1f, 0.15f);

        onFxThread(() -> {
            invokeUpdateChart(panel, List.of(first, second), List.of());
            return null;
        });

        AreaChart<Number, Number> environmentChart = chartField(panel, "environmentMetricsChart");
        assertSeriesValues(environmentChart, t("telemetry.chart.series.temperature"), 21.5, 22.0);
        assertSeriesValues(environmentChart, t("telemetry.chart.series.humidity"), 45.0, 46.5);
        assertTrue(seriesValues(environmentChart, t("telemetry.chart.series.pressure")).isEmpty());
        assertSeriesValues(environmentChart, t("telemetry.chart.series.radiation"), 0.12, 0.15);
        assertDoubleValues(pressureValues(panel), 1012.4, 1013.1);

        NumberAxis pressureAxis = pressureAxis(panel);
        assertEquals(1011.0, pressureAxis.getLowerBound(), 0.0001);
        assertEquals(1015.0, pressureAxis.getUpperBound(), 0.0001);
        assertEquals(1.0, pressureAxis.getTickUnit(), 0.0001);
        assertEquals("1012", pressureAxis.getTickLabelFormatter().toString(1012.4));
        assertNotNull(pressurePlotLayer(panel).getClip());
    }

    @Test
    void basicNodeChartsUseAlignedPlotAreaBounds() {
        NodeDetailLayout layout = onFxThread(() -> {
            TelemetryChartPanel panel = new TelemetryChartPanel(true);
            TelemetryEntry first = environmentEntry(1_700_000_000L, 21.5f, 45.0f, 993.2f, 0.12f);
            TelemetryEntry second = environmentEntry(1_700_010_000L, 22.0f, 46.5f, 994.1f, 0.15f);

            invokeUpdateChart(panel, List.of(first, second), List.of());

            StackPane root = new StackPane(panel);
            new Scene(root, 900, 760);
            root.resize(900, 760);
            root.applyCss();
            root.layout();

            Label basicTitle = nodeDetailTitleLabel(panel, "nodeDetailBasicTitleLabel");
            Label environmentTitle = nodeDetailTitleLabel(panel, "nodeDetailEnvironmentTitleLabel");
            return new NodeDetailLayout(
                    plotAreaSceneBounds(chartField(panel, "chart")),
                    plotAreaSceneBounds(chartField(panel, "environmentMetricsChart")),
                    sceneBounds(basicTitle),
                    sceneBounds(environmentTitle),
                    sceneBounds(basicTitle.getParent()),
                    sceneBounds(environmentTitle.getParent())
            );
        });

        assertEquals(layout.basicPlot().getMinX(), layout.environmentPlot().getMinX(), 0.5);
        assertEquals(layout.basicPlot().getMaxX(), layout.environmentPlot().getMaxX(), 0.5);
        assertEquals(layout.basicPlot().getWidth(), layout.environmentPlot().getWidth(), 0.5);
        assertEquals(layout.basicPlot().getHeight(), layout.environmentPlot().getHeight(), 0.5);
        assertEquals(layout.basicFrame().getHeight(), layout.environmentFrame().getHeight(), 0.5);
        assertEquals(centerX(layout.basicFrame()), centerX(layout.basicTitle()), 0.5);
        assertEquals(centerX(layout.environmentFrame()), centerX(layout.environmentTitle()), 0.5);
        assertEquals(centerX(layout.basicTitle()), centerX(layout.environmentTitle()), 0.5);
    }

    @Test
    void qualityEntriesDoNotStretchPrimaryTelemetryAxes() {
        TelemetryEntry ownEntry = new TelemetryEntry(1_700_000_000L, "!own");
        ownEntry.setVoltage(4.0f);
        TelemetryEntry remoteQualityEntry = hopEntry(1_700_086_400L, 7, 5);

        TelemetryChartDataBuilder.PreparedCharts prepared = TelemetryChartDataBuilder.build(
                false, List.of(ownEntry), List.of(remoteQualityEntry), 0);
        TelemetryChartDataBuilder.AxisRange basicAxis = prepared.axisRange(TelemetryChartDataBuilder.ChartKind.BASIC);
        TelemetryChartDataBuilder.AxisRange rxAxis = prepared.axisRange(TelemetryChartDataBuilder.ChartKind.RX);
        TelemetryChartDataBuilder.AxisRange hopsAxis = prepared.axisRange(TelemetryChartDataBuilder.ChartKind.HOPS);

        assertTrue(basicAxis.upperBound() < remoteQualityEntry.getTimestamp());
        assertTrue(rxAxis.upperBound() < remoteQualityEntry.getTimestamp());
        assertTrue(hopsAxis.lowerBound() > ownEntry.getTimestamp());
    }

    @Test
    void selectedPeriodControlsAxisEvenWhenDataCoversShorterRange() {
        long selectedPeriodSeconds = 48L * 3600;
        long now = System.currentTimeMillis() / 1000;
        TelemetryEntry recentEntry = new TelemetryEntry(now - 300, "!own");
        recentEntry.setVoltage(4.0f);

        TelemetryChartDataBuilder.PreparedCharts prepared = TelemetryChartDataBuilder.build(
                true, List.of(recentEntry), List.of(), selectedPeriodSeconds);
        TelemetryChartDataBuilder.AxisRange axis = prepared.axisRange(TelemetryChartDataBuilder.ChartKind.BASIC);

        assertTrue(axis.lowerBound() <= now - selectedPeriodSeconds);
        assertTrue(axis.upperBound() >= now);
        assertTrue(axis.upperBound() - axis.lowerBound() > selectedPeriodSeconds);
    }

    private static List<String> seriesNames(AreaChart<Number, Number> chart) {
        return chart.getData().stream()
                .map(XYChart.Series::getName)
                .toList();
    }

    private static List<Double> seriesValues(AreaChart<Number, Number> chart, String seriesName) {
        return chart.getData().stream()
                .filter(series -> seriesName.equals(series.getName()))
                .findFirst()
                .orElseThrow()
                .getData().stream()
                .map(point -> point.getYValue().doubleValue())
                .toList();
    }

    private static void assertSeriesValues(AreaChart<Number, Number> chart, String seriesName, double... expected) {
        List<Double> actual = seriesValues(chart, seriesName);
        assertEquals(expected.length, actual.size());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual.get(index), 0.0001);
        }
    }

    private static void assertDividerPositions(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual[index], 0.0001);
        }
    }

    private static void assertDoubleValues(List<Double> actual, double... expected) {
        assertEquals(expected.length, actual.size());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual.get(index), 0.0001);
        }
    }

    private static String dockTitle(TelemetryChartPanel panel, TelemetryChartDataBuilder.ChartKind kind) {
        return dockable(panel, kind).getTitle();
    }

    private static Dockable dockable(TelemetryChartPanel panel, TelemetryChartDataBuilder.ChartKind kind) {
        try {
            Field field = TelemetryChartPanel.class.getDeclaredField("chartBindings");
            field.setAccessible(true);
            List<?> bindings = (List<?>) field.get(panel);
            for (Object binding : bindings) {
                Method kindMethod = binding.getClass().getDeclaredMethod("kind");
                kindMethod.setAccessible(true);
                if (kindMethod.invoke(binding) == kind) {
                    Method dockableMethod = binding.getClass().getDeclaredMethod("dockable");
                    dockableMethod.setAccessible(true);
                    return (Dockable) dockableMethod.invoke(binding);
                }
            }
            throw new AssertionError("Missing dockable binding for " + kind);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read dockable binding", e);
        }
    }

    private static int dockCloseListenerRegistrationCount(TelemetryChartPanel panel) {
        try {
            Field field = TelemetryChartPanel.class.getDeclaredField("dockableCloseListeners");
            field.setAccessible(true);
            Map<?, ?> registrations = (Map<?, ?>) field.get(panel);
            return registrations.size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read dockable close listener registrations", e);
        }
    }

    private static int closeListenerCount(Dockable dockable) {
        try {
            Field field = Dockable.class.getDeclaredField("closeListeners");
            field.setAccessible(true);
            List<?> listeners = (List<?>) field.get(dockable);
            return listeners == null ? 0 : listeners.size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read dockable close listeners", e);
        }
    }

    private static HBox minimizedBar(TelemetryChartPanel panel) {
        return assertInstanceOf(HBox.class, dockShell(panel).getBottom());
    }

    private static Button restoreButtonFor(HBox minimizedBar, Dockable dockable) {
        return minimizedBar.getChildren().stream()
                .filter(node -> node.getUserData() == dockable)
                .findFirst()
                .map(Button.class::cast)
                .orElseThrow();
    }

    private static DockContainerBranch workspace(TelemetryChartPanel panel) {
        return assertInstanceOf(DockContainerBranch.class, dockShell(panel).getCenter());
    }

    private static Button resetLayoutButton(TelemetryChartPanel panel) {
        BorderPane periodBar = assertInstanceOf(BorderPane.class, panel.getChildren().getLast());
        return assertInstanceOf(Button.class, periodBar.getRight());
    }

    private static HBox periodButtons(TelemetryChartPanel panel) {
        BorderPane periodBar = assertInstanceOf(BorderPane.class, panel.getChildren().getLast());
        return assertInstanceOf(HBox.class, periodBar.getLeft());
    }

    private static NumberAxis pressureAxis(TelemetryChartPanel panel) {
        try {
            Field field = TelemetryChartPanel.class.getDeclaredField("environmentPressureAxis");
            field.setAccessible(true);
            return (NumberAxis) field.get(panel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read environment pressure axis", e);
        }
    }

    private static Region pressurePlotLayer(TelemetryChartPanel panel) {
        try {
            Field field = TelemetryChartPanel.class.getDeclaredField("environmentPressurePlotLayer");
            field.setAccessible(true);
            return (Region) field.get(panel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read environment pressure plot layer", e);
        }
    }

    private static Label nodeDetailTitleLabel(TelemetryChartPanel panel, String fieldName) {
        try {
            Field field = TelemetryChartPanel.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (Label) field.get(panel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read node detail title label " + fieldName, e);
        }
    }

    private static Bounds plotAreaSceneBounds(AreaChart<Number, Number> chart) {
        Region plotArea = assertInstanceOf(Region.class, chart.lookup(".chart-plot-background"));
        return plotArea.localToScene(plotArea.getBoundsInLocal());
    }

    private static Bounds sceneBounds(Node node) {
        return node.localToScene(node.getBoundsInLocal());
    }

    private static double centerX(Bounds bounds) {
        return bounds.getMinX() + bounds.getWidth() / 2.0;
    }

    @SuppressWarnings("unchecked")
    private static List<Double> pressureValues(TelemetryChartPanel panel) {
        try {
            Field field = TelemetryChartPanel.class.getDeclaredField("environmentPressureData");
            field.setAccessible(true);
            List<XYChart.Data<Number, Number>> data =
                    (List<XYChart.Data<Number, Number>>) field.get(panel);
            return data.stream()
                    .map(point -> point.getYValue().doubleValue())
                    .toList();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read environment pressure data", e);
        }
    }

    private static BorderPane dockShell(TelemetryChartPanel panel) {
        return assertInstanceOf(BorderPane.class, dockRootStack(panel).getChildren().getFirst());
    }

    private static StackPane dockRootStack(TelemetryChartPanel panel) {
        try {
            Field field = TelemetryChartPanel.class.getDeclaredField("dockRoot");
            field.setAccessible(true);
            Region dockRoot = (Region) field.get(panel);
            return assertInstanceOf(StackPane.class, dockRoot);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read minimized dock bar", e);
        }
    }

    private static MouseEvent mouseReleased() {
        return new MouseEvent(MouseEvent.MOUSE_RELEASED,
                0, 0, 0, 0,
                MouseButton.PRIMARY,
                1,
                false, false, false, false,
                false, false, false,
                false, false, false,
                null);
    }

    private static String normalizePercent(String value) {
        return value.replace(',', '.');
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    private static double chartRxReceivedDelta() {
        return CHART_RX_CURRENT_RECEIVED - CHART_RX_PREVIOUS_RECEIVED;
    }

    private static double chartRxBadDelta() {
        return CHART_RX_CURRENT_BAD - CHART_RX_PREVIOUS_BAD;
    }

    private static double chartRxDuplicateDelta() {
        return CHART_RX_CURRENT_DUPLICATE - CHART_RX_PREVIOUS_DUPLICATE;
    }

    private static TelemetryEntry telemetryEntry(long timestamp,
                                                 int rx,
                                                 int badRx,
                                                 int dupeRx,
                                                 int tx,
                                                 int txDropped,
                                                 int txRelay,
                                                 int txCanceled) {
        TelemetryEntry entry = new TelemetryEntry(timestamp, "!test");
        entry.setNumPacketsRx(rx);
        entry.setNumPacketsRxBad(badRx);
        entry.setNumRxDupe(dupeRx);
        entry.setNumPacketsTx(tx);
        entry.setNumTxDropped(txDropped);
        entry.setNumTxRelay(txRelay);
        entry.setNumTxRelayCanceled(txCanceled);
        return entry;
    }

    private static TelemetryEntry hopEntry(long timestamp, int hopStart, int hopLimit) {
        TelemetryEntry entry = new TelemetryEntry(timestamp, "!test");
        entry.setHopStart(hopStart);
        entry.setHopLimit(hopLimit);
        return entry;
    }

    private static TelemetryEntry environmentEntry(long timestamp,
                                                   float temperature,
                                                   float humidity,
                                                   float pressure,
                                                   float radiation) {
        TelemetryEntry entry = new TelemetryEntry(timestamp, "!test");
        entry.setTemperature(temperature);
        entry.setRelativeHumidity(humidity);
        entry.setBarometricPressure(pressure);
        entry.setRadiation(radiation);
        return entry;
    }

    private static void invokeUpdateChart(TelemetryChartPanel panel,
                                          List<TelemetryEntry> entries,
                                          List<TelemetryEntry> qualityEntries) {
        try {
            Method method = TelemetryChartPanel.class.getDeclaredMethod("updateChart", List.class, List.class);
            method.setAccessible(true);
            method.invoke(panel, entries, qualityEntries);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke updateChart", e);
        }
    }

    private static void invokeToggleDockMaximize(TelemetryChartPanel panel, AreaChart<Number, Number> chart) {
        try {
            Method method = TelemetryChartPanel.class.getDeclaredMethod("toggleDockMaximize", AreaChart.class);
            method.setAccessible(true);
            method.invoke(panel, chart);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke toggleDockMaximize", e);
        }
    }

    private static String invokeOwnerNodeId(TelemetryChartPanel panel, DeviceState state) {
        try {
            Method method = TelemetryChartPanel.class.getDeclaredMethod("ownerNodeId", DeviceState.class);
            method.setAccessible(true);
            return (String) method.invoke(panel, state);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke ownerNodeId", e);
        }
    }

    private static Runnable telemetryListener(TelemetryChartPanel panel) {
        try {
            Field field = TelemetryChartPanel.class.getDeclaredField("telemetryListener");
            field.setAccessible(true);
            return (Runnable) field.get(panel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read telemetry listener", e);
        }
    }

    private static AtomicBoolean refreshQueued(TelemetryChartPanel panel) {
        try {
            Field field = TelemetryChartPanel.class.getDeclaredField("refreshQueued");
            field.setAccessible(true);
            return (AtomicBoolean) field.get(panel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read refresh queue flag", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static AreaChart<Number, Number> chartField(TelemetryChartPanel panel, String fieldName) {
        try {
            Field field = TelemetryChartPanel.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (AreaChart<Number, Number>) field.get(panel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read chart field " + fieldName, e);
        }
    }

    private static void replaceChartPanel(FormDashboard dashboard, TelemetryChartPanel panel) {
        try {
            Field field = FormDashboard.class.getDeclaredField("chartPanel");
            field.setAccessible(true);
            field.set(dashboard, panel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to replace dashboard chart panel", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static TableColumn<FormDashboard.TelemetryLogRow, String> telemetryNodeColumn(FormDashboard dashboard) {
        try {
            Field field = FormDashboard.class.getDeclaredField("logTable");
            field.setAccessible(true);
            TableView<FormDashboard.TelemetryLogRow> table =
                    (TableView<FormDashboard.TelemetryLogRow>) field.get(dashboard);
            return table.getColumns().stream()
                    .filter(column -> t("telemetry.column.node").equals(column.getText()))
                    .findFirst()
                    .map(column -> (TableColumn<FormDashboard.TelemetryLogRow, String>) column)
                    .orElseThrow();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read telemetry log table", e);
        }
    }

    private static <T> T onFxThread(FxSupplier<T> supplier) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        await(latch);
        if (failure.get() != null) {
            throw new AssertionError("JavaFX task failed", failure.get());
        }
        return result.get();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for JavaFX task");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for JavaFX task", e);
        }
    }

    private static String t(String key) {
        return I18n.t(key);
    }

    private static List<String> labels(String... keys) {
        return Arrays.stream(keys)
                .map(I18n::t)
                .toList();
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }

    private static final class TrackingTelemetryChartPanel extends TelemetryChartPanel {
        private final AtomicInteger unbindCount = new AtomicInteger();

        private TrackingTelemetryChartPanel() {
            super(true);
        }

        @Override
        public void unbind() {
            unbindCount.incrementAndGet();
        }
    }
}
