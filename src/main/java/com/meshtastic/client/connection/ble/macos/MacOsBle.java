package com.meshtastic.client.connection.ble.macos;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ble.*;
import com.sun.jna.Callback;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Locale;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.meshtastic.client.connection.ble.macos.ObjCRuntime.*;

/**
 * macOS реализация BLE через CoreBluetooth (JNA + Objective-C runtime).
 * <p>
 * Delegate-классы Objective-C создаются ОДИН РАЗ (static) на весь JVM.
 * Экземпляр MacOsBle привязан к делегату через static map по pointer делегата.
 *
 * <h3>CBManagerState</h3>
 * 0=Unknown, 1=Resetting, 2=Unsupported, 3=Unauthorized, 4=PoweredOff, 5=PoweredOn
 */
public class MacOsBle implements BlePlatform {

    private static final Logger log = LoggerFactory.getLogger(MacOsBle.class);

    private static final long CB_MANAGER_STATE_POWERED_ON = 5;
    private static final long CB_WRITE_WITH_RESPONSE = 0;

    // Статические Obj-C delegate классы (создаются один раз)
    private static long centralDelegateClass;
    private static long peripheralDelegateClass;

    // JNA callbacks — static, чтобы не собрал GC (добавляются к static Obj-C классам)
    private static Callback cbDidUpdateState;
    private static Callback cbDidDiscover;
    private static Callback cbDidConnect;
    private static Callback cbDidDisconnect;
    private static Callback cbDidFailToConnect;
    private static Callback cbDidDiscoverServices;
    private static Callback cbDidDiscoverCharacteristics;
    private static Callback cbDidUpdateValue;
    private static Callback cbDidWriteValue;
    private static Callback cbDidUpdateNotificationState;

    /** Map: делегат pointer → MacOsBle instance (для dispatch из static callbacks). */
    private static final Map<Long, MacOsBle> DELEGATE_MAP = new ConcurrentHashMap<>();

    static {
        createDelegateClasses();
    }

    private long centralManager;
    private long dispatchQueue;
    private long delegateInstance;
    private long peripheralDelegateInstance;

    private volatile long connectedPeripheral;
    private volatile long fromRadioCharacteristic;
    private volatile long toRadioCharacteristic;
    private volatile long fromNumCharacteristic;
    private volatile boolean connected;
    private volatile AdapterState adapterState = AdapterState.UNKNOWN;

    private volatile Consumer<BleDevice> scanCallback;
    private volatile Consumer<byte[]> fromRadioListener;
    private volatile Consumer<BleState> stateListener;

    private final Map<String, Long> discoveredPeripherals = new ConcurrentHashMap<>();

    private volatile CountDownLatch poweredOnLatch = new CountDownLatch(1);
    private volatile CountDownLatch connectLatch;
    private volatile CountDownLatch serviceDiscoveryLatch;
    private volatile CountDownLatch characteristicDiscoveryLatch;
    private volatile CountDownLatch notifyLatch;
    private volatile CountDownLatch drainLatch;

    // Polling: fromRadio не поддерживает notifications, поэтому опрашиваем периодически
    private final ScheduledExecutorService pollScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ble-poll");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pollFuture;
    private final AtomicBoolean drainInProgress = new AtomicBoolean(false);

    public MacOsBle() {
        initCentralManager();
    }

    private void initCentralManager() {
        dispatchQueue = createDispatchQueue("com.meshtastic.ble");

        delegateInstance = allocInitClass(centralDelegateClass);
        peripheralDelegateInstance = allocInitClass(peripheralDelegateClass);

        // Привязываем оба делегата к этому экземпляру
        DELEGATE_MAP.put(delegateInstance, this);
        DELEGATE_MAP.put(peripheralDelegateInstance, this);

        long cbCentralManagerCls = cls("CBCentralManager");
        long alloc = msgSend(cbCentralManagerCls, "alloc");
        centralManager = ObjCRuntime.msgSend(alloc,
                "initWithDelegate:queue:", delegateInstance, dispatchQueue);

        log.info("CBCentralManager создан");
    }

    // ====== Scanning ======

    @Override
    public void startScan(Consumer<BleDevice> onDeviceFound) {
        this.scanCallback = onDeviceFound;
        log.info("Waiting for BLE adapter PoweredOn...");
        waitForPoweredOn();
        log.info("BLE adapter state: {}", adapterState);

        long serviceUuid = cbUuid(BleConstants.SERVICE_UUID);
        long serviceArray = nsArrayWith(serviceUuid);

        msgSend(centralManager, "scanForPeripheralsWithServices:options:", serviceArray, 0L);
        log.info("BLE scan started (filter: Meshtastic service UUID)");
    }

