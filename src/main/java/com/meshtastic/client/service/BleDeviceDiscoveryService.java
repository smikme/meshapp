package com.meshtastic.client.service;

import com.meshtastic.client.connection.ble.BleDevice;
import com.meshtastic.client.connection.ble.BlePlatform;
import com.meshtastic.client.connection.ble.BlePlatformFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Сервис автоматического обнаружения BLE-устройств Meshtastic (singleton).
 * <p>
 * Использует платформо-зависимый {@link BlePlatform} для BLE-сканирования
 * с фильтром по Meshtastic service UUID. Оповещает подписчиков при
 * обнаружении новых устройств или изменении RSSI.
 * <p>
 * Паттерн аналогичен {@link SerialPortDiscoveryService}.
 */
public class BleDeviceDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(BleDeviceDiscoveryService.class);

    private static BleDeviceDiscoveryService instance;

    private final List<Consumer<List<BleDevice>>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, BleDevice> discoveredDevices = new ConcurrentHashMap<>();
    private volatile boolean scanning;
    private BlePlatform platform;

    private BleDeviceDiscoveryService() {}

    public static synchronized BleDeviceDiscoveryService getInstance() {
        if (instance == null) {
            instance = new BleDeviceDiscoveryService();
        }
        return instance;
    }

    /**
     * Запускает BLE-сканирование. Обнаруженные устройства с Meshtastic service UUID
     * добавляются в список и оповещают подписчиков.
     */
    public void startScanning() {
        if (scanning) return;
        if (!BlePlatformFactory.isSupported()) {
            log.warn("BLE not supported on this platform");
            return;
        }

        // Один platform (CBCentralManager) на всё приложение — не пересоздаём
        if (platform == null) {
            try {
                platform = BlePlatformFactory.create();
            } catch (UnsupportedOperationException e) {
                log.warn("BLE not available: {}", e.getMessage());
                return;
            }
        }

        scanning = true;
        discoveredDevices.clear();

        platform.startScan(device -> {
            BleDevice existing = discoveredDevices.get(device.address());
            discoveredDevices.put(device.address(), device);

            // Оповещаем при новом устройстве или значительном изменении RSSI
            if (existing == null || Math.abs(existing.rssi() - device.rssi()) > 5) {
                fireChanged();
            }
        });

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

    public void addListener(Consumer<List<BleDevice>> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<List<BleDevice>> listener) {
        listeners.remove(listener);
    }

    /**
     * Возвращает {@link BlePlatform} для использования в {@link com.meshtastic.client.connection.ble.BleConnection}.
     * Если платформа ещё не создана, создаёт новую.
     */
    public BlePlatform getPlatform() {
        if (platform == null) {
            platform = BlePlatformFactory.create();
        }
        return platform;
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
