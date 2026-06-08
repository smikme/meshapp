package com.meshtastic.client.service;

/**
 * Progress notification emitted by {@link FirmwareUpdateService}.
 *
 * @param stage current update preparation stage
 * @param progress normalized progress from {@code 0.0} to {@code 1.0}, or a negative value for indeterminate progress
 * @param message localized status text for the UI
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record FirmwareUpdateProgress(
    FirmwareUpdateStage stage,
    double progress,
    String message
) {}
