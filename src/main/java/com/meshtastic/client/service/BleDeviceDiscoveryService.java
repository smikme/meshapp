package com.meshtastic.client.service;

import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatform;
import com.meshtastic.client.connection.ble.BlePlatformFactory;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
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
    private final ExecutorService scanExecutor;
    private final AtomicLong scanGeneration = new AtomicLong();
    private final Object platformLock = new Object();
    private volatile boolean scanning;
    private volatile boolean disposed;
    private volatile BleProtocolProfile scanProfile = BleProtocolProfile.MESHTASTIC;
    private volatile String lastErrorMessage;
    private volatile BlePlatform platform;
    private volatile Supplier<BlePlatform> platformFactory = BlePlatformFactory::create;
    private volatile Boolean parallelConnectionSupportOverride;

    private BleDeviceDiscoveryService() {
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "ble-discovery-worker");
            t.setDaemon(true);
            return t;
        };
        scanExecutor = Executors.newSingleThreadExecutor(threadFactory);
    }

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
        if (scanning || disposed) { return; }
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

        long generation;
        synchronized (this) {
            if (scanning || disposed) { return; }
            scanning = true;
            lastErrorMessage = null;
            discoveredDevices.clear();
            generation = scanGeneration.incrementAndGet();
        }

        try {
            scanExecutor.execute(() -> startScanningOnWorker(generation));
        } catch (RejectedExecutionException e) {
            scanning = false;
            lastErrorMessage = "BLE discovery service is stopped";
            log.warn("BLE scanning failed: {}", e.getMessage());
        }
    }

    private void startScanningOnWorker(long generation) {
        BlePlatform currentPlatform;
        try {
            // Один platform для discovery на всё приложение — transport-подключения
            // создаются отдельно через createConnectionPlatform().
            currentPlatform = getOrCreatePlatform();
        } catch (RuntimeException e) {
            handleScanStartFailure(generation, "BLE not available", e);
            return;
        }

        if (!isScanGenerationActive(generation)) {
            return;
        }

        try {
            currentPlatform.setProfile(scanProfile);
            currentPlatform.startScan(device -> {
                if (!isScanGenerationActive(generation)) {
                    return;
                }
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
            handleScanStartFailure(generation, "BLE scanning failed", e);
            return;
        }

        if (!isScanGenerationActive(generation)) {
            try {
                currentPlatform.stopScan();
            } catch (RuntimeException e) {
                log.warn("BLE scanning stop after stale start failed: {}", e.getMessage());
            }
            return;
        }

        log.info("BLE scanning started");
    }

    /** Останавливает BLE-сканирование. */
    public void stopScanning() {
        stopScanning(false);
    }

    private void stopScanning(boolean waitForStop) {
        scanGeneration.incrementAndGet();
        scanning = false;
        BlePlatform current = platform;
        if (current == null) {
            return;
        }

        Runnable stopTask = () -> {
            try {
                current.stopScan();
                log.info("BLE scanning stopped");
            } catch (RuntimeException e) {
                log.warn("BLE scanning stop failed: {}", e.getMessage());
            }
        };

        if (waitForStop) {
            Future<?> future = submitScanTask(stopTask);
            if (future != null) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log.warn("BLE scanning stop failed", e.getCause());
                }
            }
            return;
        }

        submitScanTask(stopTask);
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
        scanGeneration.incrementAndGet();
        scanning = false;
        disposed = true;
        scanExecutor.shutdownNow();

        BlePlatform current;
        synchronized (platformLock) {
            current = platform;
            platform = null;
        }
        if (current != null) {
            try {
                current.stopScan();
            } catch (RuntimeException e) {
                log.debug("BLE scanning stop during dispose failed: {}", e.getMessage());
            }
            try {
                current.dispose();
            } catch (RuntimeException e) {
                log.warn("BLE platform dispose failed: {}", e.getMessage());
            }
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
        try {
            BlePlatform current = getOrCreatePlatform();
            lastErrorMessage = null;
            return current;
        } catch (RuntimeException e) {
            lastErrorMessage = e.getMessage();
            throw e;
        }
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
        stopScanning(true);
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
        scanGeneration.incrementAndGet();
        scanning = false;
        this.platformFactory = platformFactory == null ? BlePlatformFactory::create : platformFactory;
        this.lastErrorMessage = null;
        synchronized (platformLock) {
            this.platform = null;
        }
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

    private BlePlatform getOrCreatePlatform() {
        BlePlatform current = platform;
        if (current != null) {
            return current;
        }

        BlePlatform created = platformFactory.get();
        synchronized (platformLock) {
            if (disposed) {
                created.dispose();
                throw new IllegalStateException("BLE discovery service is stopped");
            }
            if (platform == null) {
                platform = created;
                return created;
            }
            current = platform;
        }

        created.dispose();
        return current;
    }

    private boolean isScanGenerationActive(long generation) {
        return scanning && scanGeneration.get() == generation;
    }

    private void handleScanStartFailure(long generation, String logPrefix, RuntimeException e) {
        if (scanGeneration.get() != generation) {
            return;
        }
        scanning = false;
        lastErrorMessage = e.getMessage();
        log.warn("{}: {}", logPrefix, e.getMessage());
        fireChanged();
    }

    private Future<?> submitScanTask(Runnable task) {
        try {
            return scanExecutor.submit(task);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }
}
