package com.meshtastic.client.service;

import com.fazecast.jSerialComm.SerialPort;
import com.meshtastic.client.platform.OsDetect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Locale;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Сервис автоматического обнаружения serial-портов (singleton).
 * <p>
 * Периодически сканирует доступные serial-порты через jSerialComm
 * и определяет по эвристике, какие из них вероятнее всего являются Meshtastic-устройствами.
 * Оповещает подписчиков при изменении списка портов.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class SerialPortDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(SerialPortDiscoveryService.class);

    private static final long SCAN_INTERVAL_MS = 3000;

    /** Подстроки в descriptivePortName, характерные для Meshtastic USB-чипов. */
    private static final List<String> MESHTASTIC_HINTS = List.of(
            "CP210", "CH340", "CH341", "CH9102",
            "ESP32", "nRF52", "Meshtastic",
            "FTDI", "ACM", "USB to UART", "USB Serial"
    );

    private static SerialPortDiscoveryService instance;

    private final ScheduledExecutorService scheduler;
    private final List<Consumer<List<DiscoveredPort>>> listeners = new CopyOnWriteArrayList<>();
    private volatile List<DiscoveredPort> lastDiscoveredPorts = List.of();
    private volatile boolean scanning;
    private volatile boolean nativeDiscoveryUnavailable;

    /**
     * Описание обнаруженного serial-порта.
     *
     * @param systemPortName      системное имя порта (e.g. "cu.usbserial-1234", "COM3")
     * @param descriptivePortName описательное имя (e.g. "CP2104 USB to UART Bridge Controller")
     * @param likelyMeshtastic    эвристическая оценка — вероятно Meshtastic-устройство
     */
    public record DiscoveredPort(
            String systemPortName,
            String descriptivePortName,
            boolean likelyMeshtastic
    ) {}

    private SerialPortDiscoveryService() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "serial-port-scanner");
            t.setDaemon(true);
            return t;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(tf);
    }

    public static synchronized SerialPortDiscoveryService getInstance() {
        if (instance == null) {
            instance = new SerialPortDiscoveryService();
        }
        return instance;
    }

    /**
     * Запускает периодическое сканирование портов (интервал 3с).
     * Идемпотентен — повторный вызов игнорируется.
     */
    public void startScanning() {
        if (scanning) {
            return;
        }
        scanning = true;
        scheduler.scheduleWithFixedDelay(this::scan, 0, SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("Serial port scanning started");
    }

    /** Останавливает периодическое сканирование. */
    public void stopScanning() {
        scanning = false;
    }

    /** Выполняет немедленное сканирование и возвращает результат. */
    public List<DiscoveredPort> scanNow() {
        scan();
        return getDiscoveredPorts();
    }

    /** Возвращает последний известный список портов. */
    public List<DiscoveredPort> getDiscoveredPorts() {
        return lastDiscoveredPorts;
    }

    public void addListener(Consumer<List<DiscoveredPort>> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<List<DiscoveredPort>> listener) {
        listeners.remove(listener);
    }

    private void scan() {
        if (nativeDiscoveryUnavailable) {
            return;
        }
        try {
            SerialPort[] ports = SerialPort.getCommPorts();
            List<DiscoveredPort> discovered = new ArrayList<>();

            for (SerialPort port : ports) {
                String sysName = port.getSystemPortName();

                // macOS: пропускаем tty.* порты, используем только cu.*
                if (OsDetect.isMacOs() && sysName.startsWith("tty.")) {
                    continue;
                }
                // Пропускаем системные Bluetooth-порты (не SPP)
                if (sysName.contains("Bluetooth-Incoming") || sysName.contains("debug-console")) {
                    continue;
                }

                String desc = port.getDescriptivePortName();
                boolean likely = isLikelyMeshtastic(desc);
                discovered.add(new DiscoveredPort(sysName, desc, likely));
            }

            // Сортировка: вероятные Meshtastic-устройства в начале
            discovered.sort((a, b) -> Boolean.compare(b.likelyMeshtastic, a.likelyMeshtastic));

            publishIfChanged(discovered);
        } catch (LinkageError e) {
            nativeDiscoveryUnavailable = true;
            publishIfChanged(List.of());
            log.warn("Serial port discovery disabled: jSerialComm native library failed to initialize", e);
        } catch (Exception e) {
            log.warn("Serial port scan failed", e);
        }
    }

    private void publishIfChanged(List<DiscoveredPort> discovered) {
        if (discovered.equals(lastDiscoveredPorts)) {
            return;
        }
        lastDiscoveredPorts = List.copyOf(discovered);
        log.debug("Serial ports changed: {}", discovered.size());
        for (Consumer<List<DiscoveredPort>> listener : listeners) {
            try {
                listener.accept(lastDiscoveredPorts);
            } catch (Exception e) {
                log.warn("Error in discovery listener", e);
            }
        }
    }

    private boolean isLikelyMeshtastic(String descriptiveName) {
        if (descriptiveName == null) {
            return false;
        }
        String upper = descriptiveName.toUpperCase(Locale.ROOT);
        for (String hint : MESHTASTIC_HINTS) {
            if (upper.contains(hint.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
