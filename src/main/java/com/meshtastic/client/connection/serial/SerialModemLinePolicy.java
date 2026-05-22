package com.meshtastic.client.connection.serial;

/**
 * Explicit modem-line policy for serial adapters.
 * <p>
 * USB-UART bridges used with ESP32 boards often wire DTR/RTS into the auto-reset circuit.
 * Native USB CDC devices, on the other hand, commonly use DTR as the "host connected" signal.
 */
public record SerialModemLinePolicy(boolean assertDtr, boolean assertRts, String reason) {

    public static SerialModemLinePolicy usbSerialBridge() {
        return new SerialModemLinePolicy(false, false, "usb-serial bridge");
    }

    public static SerialModemLinePolicy nativeUsbCdc() {
        return new SerialModemLinePolicy(true, false, "native USB CDC");
    }

    public static SerialModemLinePolicy generic() {
        return new SerialModemLinePolicy(true, false, "generic serial");
    }
}
