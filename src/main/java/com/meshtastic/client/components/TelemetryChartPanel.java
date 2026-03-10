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
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Переиспользуемый компонент: AreaChart телеметрии + панель фильтра по периоду.
 * <p>
 * Используется в FormDashboard (телеметрия локальной ноды) и в FormNodes (телеметрия выбранной ноды).
 * Жизненный цикл: {@link #bind(DeviceState, int)} / {@link #unbind()}.
 */
public class TelemetryChartPanel extends VBox {

    private static final DateTimeFormatter AXIS_FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    // Периоды фильтрации (секунды)
    private static final long PERIOD_1H  = 3600;
    private static final long PERIOD_6H  = 6L * 3600;
    private static final long PERIOD_12H = 12L * 3600;
    private static final long PERIOD_24H = 24L * 3600;
    private static final long PERIOD_48H = 48L * 3600;
    private static final long PERIOD_1W  = 7L * 86400;
    private static final long PERIOD_MAX = 0; // без ограничений

    /** Максимальное количество точек на графике для плавного отображения */
    private static final int MAX_CHART_POINTS = 100;

    /** Диапазон LiPo аккумулятора для нормализации напряжения в % (0–100) */
    private static final float VOLTAGE_MIN = 3.0f;
    private static final float VOLTAGE_MAX = 4.2f;

    private final boolean basicOnly;
    private final AreaChart<Number, Number> chart;
    private final AreaChart<Number, Number> rxChart;
    private final AreaChart<Number, Number> rateChart;
    private final AreaChart<Number, Number> txChart;
    private final AreaChart<Number, Number> qualityChart;
    private final AreaChart<Number, Number> hopsChart;
    private ToggleGroup periodGroup;
    private long selectedPeriodSeconds = PERIOD_6H;

    private DeviceState state;
    private String nodeId;
    private final Runnable telemetryListener = () -> Platform.runLater(this::refresh);

    /** Callback, вызываемый после обновления данных (для синхронизации таблицы логов) */
    private Runnable onDataRefreshed;

    /** Последние отфильтрованные записи (для использования в таблице логов) */
    private List<TelemetryEntry> filteredEntries = Collections.emptyList();

    public TelemetryChartPanel() {
        this(false);
    }

    public TelemetryChartPanel(boolean basicOnly) {
        this.basicOnly = basicOnly;
        setSpacing(6);

        // --- Графики ---
        chart = createChart();
        chart.setPrefHeight(200);
        chart.setMinHeight(120);

        if (basicOnly) {
            rxChart = null;
            rateChart = null;
            txChart = null;
            qualityChart = null;
            hopsChart = null;

            StackPane chartWrap = wrapWithOverlay(chart);
            VBox.setVgrow(chartWrap, Priority.ALWAYS);

            HBox periodBar = createPeriodBar();
            getChildren().addAll(chartWrap, periodBar);
        } else {
            String gryCSS = getClass().getResource("/css/chart-green-red-yellow.css").toExternalForm();
            String txCSS = getClass().getResource("/css/chart-tx.css").toExternalForm();

            rxChart = createChart();
            rxChart.setPrefHeight(200);
            rxChart.setMinHeight(120);
            rxChart.getStylesheets().add(gryCSS);

            rateChart = createAutoRangeChart();
            rateChart.setPrefHeight(200);
            rateChart.setMinHeight(120);
            rateChart.getStylesheets().add(gryCSS);

            txChart = createAutoRangeChart();
            txChart.setPrefHeight(200);
            txChart.setMinHeight(120);
            txChart.getStylesheets().add(txCSS);

            String qualityCSS = getClass().getResource("/css/chart-quality.css").toExternalForm();
            qualityChart = createAutoRangeChart();
            qualityChart.setPrefHeight(200);
            qualityChart.setMinHeight(120);
            qualityChart.getStylesheets().add(qualityCSS);

            String hopsCSS = getClass().getResource("/css/chart-hops.css").toExternalForm();
            hopsChart = createAutoRangeChart();
            hopsChart.setPrefHeight(200);
            hopsChart.setMinHeight(120);
            hopsChart.getStylesheets().add(hopsCSS);

            StackPane chartWrap = wrapWithOverlay(chart);
            HBox.setHgrow(chartWrap, Priority.ALWAYS);
            StackPane rxWrap = wrapWithOverlay(rxChart);
            HBox.setHgrow(rxWrap, Priority.ALWAYS);
            StackPane rateWrap = wrapWithOverlay(rateChart);
            HBox.setHgrow(rateWrap, Priority.ALWAYS);
            StackPane txWrap = wrapWithOverlay(txChart);
            HBox.setHgrow(txWrap, Priority.ALWAYS);
            StackPane qualityWrap = wrapWithOverlay(qualityChart);
            HBox.setHgrow(qualityWrap, Priority.ALWAYS);
            StackPane hopsWrap = wrapWithOverlay(hopsChart);
            HBox.setHgrow(hopsWrap, Priority.ALWAYS);

            HBox topRow = new HBox(6, chartWrap, rxWrap);
            VBox.setVgrow(topRow, Priority.ALWAYS);

            HBox middleRow = new HBox(6, rateWrap, txWrap);
            VBox.setVgrow(middleRow, Priority.ALWAYS);

            HBox bottomRow = new HBox(6, qualityWrap, hopsWrap);
            VBox.setVgrow(bottomRow, Priority.ALWAYS);

            HBox periodBar = createPeriodBar();
            getChildren().addAll(topRow, middleRow, bottomRow, periodBar);
        }
    }

    // ==================== Публичный API ====================

    /**
     * Устанавливает callback, вызываемый после каждого обновления данных графика.
     * Используется FormDashboard для синхронизации таблицы логов.
     */
    public void setOnDataRefreshed(Runnable callback) {
        this.onDataRefreshed = callback;
    }

    /**
     * Привязать компонент к состоянию устройства и идентификатору ноды.
     * Подписывается на обновления телеметрии и загружает данные.
     * Если уже привязан к тому же state и nodeId — просто обновляет данные без переподписки.
     */
    public void bind(DeviceState state, String nodeId) {
        if (this.state == state && Objects.equals(this.nodeId, nodeId)) {
            // Уже привязан к тем же данным — просто обновить
            refresh();
            return;
        }

        unbind();
        this.state = state;
        this.nodeId = nodeId;

        if (this.state != null) {
            this.state.addTelemetryListener(telemetryListener);
        }
        refresh();
    }

    /**
     * Отвязать от текущего DeviceState и очистить график.
     */
    public void unbind() {
        if (this.state != null) {
            this.state.removeTelemetryListener(telemetryListener);
        }
        this.state = null;
        this.nodeId = null;
        this.filteredEntries = Collections.emptyList();
        chart.getData().clear();
        chart.setTitle(null);
        ((NumberAxis) chart.getXAxis()).setAutoRanging(true);
        if (rxChart != null) {
            rxChart.getData().clear();
            rxChart.setTitle(null);
            ((NumberAxis) rxChart.getXAxis()).setAutoRanging(true);
        }
        if (rateChart != null) {
            rateChart.getData().clear();
            rateChart.setTitle(null);
            ((NumberAxis) rateChart.getXAxis()).setAutoRanging(true);
        }
        if (txChart != null) {
            txChart.getData().clear();
            txChart.setTitle(null);
            ((NumberAxis) txChart.getXAxis()).setAutoRanging(true);
        }
        if (qualityChart != null) {
            qualityChart.getData().clear();
            qualityChart.setTitle(null);
            ((NumberAxis) qualityChart.getXAxis()).setAutoRanging(true);
        }
        if (hopsChart != null) {
            hopsChart.getData().clear();
            hopsChart.setTitle(null);
            ((NumberAxis) hopsChart.getXAxis()).setAutoRanging(true);
        }
    }

    /**
     * Возвращает последние отфильтрованные записи телеметрии.
     * Используется FormDashboard для таблицы логов.
     */
    public List<TelemetryEntry> getFilteredEntries() {
        return filteredEntries;
    }

    // ==================== Обновление данных ====================

    private void refresh() {
        if (state == null || nodeId == null) {
            chart.getData().clear();
            chart.setTitle("Нет подключения");
            ((NumberAxis) chart.getXAxis()).setAutoRanging(true);
            if (rxChart != null) {
                rxChart.getData().clear();
                rxChart.setTitle(null);
                ((NumberAxis) rxChart.getXAxis()).setAutoRanging(true);
            }
            if (rateChart != null) {
                rateChart.getData().clear();
                rateChart.setTitle(null);
                ((NumberAxis) rateChart.getXAxis()).setAutoRanging(true);
            }
            if (txChart != null) {
                txChart.getData().clear();
                txChart.setTitle(null);
                ((NumberAxis) txChart.getXAxis()).setAutoRanging(true);
            }
            if (qualityChart != null) {
                qualityChart.getData().clear();
                qualityChart.setTitle(null);
                ((NumberAxis) qualityChart.getXAxis()).setAutoRanging(true);
            }
            if (hopsChart != null) {
                hopsChart.getData().clear();
                hopsChart.setTitle(null);
                ((NumberAxis) hopsChart.getXAxis()).setAutoRanging(true);
            }
            filteredEntries = Collections.emptyList();
            return;
        }

        // Вся фильтрация (нода, период, нулевые артефакты, будущие даты) — в SQL
        long now = System.currentTimeMillis() / 1000;
        long sinceEpoch = selectedPeriodSeconds > 0 ? now - selectedPeriodSeconds : 0;
        long maxTs = now + 300; // допуск 5 мин на рассинхронизацию часов

        filteredEntries = NodeCacheService.getInstance()
                .loadTelemetryForNode(nodeId, sinceEpoch, maxTs);

        updateChart(filteredEntries);

        if (onDataRefreshed != null) {
            onDataRefreshed.run();
        }
    }

    // ==================== График ====================

    private AreaChart<Number, Number> createChart() {
        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("");
        xAxis.setForceZeroInRange(false);
        xAxis.setAutoRanging(true);
        xAxis.setTickLabelFormatter(new NumberAxis.DefaultFormatter(xAxis) {
            @Override
            public String toString(Number object) {
                long epoch = object.longValue();
                if (epoch <= 0) { return ""; }
                return Instant.ofEpochSecond(epoch)
                        .atZone(ZoneId.systemDefault())
                        .format(AXIS_FMT);
            }
        });

        NumberAxis yAxis = new NumberAxis(0, 100, 25);
        yAxis.setLabel("");
        yAxis.setAutoRanging(false);

        AreaChart<Number, Number> areaChart = new AreaChart<>(xAxis, yAxis);
        areaChart.setAnimated(false);
        areaChart.setCreateSymbols(false);
        areaChart.setLegendVisible(true);
        areaChart.setTitle(null);
        return areaChart;
    }

    private AreaChart<Number, Number> createAutoRangeChart() {
        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("");
        xAxis.setForceZeroInRange(false);
        xAxis.setAutoRanging(true);
        xAxis.setTickLabelFormatter(new NumberAxis.DefaultFormatter(xAxis) {
            @Override
            public String toString(Number object) {
                long epoch = object.longValue();
                if (epoch <= 0) { return ""; }
                return Instant.ofEpochSecond(epoch)
                        .atZone(ZoneId.systemDefault())
                        .format(AXIS_FMT);
            }
        });

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("");
        yAxis.setAutoRanging(true);
        yAxis.setForceZeroInRange(true);

        AreaChart<Number, Number> areaChart = new AreaChart<>(xAxis, yAxis);
        areaChart.setAnimated(false);
        areaChart.setCreateSymbols(false);
        areaChart.setLegendVisible(true);
        areaChart.setTitle(null);
        return areaChart;
    }

    /**
     * Конвертирует напряжение (В) в процент шкалы 0–100 для отображения на общем графике.
     * LiPo: 3.0V = 0%, 4.2V = 100%.
     */
    private static double voltageToPercent(float voltage) {
        double pct = (voltage - VOLTAGE_MIN) / (VOLTAGE_MAX - VOLTAGE_MIN) * 100.0;
        return Math.max(0, Math.min(100, pct));
    }

    private void updateChart(List<TelemetryEntry> entries) {
        chart.getData().clear();
        NumberAxis xAxis = (NumberAxis) chart.getXAxis();

        NumberAxis rxXAxis = null, rateXAxis = null, txXAxis = null, qualityXAxis = null, hopsXAxis = null;
        if (!basicOnly) {
            rxChart.getData().clear();
            rateChart.getData().clear();
            txChart.getData().clear();
            qualityChart.getData().clear();
            hopsChart.getData().clear();
            rxXAxis = (NumberAxis) rxChart.getXAxis();
            rateXAxis = (NumberAxis) rateChart.getXAxis();
            txXAxis = (NumberAxis) txChart.getXAxis();
            qualityXAxis = (NumberAxis) qualityChart.getXAxis();
            hopsXAxis = (NumberAxis) hopsChart.getXAxis();
        }

        if (entries.isEmpty()) {
            chart.setTitle("Нет данных");
            xAxis.setAutoRanging(true);
            if (!basicOnly) {
                rxChart.setTitle(null);
                rxXAxis.setAutoRanging(true);
                rateChart.setTitle(null);
                rateXAxis.setAutoRanging(true);
                txChart.setTitle(null);
                txXAxis.setAutoRanging(true);
                qualityChart.setTitle(null);
                qualityXAxis.setAutoRanging(true);
                hopsChart.setTitle(null);
                hopsXAxis.setAutoRanging(true);
            }
            return;
        }

        chart.setTitle("Базовые метрики");

        XYChart.Series<Number, Number> batterySeries = new XYChart.Series<>();
        batterySeries.setName("Battery %");
        XYChart.Series<Number, Number> voltageSeries = new XYChart.Series<>();
        voltageSeries.setName("Voltage В");
        XYChart.Series<Number, Number> chUtilSeries = new XYChart.Series<>();
        chUtilSeries.setName("ChUtil %");
        XYChart.Series<Number, Number> airUtilSeries = new XYChart.Series<>();
        airUtilSeries.setName("AirUtil %");
        XYChart.Series<Number, Number> goodRxSeries = null, badRxSeries = null, dupeRxSeries = null;
        XYChart.Series<Number, Number> pktRxSeries = null, pktBadSeries = null, pktDupeSeries = null;
        XYChart.Series<Number, Number> txSeries = null, txDroppedSeries = null, txRelaySeries = null, txRelayCanceledSeries = null;
        XYChart.Series<Number, Number> snrSeries = null, rssiSeries = null;
        XYChart.Series<Number, Number> avgHopsSeries = null, maxHopsSeries = null, minHopsSeries = null;

        if (!basicOnly) {
            goodRxSeries = new XYChart.Series<>();
            goodRxSeries.setName("Good RX %");
            badRxSeries = new XYChart.Series<>();
            badRxSeries.setName("Bad RX %");
            dupeRxSeries = new XYChart.Series<>();
            dupeRxSeries.setName("Dupe RX %");

            pktRxSeries = new XYChart.Series<>();
            pktRxSeries.setName("Packets Received");
            pktBadSeries = new XYChart.Series<>();
            pktBadSeries.setName("Bad Packets");
            pktDupeSeries = new XYChart.Series<>();
            pktDupeSeries.setName("Duplicates");

            txSeries = new XYChart.Series<>();
            txSeries.setName("Packets Transmitted");
            txDroppedSeries = new XYChart.Series<>();
            txDroppedSeries.setName("Dropped");
            txRelaySeries = new XYChart.Series<>();
            txRelaySeries.setName("Relayed");
            txRelayCanceledSeries = new XYChart.Series<>();
            txRelayCanceledSeries.setName("Relay Canceled");

            snrSeries = new XYChart.Series<>();
            snrSeries.setName("SNR (dB)");
            rssiSeries = new XYChart.Series<>();
            rssiSeries.setName("RSSI (dBm)");

            avgHopsSeries = new XYChart.Series<>();
            avgHopsSeries.setName("Среднее");
            maxHopsSeries = new XYChart.Series<>();
            maxHopsSeries.setName("Макс");
            minHopsSeries = new XYChart.Series<>();
            minHopsSeries.setName("Мин");
        }

        long minTs = entries.getFirst().getTimestamp();
        long maxTs = entries.getLast().getTimestamp();

        if (entries.size() <= MAX_CHART_POINTS) {
            for (TelemetryEntry e : entries) {
                long ts = e.getTimestamp();
                if (e.getBatteryLevel() > 0 && e.getBatteryLevel() <= 100) {
                    batterySeries.getData().add(new XYChart.Data<>(ts, e.getBatteryLevel()));
                }
                if (e.getVoltage() > 0) {
                    voltageSeries.getData().add(new XYChart.Data<>(ts, voltageToPercent(e.getVoltage())));
                }
                chUtilSeries.getData().add(new XYChart.Data<>(ts, e.getChannelUtilization()));
                airUtilSeries.getData().add(new XYChart.Data<>(ts, e.getAirUtilTx()));
                if (!basicOnly && e.getNumPacketsRx() > 0) {
                    double total = e.getNumPacketsRx();
                    goodRxSeries.getData().add(new XYChart.Data<>(ts, (total - e.getNumPacketsRxBad() - e.getNumRxDupe()) / total * 100.0));
                    badRxSeries.getData().add(new XYChart.Data<>(ts, e.getNumPacketsRxBad() / total * 100.0));
                    dupeRxSeries.getData().add(new XYChart.Data<>(ts, e.getNumRxDupe() / total * 100.0));
                    pktRxSeries.getData().add(new XYChart.Data<>(ts, e.getNumPacketsRx()));
                    pktBadSeries.getData().add(new XYChart.Data<>(ts, e.getNumPacketsRxBad()));
                    pktDupeSeries.getData().add(new XYChart.Data<>(ts, e.getNumRxDupe()));
                }
                if (!basicOnly && e.getNumPacketsTx() > 0) {
                    txSeries.getData().add(new XYChart.Data<>(ts, e.getNumPacketsTx()));
                    txDroppedSeries.getData().add(new XYChart.Data<>(ts, e.getNumTxDropped()));
                    txRelaySeries.getData().add(new XYChart.Data<>(ts, e.getNumTxRelay()));
                    txRelayCanceledSeries.getData().add(new XYChart.Data<>(ts, e.getNumTxRelayCanceled()));
                }
                if (!basicOnly && (e.getRxSnr() != 0 || e.getRxRssi() != 0)) {
                    snrSeries.getData().add(new XYChart.Data<>(ts, e.getRxSnr()));
                    rssiSeries.getData().add(new XYChart.Data<>(ts, e.getRxRssi()));
                }
                if (!basicOnly && e.getHopStart() > 0) {
                    int hops = e.getHopsTraveled();
                    avgHopsSeries.getData().add(new XYChart.Data<>(ts, hops));
                    maxHopsSeries.getData().add(new XYChart.Data<>(ts, hops));
                    minHopsSeries.getData().add(new XYChart.Data<>(ts, hops));
                }
            }
        } else {
            long dataRange = maxTs - minTs;
            int bucketCount = MAX_CHART_POINTS;
            long bucketSize = Math.max(dataRange / bucketCount, 1);

            int idx = 0;
            for (int b = 0; b < bucketCount && idx < entries.size(); b++) {
                long bucketStart = minTs + (long) b * bucketSize;
                long bucketEnd = bucketStart + bucketSize;

                double sumBattery = 0, sumVoltage = 0, sumChUtil = 0, sumAirUtil = 0;
                double sumGoodRx = 0, sumBadRx = 0, sumDupeRx = 0;
                double sumPktRx = 0, sumPktBad = 0, sumPktDupe = 0;
                double sumTx = 0, sumTxDropped = 0, sumTxRelay = 0, sumTxRelayCanceled = 0;
                double sumSnr = 0, sumRssi = 0;
                double sumHops = 0; int maxHops = Integer.MIN_VALUE, minHops = Integer.MAX_VALUE;
                int countBattery = 0, countVoltage = 0, count = 0;
                int countRx = 0, countTx = 0, countQuality = 0, countHops = 0;

                while (idx < entries.size() && entries.get(idx).getTimestamp() < bucketEnd) {
                    TelemetryEntry e = entries.get(idx);
                    if (e.getBatteryLevel() > 0 && e.getBatteryLevel() <= 100) {
                        sumBattery += e.getBatteryLevel();
                        countBattery++;
                    }
                    if (e.getVoltage() > 0) {
                        sumVoltage += voltageToPercent(e.getVoltage());
                        countVoltage++;
                    }
                    sumChUtil += e.getChannelUtilization();
                    sumAirUtil += e.getAirUtilTx();
                    if (!basicOnly && e.getNumPacketsRx() > 0) {
                        double total = e.getNumPacketsRx();
                        sumGoodRx += (total - e.getNumPacketsRxBad() - e.getNumRxDupe()) / total * 100.0;
                        sumBadRx += e.getNumPacketsRxBad() / total * 100.0;
                        sumDupeRx += e.getNumRxDupe() / total * 100.0;
                        sumPktRx += e.getNumPacketsRx();
                        sumPktBad += e.getNumPacketsRxBad();
                        sumPktDupe += e.getNumRxDupe();
                        countRx++;
                    }
                    if (!basicOnly && e.getNumPacketsTx() > 0) {
                        sumTx += e.getNumPacketsTx();
                        sumTxDropped += e.getNumTxDropped();
                        sumTxRelay += e.getNumTxRelay();
                        sumTxRelayCanceled += e.getNumTxRelayCanceled();
                        countTx++;
                    }
                    if (!basicOnly && (e.getRxSnr() != 0 || e.getRxRssi() != 0)) {
                        sumSnr += e.getRxSnr();
                        sumRssi += e.getRxRssi();
                        countQuality++;
                    }
                    if (!basicOnly && e.getHopStart() > 0) {
                        int hops = e.getHopsTraveled();
                        sumHops += hops;
                        maxHops = Math.max(maxHops, hops);
                        minHops = Math.min(minHops, hops);
                        countHops++;
                    }
                    count++;
                    idx++;
                }

                if (count > 0) {
                    long bucketCenter = bucketStart + bucketSize / 2;
                    if (countBattery > 0) {
                        batterySeries.getData().add(new XYChart.Data<>(bucketCenter, sumBattery / countBattery));
                    }
                    if (countVoltage > 0) {
                        voltageSeries.getData().add(new XYChart.Data<>(bucketCenter, sumVoltage / countVoltage));
                    }
                    chUtilSeries.getData().add(new XYChart.Data<>(bucketCenter, sumChUtil / count));
                    airUtilSeries.getData().add(new XYChart.Data<>(bucketCenter, sumAirUtil / count));
                    if (countRx > 0) {
                        goodRxSeries.getData().add(new XYChart.Data<>(bucketCenter, sumGoodRx / countRx));
                        badRxSeries.getData().add(new XYChart.Data<>(bucketCenter, sumBadRx / countRx));
                        dupeRxSeries.getData().add(new XYChart.Data<>(bucketCenter, sumDupeRx / countRx));
                        pktRxSeries.getData().add(new XYChart.Data<>(bucketCenter, sumPktRx / countRx));
                        pktBadSeries.getData().add(new XYChart.Data<>(bucketCenter, sumPktBad / countRx));
                        pktDupeSeries.getData().add(new XYChart.Data<>(bucketCenter, sumPktDupe / countRx));
                    }
                    if (countTx > 0) {
                        txSeries.getData().add(new XYChart.Data<>(bucketCenter, sumTx / countTx));
                        txDroppedSeries.getData().add(new XYChart.Data<>(bucketCenter, sumTxDropped / countTx));
                        txRelaySeries.getData().add(new XYChart.Data<>(bucketCenter, sumTxRelay / countTx));
                        txRelayCanceledSeries.getData().add(new XYChart.Data<>(bucketCenter, sumTxRelayCanceled / countTx));
                    }
                    if (countQuality > 0) {
                        snrSeries.getData().add(new XYChart.Data<>(bucketCenter, sumSnr / countQuality));
                        rssiSeries.getData().add(new XYChart.Data<>(bucketCenter, sumRssi / countQuality));
                    }
                    if (countHops > 0) {
                        avgHopsSeries.getData().add(new XYChart.Data<>(bucketCenter, sumHops / countHops));
                        maxHopsSeries.getData().add(new XYChart.Data<>(bucketCenter, maxHops));
                        minHopsSeries.getData().add(new XYChart.Data<>(bucketCenter, minHops));
                    }
                }
            }
        }

        // Синхронизируем границы оси X на обоих графиках
        long range = maxTs - minTs;
        long padding = Math.max(range / 20, 60);
        long lowerBound = minTs - padding;
        long upperBound = maxTs + padding;
        long tickUnit = Math.max((range + 2 * padding) / 8, 60);

        xAxis.setAutoRanging(false);
        xAxis.setLowerBound(lowerBound);
        xAxis.setUpperBound(upperBound);
        xAxis.setTickUnit(tickUnit);

        // Базовые метрики
        if (!batterySeries.getData().isEmpty()) { chart.getData().add(batterySeries); }
        if (!voltageSeries.getData().isEmpty()) { chart.getData().add(voltageSeries); }
        chart.getData().add(chUtilSeries);
        chart.getData().add(airUtilSeries);

        if (!basicOnly) {
            rxXAxis.setAutoRanging(false);
            rxXAxis.setLowerBound(lowerBound);
            rxXAxis.setUpperBound(upperBound);
            rxXAxis.setTickUnit(tickUnit);

            rateXAxis.setAutoRanging(false);
            rateXAxis.setLowerBound(lowerBound);
            rateXAxis.setUpperBound(upperBound);
            rateXAxis.setTickUnit(tickUnit);

            txXAxis.setAutoRanging(false);
            txXAxis.setLowerBound(lowerBound);
            txXAxis.setUpperBound(upperBound);
            txXAxis.setTickUnit(tickUnit);

            // Статистика эфира (Good=зеленый, Bad=красный, Dupe=желтый)
            boolean hasRxData = !goodRxSeries.getData().isEmpty();
            if (hasRxData) {
                rxChart.setTitle("Статистика эфира");
                rxChart.getData().add(goodRxSeries);
                rxChart.getData().add(badRxSeries);
                rxChart.getData().add(dupeRxSeries);
            } else {
                rxChart.setTitle(null);
            }

            // Скорость приема (Received=зеленый, Bad=красный, Duplicates=желтый)
            boolean hasRateData = !pktRxSeries.getData().isEmpty();
            if (hasRateData) {
                rateChart.setTitle("Скорость приема");
                rateChart.getData().add(pktRxSeries);
                rateChart.getData().add(pktBadSeries);
                rateChart.getData().add(pktDupeSeries);
            } else {
                rateChart.setTitle(null);
            }

            // Скорость передачи (Transmitted=зеленый, Dropped=красный, Relayed=желтый, Canceled=фиолетовый)
            boolean hasTxData = !txSeries.getData().isEmpty();
            if (hasTxData) {
                txChart.setTitle("Скорость передачи");
                txChart.getData().add(txSeries);
                txChart.getData().add(txDroppedSeries);
                txChart.getData().add(txRelaySeries);
                txChart.getData().add(txRelayCanceledSeries);
            } else {
                txChart.setTitle(null);
            }

            // Качество соединения (SNR=зеленый, RSSI=красный)
            qualityXAxis.setAutoRanging(false);
            qualityXAxis.setLowerBound(lowerBound);
            qualityXAxis.setUpperBound(upperBound);
            qualityXAxis.setTickUnit(tickUnit);

            boolean hasQualityData = !snrSeries.getData().isEmpty();
            if (hasQualityData) {
                qualityChart.setTitle("Качество соединения");
                qualityChart.getData().add(snrSeries);
                qualityChart.getData().add(rssiSeries);
            } else {
                qualityChart.setTitle(null);
            }

            // Прыжки (Среднее=синий, Макс=оранжевый, Мин=бирюзовый)
            hopsXAxis.setAutoRanging(false);
            hopsXAxis.setLowerBound(lowerBound);
            hopsXAxis.setUpperBound(upperBound);
            hopsXAxis.setTickUnit(tickUnit);

            boolean hasHopsData = !avgHopsSeries.getData().isEmpty();
            if (hasHopsData) {
                hopsChart.setTitle("Прыжки");
                hopsChart.getData().add(avgHopsSeries);
                hopsChart.getData().add(maxHopsSeries);
                hopsChart.getData().add(minHopsSeries);
            } else {
                hopsChart.setTitle(null);
            }
        }
    }

    // ==================== Панель фильтра периода ====================

    private HBox createPeriodBar() {
        periodGroup = new ToggleGroup();

        ToggleButton btn1h  = periodButton("1ч",  PERIOD_1H);
        ToggleButton btn6h  = periodButton("6ч",  PERIOD_6H);
        ToggleButton btn12h = periodButton("12ч", PERIOD_12H);
        ToggleButton btn24h = periodButton("24ч", PERIOD_24H);
        ToggleButton btn48h = periodButton("48ч", PERIOD_48H);
        ToggleButton btn1w  = periodButton("1нед", PERIOD_1W);
        ToggleButton btnMax = periodButton("Всё",  PERIOD_MAX);

        btn6h.setSelected(true);

        HBox bar = new HBox(0, btn1h, btn6h, btn12h, btn24h, btn48h, btn1w, btnMax);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-border-color: -color-border-default; -fx-border-radius: 6; -fx-background-radius: 6;");
        return bar;
    }

    private ToggleButton periodButton(String text, long periodSeconds) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(periodGroup);
        btn.setUserData(periodSeconds);
        btn.setPadding(new Insets(6, 16, 6, 16));
        btn.setStyle("-fx-background-radius: 0; -fx-font-size: 12px;");
        btn.setOnAction(e -> {
            selectedPeriodSeconds = periodSeconds;
            refresh();
        });
        return btn;
    }

    // ==================== Интерактивный курсор ====================

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
                                  Line crosshair, Label valueLabel, StackPane wrapper) {
        areaChart.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) return;
            Platform.runLater(() -> {
                Node plotBg = areaChart.lookup(".chart-plot-background");
                if (!(plotBg instanceof Region plotArea)) return;

                Node chartContent = areaChart.lookup(".chart-content");
                if (chartContent == null) return;

                // Event filter перехватывает клики ДО дочерних элементов (area fill, line),
                // поэтому работает и на заполненной области графика
                chartContent.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
                    Point2D local = plotArea.sceneToLocal(event.getSceneX(), event.getSceneY());
                    if (plotArea.contains(local)) {
                        showCrosshair(local.getX(), areaChart, crosshair, valueLabel, wrapper, plotArea);
                    }
                });

                chartContent.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
                    Point2D local = plotArea.sceneToLocal(event.getSceneX(), event.getSceneY());
                    if (!plotArea.contains(local)) {
                        crosshair.setVisible(false);
                        valueLabel.setVisible(false);
                    }
                });
            });
        });
    }

    private void showCrosshair(double localX, AreaChart<Number, Number> areaChart,
                               Line crosshair, Label valueLabel, StackPane wrapper, Region plotArea) {
        if (areaChart.getData().isEmpty()) return;

        NumberAxis xAxis = (NumberAxis) areaChart.getXAxis();
        long epoch = xAxis.getValueForDisplay(localX).longValue();

        // Форматируем время
        String timeStr = Instant.ofEpochSecond(epoch)
                .atZone(ZoneId.systemDefault())
                .format(AXIS_FMT);

        StringBuilder sb = new StringBuilder(timeStr).append("\n");

        // Значения всех серий в ближайшей точке
        for (XYChart.Series<Number, Number> series : areaChart.getData()) {
            XYChart.Data<Number, Number> nearest = findNearest(series, epoch);
            if (nearest != null) {
                double val = nearest.getYValue().doubleValue();
                sb.append(series.getName()).append(": ").append(String.format("%.1f", val)).append("\n");
            }
        }

        valueLabel.setText(sb.toString().strip());

        // Позиция курсора: конвертируем из координат plotArea в координаты wrapper
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

        // Label позиция — справа от курсора, но если близко к правому краю — слева
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

    /**
     * Находит ближайшую по оси X точку в серии.
     */
    private static XYChart.Data<Number, Number> findNearest(XYChart.Series<Number, Number> series, long targetEpoch) {
        if (series.getData().isEmpty()) return null;
        XYChart.Data<Number, Number> best = null;
        long bestDist = Long.MAX_VALUE;
        for (XYChart.Data<Number, Number> d : series.getData()) {
            long dist = Math.abs(d.getXValue().longValue() - targetEpoch);
            if (dist < bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        return best;
    }
}
