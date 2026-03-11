package com.meshtastic.client.connection.ble.linux;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ble.*;
import com.meshtastic.client.connection.ble.linux.BluezInterfaces.Adapter1;
import com.meshtastic.client.connection.ble.linux.BluezInterfaces.Device1;
import com.meshtastic.client.connection.ble.linux.BluezInterfaces.GattCharacteristic1;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.freedesktop.dbus.DBusPath;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Linux-реализация BLE через BlueZ D-Bus API (dbus-java).
 * <p>
 * Паттерн аналогичен {@link com.meshtastic.client.connection.ble.windows.WinBle}:
 * <ul>
 *   <li>Polling (200ms) для чтения fromRadio</li>
 *   <li>Drain guard для предотвращения конкурентных чтений</li>
 *   <li>Volatile поля для thread-safety</li>
 * </ul>
 *
 * @see BlePlatform
 */
public class LinuxBle implements BlePlatform {

    private static final Logger log = LoggerFactory.getLogger(LinuxBle.class);

    private static final int POLL_INTERVAL_MS = 200;
    private static final String BLUEZ_BUS = "org.bluez";
    private static final String ADAPTER_IFACE = "org.bluez.Adapter1";
    private static final String DEVICE_IFACE = "org.bluez.Device1";
    private static final String CHAR_IFACE = "org.bluez.GattCharacteristic1";

    private final DBusConnection dbus;
    private final String adapterPath;
    private final Adapter1 adapter;
    private final Properties adapterProps;
    private final ObjectManager objectManager;

    private volatile Consumer<BleDevice> scanCallback;
    private volatile Consumer<byte[]> fromRadioListener;
    private volatile Consumer<BleState> stateListener;

    private volatile boolean connected;
    private volatile AdapterState adapterState = AdapterState.UNKNOWN;

    private volatile Device1 connectedDevice;
    private volatile String connectedDevicePath;
    private volatile GattCharacteristic1 fromRadioChar;
    private volatile GattCharacteristic1 toRadioChar;
    private volatile GattCharacteristic1 fromNumChar;

    // Polling
    private final ScheduledExecutorService pollScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ble-linux-poll");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pollFuture;
    private volatile boolean drainInProgress;

    // D-Bus signal handlers
    private AutoCloseable interfacesAddedHandler;
    private AutoCloseable devicePropsHandler;

    public LinuxBle() {
        try {
            dbus = DBusConnectionBuilder.forSystemBus().build();
        } catch (DBusException e) {
            throw new UnsupportedOperationException(
                    "Не удалось подключиться к системной шине D-Bus. BlueZ не доступен.", e);
        }

        try {
            adapterPath = findAdapterPath();
            adapter = dbus.getRemoteObject(BLUEZ_BUS, adapterPath, Adapter1.class);
            adapterProps = dbus.getRemoteObject(BLUEZ_BUS, adapterPath, Properties.class);
            objectManager = dbus.getRemoteObject(BLUEZ_BUS, "/", ObjectManager.class);

            updateAdapterState();
            log.info("BlueZ D-Bus инициализирован, адаптер: {}", adapterPath);
        } catch (DBusException e) {
            closeQuietly(dbus);
            throw new UnsupportedOperationException(
                    "BlueZ адаптер не найден. Убедитесь, что Bluetooth включён.", e);
        }
    }

    // ==================== BlePlatform: Scanning ====================

    @Override
    public void startScan(Consumer<BleDevice> onDeviceFound) {
        this.scanCallback = onDeviceFound;
        log.info("startScan: начало инициализации");

        try {
            // Фильтр: только LE устройства с Meshtastic-сервисом
            try {
                Map<String, Variant<?>> filter = new HashMap<>();
                filter.put("UUIDs", new Variant<>(
                        Arrays.asList(BleConstants.SERVICE_UUID), "as"));
                filter.put("Transport", new Variant<>("le"));
                adapter.SetDiscoveryFilter(filter);
                log.info("startScan: SetDiscoveryFilter установлен (UUID={})", BleConstants.SERVICE_UUID);
            } catch (Exception e) {
                log.warn("startScan: SetDiscoveryFilter не удался, сканируем без фильтра: {}", e.getMessage());
            }

            // InterfacesAdded — новые устройства при discovery
            interfacesAddedHandler = dbus.addSigHandler(
                    ObjectManager.InterfacesAdded.class,
                    signal -> {
                        log.debug("InterfacesAdded: path={}", signal.getObjectPath());
                        onInterfacesAdded(signal.getObjectPath(), signal.getInterfaces());
                    });
            log.info("startScan: InterfacesAdded handler зарегистрирован");

            // Эмитим уже известные BlueZ устройства (кэш предыдущих сканов)
            emitCachedDevices();

            adapter.StartDiscovery();
            log.info("BLE сканирование запущено (BlueZ)");
        } catch (DBusException e) {
            log.error("Не удалось запустить BLE сканирование", e);
        }
    }

