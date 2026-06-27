package com.meshtastic.client.utils;

/**
 * Converts RX packet counters into percentages suitable for display.
 * <p>
 * Meshtastic firmware counters can be temporarily inconsistent, for example when bad
 * and duplicate counters grow faster than the total RX counter between telemetry samples.
 * This class keeps the displayed percentages bounded and proportional instead of
 * allowing impossible negative Good RX values.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RxQualityCalculator {

    private static final double MIN_VALID_COUNTER = 0.0;
    private static final double ZERO_PERCENT = 0.0;
    private static final double PERCENT_SCALE = 100.0;

    private RxQualityCalculator() {
    }

    /**
     * Normalized RX quality percentages.
     *
     * @param good percentage of received packets not classified as bad or duplicate
     * @param bad percentage of bad packets after normalization
     * @param duplicate percentage of duplicate packets after normalization
     */
    public record Percentages(double good, double bad, double duplicate) {}

    /**
     * Calculates bounded RX quality percentages from packet counters.
     * <p>
     * Negative, infinite, and {@code NaN} invalid counters are treated as zero. If
     * {@code bad + duplicate} exceeds {@code received}, bad and duplicate are scaled
     * down proportionally so the three output percentages sum to 100 and Good RX stays
     * at zero instead of becoming negative.
     *
     * @param received total received packet count for the sample or interval
     * @param bad bad packet count for the sample or interval
     * @param duplicate duplicate packet count for the sample or interval
     * @return normalized RX quality percentages
     */
    public static Percentages percentages(double received, double bad, double duplicate) {
        if (!Double.isFinite(received) || received <= MIN_VALID_COUNTER) {
            return new Percentages(ZERO_PERCENT, ZERO_PERCENT, ZERO_PERCENT);
        }

        double sanitizedBad = nonNegativeCounter(bad);
        double sanitizedDuplicate = nonNegativeCounter(duplicate);
        double invalid = sanitizedBad + sanitizedDuplicate;

        if (invalid > received) {
            double scale = received / invalid;
            sanitizedBad *= scale;
            sanitizedDuplicate *= scale;
            invalid = received;
        }

        double good = received - invalid;
        return new Percentages(
                good / received * PERCENT_SCALE,
                sanitizedBad / received * PERCENT_SCALE,
                sanitizedDuplicate / received * PERCENT_SCALE
        );
    }

    private static double nonNegativeCounter(double value) {
        if (!Double.isFinite(value) || value <= MIN_VALID_COUNTER) {
            return MIN_VALID_COUNTER;
        }
        return value;
    }
}
