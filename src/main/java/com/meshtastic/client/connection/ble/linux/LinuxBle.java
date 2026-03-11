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

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Linux-реализация BLE через BlueZ D-Bus API (dbus-java).
 * <p>
 * Архитектура приёма данных: notification-based.
 * <ul>
 *   <li>fromRadio: {@code StartNotify()} + {@code PropertiesChanged} handler
 *       (BlueZ D-Bus {@code ReadValue} зависает — Issues #21, #947)</li>
 *   <li>Fallback polling с {@code ReadValue} через gattExecutor (если уведомления не работают)</li>
 *   <li>toRadio: {@code WriteValue} через gattExecutor с таймаутом</li>
 * </ul>
 *
 * @see BlePlatform
 */
public class LinuxBle implements BlePlatform {

    private static final Logger log = LoggerFactory.getLogger(LinuxBle.class);

    private static final String BLUEZ_BUS = "org.bluez";
    private static final String ADAPTER_IFACE = "org.bluez.Adapter1";
    private static final String DEVICE_IFACE = "org.bluez.Device1";
    private static final String CHAR_IFACE = "org.bluez.GattCharacteristic1";

    private static final int GATT_TIMEOUT_MS = 5000;
    private static final int MAX_CONSECUTIVE_ERRORS = 3;
    private static final int FALLBACK_CHECK_INTERVAL_MS = 2000;
    private static final int FALLBACK_READ_TIMEOUT_MS = 3000;

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
    private volatile String fromRadioCharPath;

    // Выделенный executor для GATT D-Bus вызовов (WriteValue, fallback ReadValue).
    // CachedThreadPool — зависшие вызовы не блокируют новые.
    private final ExecutorService gattExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "ble-linux-gatt");
                t.setDaemon(true);
                return t;
            });

    // Fallback polling scheduler
    private final ScheduledExecutorService pollScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ble-linux-poll");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pollFuture;
    private volatile int consecutiveErrors;
    private volatile long lastDataReceivedAt;

    // D-Bus signal handlers
    private AutoCloseable interfacesAddedHandler;
    private AutoCloseable devicePropsHandler;
    private AutoCloseable fromRadioHandler;

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
        // Чистим старые хэндлеры от предыдущего подключения (reconnect-safe)
        closeQuietly(devicePropsHandler);
        closeQuietly(fromRadioHandler);
        devicePropsHandler = null;
        fromRadioHandler = null;

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

        // Trusted=true — без этого BlueZ может блокировать GATT-операции
        try {
            deviceProps.Set(DEVICE_IFACE, "Trusted", new Variant<>(true));
            log.info("connect: Trusted=true установлено");
        } catch (Exception e) {
            log.debug("connect: не удалось установить Trusted: {}", e.getMessage());
        }

        // Latch для ожидания ServicesResolved
        CountDownLatch servicesLatch = new CountDownLatch(1);

        // PropertiesChanged handler — фильтруем по пути устройства
        AutoCloseable propsHandler;
        try {
            propsHandler = dbus.addSigHandler(
                    Properties.PropertiesChanged.class,
                    signal -> {
                        if (!devPath.equals(signal.getPath())) return;
                        if (!DEVICE_IFACE.equals(signal.getInterfaceName())) return;
                        Map<String, Variant<?>> changed = signal.getPropertiesChanged();
                        if (changed == null || changed.isEmpty()) return;

                        log.debug("PropertiesChanged ({}): {}", devPath, changed.keySet());

                        Variant<?> connVar = changed.get("Connected");
                        if (connVar != null) {
                            boolean conn = Boolean.TRUE.equals(connVar.getValue());
                            log.info("connect: Connected={}", conn);
                            if (!conn && connected) {
                                onUnexpectedDisconnect();
                            }
                        }

                        Variant<?> srvVar = changed.get("ServicesResolved");
                        if (srvVar != null && Boolean.TRUE.equals(srvVar.getValue())) {
                            log.info("connect: ServicesResolved=true (сигнал)");
                            servicesLatch.countDown();
                        }
                    });
        } catch (DBusException e) {
            throw new ConnectionException("Не удалось подписаться на D-Bus сигналы: " + e.getMessage());
        }

        // НЕ вызываем Pair() — Meshtastic не требует пейринга.
        // Pair() может вызвать BlueZ Issue #21: ReadValue виснет если пейринг в процессе.

        log.info("connect: вызываем Device1.Connect()...");
        try {
            device.Connect();
            log.info("connect: Device1.Connect() вернулся");
        } catch (Exception e) {
            if (isObjectGone(e)) {
                log.info("connect: устройство удалено из BlueZ, пересканируем...");
                try {
                    device = rediscoverDevice(address, devPath);
                    deviceProps = dbus.getRemoteObject(BLUEZ_BUS, devPath, Properties.class);
                    try {
                        deviceProps.Set(DEVICE_IFACE, "Trusted", new Variant<>(true));
                    } catch (Exception ignored) {}
                    device.Connect();
                    log.info("connect: Device1.Connect() после rediscovery вернулся");
                } catch (Exception e2) {
                    closeQuietly(propsHandler);
                    throw new ConnectionException("BLE подключение не удалось после пересканирования: " + e2.getMessage());
                }
            } else {
                closeQuietly(propsHandler);
                throw new ConnectionException("BLE подключение не удалось: " + e.getMessage());
            }
        }

        // Проверяем ServicesResolved — Properties.Get() в dbus-java 5.x
        // автоматически распаковывает Variant, возвращая Boolean напрямую
        try {
            Object resolved = deviceProps.Get(DEVICE_IFACE, "ServicesResolved");
            log.info("connect: ServicesResolved текущее значение: {}", resolved);
            if (Boolean.TRUE.equals(resolved)) {
                servicesLatch.countDown();
            } else if (resolved instanceof Variant<?> v && Boolean.TRUE.equals(v.getValue())) {
                servicesLatch.countDown();
            }
        } catch (Exception e) {
            log.debug("connect: не удалось прочитать ServicesResolved: {}", e.getMessage());
        }

        // Ждём ServicesResolved
        if (servicesLatch.getCount() > 0) {
            log.info("connect: ждём ServicesResolved (таймаут {}мс)...", BleConstants.CONNECT_TIMEOUT_MS);
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
        } else {
            log.info("connect: ServicesResolved уже true, пропускаем ожидание");
        }

        // Ищем GATT-характеристики (без задержки — notification-based подход
        // не требует немедленной готовности GATT для ReadValue)
        findGattCharacteristics(devPath);
        log.info("connect: fromRadio={}, toRadio={}, fromNum={}",
                fromRadioChar != null, toRadioChar != null, fromNumChar != null);

        if (toRadioChar == null || fromRadioChar == null) {
            closeQuietly(propsHandler);
            try { device.Disconnect(); } catch (Exception ignored) {}
            throw new ConnectionException("Meshtastic GATT-характеристики не найдены: " + address);
        }

        // Notification-based приём данных: подписка на PropertiesChanged для fromRadio.
        // Это КЛЮЧЕВОЕ отличие от предыдущей реализации — вместо polling ReadValue
        // (который зависает на BlueZ D-Bus) используем StartNotify + сигналы.
        setupFromRadioNotifications();

        // Подписка на fromNum (уведомления о новых данных — для будущего использования)
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
        consecutiveErrors = 0;
        lastDataReceivedAt = System.currentTimeMillis();

        // Fallback polling — если notifications не работают, через 2с
        // начнёт пробовать ReadValue
        startFallbackPolling();
        log.info("BLE подключено: {}", address);
    }

    @Override
    public void disconnect() {
        connected = false;
        stopFallbackPolling();

        if (fromRadioChar != null) {
            try { fromRadioChar.StopNotify(); } catch (Exception ignored) {}
        }
        if (fromNumChar != null) {
            try { fromNumChar.StopNotify(); } catch (Exception ignored) {}
        }
        if (connectedDevice != null) {
            try { connectedDevice.Disconnect(); } catch (Exception ignored) {}
        }

        closeQuietly(fromRadioHandler);
        closeQuietly(devicePropsHandler);
        fromRadioHandler = null;
        devicePropsHandler = null;
        connectedDevice = null;
        connectedDevicePath = null;
        fromRadioChar = null;
        toRadioChar = null;
        fromNumChar = null;
        fromRadioCharPath = null;
        consecutiveErrors = 0;
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
            Future<Void> future = gattExecutor.submit(() -> {
                // "type": "request" — явный write-with-response для надёжности
                Map<String, Variant<?>> options = new HashMap<>();
                options.put("type", new Variant<>("request"));
                toRadio.WriteValue(protobufPayload, options);
                return null;
            });
            future.get(GATT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            log.debug("Отправлено {} байт в toRadio", protobufPayload.length);
            consecutiveErrors = 0;
        } catch (TimeoutException te) {
            consecutiveErrors++;
            log.error("writeToRadio timeout ({}/{})",
                    consecutiveErrors, MAX_CONSECUTIVE_ERRORS);
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                onUnexpectedDisconnect();
            }
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            consecutiveErrors++;
            log.error("writeToRadio failed ({}/{}): {}",
                    consecutiveErrors, MAX_CONSECUTIVE_ERRORS,
                    cause != null ? cause.getMessage() : e.getMessage());
            if (isFatalBleError(cause instanceof Exception ex ? ex : e)
                    || consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                onUnexpectedDisconnect();
            }
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
        gattExecutor.shutdownNow();
        closeQuietly(dbus);
        log.info("LinuxBle disposed");
    }

    // ==================== Notification-based fromRadio ====================

    /**
     * Подписка на уведомления fromRadio через StartNotify + PropertiesChanged.
     * <p>
     * Это замена polling с ReadValue, который зависает на BlueZ D-Bus
     * (Issues #21, #947). PropertiesChanged сигнал приходит асинхронно
     * без блокировки потоков.
     */
    private void setupFromRadioNotifications() {
        String charPath = fromRadioCharPath;
        if (charPath == null || fromRadioChar == null) return;

        try {
            // Подписка на PropertiesChanged для характеристики fromRadio
            fromRadioHandler = dbus.addSigHandler(
                    Properties.PropertiesChanged.class,
                    signal -> {
                        if (!charPath.equals(signal.getPath())) return;
                        if (!CHAR_IFACE.equals(signal.getInterfaceName())) return;
                        Map<String, Variant<?>> changed = signal.getPropertiesChanged();
                        if (changed == null) return;

                        Variant<?> valueVar = changed.get("Value");
                        if (valueVar == null) return;

                        byte[] data = extractByteArray(valueVar);
                        if (data != null && data.length > 0) {
                            lastDataReceivedAt = System.currentTimeMillis();
                            consecutiveErrors = 0;
                            Consumer<byte[]> listener = fromRadioListener;
                            if (listener != null) {
                                listener.accept(data);
                            }
                            log.debug("fromRadio notification: {} байт", data.length);
                        }
                    });

            // StartNotify — просим BlueZ подписаться на GATT notifications
            fromRadioChar.StartNotify();
            log.info("connect: fromRadio StartNotify + PropertiesChanged handler установлены");
        } catch (Exception e) {
            log.warn("connect: fromRadio StartNotify не удался (fallback polling): {}", e.getMessage());
            // Не фатально — fallback polling подхватит
        }
    }

    /**
     * Извлекает byte[] из Variant. dbus-java может возвращать данные
     * как byte[] напрямую или обёрнутые в Variant.
     */
    @SuppressWarnings("unchecked")
    private static byte[] extractByteArray(Variant<?> variant) {
        if (variant == null) return null;
        Object val = variant.getValue();
        if (val instanceof byte[] bytes) return bytes;
        // dbus-java иногда возвращает List<Byte>
        if (val instanceof List<?> list) {
            byte[] result = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Number n) {
                    result[i] = n.byteValue();
                }
            }
            return result;
        }
        return null;
    }

    // ==================== Fallback Polling ====================

    /**
     * Fallback polling — если уведомления не работают (как на macOS),
     * периодически проверяет, прошло ли 2с без данных, и пробует ReadValue.
     */
    private void startFallbackPolling() {
        stopFallbackPolling();
        pollFuture = pollScheduler.scheduleWithFixedDelay(
                this::fallbackCheck, FALLBACK_CHECK_INTERVAL_MS,
                FALLBACK_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopFallbackPolling() {
        ScheduledFuture<?> f = pollFuture;
        if (f != null) {
            f.cancel(false);
            pollFuture = null;
        }
    }

    /**
     * Fallback: если давно не было данных через notifications, пробуем ReadValue.
     * Один вызов с коротким таймаутом — не блокируем надолго.
     */
    private void fallbackCheck() {
        if (!connected) return;

        long silence = System.currentTimeMillis() - lastDataReceivedAt;
        if (silence < FALLBACK_CHECK_INTERVAL_MS) return; // данные приходят через notifications

        GattCharacteristic1 fromRadio = fromRadioChar;
        if (fromRadio == null) return;

        log.debug("fallback: нет данных {}мс, пробуем ReadValue...", silence);

        try {
            Future<byte[]> future = gattExecutor.submit(() -> fromRadio.ReadValue(Map.of()));
            byte[] data;
            try {
                data = future.get(FALLBACK_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                log.debug("fallback: ReadValue timeout (это нормально если notifications работают)");
                return;
            }

            if (data != null && data.length > 0) {
                lastDataReceivedAt = System.currentTimeMillis();
                consecutiveErrors = 0;
                Consumer<byte[]> listener = fromRadioListener;
                if (listener != null) {
                    listener.accept(data);
                }
                log.info("fallback: ReadValue вернул {} байт (notifications не работают?)", data.length);

                // Если ReadValue работает, drain оставшиеся данные
                drainViaReadValue(fromRadio);
            }
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            log.debug("fallback: ReadValue error: {}",
                    cause != null ? cause.getMessage() : e.getMessage());
        }
    }

    /**
     * Вычитывает данные из fromRadio через ReadValue (используется только в fallback).
     */
    private void drainViaReadValue(GattCharacteristic1 fromRadio) {
        try {
            for (int i = 0; i < 50; i++) {
                byte[] data = fromRadio.ReadValue(Map.of());
                if (data == null || data.length == 0) break;
                lastDataReceivedAt = System.currentTimeMillis();
                Consumer<byte[]> listener = fromRadioListener;
                if (listener != null) listener.accept(data);
            }
        } catch (Exception e) {
            log.debug("fallback drain error: {}", e.getMessage());
        }
    }

    // ==================== Internal ====================

    /**
     * Пересканирует BlueZ для повторного обнаружения устройства.
     */
    private Device1 rediscoverDevice(String address, String devPath) throws ConnectionException {
        log.info("rediscoverDevice: запускаем сканирование для {}", address);
        try {
            Map<String, Variant<?>> filter = new HashMap<>();
            filter.put("UUIDs", new Variant<>(
                    Arrays.asList(BleConstants.SERVICE_UUID), "as"));
            filter.put("Transport", new Variant<>("le"));
            adapter.SetDiscoveryFilter(filter);
            adapter.StartDiscovery();
            Thread.sleep(3000);
            try { adapter.StopDiscovery(); } catch (Exception ignored) {}
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionException("Прервано при пересканировании: " + address);
        } catch (Exception e) {
            log.warn("rediscoverDevice: ошибка сканирования: {}", e.getMessage());
        }

        var objects = objectManager.GetManagedObjects();
        for (var entry : objects.entrySet()) {
            if (entry.getKey().getPath().equals(devPath)) {
                log.info("rediscoverDevice: устройство найдено в BlueZ");
                try {
                    return dbus.getRemoteObject(BLUEZ_BUS, devPath, Device1.class);
                } catch (DBusException e) {
                    throw new ConnectionException("Не удалось создать прокси устройства: " + e.getMessage());
                }
            }
        }
        throw new ConnectionException("Устройство не найдено после пересканирования: " + address);
    }

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
            Object powered = adapterProps.Get(ADAPTER_IFACE, "Powered");
            if (Boolean.TRUE.equals(powered)) {
                adapterState = AdapterState.POWERED_ON;
            } else if (powered instanceof Variant<?> v && Boolean.TRUE.equals(v.getValue())) {
                adapterState = AdapterState.POWERED_ON;
            } else {
                adapterState = AdapterState.POWERED_OFF;
            }
        } catch (Exception e) {
            adapterState = AdapterState.UNKNOWN;
        }
    }

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
     * Сохраняет пути характеристик для использования в PropertiesChanged handlers.
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
                    fromRadioCharPath = path;
                    log.info("fromRadio характеристика: {}", path);
                } else if (uuid.equalsIgnoreCase(BleConstants.TO_RADIO_UUID)) {
                    toRadioChar = dbus.getRemoteObject(BLUEZ_BUS, path, GattCharacteristic1.class);
                    log.info("toRadio характеристика: {}", path);
                } else if (uuid.equalsIgnoreCase(BleConstants.FROM_NUM_UUID)) {
                    fromNumChar = dbus.getRemoteObject(BLUEZ_BUS, path, GattCharacteristic1.class);
                    log.info("fromNum характеристика: {}", path);
                }
            }
        } catch (DBusException e) {
            log.error("Ошибка поиска GATT-характеристик", e);
        }
    }

    private void onUnexpectedDisconnect() {
        log.warn("BLE соединение разорвано неожиданно");
        connected = false;
        stopFallbackPolling();
        Consumer<BleState> sl = stateListener;
        if (sl != null) {
            sl.accept(new BleState.Disconnected());
        }
    }

    private static boolean isObjectGone(Exception e) {
        return e.getClass().getSimpleName().contains("UnknownObject");
    }

    private static boolean isFatalBleError(Exception e) {
        return isObjectGone(e)
                || e.getClass().getSimpleName().contains("NotConnected")
                || e.getClass().getSimpleName().contains("NotPermitted");
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
