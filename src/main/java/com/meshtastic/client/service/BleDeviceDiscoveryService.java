package com.meshtastic.client.service;

import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatform;
import com.meshtastic.client.connection.ble.BlePlatformFactory;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.model.ProtocolType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Сервис автоматического обнаружения BLE-устройств Meshtastic и MeshCore (singleton).
 * <p>
 * Использует платформо-зависимый {@link BlePlatform} для BLE-сканирования
 * с фильтром по service UUID выбранного BLE-профиля. Оповещает подписчиков при
 * обнаружении новых устройств или изменении RSSI.
 * <p>
 * Паттерн аналогичен {@link SerialPortDiscoveryService}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class BleDeviceDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(BleDeviceDiscoveryService.class);

    private static BleDeviceDiscoveryService instance;

    private final List<Consumer<List<BleDevice>>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, BleDevice> discoveredDevices = new ConcurrentHashMap<>();
    private volatile boolean scanning;
    private volatile BleProtocolProfile scanProfile = BleProtocolProfile.MESHTASTIC;
    private volatile String lastErrorMessage;
    private BlePlatform platform;
    private Supplier<BlePlatform> platformFactory = BlePlatformFactory::create;
    private Boolean parallelConnectionSupportOverride;

    private BleDeviceDiscoveryService() {}

    public static synchronized BleDeviceDiscoveryService getInstance() {
        if (instance == null) {
            instance = new BleDeviceDiscoveryService();
        }
        return instance;
    }

    /**
     * Запускает BLE-сканирование. Обнаруженные устройства с выбранным service UUID
     * добавляются в список и оповещают подписчиков.
     */
    public void startScanning() {
        if (scanning) { return; }
        if (!BlePlatformFactory.isSupported()) {
            lastErrorMessage = "BLE не поддерживается на этой платформе";
            log.warn(lastErrorMessage);
            return;
        }

        if (!supportsParallelConnections() && ConnectionManager.getInstance().hasActiveBleTransport()) {
            lastErrorMessage = "BLE сканирование недоступно, пока активно BLE-подключение";
            log.warn(lastErrorMessage);
            return;
        }

        // Один platform для discovery на всё приложение — transport-подключения
        // создаются отдельно через createConnectionPlatform().
        if (platform == null) {
            try {
                platform = platformFactory.get();
            } catch (RuntimeException e) {
                lastErrorMessage = e.getMessage();
                log.warn("BLE not available: {}", e.getMessage());
                return;
            }
        }

        scanning = true;
        lastErrorMessage = null;
        discoveredDevices.clear();
        try {
            platform.setProfile(scanProfile);
            platform.startScan(device -> {
                BleDevice existing = discoveredDevices.get(device.address());
                BleDevice profiledDevice = device.protocolType() == null
                        ? new BleDevice(device.address(), device.name(), device.rssi(), scanProfile.protocolType())
                        : device;
                discoveredDevices.put(device.address(), profiledDevice);

                // Оповещаем при новом устройстве или значительном изменении RSSI
                if (existing == null || Math.abs(existing.rssi() - profiledDevice.rssi()) > 5) {
                    fireChanged();
                }
            });
        } catch (RuntimeException e) {
            scanning = false;
            lastErrorMessage = e.getMessage();
            log.warn("BLE scanning failed: {}", e.getMessage());
            return;
        }

        log.info("BLE scanning started");
    }

    /** Останавливает BLE-сканирование. */
    public void stopScanning() {
        scanning = false;
        if (platform != null) {
            platform.stopScan();
        }
        log.info("BLE scanning stopped");
    }

    /** Выполняет немедленное сканирование (запускает, если не запущено). */
    public List<BleDevice> scanNow() {
        if (!scanning) {
            startScanning();
        }
        return getDiscoveredDevices();
    }

    /** Возвращает текущий список обнаруженных устройств, отсортированный по RSSI. */
    public List<BleDevice> getDiscoveredDevices() {
        List<BleDevice> devices = new ArrayList<>(discoveredDevices.values());
        devices.sort((a, b) -> Integer.compare(b.rssi(), a.rssi())); // Сильный сигнал первым
        return List.copyOf(devices);
    }

    /** Проверяет, поддерживается ли BLE на текущей платформе. */
    public boolean isSupported() {
        return BlePlatformFactory.isSupported();
    }

    /** Проверяет, идёт ли сейчас сканирование. */
    public boolean isScanning() {
        return scanning;
    }

    /**
     * Освобождает discovery backend и его native worker resources.
     */
    public void dispose() {
        stopScanning();
        BlePlatform current = platform;
        platform = null;
        if (current != null) {
            current.dispose();
        }
    }

    /**
     * Возвращает последнюю ошибку запуска BLE discovery, если сканирование не стартовало.
     */
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    /**
     * Меняет BLE-профиль для следующего сканирования. Если сканирование уже идёт,
     * оно перезапускается с новым фильтром UUID.
     *
     * @param profile BLE-профиль; null трактуется как Meshtastic
     */
    public void setScanProfile(BleProtocolProfile profile) {
        BleProtocolProfile newProfile = profile == null ? BleProtocolProfile.MESHTASTIC : profile;
        if (newProfile == scanProfile) {
            return;
        }
        scanProfile = newProfile;
        if (scanning) {
            stopScanning();
            startScanning();
        }
    }

    public BleProtocolProfile getScanProfile() {
        return scanProfile;
    }

    public void addListener(Consumer<List<BleDevice>> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<List<BleDevice>> listener) {
        listeners.remove(listener);
    }

    /**
     * Возвращает discovery {@link BlePlatform}. Transport-подключения должны
     * использовать {@link #createConnectionPlatform()}, чтобы не делить callbacks
     * и GATT-состояние со сканированием.
     */
    public BlePlatform getPlatform() {
        if (platform == null) {
            try {
                platform = platformFactory.get();
                lastErrorMessage = null;
            } catch (RuntimeException e) {
                lastErrorMessage = e.getMessage();
                throw e;
            }
        }
        return platform;
    }

    /**
     * Создаёт backend для BLE transport-сессии.
     * <p>
     * Каждый вызов возвращает независимый backend: macOS создаёт отдельный
     * CoreBluetooth stack, Linux/Windows загружают отдельную временную копию
     * native library с собственным singleton state внутри этой SO/DLL.
     */
    public BlePlatform createConnectionPlatform() {
        if (supportsParallelConnections()) {
            return platformFactory.get();
        }
        stopScanning();
        return getPlatform();
    }

    /**
     * Нужно ли transport-у освобождать platform при disconnect.
     */
    public boolean shouldDisposeConnectionPlatform() {
        return supportsParallelConnections();
    }

    public boolean supportsParallelConnections() {
        return parallelConnectionSupportOverride != null
                ? parallelConnectionSupportOverride
                : BlePlatformFactory.supportsParallelConnections();
    }

    void setPlatformFactoryForTests(Supplier<BlePlatform> platformFactory) {
        this.platformFactory = platformFactory == null ? BlePlatformFactory::create : platformFactory;
        this.platform = null;
        this.lastErrorMessage = null;
    }

    void setParallelConnectionSupportForTests(Boolean supported) {
        this.parallelConnectionSupportOverride = supported;
    }

    private void fireChanged() {
        List<BleDevice> devices = getDiscoveredDevices();
        for (Consumer<List<BleDevice>> listener : listeners) {
            try {
                listener.accept(devices);
            } catch (Exception e) {
                log.warn("Error in BLE discovery listener", e);
            }
        }
    }
}
