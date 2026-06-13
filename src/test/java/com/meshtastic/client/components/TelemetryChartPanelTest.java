package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.forms.FormDashboard;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.meshtastic.proto.MeshProtos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class TelemetryChartPanelTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
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

        assertEquals(t("telemetry.chart.title.basic"), basicChart.getTitle());
        assertEquals(labels(
                "telemetry.chart.series.voltage",
                "telemetry.chart.series.battery",
                "telemetry.chart.series.chUtil",
                "telemetry.chart.series.airUtil"), seriesNames(basicChart));

        assertEquals(t("telemetry.chart.title.rx"), rxChart.getTitle());
        assertEquals(labels(
                "telemetry.chart.series.goodRx",
                "telemetry.chart.series.badRx",
                "telemetry.chart.series.dupeRx"), seriesNames(rxChart));

        assertEquals(t("telemetry.chart.title.rate"), rateChart.getTitle());
        assertEquals(labels(
                "telemetry.chart.series.packetsReceived",
                "telemetry.chart.series.badPackets",
                "telemetry.chart.series.duplicates"), seriesNames(rateChart));

        assertEquals(t("telemetry.chart.title.tx"), txChart.getTitle());
        assertEquals(labels(
                "telemetry.chart.series.packetsTransmitted",
                "telemetry.chart.series.dropped",
                "telemetry.chart.series.relayed",
                "telemetry.chart.series.relayCanceled"), seriesNames(txChart));

        assertEquals(t("telemetry.chart.title.quality"), qualityChart.getTitle());
        assertEquals(labels(
                "telemetry.chart.series.snr",
                "telemetry.chart.series.rssi"), seriesNames(qualityChart));

        assertEquals(t("telemetry.chart.title.hops"), hopsChart.getTitle());
        assertEquals(labels(
                "telemetry.chart.series.hopsMax",
                "telemetry.chart.series.hopsMin",
                "telemetry.chart.series.hopsAvg"), seriesNames(hopsChart));

        assertFalse(((NumberAxis) basicChart.getXAxis()).isAutoRanging());
        assertFalse(((NumberAxis) rxChart.getXAxis()).isAutoRanging());
        assertFalse(((NumberAxis) qualityChart.getXAxis()).isAutoRanging());
    }

    @Test
    void initializesPlaceholderSeriesInBasicNodeMode() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(true));
        AreaChart<Number, Number> basicChart = chartField(panel, "chart");

        assertEquals(t("telemetry.chart.title.basic"), basicChart.getTitle());
        assertEquals(labels(
                "telemetry.chart.series.voltage",
                "telemetry.chart.series.battery",
                "telemetry.chart.series.chUtil",
                "telemetry.chart.series.airUtil"), seriesNames(basicChart));
        assertFalse(((NumberAxis) basicChart.getXAxis()).isAutoRanging());
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
