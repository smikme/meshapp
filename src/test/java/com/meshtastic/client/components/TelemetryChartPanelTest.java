package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import javafx.application.Platform;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        assertEquals(List.of("Battery %", "Voltage В", "ChUtil %", "AirUtil %"), seriesNames(basicChart));

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
        assertEquals(List.of("Battery %", "Voltage В", "ChUtil %", "AirUtil %"), seriesNames(basicChart));
        assertFalse(((NumberAxis) basicChart.getXAxis()).isAutoRanging());
    }

    private static List<String> seriesNames(AreaChart<Number, Number> chart) {
        return chart.getData().stream()
                .map(XYChart.Series::getName)
                .toList();
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
