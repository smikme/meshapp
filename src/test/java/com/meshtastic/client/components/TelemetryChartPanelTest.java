package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.forms.FormDashboard;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import javafx.application.Platform;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

        assertEquals("Базовые метрики", basicChart.getTitle());
        assertEquals(List.of("Voltage В", "Battery %", "ChUtil %", "AirUtil %"), seriesNames(basicChart));

        assertEquals("Статистика эфира", rxChart.getTitle());
        assertEquals(List.of("Good RX %", "Bad RX %", "Dupe RX %"), seriesNames(rxChart));

        assertEquals("Скорость приема", rateChart.getTitle());
        assertEquals(List.of("Packets Received", "Bad Packets", "Duplicates"), seriesNames(rateChart));

        assertEquals("Скорость передачи", txChart.getTitle());
        assertEquals(List.of("Packets Transmitted", "Dropped", "Relayed", "Relay Canceled"), seriesNames(txChart));

        assertEquals("Качество соединения", qualityChart.getTitle());
        assertEquals(List.of("SNR (dB)", "RSSI (dBm)"), seriesNames(qualityChart));

        assertEquals("Прыжки", hopsChart.getTitle());
        assertEquals(List.of("Макс", "Мин", "Среднее"), seriesNames(hopsChart));

        assertFalse(((NumberAxis) basicChart.getXAxis()).isAutoRanging());
        assertFalse(((NumberAxis) rxChart.getXAxis()).isAutoRanging());
        assertFalse(((NumberAxis) qualityChart.getXAxis()).isAutoRanging());
    }

    @Test
    void initializesPlaceholderSeriesInBasicNodeMode() {
        TelemetryChartPanel panel = onFxThread(() -> new TelemetryChartPanel(true));
        AreaChart<Number, Number> basicChart = chartField(panel, "chart");

        assertEquals("Базовые метрики", basicChart.getTitle());
        assertEquals(List.of("Voltage В", "Battery %", "ChUtil %", "AirUtil %"), seriesNames(basicChart));
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
                .filter(series -> "Voltage В".equals(series.getName()))
                .findFirst()
                .orElseThrow();
        XYChart.Data<Number, Number> point = voltageSeries.getData().getFirst();

        assertEquals(83.0, point.getYValue().doubleValue(), 0.0001);
        Number actualVoltage = assertInstanceOf(Number.class, point.getExtraValue());
        assertEquals(4.0, actualVoltage.doubleValue(), 0.0001);
        assertTrue(TelemetryChartPanel.formatSeriesValue(voltageSeries.getName(), point).matches("4[,.]00V"));

        assertSeriesValues(basicChart, "Battery %", 83.0);
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
        assertSeriesValues(basicChart, "Battery %", 77.0);
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
        assertSeriesValues(basicChart, "Battery %", 83.0);
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

        assertSeriesValues(rateChart, "Packets Received", 15.0, 5.0);
        assertSeriesValues(rateChart, "Bad Packets", 3.0, 1.0);
        assertSeriesValues(rateChart, "Duplicates", 1.0, 0.0);

        assertSeriesValues(rxChart, "Good RX %", 73.33333333333333, 80.0);
        assertSeriesValues(rxChart, "Bad RX %", 20.0, 20.0);
        assertSeriesValues(rxChart, "Dupe RX %", 6.666666666666667, 0.0);

        assertSeriesValues(txChart, "Packets Transmitted", 12.0, 6.0);
        assertSeriesValues(txChart, "Dropped", 3.0, 1.0);
        assertSeriesValues(txChart, "Relayed", 4.0, 1.0);
        assertSeriesValues(txChart, "Relay Canceled", 1.0, 0.0);
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
        assertSeriesValues(hopsChart, "Макс", 2.0, 3.0);
        assertSeriesValues(hopsChart, "Мин", 2.0, 3.0);
        assertSeriesValues(hopsChart, "Среднее", 2.0, 3.0);
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

    @SuppressWarnings("unchecked")
    private static TableColumn<FormDashboard.TelemetryLogRow, String> telemetryNodeColumn(FormDashboard dashboard) {
        try {
            Field field = FormDashboard.class.getDeclaredField("logTable");
            field.setAccessible(true);
            TableView<FormDashboard.TelemetryLogRow> table =
                    (TableView<FormDashboard.TelemetryLogRow>) field.get(dashboard);
            return table.getColumns().stream()
                    .filter(column -> "Нода".equals(column.getText()))
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

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }
}