    @Override
    public void stopScan() {
        if (centralManager != 0) {
            msgSend(centralManager, "stopScan");
        }
        this.scanCallback = null;
        log.info("BLE scan stopped");
    }

    // ====== Connection ======

    @Override
    public void connect(String address) throws ConnectionException {
        log.info("[BLE] connect() start: address={}", address);

        log.info("[BLE] Step 1: waitForPoweredOn...");
        waitForPoweredOn();
        log.info("[BLE] Step 1 done, adapter state: {}", adapterState);

        Long peripheralPtr = discoveredPeripherals.get(address);
        if (peripheralPtr == null) {
            // Reconnect: retrieve peripheral by UUID from CoreBluetooth system cache
            log.info("[BLE] Device not in scan cache, trying retrievePeripheralsWithIdentifiers...");
            long nsuuidCls = cls("NSUUID");
            long nsuuid = msgSend(msgSend(nsuuidCls, "alloc"), "initWithUUIDString:", nsString(address));
            long identifiers = nsArrayWith(nsuuid);
            long peripherals = msgSend(centralManager, "retrievePeripheralsWithIdentifiers:", identifiers);
            long count = msgSend(peripherals, "count");
            if (count > 0) {
                long p = msgSend(peripherals, "objectAtIndex:", 0L);
                msgSend(p, "retain");
                discoveredPeripherals.put(address, p);
                peripheralPtr = p;
                log.info("[BLE] Retrieved peripheral from system cache");
            } else {
                log.error("[BLE] Device not found in system cache either (address={})", address);
                throw new ConnectionException(
                        "BLE device not found: " + address + ". Run scan first.");
            }
        }
        long peripheral = peripheralPtr;

        connectLatch = new CountDownLatch(1);
        serviceDiscoveryLatch = new CountDownLatch(1);
        characteristicDiscoveryLatch = new CountDownLatch(1);

        log.info("[BLE] Step 2: connectPeripheral...");
        msgSend(centralManager, "connectPeripheral:options:", peripheral, 0L);

        try {
            if (!connectLatch.await(BleConstants.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                msgSend(centralManager, "cancelPeripheralConnection:", peripheral);
                throw new ConnectionException("BLE connect timeout (" +
                        BleConstants.CONNECT_TIMEOUT_MS + "ms): " + address);
            }
            log.info("[BLE] Step 2 done: peripheral connected");

            connectedPeripheral = peripheral;

            // Устанавливаем делегат для peripheral
            msgSend(peripheral, "setDelegate:", peripheralDelegateInstance);

            // Обнаруживаем сервисы
            log.info("[BLE] Step 3: discoverServices...");
            long serviceUuid = cbUuid(BleConstants.SERVICE_UUID);
            long serviceArray = nsArrayWith(serviceUuid);
            msgSend(peripheral, "discoverServices:", serviceArray);

            if (!serviceDiscoveryLatch.await(
                    BleConstants.SERVICE_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                disconnect();
                throw new ConnectionException("BLE service discovery timeout (" +
                        BleConstants.SERVICE_DISCOVERY_TIMEOUT_MS + "ms): " + address);
            }
            log.info("[BLE] Step 3 done: services discovered");

            // Обнаруживаем характеристики
            long services = msgSend(peripheral, "services");
            long serviceCount = msgSend(services, "count");
            if (serviceCount == 0) {
                disconnect();
                throw new ConnectionException("Meshtastic GATT service not found on: " + address);
            }
            long service = msgSend(services, "objectAtIndex:", 0L);

            log.info("[BLE] Step 4: discoverCharacteristics...");
            long fromRadioUuid = cbUuid(BleConstants.FROM_RADIO_UUID);
            long toRadioUuid = cbUuid(BleConstants.TO_RADIO_UUID);
            long fromNumUuid = cbUuid(BleConstants.FROM_NUM_UUID);
            long charUuids = cls("NSArray");
            charUuids = ObjCRuntime.msgSendPtrLong(charUuids, "arrayWithObjects:count:",
                    buildPointerArray(fromRadioUuid, toRadioUuid, fromNumUuid), 3L);

            msgSend(peripheral, "discoverCharacteristics:forService:", charUuids, service);

            if (!characteristicDiscoveryLatch.await(
                    BleConstants.SERVICE_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                disconnect();
                throw new ConnectionException("BLE characteristic discovery timeout (" +
                        BleConstants.SERVICE_DISCOVERY_TIMEOUT_MS + "ms): " + address);
            }
            log.info("[BLE] Step 4 done: fromRadio={}, toRadio={}, fromNum={}",
                    fromRadioCharacteristic != 0, toRadioCharacteristic != 0,
                    fromNumCharacteristic != 0);

            if (toRadioCharacteristic == 0 || fromRadioCharacteristic == 0) {
                disconnect();
                throw new ConnectionException(
                        "Required Meshtastic GATT characteristics not found on: " + address);
            }

            // fromRadio НЕ поддерживает notifications — используем polling.
            // fromNum поддерживает — подписываемся как быстрый триггер drain.
            log.info("[BLE] Step 5: subscribe to fromNum notifications...");

            if (fromNumCharacteristic != 0) {
                notifyLatch = new CountDownLatch(1);
                setNotify(peripheral, true, fromNumCharacteristic);
                if (!notifyLatch.await(5, TimeUnit.SECONDS)) {
                    log.warn("[BLE] fromNum notification subscription timeout");
                }
            }

            // Drain stale fromRadio data
            log.info("[BLE] Step 6: initial drain of fromRadio...");
            drainInProgress.set(true);
            drainLatch = new CountDownLatch(1);
            drainFromRadio();
            drainLatch.await(3, TimeUnit.SECONDS);
            log.info("[BLE] Step 6 done: initial drain complete");

            // Step 7: start periodic polling of fromRadio
            startPolling();

            connected = true;
            log.info("[BLE] connect() DONE — fully connected to {}", address);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionException("BLE connect interrupted: " + address, e);
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        stopPolling();
        long peripheral = connectedPeripheral;
        if (peripheral != 0) {
            if (fromNumCharacteristic != 0) {
                setNotify(peripheral, false, fromNumCharacteristic);
            }
            msgSend(centralManager, "cancelPeripheralConnection:", peripheral);
        }
        connectedPeripheral = 0;
        fromRadioCharacteristic = 0;
        toRadioCharacteristic = 0;
        fromNumCharacteristic = 0;
        drainInProgress.set(false);
        log.info("BLE disconnected");
    }

    @Override
    public boolean isConnected() {
        return connected && connectedPeripheral != 0;
    }

    @Override
    public void writeToRadio(byte[] protobufPayload) {
        if (!isConnected() || toRadioCharacteristic == 0) {
            log.warn("Cannot write: BLE not connected or toRadio characteristic missing");
            return;
        }
        log.debug("[BLE] writeToRadio: {} bytes", protobufPayload.length);
        long nsData = nsData(protobufPayload);
        writeValueForCharacteristic(connectedPeripheral, nsData,
                toRadioCharacteristic, CB_WRITE_WITH_RESPONSE);

        // Kickstart drain after write — не ждём poll cycle
        pollScheduler.schedule(this::triggerDrain, 200, TimeUnit.MILLISECONDS);
    }

    @Override
    public void setFromRadioListener(Consumer<byte[]> listener) {
        this.fromRadioListener = listener;
    }

    @Override
    public void setStateListener(Consumer<BleState> listener) {
        this.stateListener = listener;
    }

    @Override
    public AdapterState getAdapterState() {
        return adapterState;
    }

    @Override
    public void dispose() {
        disconnect();
        stopScan();
        pollScheduler.shutdown();
        DELEGATE_MAP.remove(delegateInstance);
        DELEGATE_MAP.remove(peripheralDelegateInstance);
        discoveredPeripherals.clear();
        log.info("MacOsBle disposed");
    }

    // ====== Helpers ======

    private void waitForPoweredOn() {
        if (adapterState == AdapterState.POWERED_ON) { return; }
        try {
            if (!poweredOnLatch.await(5, TimeUnit.SECONDS)) {
                log.warn("BLE adapter not PoweredOn within 5s (state: {})", adapterState);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final com.sun.jna.Function OBJC_MSG_SEND =
            com.sun.jna.NativeLibrary.getInstance("objc").getFunction("objc_msgSend");

    private void setNotify(long peripheral, boolean enabled, long characteristic) {
        OBJC_MSG_SEND.invokeLong(new Object[]{
                peripheral, sel("setNotifyValue:forCharacteristic:"),
                enabled ? 1L : 0L, characteristic
        });
    }

    private void writeValueForCharacteristic(long peripheral, long data, long characteristic, long type) {
        OBJC_MSG_SEND.invokeLong(new Object[]{
                peripheral, sel("writeValue:forCharacteristic:type:"),
                data, characteristic, type
        });
    }

    private static Pointer buildPointerArray(long... objects) {
        com.sun.jna.Memory mem = new com.sun.jna.Memory(
                (long) objects.length * com.sun.jna.Native.POINTER_SIZE);
        for (int i = 0; i < objects.length; i++) {
            mem.setLong((long) i * com.sun.jna.Native.POINTER_SIZE, objects[i]);
        }
        return mem;
    }

    /** Запускает drain если ещё не активен (вызывается poll-таймером и fromNum). */
    private void triggerDrain() {
        if (!drainInProgress.compareAndSet(false, true)) { return; }
        drainFromRadio();
    }

    private void drainFromRadio() {
        if (connectedPeripheral == 0 || fromRadioCharacteristic == 0) {
            drainInProgress.set(false);
            return;
        }
        msgSend(connectedPeripheral, "readValueForCharacteristic:", fromRadioCharacteristic);
    }

    private void startPolling() {
        stopPolling();
        pollFuture = pollScheduler.scheduleWithFixedDelay(
                this::triggerDrain, 200, 200, TimeUnit.MILLISECONDS);
        log.info("[BLE] Started fromRadio polling (200ms interval)");
    }

    private void stopPolling() {
        ScheduledFuture<?> f = pollFuture;
        if (f != null) {
            f.cancel(false);
            pollFuture = null;
        }
    }

    /** Находит MacOsBle по pointer делегата. */
    private static MacOsBle resolve(long delegatePtr) {
        MacOsBle instance = DELEGATE_MAP.get(delegatePtr);
        if (instance == null) {
            log.warn("No MacOsBle instance for delegate pointer: {}", delegatePtr);
        }
        return instance;
    }

    // ====== Static delegate class creation (один раз на JVM) ======

    private static synchronized void createDelegateClasses() {
        if (centralDelegateClass != 0) { return; }

        centralDelegateClass = createCentralDelegateClassStatic();
        peripheralDelegateClass = createPeripheralDelegateClassStatic();
    }

    private static long createCentralDelegateClassStatic() {
        long cls = createClass("MeshCentralDelegate", "NSObject");
        addProtocol(cls, "CBCentralManagerDelegate");

        cbDidUpdateState = (CentralDidUpdateStateCallback) (self, cmd, central) -> {
            MacOsBle me = resolve(self);
            if (me == null) { return; }
            long state = msgSend(central, "state");
            log.info("CBCentralManager state changed: {}", state);
            me.adapterState = switch ((int) state) {
                case 5 -> AdapterState.POWERED_ON;
                case 4 -> AdapterState.POWERED_OFF;
                case 2 -> AdapterState.UNSUPPORTED;
                case 3 -> AdapterState.UNAUTHORIZED;
                default -> AdapterState.UNKNOWN;
            };
            if (state == CB_MANAGER_STATE_POWERED_ON) {
                me.poweredOnLatch.countDown();
            }
        };
        addMethod(cls, "centralManagerDidUpdateState:", cbDidUpdateState, "v@:@");

        cbDidDiscover = (CentralDidDiscoverCallback) (self, cmd, central,
                                                      peripheral, advertisementData, rssi) -> {
            MacOsBle me = resolve(self);
            if (me == null) { return; }

            long identifier = msgSend(peripheral, "identifier");
            long uuidString = msgSend(identifier, "UUIDString");
            String address = toJavaString(uuidString);

            long nsName = msgSend(peripheral, "name");
            String name = toJavaString(nsName);

            int rssiValue = (int) msgSend(rssi, "intValue");

            // Retain (CoreBluetooth может освободить peripheral после scan callback)
            Long prev = me.discoveredPeripherals.put(address, peripheral);
            if (prev == null) {
                msgSend(peripheral, "retain");
            }

            log.info("Discovered BLE: {} ({}) RSSI: {}", name, address, rssiValue);

            Consumer<BleDevice> cb = me.scanCallback;
            if (cb != null) {
                cb.accept(new BleDevice(address, name, rssiValue));
            }
        };
        addMethod(cls, "centralManager:didDiscoverPeripheral:advertisementData:RSSI:",
                cbDidDiscover, "v@:@@@@");

        cbDidConnect = (CentralDidConnectCallback) (self, cmd, central, peripheral) -> {
            MacOsBle me = resolve(self);
            if (me == null) { return; }
            log.info("CBCentralManager: didConnectPeripheral");
            CountDownLatch latch = me.connectLatch;
            if (latch != null) { latch.countDown(); }
        };
        addMethod(cls, "centralManager:didConnectPeripheral:", cbDidConnect, "v@:@@");

        cbDidDisconnect = (CentralDidDisconnectCallback) (self, cmd, central, peripheral, error) -> {
            MacOsBle me = resolve(self);
            if (me == null) { return; }
            log.info("CBCentralManager: didDisconnectPeripheral");
            me.connected = false;
            me.connectedPeripheral = 0;
            Consumer<BleState> listener = me.stateListener;
            if (listener != null) {
                listener.accept(new BleState.Disconnected());
            }
        };
        addMethod(cls, "centralManager:didDisconnectPeripheral:error:", cbDidDisconnect, "v@:@@@");

        cbDidFailToConnect = (CentralDidDisconnectCallback) (self, cmd, central, peripheral, error) -> {
            MacOsBle me = resolve(self);
            if (me == null) { return; }
            String msg = "Failed to connect";
            if (error != 0) {
                long desc = msgSend(error, "localizedDescription");
                msg = toJavaString(desc);
            }
            log.error("CBCentralManager: didFailToConnect — {}", msg);
            CountDownLatch latch = me.connectLatch;
            if (latch != null) { latch.countDown(); }
            Consumer<BleState> listener = me.stateListener;
            if (listener != null) {
                listener.accept(new BleState.Error(msg, null));
            }
        };
        addMethod(cls, "centralManager:didFailToConnectPeripheral:error:",
                cbDidFailToConnect, "v@:@@@");

        registerClass(cls);
        return cls;
    }

    private static long createPeripheralDelegateClassStatic() {
        long cls = createClass("MeshPeripheralDelegate", "NSObject");
        addProtocol(cls, "CBPeripheralDelegate");

        cbDidDiscoverServices = (PeripheralDelegateCallback) (self, cmd, peripheral, arg) -> {
            MacOsBle me = resolve(self);
            if (me == null) { return; }
            if (arg != 0) {
                long desc = msgSend(arg, "localizedDescription");
                log.error("GATT service discovery error: {}", toJavaString(desc));
            } else {
                long services = msgSend(peripheral, "services");
                long count = msgSend(services, "count");
                log.info("Discovered {} GATT services", count);
            }
            CountDownLatch latch = me.serviceDiscoveryLatch;
            if (latch != null) { latch.countDown(); }
        };
        addMethod(cls, "peripheral:didDiscoverServices:", cbDidDiscoverServices, "v@:@@");

        cbDidDiscoverCharacteristics = (PeripheralDelegateCallback)
                (self, cmd, peripheral, service) -> {
            MacOsBle me = resolve(self);
            if (me == null) { return; }
            // Третий аргумент — service (не error); error идёт как 4-й, но наш callback
            // interface имеет только 4 аргумента, поэтому error не доступен здесь.
            // Для didDiscoverCharacteristicsForService:error: нужен 5-arg callback.
            long characteristics = msgSend(service, "characteristics");
            long count = msgSend(characteristics, "count");
            log.info("Discovered {} characteristics for service", count);

            for (long i = 0; i < count; i++) {
                long characteristic = msgSend(characteristics, "objectAtIndex:", i);
                long uuid = msgSend(characteristic, "UUID");
                long uuidStr = msgSend(uuid, "UUIDString");
                String uuidJava = toJavaString(uuidStr);

                if (uuidJava != null) {
                    String lower = uuidJava.toLowerCase(Locale.ROOT);
                    if (lower.equals(BleConstants.FROM_RADIO_UUID)) {
                        me.fromRadioCharacteristic = characteristic;
                        log.info("Found fromRadio characteristic");
                    } else if (lower.equals(BleConstants.TO_RADIO_UUID)) {
                        me.toRadioCharacteristic = characteristic;
                        log.info("Found toRadio characteristic");
                    } else if (lower.equals(BleConstants.FROM_NUM_UUID)) {
                        me.fromNumCharacteristic = characteristic;
                        log.info("Found fromNum characteristic");
                    }
                }
            }
            CountDownLatch latch = me.characteristicDiscoveryLatch;
            if (latch != null) { latch.countDown(); }
        };
        addMethod(cls, "peripheral:didDiscoverCharacteristicsForService:error:",
                cbDidDiscoverCharacteristics, "v@:@@@");

        cbDidUpdateValue = (PeripheralDelegateCallback)
                (self, cmd, peripheral, characteristic) -> {
            MacOsBle me = resolve(self);
            if (me == null) { return; }

            // Сравнение по pointer характеристики — без ObjC-аллокаций на hot path
            if (characteristic == me.fromNumCharacteristic) {
                log.debug("[BLE] fromNum notification → triggering drain...");
                me.triggerDrain();
            } else if (characteristic == me.fromRadioCharacteristic) {
                long value = msgSend(characteristic, "value");
                byte[] data = toBytes(value);
                if (data.length > 0) {
                    log.debug("[BLE] Received {} bytes from fromRadio", data.length);
                    Consumer<byte[]> listener = me.fromRadioListener;
                    if (listener != null) {
                        try {
                            listener.accept(data);
                        } catch (Exception e) {
                            log.error("[BLE] Error in fromRadioListener", e);
                        }
                    }
                    // Продолжаем чтение — chain drain (без guard, мы уже в drain)
                    me.drainFromRadio();
                } else {
                    log.debug("[BLE] fromRadio empty — drain complete");
                    me.drainInProgress.set(false);
                    CountDownLatch latch = me.drainLatch;
                    if (latch != null) { latch.countDown(); }
                }
            } else {
                log.debug("[BLE] didUpdateValue for unknown characteristic: {}", characteristic);
            }
        };
        addMethod(cls, "peripheral:didUpdateValueForCharacteristic:error:",
                cbDidUpdateValue, "v@:@@@");

        cbDidWriteValue = (PeripheralDelegateWithErrorCallback)
                (self, cmd, peripheral, characteristic, error) -> {
            if (error != 0) {
                long desc = msgSend(error, "localizedDescription");
                log.error("[BLE] Write error: {}", toJavaString(desc));
            } else {
                log.debug("[BLE] Write successful");
            }
        };
        addMethod(cls, "peripheral:didWriteValueForCharacteristic:error:",
                cbDidWriteValue, "v@:@@@");

        cbDidUpdateNotificationState = (PeripheralDelegateWithErrorCallback)
                (self, cmd, peripheral, characteristic, error) -> {
            MacOsBle me = resolve(self);
            if (me == null) { return; }
            long uuid = msgSend(characteristic, "UUID");
            long uuidStr = msgSend(uuid, "UUIDString");
            if (error != 0) {
                long desc = msgSend(error, "localizedDescription");
                log.error("[BLE] Notification subscription error for {}: {}",
                        toJavaString(uuidStr), toJavaString(desc));
            } else {
                log.info("[BLE] Notification subscribed for: {}", toJavaString(uuidStr));
            }
            CountDownLatch latch = me.notifyLatch;
            if (latch != null) { latch.countDown(); }
        };
        addMethod(cls, "peripheral:didUpdateNotificationStateForCharacteristic:error:",
                cbDidUpdateNotificationState, "v@:@@@");

        registerClass(cls);
        return cls;
    }

    // ====== JNA Callback interfaces ======

    interface CentralDidUpdateStateCallback extends Callback {
        void callback(long self, long cmd, long central);
    }

    interface CentralDidDiscoverCallback extends Callback {
        void callback(long self, long cmd, long central,
                      long peripheral, long advertisementData, long rssi);
    }

    interface CentralDidConnectCallback extends Callback {
        void callback(long self, long cmd, long central, long peripheral);
    }

    interface CentralDidDisconnectCallback extends Callback {
        void callback(long self, long cmd, long central, long peripheral, long error);
    }

    /** General peripheral delegate callback: self, _cmd, peripheral, arg (service/characteristic/error). */
    interface PeripheralDelegateCallback extends Callback {
        void callback(long self, long cmd, long peripheral, long arg);
    }

    /** Peripheral delegate callback with error: self, _cmd, peripheral, characteristic, error. */
    interface PeripheralDelegateWithErrorCallback extends Callback {
        void callback(long self, long cmd, long peripheral, long characteristic, long error);
    }
}
