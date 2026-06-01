package com.meshtastic.client.service;

import com.fazecast.jSerialComm.SerialPort;
import com.meshtastic.client.connection.serial.SerialPortAccessAdvisor;
import com.meshtastic.client.platform.OsDetect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Locale;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Singleton service for automatic serial-port discovery.
 * <p>
 * The service periodically scans available serial ports through jSerialComm,
 * estimates which ones are likely Meshtastic devices, and notifies subscribers
 * when the list changes.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class SerialPortDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(SerialPortDiscoveryService.class);

    private static final long SCAN_INTERVAL_MS = 3000;

    /** Substrings in descriptive port names that commonly identify Meshtastic USB adapters. */
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
     * Description of a discovered serial port.
     *
     * @param systemPortName system port name, for example {@code "cu.usbserial-1234"} or {@code "COM3"}
     * @param descriptivePortName descriptive adapter name, for example {@code "CP2104 USB to UART Bridge Controller"}
     * @param likelyMeshtastic heuristic estimate that the port is probably a Meshtastic device
     * @param accessible whether the current user has read/write access to the device node
     * @param accessWarning readable guidance for fixing access permissions
     */
    public record DiscoveredPort(
            String systemPortName,
            String descriptivePortName,
            boolean likelyMeshtastic,
            boolean accessible,
            String accessWarning
    ) {
        public DiscoveredPort(String systemPortName,
                              String descriptivePortName,
                              boolean likelyMeshtastic) {
            this(systemPortName, descriptivePortName, likelyMeshtastic, true, null);
        }
    }

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
     * Starts periodic port scanning at a three-second interval.
     * The method is idempotent; repeated calls are ignored.
     */
    public void startScanning() {
        if (scanning) {
            return;
        }
        scanning = true;
        scheduler.scheduleWithFixedDelay(this::scan, 0, SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("Serial port scanning started");
    }

    /** Stops periodic scanning. */
    public void stopScanning() {
        scanning = false;
    }

    /** Runs an immediate scan and returns its result. */
    public List<DiscoveredPort> scanNow() {
        scan();
        return getDiscoveredPorts();
    }

    /** Returns the last known port list. */
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

                // On macOS, skip tty.* ports and use only cu.* endpoints.
                if (OsDetect.isMacOs() && sysName.startsWith("tty.")) {
                    continue;
                }
            // Skip system Bluetooth ports that are not SPP endpoints.
                if (sysName.contains("Bluetooth-Incoming") || sysName.contains("debug-console")) {
                    continue;
                }

                String desc = port.getDescriptivePortName();
                boolean likely = isLikelyMeshtastic(desc);
                SerialPortAccessAdvisor.PortAccess access = SerialPortAccessAdvisor.check(sysName);
                discovered.add(new DiscoveredPort(
                        sysName,
                        desc,
                        likely,
                        access.accessible(),
                        access.warning()));
            }

        // Sort likely Meshtastic devices first.
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
