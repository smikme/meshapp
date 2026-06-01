package com.meshtastic.client.model;

/**
 * Modem-line control mode for a Serial connection profile.
 * <p>
 * The value is stored by name in {@code ~/.meshapp/connections.json}. {@link #AUTO}
 * keeps the adapter-based heuristic, while explicit modes support USB-UART
 * boards with unusual DTR/RTS wiring.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum SerialModemLineMode {
    /** Automatic selection based on port name and adapter description. */
    AUTO(null, null),

    /** DTR and RTS are explicitly disabled. */
    DTR_OFF_RTS_OFF(false, false),

    /** DTR is disabled and RTS is enabled. */
    DTR_OFF_RTS_ON(false, true),

    /** DTR is enabled and RTS is disabled. */
    DTR_ON_RTS_OFF(true, false),

    /** DTR and RTS are explicitly enabled. */
    DTR_ON_RTS_ON(true, true);

    private final Boolean assertDtr;
    private final Boolean assertRts;

    SerialModemLineMode(Boolean assertDtr, Boolean assertRts) {
        this.assertDtr = assertDtr;
        this.assertRts = assertRts;
    }

    /**
     * Returns whether adapter heuristics should decide modem-line state.
     *
     * @return {@code true} when the mode does not explicitly set DTR or RTS
     */
    public boolean isAuto() {
        return this == AUTO;
    }

    /**
     * Returns the required DTR state for an explicit mode.
     *
     * @return {@code true} when DTR should be enabled
     */
    public boolean assertDtr() {
        return Boolean.TRUE.equals(assertDtr);
    }

    /**
     * Returns the required RTS state for an explicit mode.
     *
     * @return {@code true} when RTS should be enabled
     */
    public boolean assertRts() {
        return Boolean.TRUE.equals(assertRts);
    }
}
