package com.meshtastic.client.connection.ble;

import com.meshtastic.client.connection.ble.linux.LinuxBle;
import com.meshtastic.client.connection.ble.macos.MacOsBle;
import com.meshtastic.client.connection.ble.windows.WinBle;
import com.meshtastic.client.platform.OsDetect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for platform-specific BLE implementations.
 * Selects an implementation from the current operating system ({@link OsDetect}).
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class BlePlatformFactory {

    private static final Logger log = LoggerFactory.getLogger(BlePlatformFactory.class);

    /**
     * Creates a BLE platform for the current operating system.
     *
     * @return {@link BlePlatform} implementation
     * @throws UnsupportedOperationException when BLE is not supported on this OS
     */
    public static BlePlatform create() {
        return switch (OsDetect.current()) {
            case MACOS -> {
                log.info("Используется CoreBluetooth (macOS)");
                yield new MacOsBle();
            }
            case LINUX -> {
                log.info("Используется BlueZ D-Bus (Linux)");
                yield new LinuxBle();
            }
            case WINDOWS -> {
                log.info("Используется WinRT BLE (Windows)");
                yield new WinBle();
            }
            case UNKNOWN -> throw new UnsupportedOperationException(
                    "BLE не поддерживается на этой ОС");
        };
    }

    /**
     * Returns whether BLE is supported on the current platform.
     */
    public static boolean isSupported() {
        return OsDetect.isMacOs() || OsDetect.isWindows() || OsDetect.isLinux();
    }

    /**
     * Returns {@code true} when the backend can safely hold multiple independent
     * BLE sessions in one process.
     * <p>
     * macOS keeps state in separate {@link MacOsBle} instances. Linux and Windows
     * load an isolated native library copy per {@link LinuxBle} or {@link WinBle},
     * so singleton native state remains scoped to one SO/DLL instance.
     */
    public static boolean supportsParallelConnections() {
        return isSupported();
    }

    private BlePlatformFactory() {}
}
