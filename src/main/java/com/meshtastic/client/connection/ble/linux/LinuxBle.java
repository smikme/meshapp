package com.meshtastic.client.connection.ble.linux;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ble.*;
import com.meshtastic.client.connection.ble.linux.BluezInterfaces.Adapter1;
import com.meshtastic.client.connection.ble.linux.BluezInterfaces.Adapter1RemoveDevice;
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
    // Выделенный executor для GATT D-Bus вызовов.
    // НЕ используем ForkJoinPool — зависшие ReadValue (25с D-Bus таймаут)
    // блокировали бы общий пул и не давали запускаться новым вызовам.
    private final ExecutorService gattExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "ble-linux-gatt");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pollFuture;
    private volatile boolean drainInProgress;
    private volatile int consecutiveErrors;
    private static final int MAX_CONSECUTIVE_ERRORS = 3;
    private static final int GATT_TIMEOUT_MS = 5000;

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
        // Чистим старые хэндлеры от предыдущего подключения (reconnect-safe)
        closeQuietly(devicePropsHandler);
        devicePropsHandler = null;

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

        // Устанавливаем Trusted=true — без этого BlueZ может блокировать GATT-операции
        try {
            deviceProps.Set(DEVICE_IFACE, "Trusted", new Variant<>(true));
            log.info("connect: Trusted=true установлено");
        } catch (Exception e) {
            log.debug("connect: не удалось установить Trusted: {}", e.getMessage());
        }

        // Latch для ожидания ServicesResolved
        CountDownLatch servicesLatch = new CountDownLatch(1);

        // Следим за PropertiesChanged на устройстве (без source object —
        // тот же паттерн что и для InterfacesAdded, фильтруем по path в хендлере)
        AutoCloseable propsHandler;
        try {
            propsHandler = dbus.addSigHandler(
                    Properties.PropertiesChanged.class,
                    signal -> {
                        // Фильтр по пути устройства
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

        // Пейринг: если устройство не сопряжено, BlueZ может блокировать GATT
        try {
            Variant<?> paired = deviceProps.Get(DEVICE_IFACE, "Paired");
            if (!Boolean.TRUE.equals(paired.getValue())) {
                log.info("connect: устройство не сопряжено, вызываем Pair()...");
                try {
                    device.Pair();
                    log.info("connect: Pair() завершён");
                } catch (Exception pe) {
                    // AlreadyExists = уже сопряжено, AuthenticationCanceled/Failed = не критично
                    log.debug("connect: Pair() результат: {}", pe.getMessage());
                }
            } else {
                log.info("connect: устройство уже сопряжено");
            }
        } catch (Exception e) {
            log.debug("connect: не удалось проверить Paired: {}", e.getMessage());
        }

        log.info("connect: вызываем Device1.Connect()...");
        try {
            device.Connect();
            log.info("connect: Device1.Connect() вернулся");
        } catch (Exception e) {
            // Если BlueZ удалил device object (после disconnect) — пересканируем
            if (isObjectGone(e)) {
                log.info("connect: устройство удалено из BlueZ, пересканируем...");
                try {
                    device = rediscoverDevice(address, devPath);
                    deviceProps = dbus.getRemoteObject(BLUEZ_BUS, devPath, Properties.class);
                    // Trusted и Pair для нового объекта
                    try {
                        deviceProps.Set(DEVICE_IFACE, "Trusted", new Variant<>(true));
                    } catch (Exception ignored) {}
                    device.Connect();
                    log.info("connect: Device1.Connect() после rediscovery вернулся");
                } catch (Exception e2) {
                    log.error("connect: повторное подключение не удалось: {}", e2.getMessage());
                    closeQuietly(propsHandler);
                    throw new ConnectionException("BLE подключение не удалось после пересканирования: " + e2.getMessage());
                }
            } else {
                log.error("connect: Device1.Connect() ошибка: {}", e.getMessage());
                closeQuietly(propsHandler);
                throw new ConnectionException("BLE подключение не удалось: " + e.getMessage());
            }
        }

        // Device1.Connect() в BlueZ блокирует до завершения ACL-подключения.
        // ServicesResolved может уже быть true к этому моменту (пришёл сигнал или кэш).
        try {
            Variant<?> resolved = deviceProps.Get(DEVICE_IFACE, "ServicesResolved");
            log.info("connect: ServicesResolved текущее значение: {}", resolved.getValue());
            if (Boolean.TRUE.equals(resolved.getValue())) {
                servicesLatch.countDown();
            }
        } catch (Exception e) {
            log.debug("connect: не удалось прочитать ServicesResolved: {}", e.getMessage());
        }

        // Ждём ServicesResolved если ещё не true
        if (servicesLatch.getCount() > 0) {
            log.info("connect: ждём ServicesResolved (таймаут {}мс)...", BleConstants.CONNECT_TIMEOUT_MS);
            try {
                if (!servicesLatch.await(BleConstants.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    log.error("connect: ТАЙМАУТ ServicesResolved для {}", address);
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

        // Задержка после ServicesResolved — GATT на BlueZ не готов мгновенно.
        // BlueZ кэширует GATT-атрибуты, но операции чтения/записи могут висеть
        // если устройство ещё инициализирует BLE-стек после reconnect.
        log.info("connect: ждём стабилизации GATT (2с)...");
        try { Thread.sleep(2000); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(propsHandler);
            throw new ConnectionException("Прервано при подключении: " + address);
        }

        // Ищем GATT-характеристики
        findGattCharacteristics(devPath);
        log.info("connect: fromRadio={}, toRadio={}, fromNum={}",
                fromRadioChar != null, toRadioChar != null, fromNumChar != null);

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

        // Сохраняем состояние — НЕ верифицируем GATT через ReadValue.
        // fromRadio.ReadValue() блокируется если нет данных (устройство ждёт want_config_id).
        // Верификация произойдёт естественно: protocol handler отправит want_config_id →
        // writeToRadio → pollFromRadio получит ответ.
        connectedDevice = device;
        connectedDevicePath = devPath;
        devicePropsHandler = propsHandler;
        connected = true;
        consecutiveErrors = 0;

        // Start polling — первый poll через 500мс (даём время на первый write)
        startPolling(500);
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
            // WriteValue через gattExecutor с таймаутом — без этого D-Bus таймаут (25с)
            // блокирует вызывающий поток. gattExecutor = CachedThreadPool, новый поток
            // создаётся даже если предыдущий ещё висит на D-Bus.
            Future<Void> future = gattExecutor.submit(() -> {
                toRadio.WriteValue(protobufPayload, Map.of());
                return null;
            });
            future.get(GATT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            log.debug("Отправлено {} байт в toRadio", protobufPayload.length);
            consecutiveErrors = 0;
            scheduleDrainAfterWrite();
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

    // ==================== Polling ====================

    private void startPolling(int initialDelayMs) {
        stopPolling();
        pollFuture = pollScheduler.scheduleWithFixedDelay(
                this::triggerDrain, initialDelayMs, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
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
     * ReadValue выполняется через gattExecutor с таймаутом 5с —
     * без этого D-Bus таймаут (25с) блокирует poll-поток.
     * gattExecutor = CachedThreadPool, поэтому даже если предыдущий ReadValue
     * ещё висит, новый вызов получит свой поток.
     */
    private void pollFromRadio() {
        GattCharacteristic1 fromRadio = fromRadioChar;
        if (!connected || fromRadio == null) {
            drainInProgress = false;
            return;
        }
        try {
            for (int i = 0; i < 100; i++) {
                Future<byte[]> future = gattExecutor.submit(() -> fromRadio.ReadValue(Map.of()));

                byte[] data;
                try {
                    data = future.get(GATT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true);
                    consecutiveErrors++;
                    log.warn("Polling fromRadio timeout ({}/{})",
                            consecutiveErrors, MAX_CONSECUTIVE_ERRORS);
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        onUnexpectedDisconnect();
                    }
                    return;
                }

                if (data == null || data.length == 0) break;

                consecutiveErrors = 0;
                Consumer<byte[]> listener = fromRadioListener;
                if (listener != null) {
                    listener.accept(data);
                }
            }
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            consecutiveErrors++;
            log.warn("Polling fromRadio error ({}/{}): {}",
                    consecutiveErrors, MAX_CONSECUTIVE_ERRORS,
                    cause != null ? cause.getMessage() : e.getMessage());
            if (isFatalBleError(cause instanceof Exception ex ? ex : e)
                    || consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                onUnexpectedDisconnect();
            }
        } finally {
            drainInProgress = false;
        }
    }

    private void scheduleDrainAfterWrite() {
        pollScheduler.schedule(this::triggerDrain, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // ==================== Internal ====================

    /**
     * Пересканирует BlueZ для повторного обнаружения устройства.
     * Используется при reconnect, когда BlueZ удалил device object после disconnect.
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

        // Проверяем, появилось ли устройство в managed objects
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

    /** D-Bus объект удалён — повторять бессмысленно (устройство отключилось). */
    private static boolean isObjectGone(Exception e) {
        return e.getClass().getSimpleName().contains("UnknownObject");
    }

    /**
     * Безусловно фатальная ошибка (повторять нет смысла).
     * NoReply теперь обрабатывается через счётчик consecutiveErrors.
     */
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