    @Override
    public void stopScan() {
        scanCallback = null;
        closeQuietly(interfacesAddedHandler);
        interfacesAddedHandler = null;
        try {
            adapter.StopDiscovery();
        } catch (Exception e) {
            log.debug("StopDiscovery: {}", e.getMessage());
        }
        log.info("BLE сканирование остановлено");
    }

    // ==================== BlePlatform: Connection ====================

    @Override
    public void connect(String address) throws ConnectionException {
        String devPath = adapterPath + "/dev_" + address.replace(':', '_');
        log.info("Подключение к BLE устройству: {} ({})", address, devPath);

        Device1 device;
        Properties deviceProps;
        try {
            device = dbus.getRemoteObject(BLUEZ_BUS, devPath, Device1.class);
            deviceProps = dbus.getRemoteObject(BLUEZ_BUS, devPath, Properties.class);
        } catch (DBusException e) {
            throw new ConnectionException("BLE устройство не найдено: " + address);
        }

        // Лatch для ожидания ServicesResolved
        CountDownLatch servicesLatch = new CountDownLatch(1);
        // Лatch для ожидания Connected
        CountDownLatch connectLatch = new CountDownLatch(1);

        // Следим за PropertiesChanged на устройстве
        AutoCloseable propsHandler;
        try {
            propsHandler = dbus.addSigHandler(
                    Properties.PropertiesChanged.class, BLUEZ_BUS, deviceProps,
                    signal -> {
                        if (!DEVICE_IFACE.equals(signal.getInterfaceName())) return;
                        Map<String, Variant<?>> changed = signal.getPropertiesChanged();
                        if (changed == null) return;

                        Variant<?> connVar = changed.get("Connected");
                        if (connVar != null) {
                            boolean conn = Boolean.TRUE.equals(connVar.getValue());
                            if (conn) {
                                connectLatch.countDown();
                            } else if (connected) {
                                onUnexpectedDisconnect();
                            }
                        }

                        Variant<?> srvVar = changed.get("ServicesResolved");
                        if (srvVar != null && Boolean.TRUE.equals(srvVar.getValue())) {
                            servicesLatch.countDown();
                        }
                    });
        } catch (DBusException e) {
            throw new ConnectionException("Не удалось подписаться на D-Bus сигналы: " + e.getMessage());
        }

        try {
            device.Connect();
        } catch (Exception e) {
            closeQuietly(propsHandler);
            throw new ConnectionException("BLE подключение не удалось: " + e.getMessage());
        }

        // Ждём ServicesResolved (может уже быть true, если устройство было подключено ранее)
        try {
            Variant<?> resolved = deviceProps.Get(DEVICE_IFACE, "ServicesResolved");
            if (Boolean.TRUE.equals(resolved.getValue())) {
                servicesLatch.countDown();
            }
        } catch (Exception ignored) { /* ещё не подключено */ }

        try {
            if (!servicesLatch.await(BleConstants.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                closeQuietly(propsHandler);
                try { device.Disconnect(); } catch (Exception ignored) {}
                throw new ConnectionException("Таймаут обнаружения GATT-сервисов: " + address);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(propsHandler);
            throw new ConnectionException("Прервано при подключении: " + address);
        }

        // Ищем GATT-характеристики
        findGattCharacteristics(devPath);

        if (toRadioChar == null || fromRadioChar == null) {
            closeQuietly(propsHandler);
            try { device.Disconnect(); } catch (Exception ignored) {}
            throw new ConnectionException("Meshtastic GATT-характеристики не найдены: " + address);
        }

        // Подписка на fromNum (уведомления о новых данных)
        if (fromNumChar != null) {
            try {
                fromNumChar.StartNotify();
            } catch (Exception e) {
                log.warn("Не удалось подписаться на fromNum: {}", e.getMessage());
            }
        }

        // Сохраняем состояние
        connectedDevice = device;
        connectedDevicePath = devPath;
        devicePropsHandler = propsHandler;
        connected = true;

        // Initial drain
        pollFromRadio();

        // Start polling
        startPolling();
        log.info("BLE подключено: {}", address);
    }

    @Override
    public void disconnect() {
        connected = false;
        stopPolling();

        if (fromNumChar != null) {
            try { fromNumChar.StopNotify(); } catch (Exception ignored) {}
        }
        if (connectedDevice != null) {
            try { connectedDevice.Disconnect(); } catch (Exception ignored) {}
        }

        closeQuietly(devicePropsHandler);
        devicePropsHandler = null;
        connectedDevice = null;
        connectedDevicePath = null;
        fromRadioChar = null;
        toRadioChar = null;
        fromNumChar = null;
        drainInProgress = false;
        log.info("BLE отключено");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    // ==================== BlePlatform: Data ====================

    @Override
    public void writeToRadio(byte[] protobufPayload) {
        GattCharacteristic1 toRadio = toRadioChar;
        if (!connected || toRadio == null) {
            log.warn("writeToRadio: не подключено");
            return;
        }
        try {
            toRadio.WriteValue(protobufPayload, Map.of());
            log.debug("Отправлено {} байт в toRadio", protobufPayload.length);
            scheduleDrainAfterWrite();
        } catch (Exception e) {
            log.error("writeToRadio failed", e);
        }
    }

    @Override
    public void setFromRadioListener(Consumer<byte[]> listener) {
        this.fromRadioListener = listener;
    }

    @Override
    public void setStateListener(Consumer<BleState> listener) {
        this.stateListener = listener;
    }

    // ==================== BlePlatform: State ====================

    @Override
    public AdapterState getAdapterState() {
        return adapterState;
    }

    @Override
    public void dispose() {
        disconnect();
        stopScan();
        pollScheduler.shutdownNow();
        closeQuietly(dbus);
        log.info("LinuxBle disposed");
    }

    // ==================== Polling ====================

    private void startPolling() {
        stopPolling();
        pollFuture = pollScheduler.scheduleWithFixedDelay(
                this::triggerDrain, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        ScheduledFuture<?> f = pollFuture;
        if (f != null) {
            f.cancel(false);
            pollFuture = null;
        }
    }

    private void triggerDrain() {
        if (drainInProgress) return;
        drainInProgress = true;
        pollFromRadio();
    }

    /**
     * Читаем fromRadio до пустого ответа (макс 100 итераций).
     * ReadValue в BlueZ синхронный — проще чем callback-chain в macOS.
     */
    private void pollFromRadio() {
        GattCharacteristic1 fromRadio = fromRadioChar;
        if (!connected || fromRadio == null) {
            drainInProgress = false;
            return;
        }
        try {
            for (int i = 0; i < 100; i++) {
                byte[] data = fromRadio.ReadValue(Map.of());
                if (data == null || data.length == 0) break;

                Consumer<byte[]> listener = fromRadioListener;
                if (listener != null) {
                    listener.accept(data);
                }
            }
        } catch (Exception e) {
            log.warn("Polling fromRadio error", e);
        } finally {
            drainInProgress = false;
        }
    }

    private void scheduleDrainAfterWrite() {
        pollScheduler.schedule(this::triggerDrain, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // ==================== Internal ====================

    /**
     * Находит путь первого BlueZ-адаптера через GetManagedObjects.
     */
    private String findAdapterPath() throws DBusException {
        ObjectManager om = dbus.getRemoteObject(BLUEZ_BUS, "/", ObjectManager.class);
        var objects = om.GetManagedObjects();

        for (var entry : objects.entrySet()) {
            if (entry.getValue().containsKey(ADAPTER_IFACE)) {
                return entry.getKey().getPath();
            }
        }
        throw new DBusException("BlueZ BLE адаптер не найден");
    }

    private void updateAdapterState() {
        try {
            Variant<?> powered = adapterProps.Get(ADAPTER_IFACE, "Powered");
            adapterState = Boolean.TRUE.equals(powered.getValue())
                    ? AdapterState.POWERED_ON
                    : AdapterState.POWERED_OFF;
        } catch (Exception e) {
            adapterState = AdapterState.UNKNOWN;
        }
    }

    /**
     * Эмитит устройства, уже известные BlueZ (кэш предыдущих сканов).
     */
    private void emitCachedDevices() {
        try {
            var objects = objectManager.GetManagedObjects();
            int deviceCount = 0;
            for (var entry : objects.entrySet()) {
                Map<String, Variant<?>> deviceIface = entry.getValue().get(DEVICE_IFACE);
                if (deviceIface != null) {
                    deviceCount++;
                    emitDevice(deviceIface);
                }
            }
            log.info("emitCachedDevices: {} объектов всего, {} устройств",
                    objects.size(), deviceCount);
        } catch (Exception e) {
            log.warn("Не удалось получить кэшированные устройства: {}", e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void onInterfacesAdded(String path, Map<String, Map<String, Variant<?>>> interfaces) {
        if (interfaces == null) return;
        Map<String, Variant<?>> deviceIface = interfaces.get(DEVICE_IFACE);
        if (deviceIface != null) {
            emitDevice(deviceIface);
        }
    }

    /**
     * Эмитит устройство в scanCallback.
     * UUID-фильтрация не нужна — BlueZ уже фильтрует через SetDiscoveryFilter.
     * При InterfacesAdded UUIDs часто ещё не заполнены.
     */
    private void emitDevice(Map<String, Variant<?>> props) {
        Consumer<BleDevice> callback = scanCallback;
        if (callback == null) return;

        try {
            String address = getStringProp(props, "Address");
            if (address == null) return;

            String name = getStringProp(props, "Name");
            int rssi = -100;
            Variant<?> rssiVar = props.get("RSSI");
            if (rssiVar != null && rssiVar.getValue() instanceof Number n) {
                rssi = n.intValue();
            }

            log.info("emitDevice: address={}, name={}, rssi={}", address, name, rssi);
            callback.accept(new BleDevice(address, name != null ? name : "Unknown", rssi));
        } catch (Exception e) {
            log.warn("Ошибка разбора данных устройства: {}", e.getMessage(), e);
        }
    }

    /**
     * Находит GATT-характеристики Meshtastic через GetManagedObjects.
     */
    private void findGattCharacteristics(String devicePath) {
        try {
            var objects = objectManager.GetManagedObjects();
            for (var entry : objects.entrySet()) {
                String path = entry.getKey().getPath();
                if (!path.startsWith(devicePath)) continue;

                Map<String, Variant<?>> charIface = entry.getValue().get(CHAR_IFACE);
                if (charIface == null) continue;

                String uuid = getStringProp(charIface, "UUID");
                if (uuid == null) continue;

                if (uuid.equalsIgnoreCase(BleConstants.FROM_RADIO_UUID)) {
                    fromRadioChar = dbus.getRemoteObject(BLUEZ_BUS, path, GattCharacteristic1.class);
                    log.debug("fromRadio характеристика: {}", path);
                } else if (uuid.equalsIgnoreCase(BleConstants.TO_RADIO_UUID)) {
                    toRadioChar = dbus.getRemoteObject(BLUEZ_BUS, path, GattCharacteristic1.class);
                    log.debug("toRadio характеристика: {}", path);
                } else if (uuid.equalsIgnoreCase(BleConstants.FROM_NUM_UUID)) {
                    fromNumChar = dbus.getRemoteObject(BLUEZ_BUS, path, GattCharacteristic1.class);
                    log.debug("fromNum характеристика: {}", path);
                }
            }
        } catch (DBusException e) {
            log.error("Ошибка поиска GATT-характеристик", e);
        }
    }

    private void onUnexpectedDisconnect() {
        log.warn("BLE соединение разорвано неожиданно");
        connected = false;
        stopPolling();
        Consumer<BleState> sl = stateListener;
        if (sl != null) {
            sl.accept(new BleState.Disconnected());
        }
    }

    private static String getStringProp(Map<String, Variant<?>> props, String key) {
        Variant<?> v = props.get(key);
        if (v != null && v.getValue() instanceof String s) {
            return s;
        }
        return null;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }
}
