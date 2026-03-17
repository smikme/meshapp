package com.meshtastic.client.connection.serial;

import com.meshtastic.client.platform.OsDetect;

/**
 * Фабрика для создания платформо-специфичного {@link NativeSerialPort}.
 */
public final class NativeSerialPortFactory {

    private NativeSerialPortFactory() {}

    public static NativeSerialPort create() {
        return switch (OsDetect.current()) {
            case WINDOWS -> new WinSerialPort();
            case MACOS, LINUX -> new PosixSerialPort();
            case UNKNOWN -> throw new UnsupportedOperationException(
                    "Serial port not supported on " + System.getProperty("os.name"));
        };
    }
}
