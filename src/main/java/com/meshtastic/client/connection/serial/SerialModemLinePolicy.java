package com.meshtastic.client.connection.serial;

/**
 * Explicit modem-line policy for serial adapters.
 * <p>
 * USB-UART bridges used with ESP32 boards often wire DTR/RTS into the auto-reset circuit.
 * For common CP210x/CH340 boards, keeping DTR low and RTS high avoids the reset loop
 * while leaving the ESP32 in the normal running state.
 * Native USB CDC devices, on the other hand, commonly use DTR as the "host connected" signal.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record SerialModemLinePolicy(boolean assertDtr, boolean assertRts, String reason) {

    public static SerialModemLinePolicy usbSerialBridge() {
        return new SerialModemLinePolicy(false, true, "usb-serial bridge");
    }

    public static SerialModemLinePolicy nativeUsbCdc() {
        return new SerialModemLinePolicy(true, false, "native USB CDC");
    }

    public static SerialModemLinePolicy generic() {
        return new SerialModemLinePolicy(true, false, "generic serial");
    }

    /**
     * Creates a policy from the persisted manual mode of a serial profile.
     *
     * @param assertDtr whether to assert DTR when the port opens
     * @param assertRts whether to assert RTS when the port opens
     * @return policy with explicit DTR/RTS values
     */
    public static SerialModemLinePolicy manual(boolean assertDtr, boolean assertRts) {
        return new SerialModemLinePolicy(assertDtr, assertRts, "manual override");
    }
}
