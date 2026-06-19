package com.meshtastic.client.components;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.utils.BatteryLevelEstimator;
import javafx.scene.chart.XYChart;

import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TelemetryChartDataBuilder {

    enum ChartKind {
        BASIC,
        RX,
        RATE,
        TX,
        QUALITY,
        HOPS,
        TEMPERATURE,
        HUMIDITY,
        PRESSURE,
        RADIATION
    }

    private static final String TITLE_BASIC_KEY = "telemetry.chart.title.basic";
    private static final String TITLE_RX_KEY = "telemetry.chart.title.rx";
    private static final String TITLE_RATE_KEY = "telemetry.chart.title.rate";
    private static final String TITLE_TX_KEY = "telemetry.chart.title.tx";
    private static final String TITLE_QUALITY_KEY = "telemetry.chart.title.quality";
    private static final String TITLE_HOPS_KEY = "telemetry.chart.title.hops";
    private static final String TITLE_ENVIRONMENT_KEY = "telemetry.chart.title.environment";
    private static final String TITLE_TEMPERATURE_KEY = "telemetry.chart.title.temperature";
    private static final String TITLE_HUMIDITY_KEY = "telemetry.chart.title.humidity";
    private static final String TITLE_PRESSURE_KEY = "telemetry.chart.title.pressure";
    private static final String TITLE_RADIATION_KEY = "telemetry.chart.title.radiation";

    private static final String SERIES_BATTERY_KEY = "telemetry.chart.series.battery";
    private static final String SERIES_VOLTAGE_KEY = "telemetry.chart.series.voltage";
    private static final String SERIES_CH_UTIL_KEY = "telemetry.chart.series.chUtil";
    private static final String SERIES_AIR_UTIL_KEY = "telemetry.chart.series.airUtil";
    private static final String SERIES_GOOD_RX_KEY = "telemetry.chart.series.goodRx";
    private static final String SERIES_BAD_RX_KEY = "telemetry.chart.series.badRx";
    private static final String SERIES_DUPE_RX_KEY = "telemetry.chart.series.dupeRx";
    private static final String SERIES_PACKETS_RECEIVED_KEY = "telemetry.chart.series.packetsReceived";
    private static final String SERIES_BAD_PACKETS_KEY = "telemetry.chart.series.badPackets";
    private static final String SERIES_DUPLICATES_KEY = "telemetry.chart.series.duplicates";
    private static final String SERIES_PACKETS_TRANSMITTED_KEY = "telemetry.chart.series.packetsTransmitted";
    private static final String SERIES_DROPPED_KEY = "telemetry.chart.series.dropped";
    private static final String SERIES_RELAYED_KEY = "telemetry.chart.series.relayed";
    private static final String SERIES_RELAY_CANCELED_KEY = "telemetry.chart.series.relayCanceled";
    private static final String SERIES_SNR_KEY = "telemetry.chart.series.snr";
    private static final String SERIES_RSSI_KEY = "telemetry.chart.series.rssi";
    private static final String SERIES_HOPS_MAX_KEY = "telemetry.chart.series.hopsMax";
    private static final String SERIES_HOPS_MIN_KEY = "telemetry.chart.series.hopsMin";
    private static final String SERIES_HOPS_AVG_KEY = "telemetry.chart.series.hopsAvg";
    private static final String SERIES_TEMPERATURE_KEY = "telemetry.chart.series.temperature";
    private static final String SERIES_HUMIDITY_KEY = "telemetry.chart.series.humidity";
    private static final String SERIES_PRESSURE_KEY = "telemetry.chart.series.pressure";
    private static final String SERIES_RADIATION_KEY = "telemetry.chart.series.radiation";

    private static final int MAX_CHART_POINTS = 60;
    private static final long EMPTY_PERIOD_FALLBACK = 24L * 3600;
    private static final Predicate<TelemetryEntry> HAS_BATTERY =
            entry -> BatteryLevelEstimator.hasBatteryPercent(entry.getBatteryLevel(), entry.getVoltage());
    private static final Predicate<TelemetryEntry> HAS_VOLTAGE = entry -> entry.getVoltage() > 0;
    private static final Predicate<TelemetryEntry> HAS_RX_COUNTERS = entry -> entry.getNumPacketsRx() > 0;
    private static final Predicate<TelemetryEntry> HAS_TX_COUNTERS = entry -> entry.getNumPacketsTx() > 0;
    private static final Predicate<TelemetryEntry> HAS_QUALITY =
            entry -> entry.getRxSnr() != 0 || entry.getRxRssi() != 0;
    private static final Predicate<TelemetryEntry> HAS_HOPS = TelemetryEntry::hasValidHopData;
    private static final Predicate<TelemetryEntry> HAS_TEMPERATURE = entry -> entry.getTemperature() != 0;
    private static final Predicate<TelemetryEntry> HAS_HUMIDITY = entry -> entry.getRelativeHumidity() != 0;
    private static final Predicate<TelemetryEntry> HAS_PRESSURE = entry -> entry.getBarometricPressure() != 0;
    private static final Predicate<TelemetryEntry> HAS_RADIATION = entry -> entry.getRadiation() != null;

    private TelemetryChartDataBuilder() {
    }

    record AxisRange(long lowerBound, long upperBound, long tickUnit) {}

    record ChartPayload(String title, List<XYChart.Series<Number, Number>> series) {}

    record PreparedCharts(Map<ChartKind, AxisRange> axisRanges, Map<ChartKind, ChartPayload> payloads) {
        AxisRange axisRange(ChartKind chartKind) {
            return Optional.ofNullable(axisRanges.get(chartKind))
                    .orElseThrow(() -> new IllegalArgumentException("Missing chart axis range: " + chartKind));
        }

        ChartPayload payload(ChartKind chartKind) {
            return Optional.ofNullable(payloads.get(chartKind))
                    .orElseThrow(() -> new IllegalArgumentException("Missing chart payload: " + chartKind));
        }
    }

    private record Bucket<T>(long center, List<T> items) {}

    private record RxMetric(long timestamp, double received, double bad, double duplicate) {}

    private record TxMetric(long timestamp, double transmitted, double dropped, double relayed, double canceled) {}

    private record QualityMetric(long timestamp, double snr, double rssi) {}

    private record HopMetric(long timestamp, double average, double max, double min) {}

    @FunctionalInterface
    private interface PointFactory {
        XYChart.Data<Number, Number> create(long timestamp, double value);
    }

    static PreparedCharts build(boolean basicOnly,
                                List<TelemetryEntry> entries,
                                List<TelemetryEntry> qualityEntries,
                                long selectedPeriodSeconds) {
        AxisRange primaryAxisRange = buildAxisRange(entries, List.of(), selectedPeriodSeconds);
        AxisRange qualityAxisRange = qualityEntries.isEmpty()
                ? primaryAxisRange
                : buildAxisRange(List.of(), qualityEntries, selectedPeriodSeconds);

        Map<ChartKind, ChartPayload> payloads = Stream.concat(
                Stream.of(Map.entry(ChartKind.BASIC, buildBasicChart(entries))),
                basicOnly ? Stream.empty() : secondaryPayloads(entries, qualityEntries))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        Map<ChartKind, AxisRange> axisRanges = new LinkedHashMap<>();
        axisRanges.put(ChartKind.BASIC, primaryAxisRange);
        if (!basicOnly) {
            axisRanges.put(ChartKind.RX, primaryAxisRange);
            axisRanges.put(ChartKind.RATE, primaryAxisRange);
            axisRanges.put(ChartKind.TX, primaryAxisRange);
            axisRanges.put(ChartKind.QUALITY, qualityAxisRange);
            axisRanges.put(ChartKind.HOPS, qualityAxisRange);
            axisRanges.put(ChartKind.TEMPERATURE, primaryAxisRange);
            axisRanges.put(ChartKind.HUMIDITY, primaryAxisRange);
            axisRanges.put(ChartKind.PRESSURE, primaryAxisRange);
            axisRanges.put(ChartKind.RADIATION, primaryAxisRange);
        }

        return new PreparedCharts(Map.copyOf(axisRanges), Map.copyOf(payloads));
    }

    private static Stream<Map.Entry<ChartKind, ChartPayload>> secondaryPayloads(List<TelemetryEntry> entries,
                                                                                List<TelemetryEntry> qualityEntries) {
        List<RxMetric> rxMetrics = aggregateIfNeeded(buildRxMetrics(entries), entries, RxMetric::timestamp,
                TelemetryChartDataBuilder::sumRxMetric);
        List<TxMetric> txMetrics = aggregateIfNeeded(buildTxMetrics(entries), entries, TxMetric::timestamp,
                TelemetryChartDataBuilder::sumTxMetric);
        return Stream.of(
                Map.entry(ChartKind.RX, buildRxChart(rxMetrics)),
                Map.entry(ChartKind.RATE, buildRateChart(rxMetrics)),
                Map.entry(ChartKind.TX, buildTxChart(txMetrics)),
                Map.entry(ChartKind.QUALITY, buildQualityChart(qualityEntries)),
                Map.entry(ChartKind.HOPS, buildHopsChart(qualityEntries)),
                Map.entry(ChartKind.TEMPERATURE, buildEnvironmentChart(
                        entries,
                        TITLE_TEMPERATURE_KEY,
                        SERIES_TEMPERATURE_KEY,
                        HAS_TEMPERATURE,
                        TelemetryEntry::getTemperature)),
                Map.entry(ChartKind.HUMIDITY, buildEnvironmentChart(
                        entries,
                        TITLE_HUMIDITY_KEY,
                        SERIES_HUMIDITY_KEY,
                        HAS_HUMIDITY,
                        TelemetryEntry::getRelativeHumidity)),
                Map.entry(ChartKind.PRESSURE, buildEnvironmentChart(
                        entries,
                        TITLE_PRESSURE_KEY,
                        SERIES_PRESSURE_KEY,
                        HAS_PRESSURE,
                        TelemetryEntry::getBarometricPressure)),
                Map.entry(ChartKind.RADIATION, buildEnvironmentChart(
                        entries,
                        TITLE_RADIATION_KEY,
                        SERIES_RADIATION_KEY,
                        HAS_RADIATION,
                        entry -> entry.getRadiation()))
        );
    }

    private static ChartPayload buildBasicChart(List<TelemetryEntry> entries) {
        if (isBucketed(entries)) {
            List<Bucket<TelemetryEntry>> buckets = bucketize(entries, TelemetryEntry::getTimestamp, MAX_CHART_POINTS);
            return new ChartPayload(t(TITLE_BASIC_KEY), List.of(
                    series(t(SERIES_VOLTAGE_KEY), averageData(buckets, HAS_VOLTAGE, entry -> entry.getVoltage(),
                            TelemetryChartDataBuilder::createVoltageData)),
                    series(t(SERIES_BATTERY_KEY), averageData(buckets, HAS_BATTERY,
                            entry -> BatteryLevelEstimator.effectivePercent(entry.getBatteryLevel(), entry.getVoltage()),
                            TelemetryChartDataBuilder::dataPoint)),
                    series(t(SERIES_CH_UTIL_KEY), averageData(buckets, entry -> true, entry -> entry.getChannelUtilization(),
                            TelemetryChartDataBuilder::dataPoint)),
                    series(t(SERIES_AIR_UTIL_KEY), averageData(buckets, entry -> true, entry -> entry.getAirUtilTx(),
                            TelemetryChartDataBuilder::dataPoint))
            ));
        }

        return new ChartPayload(t(TITLE_BASIC_KEY), List.of(
                series(t(SERIES_VOLTAGE_KEY), pointData(entries, HAS_VOLTAGE, TelemetryEntry::getTimestamp,
                        entry -> entry.getVoltage(), TelemetryChartDataBuilder::createVoltageData)),
                series(t(SERIES_BATTERY_KEY), pointData(entries, HAS_BATTERY, TelemetryEntry::getTimestamp,
                        entry -> BatteryLevelEstimator.effectivePercent(entry.getBatteryLevel(), entry.getVoltage()),
                        TelemetryChartDataBuilder::dataPoint)),
                series(t(SERIES_CH_UTIL_KEY), pointData(entries, entry -> true, TelemetryEntry::getTimestamp,
                        entry -> entry.getChannelUtilization(), TelemetryChartDataBuilder::dataPoint)),
                series(t(SERIES_AIR_UTIL_KEY), pointData(entries, entry -> true, TelemetryEntry::getTimestamp,
                        entry -> entry.getAirUtilTx(), TelemetryChartDataBuilder::dataPoint))
        ));
    }

    private static ChartPayload buildRxChart(List<RxMetric> metrics) {
        return new ChartPayload(t(TITLE_RX_KEY), List.of(
                series(t(SERIES_GOOD_RX_KEY), metrics.stream()
                        .filter(metric -> metric.received() > 0)
                        .map(metric -> dataPoint(metric.timestamp(),
                                (metric.received() - metric.bad() - metric.duplicate()) / metric.received() * 100.0))
                        .toList()),
                series(t(SERIES_BAD_RX_KEY), metrics.stream()
                        .filter(metric -> metric.received() > 0)
                        .map(metric -> dataPoint(metric.timestamp(), metric.bad() / metric.received() * 100.0))
                        .toList()),
                series(t(SERIES_DUPE_RX_KEY), metrics.stream()
                        .filter(metric -> metric.received() > 0)
                        .map(metric -> dataPoint(metric.timestamp(), metric.duplicate() / metric.received() * 100.0))
                        .toList())
        ));
    }

    private static ChartPayload buildRateChart(List<RxMetric> metrics) {
        return new ChartPayload(t(TITLE_RATE_KEY), List.of(
                series(t(SERIES_PACKETS_RECEIVED_KEY), pointData(metrics, metric -> true, RxMetric::timestamp,
                        RxMetric::received, TelemetryChartDataBuilder::dataPoint)),
                series(t(SERIES_BAD_PACKETS_KEY), pointData(metrics, metric -> true, RxMetric::timestamp,
                        RxMetric::bad, TelemetryChartDataBuilder::dataPoint)),
                series(t(SERIES_DUPLICATES_KEY), pointData(metrics, metric -> true, RxMetric::timestamp,
                        RxMetric::duplicate, TelemetryChartDataBuilder::dataPoint))
        ));
    }

    private static ChartPayload buildTxChart(List<TxMetric> metrics) {
        return new ChartPayload(t(TITLE_TX_KEY), List.of(
                series(t(SERIES_PACKETS_TRANSMITTED_KEY), pointData(metrics, metric -> true, TxMetric::timestamp,
                        TxMetric::transmitted, TelemetryChartDataBuilder::dataPoint)),
                series(t(SERIES_DROPPED_KEY), pointData(metrics, metric -> true, TxMetric::timestamp,
                        TxMetric::dropped, TelemetryChartDataBuilder::dataPoint)),
                series(t(SERIES_RELAYED_KEY), pointData(metrics, metric -> true, TxMetric::timestamp,
                        TxMetric::relayed, TelemetryChartDataBuilder::dataPoint)),
                series(t(SERIES_RELAY_CANCELED_KEY), pointData(metrics, metric -> true, TxMetric::timestamp,
                        TxMetric::canceled, TelemetryChartDataBuilder::dataPoint))
        ));
    }

    private static ChartPayload buildQualityChart(List<TelemetryEntry> qualityEntries) {
        List<QualityMetric> pointMetrics = qualityEntries.stream()
                .filter(HAS_QUALITY)
                .map(entry -> new QualityMetric(entry.getTimestamp(), entry.getRxSnr(), entry.getRxRssi()))
                .toList();
        List<QualityMetric> metrics = aggregateIfNeeded(pointMetrics, qualityEntries, QualityMetric::timestamp,
                TelemetryChartDataBuilder::averageQualityMetric);
        return new ChartPayload(t(TITLE_QUALITY_KEY), List.of(
                series(t(SERIES_SNR_KEY), pointData(metrics, metric -> true, QualityMetric::timestamp,
                        QualityMetric::snr, TelemetryChartDataBuilder::dataPoint)),
                series(t(SERIES_RSSI_KEY), pointData(metrics, metric -> true, QualityMetric::timestamp,
                        QualityMetric::rssi, TelemetryChartDataBuilder::dataPoint))
        ));
    }

    private static ChartPayload buildHopsChart(List<TelemetryEntry> qualityEntries) {
        List<HopMetric> metrics = buildHopMetrics(qualityEntries);
        return new ChartPayload(t(TITLE_HOPS_KEY), List.of(
                series(t(SERIES_HOPS_MAX_KEY), pointData(metrics, metric -> true, HopMetric::timestamp,
                        HopMetric::max, TelemetryChartDataBuilder::dataPoint)),
                series(t(SERIES_HOPS_MIN_KEY), pointData(metrics, metric -> true, HopMetric::timestamp,
                        HopMetric::min, TelemetryChartDataBuilder::dataPoint)),
                series(t(SERIES_HOPS_AVG_KEY), pointData(metrics, metric -> true, HopMetric::timestamp,
                        HopMetric::average, TelemetryChartDataBuilder::dataPoint))
        ));
    }

    static ChartPayload buildEnvironmentMetricsChart(List<TelemetryEntry> entries) {
        if (isBucketed(entries)) {
            List<Bucket<TelemetryEntry>> buckets = bucketize(entries, TelemetryEntry::getTimestamp, MAX_CHART_POINTS);
            return new ChartPayload(t(TITLE_ENVIRONMENT_KEY), List.of(
                    series(t(SERIES_TEMPERATURE_KEY), averageData(buckets, HAS_TEMPERATURE,
                            TelemetryEntry::getTemperature, TelemetryChartDataBuilder::dataPoint)),
                    series(t(SERIES_HUMIDITY_KEY), averageData(buckets, HAS_HUMIDITY,
                            TelemetryEntry::getRelativeHumidity, TelemetryChartDataBuilder::dataPoint)),
                    series(t(SERIES_PRESSURE_KEY), List.of()),
                    series(t(SERIES_RADIATION_KEY), averageData(buckets, HAS_RADIATION,
                            entry -> entry.getRadiation(), TelemetryChartDataBuilder::dataPoint))
            ));
        }

        return new ChartPayload(t(TITLE_ENVIRONMENT_KEY), List.of(
                series(t(SERIES_TEMPERATURE_KEY), pointData(entries, HAS_TEMPERATURE, TelemetryEntry::getTimestamp,
                        TelemetryEntry::getTemperature, TelemetryChartDataBuilder::dataPoint)),
                series(t(SERIES_HUMIDITY_KEY), pointData(entries, HAS_HUMIDITY, TelemetryEntry::getTimestamp,
                        TelemetryEntry::getRelativeHumidity, TelemetryChartDataBuilder::dataPoint)),
                series(t(SERIES_PRESSURE_KEY), List.of()),
                series(t(SERIES_RADIATION_KEY), pointData(entries, HAS_RADIATION, TelemetryEntry::getTimestamp,
                        entry -> entry.getRadiation(), TelemetryChartDataBuilder::dataPoint))
        ));
    }

    static ChartPayload buildEnvironmentPressureChart(List<TelemetryEntry> entries) {
        return buildEnvironmentChart(
                entries,
                TITLE_ENVIRONMENT_KEY,
                SERIES_PRESSURE_KEY,
                HAS_PRESSURE,
                TelemetryEntry::getBarometricPressure
        );
    }

    private static ChartPayload buildEnvironmentChart(List<TelemetryEntry> entries,
                                                      String titleKey,
                                                      String seriesKey,
                                                      Predicate<TelemetryEntry> filter,
                                                      ToDoubleFunction<TelemetryEntry> valueExtractor) {
        if (isBucketed(entries)) {
            List<Bucket<TelemetryEntry>> buckets = bucketize(entries, TelemetryEntry::getTimestamp, MAX_CHART_POINTS);
            return new ChartPayload(t(titleKey), List.of(
                    series(t(seriesKey), averageData(buckets, filter, valueExtractor,
                            TelemetryChartDataBuilder::dataPoint))
            ));
        }

        return new ChartPayload(t(titleKey), List.of(
                series(t(seriesKey), pointData(entries, filter, TelemetryEntry::getTimestamp, valueExtractor,
                        TelemetryChartDataBuilder::dataPoint))
        ));
    }

    private static List<RxMetric> buildRxMetrics(List<TelemetryEntry> entries) {
        List<TelemetryEntry> rxEntries = entries.stream()
                .filter(HAS_RX_COUNTERS)
                .toList();
        return IntStream.range(1, rxEntries.size())
                .mapToObj(index -> createRxMetric(rxEntries.get(index - 1), rxEntries.get(index)))
                .toList();
    }

    private static List<TxMetric> buildTxMetrics(List<TelemetryEntry> entries) {
        List<TelemetryEntry> txEntries = entries.stream()
                .filter(HAS_TX_COUNTERS)
                .toList();
        return IntStream.range(1, txEntries.size())
                .mapToObj(index -> createTxMetric(txEntries.get(index - 1), txEntries.get(index)))
                .toList();
    }

    private static List<HopMetric> buildHopMetrics(List<TelemetryEntry> qualityEntries) {
        if (qualityEntries.isEmpty()) {
            return List.of();
        }

        long minTs = qualityEntries.getFirst().getTimestamp();
        long maxTs = qualityEntries.getLast().getTimestamp();
        long range = maxTs - minTs;
        int hopBuckets = Math.min(30, Math.max(1, (int) (range / 900)));
        if (hopBuckets < 3) {
            hopBuckets = Math.min(3, qualityEntries.size());
        }

        return bucketize(
                qualityEntries.stream().filter(HAS_HOPS).toList(),
                TelemetryEntry::getTimestamp,
                hopBuckets,
                minTs,
                maxTs
        ).stream()
                .map(TelemetryChartDataBuilder::averageHopMetric)
                .toList();
    }

    private static AxisRange buildAxisRange(List<TelemetryEntry> entries,
                                            List<TelemetryEntry> qualityEntries,
                                            long selectedPeriodSeconds) {
        if (selectedPeriodSeconds > 0) {
            return createPeriodAxisRange(selectedPeriodSeconds);
        }

        LongSummaryStatistics stats = Stream.of(entries, qualityEntries)
                .flatMap(List::stream)
                .mapToLong(TelemetryEntry::getTimestamp)
                .summaryStatistics();

        if (stats.getCount() == 0) {
            return createPeriodAxisRange(EMPTY_PERIOD_FALLBACK);
        }

        long range = Math.max(stats.getMax() - stats.getMin(), 1);
        long padding = Math.max(range / 20, 60);
        long totalRange = range + 2 * padding;
        return new AxisRange(stats.getMin() - padding, stats.getMax() + padding, Math.max(totalRange / 8, 60));
    }

    private static AxisRange createPeriodAxisRange(long selectedPeriodSeconds) {
        long now = System.currentTimeMillis() / 1000;
        long visibleRange = selectedPeriodSeconds > 0 ? selectedPeriodSeconds : EMPTY_PERIOD_FALLBACK;
        long padding = Math.max(visibleRange / 20, 60);
        long totalRange = visibleRange + 2 * padding;
        return new AxisRange(now - visibleRange - padding, now + padding, Math.max(totalRange / 8, 60));
    }

    private static <T> List<T> aggregateIfNeeded(List<T> metrics,
                                                 List<TelemetryEntry> sourceEntries,
                                                 ToLongFunction<T> timestampExtractor,
                                                 java.util.function.BiFunction<Long, List<T>, T> aggregator) {
        if (!isBucketed(sourceEntries)) {
            return metrics;
        }
        return bucketize(metrics, timestampExtractor, MAX_CHART_POINTS,
                sourceEntries.getFirst().getTimestamp(),
                sourceEntries.getLast().getTimestamp()).stream()
                .map(bucket -> aggregator.apply(bucket.center(), bucket.items()))
                .toList();
    }

    private static <T> List<XYChart.Data<Number, Number>> pointData(List<T> entries,
                                                                    Predicate<T> filter,
                                                                    ToLongFunction<T> timestampExtractor,
                                                                    ToDoubleFunction<T> valueExtractor,
                                                                    PointFactory pointFactory) {
        return entries.stream()
                .filter(filter)
                .map(entry -> pointFactory.create(timestampExtractor.applyAsLong(entry), valueExtractor.applyAsDouble(entry)))
                .toList();
    }

    private static <T> List<XYChart.Data<Number, Number>> averageData(List<Bucket<T>> buckets,
                                                                      Predicate<T> filter,
                                                                      ToDoubleFunction<T> valueExtractor,
                                                                      PointFactory pointFactory) {
        return buckets.stream()
                .map(bucket -> bucket.items().stream()
                        .filter(filter)
                        .mapToDouble(valueExtractor)
                        .average()
                        .stream()
                        .mapToObj(value -> pointFactory.create(bucket.center(), value))
                        .findFirst())
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private static <T> List<Bucket<T>> bucketize(List<T> entries,
                                                 ToLongFunction<T> timestampExtractor,
                                                 int bucketCount) {
        if (entries.isEmpty()) {
            return List.of();
        }
        LongSummaryStatistics stats = entries.stream()
                .mapToLong(timestampExtractor)
                .summaryStatistics();
        return bucketize(entries, timestampExtractor, bucketCount, stats.getMin(), stats.getMax());
    }

    private static <T> List<Bucket<T>> bucketize(List<T> entries,
                                                 ToLongFunction<T> timestampExtractor,
                                                 int bucketCount,
                                                 long minTs,
                                                 long maxTs) {
        if (entries.isEmpty() || bucketCount <= 0) {
            return List.of();
        }

        long bucketSize = Math.max((maxTs - minTs) / bucketCount, 1);
        Map<Integer, List<T>> grouped = entries.stream()
                .collect(Collectors.groupingBy(
                        entry -> bucketIndex(timestampExtractor.applyAsLong(entry), minTs, bucketSize, bucketCount),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Bucket<>(bucketCenter(minTs, bucketSize, entry.getKey()), entry.getValue()))
                .toList();
    }

    private static int bucketIndex(long timestamp, long minTs, long bucketSize, int bucketCount) {
        return Math.toIntExact(Math.min((timestamp - minTs) / bucketSize, bucketCount - 1L));
    }

    private static long bucketCenter(long minTs, long bucketSize, int bucketIndex) {
        return minTs + (long) bucketIndex * bucketSize + bucketSize / 2;
    }

    private static XYChart.Series<Number, Number> series(String name, List<XYChart.Data<Number, Number>> data) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(name);
        series.getData().setAll(data);
        return series;
    }

    private static XYChart.Data<Number, Number> dataPoint(long timestamp, double value) {
        return new XYChart.Data<>(timestamp, value);
    }

    private static XYChart.Data<Number, Number> createVoltageData(long timestamp, double voltage) {
        return new XYChart.Data<>(timestamp, BatteryLevelEstimator.fromVoltage((float) voltage), voltage);
    }

    private static RxMetric createRxMetric(TelemetryEntry previous, TelemetryEntry current) {
        int received = current.getNumPacketsRx() - previous.getNumPacketsRx();
        int bad = current.getNumPacketsRxBad() - previous.getNumPacketsRxBad();
        int duplicate = current.getNumRxDupe() - previous.getNumRxDupe();
        return received < 0
                ? new RxMetric(current.getTimestamp(), current.getNumPacketsRx(), current.getNumPacketsRxBad(), current.getNumRxDupe())
                : new RxMetric(current.getTimestamp(), received, bad, duplicate);
    }

    private static TxMetric createTxMetric(TelemetryEntry previous, TelemetryEntry current) {
        int transmitted = current.getNumPacketsTx() - previous.getNumPacketsTx();
        int dropped = current.getNumTxDropped() - previous.getNumTxDropped();
        int relayed = current.getNumTxRelay() - previous.getNumTxRelay();
        int canceled = current.getNumTxRelayCanceled() - previous.getNumTxRelayCanceled();
        return transmitted < 0
                ? new TxMetric(current.getTimestamp(), current.getNumPacketsTx(), current.getNumTxDropped(),
                current.getNumTxRelay(), current.getNumTxRelayCanceled())
                : new TxMetric(current.getTimestamp(), transmitted, dropped, relayed, canceled);
    }

    private static RxMetric sumRxMetric(long timestamp, List<RxMetric> metrics) {
        return new RxMetric(
                timestamp,
                metrics.stream().mapToDouble(RxMetric::received).sum(),
                metrics.stream().mapToDouble(RxMetric::bad).sum(),
                metrics.stream().mapToDouble(RxMetric::duplicate).sum()
        );
    }

    private static TxMetric sumTxMetric(long timestamp, List<TxMetric> metrics) {
        return new TxMetric(
                timestamp,
                metrics.stream().mapToDouble(TxMetric::transmitted).sum(),
                metrics.stream().mapToDouble(TxMetric::dropped).sum(),
                metrics.stream().mapToDouble(TxMetric::relayed).sum(),
                metrics.stream().mapToDouble(TxMetric::canceled).sum()
        );
    }

    private static QualityMetric averageQualityMetric(long timestamp, List<QualityMetric> metrics) {
        return new QualityMetric(
                timestamp,
                metrics.stream().mapToDouble(QualityMetric::snr).average().orElse(0),
                metrics.stream().mapToDouble(QualityMetric::rssi).average().orElse(0)
        );
    }

    private static HopMetric averageHopMetric(Bucket<TelemetryEntry> bucket) {
        IntSummaryStatistics stats = bucket.items().stream()
                .mapToInt(TelemetryEntry::getHopsTraveled)
                .summaryStatistics();
        return new HopMetric(bucket.center(), stats.getAverage(), stats.getMax(), stats.getMin());
    }

    private static boolean isBucketed(List<?> entries) {
        return entries.size() > MAX_CHART_POINTS;
    }

    private static String t(String key) {
        return I18n.t(key);
    }
}
