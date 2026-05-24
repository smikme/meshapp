package com.meshtastic.client.utils;

/**
 * Converts a single-cell Li-ion/LiPo voltage reading to an approximate battery percentage.
 */
public final class BatteryLevelEstimator {

    private static final float MIN_VOLTAGE = 3.0f;
    private static final float MAX_VOLTAGE = 4.2f;

    private BatteryLevelEstimator() {
    }

    public static int fromVoltage(float voltage) {
        if (voltage <= 0) {
            return 0;
        }
        double percent = (voltage - MIN_VOLTAGE) / (MAX_VOLTAGE - MIN_VOLTAGE) * 100.0;
        return (int) Math.round(Math.max(0, Math.min(100, percent)));
    }

    public static boolean hasBatteryPercent(int reportedLevel, float voltage) {
        return (reportedLevel > 0 && reportedLevel <= 100)
                || (reportedLevel == 0 && voltage > 0);
    }

    public static int effectivePercent(int reportedLevel, float voltage) {
        if (reportedLevel > 0 && reportedLevel <= 100) {
            return reportedLevel;
        }
        return fromVoltage(voltage);
    }
}
