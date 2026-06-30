package com.meshtastic.client.service;

import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatform;
import com.meshtastic.client.connection.ble.BlePlatformFactory;
import com.meshtastic.client.connection.ble.BleProtocolProfile;
import com.meshtastic.client.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Singleton service for automatic discovery of Meshtastic and MeshCore BLE devices.
 * <p>
 * Uses the platform-specific {@link BlePlatform} to scan BLE devices with a
 * service UUID filter from the selected BLE profile. Subscribers are notified
 * when new devices are found or RSSI changes.
 * <p>
 * The pattern mirrors {@link SerialPortDiscoveryService}.
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
     * Starts BLE scanning. Devices that advertise the selected service UUID are
     * added to the list and broadcast to subscribers.
     */
    public void startScanning() {
        if (scanning || disposed) { return; }
        if (!BlePlatformFactory.isSupported()) {
            lastErrorMessage = I18n.t("connection.ble.unsupported");
            log.warn(lastErrorMessage);
            return;
        }

        if (!supportsParallelConnections() && ConnectionManager.getInstance().hasActiveBleTransport()) {
            lastErrorMessage = I18n.t("connection.ble.scanUnavailableActive");
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
            lastErrorMessage = I18n.t("connection.ble.discoveryStopped");
            log.warn("BLE scanning failed: {}", e.getMessage());
        }
    }

    private void startScanningOnWorker(long generation) {
        BlePlatform currentPlatform;
        try {
            // One platform instance is shared by discovery for the whole app.
            // Transport connections are created separately via createConnectionPlatform().
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

                // Notify on new devices, resolved names, protocol changes, or meaningful RSSI changes.
                if (existing == null || deviceChanged(existing, profiledDevice)) {
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

    /** Stops BLE scanning. */
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

    /** Performs an immediate scan, starting scanning if needed. */
    public List<BleDevice> scanNow() {
        if (!scanning) {
            startScanning();
        }
        return getDiscoveredDevices();
    }

    /** Returns the current discovered-device list, sorted by RSSI. */
    public List<BleDevice> getDiscoveredDevices() {
        List<BleDevice> devices = new ArrayList<>(discoveredDevices.values());
        devices.sort((a, b) -> Integer.compare(b.rssi(), a.rssi())); // Strongest signal first.
        return List.copyOf(devices);
    }

    /** Checks whether BLE is supported on the current platform. */
    public boolean isSupported() {
        return BlePlatformFactory.isSupported();
    }

    /** Checks whether scanning is currently active. */
    public boolean isScanning() {
        return scanning;
    }

    /**
     * Releases the discovery backend and its native worker resources.
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
     * Returns the latest BLE discovery startup error when scanning did not start.
     */
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    /**
     * Changes the BLE profile used by the next scan. If scanning is already
     * active, it is restarted with the new UUID filter.
 *
     * @param profile BLE profile; null is treated as Meshtastic
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
     * Returns the discovery {@link BlePlatform}. Transport connections should
     * use {@link #createConnectionPlatform()} so callbacks and GATT state are not
     * shared with scanning.
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
     * Creates a backend for a BLE transport session.
     * <p>
     * Each call returns an independent backend: macOS creates a separate
     * CoreBluetooth stack, while Linux/Windows load a separate temporary copy of
     * the native library with its own singleton state inside that SO/DLL.
     */
    public BlePlatform createConnectionPlatform() {
        if (supportsParallelConnections()) {
            return platformFactory.get();
        }
        stopScanning(true);
        return getPlatform();
    }

    /**
     * Whether the transport should dispose the platform on disconnect.
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
                throw new IllegalStateException(I18n.t("connection.ble.discoveryStopped"));
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

    private static boolean deviceChanged(BleDevice existing, BleDevice current) {
        return Math.abs(existing.rssi() - current.rssi()) > 5
                || !Objects.equals(existing.name(), current.name())
                || existing.protocolType() != current.protocolType();
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
