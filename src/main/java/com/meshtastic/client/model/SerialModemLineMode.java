package com.meshtastic.client.model;

/**
 * Saved serial modem-line mode for one connection profile.
 * <p>
 * {@link #AUTO} preserves adapter-based detection. Explicit modes are useful for
 * USB-UART boards whose DTR/RTS wiring differs from the common CP210x/CH340 layout.
 */
public enum SerialModemLineMode {
    AUTO(null, null),
    DTR_OFF_RTS_OFF(false, false),
    DTR_OFF_RTS_ON(false, true),
    DTR_ON_RTS_OFF(true, false),
    DTR_ON_RTS_ON(true, true);

    private final Boolean assertDtr;
    private final Boolean assertRts;

    SerialModemLineMode(Boolean assertDtr, Boolean assertRts) {
        this.assertDtr = assertDtr;
        this.assertRts = assertRts;
    }

    public boolean isAuto() {
        return this == AUTO;
    }

    public boolean assertDtr() {
        return Boolean.TRUE.equals(assertDtr);
    }

    public boolean assertRts() {
        return Boolean.TRUE.equals(assertRts);
    }
}
