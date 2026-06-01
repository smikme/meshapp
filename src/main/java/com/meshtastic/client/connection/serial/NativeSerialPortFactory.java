package com.meshtastic.client.connection.serial;

import com.meshtastic.client.platform.OsDetect;

/**
 * Factory for platform-specific {@link NativeSerialPort} implementations.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
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
