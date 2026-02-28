package com.meshtastic.client.connection.ble;

import com.meshtastic.client.connection.ble.macos.MacOsBle;
import com.meshtastic.client.platform.OsDetect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Фабрика платформо-зависимых BLE-реализаций.
 * Выбирает реализацию на основе текущей ОС ({@link OsDetect}).
 */
public final class BlePlatformFactory {

    private static final Logger log = LoggerFactory.getLogger(BlePlatformFactory.class);

    /**
     * Создаёт BLE-платформу для текущей ОС.
     *
     * @return реализация {@link BlePlatform}
     * @throws UnsupportedOperationException если ОС не поддерживает BLE
     */
    public static BlePlatform create() {
        return switch (OsDetect.current()) {
            case MACOS -> {
                log.info("Используется CoreBluetooth (macOS)");
                yield new MacOsBle();
            }
            case LINUX -> throw new UnsupportedOperationException(
                    "BLE на Linux ещё не реализован (BlueZ D-Bus)");
            case WINDOWS -> throw new UnsupportedOperationException(
                    "BLE на Windows ещё не реализован (WinRT)");
            case UNKNOWN -> throw new UnsupportedOperationException(
                    "BLE не поддерживается на этой ОС");
        };
    }

    /**
     * Проверяет, поддерживается ли BLE на текущей платформе.
     */
    public static boolean isSupported() {
        return OsDetect.isMacOs(); // Linux и Windows будут добавлены позже
    }

    private BlePlatformFactory() {}
}
