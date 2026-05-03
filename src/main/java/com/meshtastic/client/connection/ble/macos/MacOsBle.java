package com.meshtastic.client.connection.ble.macos;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ble.*;
import com.sun.jna.Callback;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
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
    private volatile BleProtocolProfile profile = BleProtocolProfile.MESHTASTIC;

    private volatile Consumer<BleDevice> scanCallback;
    private volatile Consumer<byte[]> fromRadioListener;
    private volatile Consumer<BleState> stateListener;

    /**
     * Retained CBPeripheral instances keyed by CoreBluetooth identifier (UUID string).
     * <p>
     * macOS can hand back a new CBPeripheral object for the same logical device after
     * a disconnect or reboot, so the cache must retain replacements and release stale
     * instances explicitly instead of assuming the pointer is stable forever.
     */
    private final Map<String, Long> discoveredPeripherals = new ConcurrentHashMap<>();

    private volatile CountDownLatch poweredOnLatch = new CountDownLatch(1);
    private volatile CountDownLatch connectLatch;
    private volatile CountDownLatch serviceDiscoveryLatch;
    private volatile CountDownLatch characteristicDiscoveryLatch;
    private volatile CountDownLatch notifyLatch;
    private volatile CountDownLatch drainLatch;
    // connect() advances through several async CoreBluetooth phases; callbacks store the first
    // terminal error here so the blocking connect() method can fail fast instead of reporting success.
    private volatile String connectErrorMessage;
    private volatile String serviceDiscoveryErrorMessage;
    private volatile String characteristicDiscoveryErrorMessage;

    // Polling: fromRadio не поддерживает notifications, поэтому опрашиваем периодически
    private final ScheduledExecutorService pollScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ble-poll");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pollFuture;
    private final AtomicBoolean drainInProgress = new AtomicBoolean(false);
    /**
     * Protects stored CoreBluetooth object pointers from concurrent use-after-release.
     * <p>
     * The poll thread, reconnect logic and disconnect callbacks can all touch the same retained
     * CBPeripheral/CBCharacteristic pointers. Keep outbound objc_msgSend calls and teardown
     * under one lock so disconnect cannot release a peripheral while ble-poll is still reading.
     */
    private final Object connectionIoLock = new Object();

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

        long serviceArray = serviceUuidArray(profile);

        msgSend(centralManager, "scanForPeripheralsWithServices:options:", serviceArray, 0L);
        log.info("BLE scan started (filter: {} service UUID)", profile.displayName());
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
        if (adapterState != AdapterState.POWERED_ON) {
            throw new ConnectionException("BLE adapter is not ready: " + adapterState);
        }

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
                cacheDiscoveredPeripheral(address, p);
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
        connectErrorMessage = null;
        serviceDiscoveryErrorMessage = null;
        characteristicDiscoveryErrorMessage = null;

        log.info("[BLE] Step 2: connectPeripheral...");
        msgSend(centralManager, "connectPeripheral:options:", peripheral, 0L);

        try {
            if (!connectLatch.await(BleConstants.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                msgSend(centralManager, "cancelPeripheralConnection:", peripheral);
                throw new ConnectionException("BLE connect timeout (" +
                        BleConstants.CONNECT_TIMEOUT_MS + "ms): " + address);
            }
            failIfConnectErrored();
            log.info("[BLE] Step 2 done: peripheral connected");

            connectedPeripheral = peripheral;

            // Устанавливаем делегат для peripheral
            msgSend(peripheral, "setDelegate:", peripheralDelegateInstance);

            // Обнаруживаем сервисы
            log.info("[BLE] Step 3: discoverServices...");
            long serviceArray = serviceUuidArray(profile);
            msgSend(peripheral, "discoverServices:", serviceArray);

            if (!serviceDiscoveryLatch.await(
                    BleConstants.SERVICE_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                disconnect();
                throw new ConnectionException("BLE service discovery timeout (" +
                        BleConstants.SERVICE_DISCOVERY_TIMEOUT_MS + "ms): " + address);
            }
            failIfConnectErrored();
            if (serviceDiscoveryErrorMessage != null) {
                disconnect();
                throw new ConnectionException("BLE service discovery failed: "
                        + serviceDiscoveryErrorMessage);
            }
            log.info("[BLE] Step 3 done: services discovered");

            // Обнаруживаем характеристики
            long services = msgSend(peripheral, "services");
            long serviceCount = msgSend(services, "count");
            if (serviceCount == 0) {
                disconnect();
                throw new ConnectionException(profile.displayName() + " GATT service not found on: " + address);
            }
            long service = msgSend(services, "objectAtIndex:", 0L);

            log.info("[BLE] Step 4: discoverCharacteristics...");
            long inboundUuid = cbUuid(profile.inboundCharacteristicUuid());
            long outboundUuid = cbUuid(profile.outboundCharacteristicUuid());
            long charUuids = characteristicUuidArray(profile, inboundUuid, outboundUuid);

            msgSend(peripheral, "discoverCharacteristics:forService:", charUuids, service);

            if (!characteristicDiscoveryLatch.await(
                    BleConstants.SERVICE_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                disconnect();
                throw new ConnectionException("BLE characteristic discovery timeout (" +
                        BleConstants.SERVICE_DISCOVERY_TIMEOUT_MS + "ms): " + address);
            }
            failIfConnectErrored();
            if (characteristicDiscoveryErrorMessage != null) {
                disconnect();
                throw new ConnectionException("BLE characteristic discovery failed: "
                        + characteristicDiscoveryErrorMessage);
            }
            log.info("[BLE] Step 4 done: inbound={}, outbound={}, trigger={}",
                    fromRadioCharacteristic != 0, toRadioCharacteristic != 0,
                    fromNumCharacteristic != 0);

            if (toRadioCharacteristic == 0 || fromRadioCharacteristic == 0) {
                disconnect();
                throw new ConnectionException(
                        "Required " + profile.displayName() + " GATT characteristics not found on: " + address);
            }

            if (profile.hasNotifyTriggerCharacteristic()) {
                // Meshtastic: fromRadio НЕ поддерживает notifications — используем polling.
                // fromNum поддерживает — подписываемся как быстрый триггер drain.
                log.info("[BLE] Step 5: subscribe to fromNum notifications...");
                notifyLatch = new CountDownLatch(1);
                setNotify(peripheral, true, fromNumCharacteristic);
                if (!notifyLatch.await(5, TimeUnit.SECONDS)) {
                    log.warn("[BLE] fromNum notification subscription timeout");
                }
                failIfConnectErrored();

                // Drain stale fromRadio data
                log.info("[BLE] Step 6: initial drain of fromRadio...");
                drainInProgress.set(true);
                drainLatch = new CountDownLatch(1);
                drainFromRadio();
                drainLatch.await(3, TimeUnit.SECONDS);
                failIfConnectErrored();
                log.info("[BLE] Step 6 done: initial drain complete");

                // Step 7: start periodic polling of fromRadio
                startPolling();
                failIfConnectErrored();
            } else {
                log.info("[BLE] Step 5: subscribe to inbound notifications...");
                notifyLatch = new CountDownLatch(1);
                setNotify(peripheral, true, fromRadioCharacteristic);
                if (!notifyLatch.await(5, TimeUnit.SECONDS)) {
                    log.warn("[BLE] inbound notification subscription timeout");
                }
                failIfConnectErrored();
            }

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
        synchronized (connectionIoLock) {
            long peripheral = connectedPeripheral;
            long notificationCharacteristic = fromNumCharacteristic != 0
                    ? fromNumCharacteristic
                    : fromRadioCharacteristic;
            if (peripheral != 0) {
                if (notificationCharacteristic != 0) {
                    setNotify(peripheral, false, notificationCharacteristic);
                }
                msgSend(centralManager, "cancelPeripheralConnection:", peripheral);
                // Once a session is torn down, the cached CBPeripheral can become stale after a
                // device reboot. Force the next connect() to reacquire a fresh object.
                evictCachedPeripheral(peripheral);
            }
            connectedPeripheral = 0;
            fromRadioCharacteristic = 0;
            toRadioCharacteristic = 0;
            fromNumCharacteristic = 0;
            drainInProgress.set(false);
        }
        log.info("BLE disconnected");
    }

    @Override
    public boolean isConnected() {
        return connected && connectedPeripheral != 0;
    }

    @Override
    public boolean writeToRadio(byte[] protobufPayload) {
        synchronized (connectionIoLock) {
            long peripheral = connectedPeripheral;
            long characteristic = toRadioCharacteristic;
            if (!connected || peripheral == 0 || characteristic == 0) {
                log.warn("Cannot write: BLE not connected or toRadio characteristic missing");
                return false;
            }
            log.debug("[BLE] writeToRadio: {} bytes ({})", protobufPayload.length, profile.displayName());
            long nsData = nsData(protobufPayload);
            writeValueForCharacteristic(peripheral, nsData, characteristic, CB_WRITE_WITH_RESPONSE);
        }

        if (profile.hasNotifyTriggerCharacteristic()) {
            // Kickstart drain after write — не ждём poll cycle
            pollScheduler.schedule(this::triggerDrain, 200, TimeUnit.MILLISECONDS);
        }
        return true;
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
    public void setProfile(BleProtocolProfile profile) {
        this.profile = profile == null ? BleProtocolProfile.MESHTASTIC : profile;
    }

    @Override
    public BleProtocolProfile getProfile() {
        return profile;
    }

    @Override
    public void dispose() {
        disconnect();
        stopScan();
        pollScheduler.shutdown();
        DELEGATE_MAP.remove(delegateInstance);
        DELEGATE_MAP.remove(peripheralDelegateInstance);
        clearCachedPeripherals();
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

    /**
     * Извлекает human-readable описание из NSError.
     *
     * @param error pointer на NSError или {@code 0}
     * @param fallback запасное сообщение, если NSError пуст или недоступен
     * @return локализованное описание ошибки либо {@code fallback}
     */
    private static String localizedError(long error, String fallback) {
        if (error == 0) { return fallback; }
        long desc = msgSend(error, "localizedDescription");
        String message = toJavaString(desc);
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message;
    }

    /**
     * Прерывает блокирующий connect-flow, если один из async CoreBluetooth callbacks
     * уже сообщил об ошибке или разрыве соединения.
     *
     * @throws ConnectionException если в процессе подключения накопилась terminal error
     */
    private void failIfConnectErrored() throws ConnectionException {
        if (connectErrorMessage != null) {
            // Tear down partial CoreBluetooth state before surfacing the error to the Java caller.
            disconnect();
            throw new ConnectionException("BLE connect failed: " + connectErrorMessage);
        }
    }

    /**
     * Caches a CoreBluetooth peripheral under its stable identifier.
     * <p>
     * Re-scans after reboot can return a different CBPeripheral pointer for the same UUID.
     * The cache therefore retains replacements and releases the previous retained object so
     * reconnect does not reuse stale native pointers.
     */
    private void cacheDiscoveredPeripheral(String address, long peripheral) {
        if (address == null || address.isBlank() || peripheral == 0) {
            return;
        }
        discoveredPeripherals.compute(address, (key, existing) -> {
            if (existing != null && existing == peripheral) {
                return existing;
            }
            msgSend(peripheral, "retain");
            if (existing != null && existing != peripheral) {
                msgSend(existing, "release");
                log.info("[BLE] Replaced cached peripheral for {}", address);
            }
            return peripheral;
        });
    }

    /**
     * Evicts a disconnected peripheral from the local cache and releases the retain held by Java.
     * <p>
     * This is essential after device reboot: the next connect must reacquire a fresh
     * CBPeripheral from scan results or CoreBluetooth system cache instead of reusing a
     * disconnected object from a previous session.
     */
    private void evictCachedPeripheral(long peripheral) {
        if (peripheral == 0) {
            return;
        }
        for (Map.Entry<String, Long> entry : discoveredPeripherals.entrySet()) {
            Long cachedPeripheral = entry.getValue();
            if (cachedPeripheral != null
                    && cachedPeripheral == peripheral
                    && discoveredPeripherals.remove(entry.getKey(), cachedPeripheral)) {
                msgSend(peripheral, "release");
                log.info("[BLE] Evicted cached peripheral {} after disconnect", entry.getKey());
                return;
            }
        }
    }

    /**
     * Releases all retained peripherals cached by this BLE backend.
     */
    private void clearCachedPeripherals() {
        Set<Long> retainedPeripherals = new HashSet<>(discoveredPeripherals.values());
        discoveredPeripherals.clear();
        for (Long peripheral : retainedPeripherals) {
            if (peripheral != null && peripheral != 0) {
                msgSend(peripheral, "release");
            }
        }
    }

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

    private static long serviceUuidArray(BleProtocolProfile profile) {
        long[] uuids = profile == BleProtocolProfile.AUTO
                ? new long[]{
                    cbUuid(BleConstants.SERVICE_UUID),
                    cbUuid(BleConstants.MESHCORE_SERVICE_UUID)
                }
                : new long[]{cbUuid(profile.primaryServiceUuid())};
        long charUuids = cls("NSArray");
        return ObjCRuntime.msgSendPtrLong(charUuids, "arrayWithObjects:count:",
                buildPointerArray(uuids), uuids.length);
    }

    private static long characteristicUuidArray(BleProtocolProfile profile, long inboundUuid, long outboundUuid) {
        long[] uuids;
        if (profile.hasNotifyTriggerCharacteristic()) {
            uuids = new long[]{
                    inboundUuid,
                    outboundUuid,
                    cbUuid(profile.notifyTriggerCharacteristicUuid())
            };
        } else {
            uuids = new long[]{inboundUuid, outboundUuid};
        }
        long charUuids = cls("NSArray");
        return ObjCRuntime.msgSendPtrLong(charUuids, "arrayWithObjects:count:",
                buildPointerArray(uuids), uuids.length);
    }

    /** Запускает drain если ещё не активен (вызывается poll-таймером и fromNum). */
    private void triggerDrain() {
        if (!drainInProgress.compareAndSet(false, true)) { return; }
        drainFromRadio();
    }

    private void drainFromRadio() {
        synchronized (connectionIoLock) {
            long peripheral = connectedPeripheral;
            long characteristic = fromRadioCharacteristic;
            if (peripheral == 0 || characteristic == 0) {
                drainInProgress.set(false);
                return;
            }
            msgSend(peripheral, "readValueForCharacteristic:", characteristic);
        }
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

            // The same logical device can surface as a fresh CBPeripheral after reboot or
            // re-scan; cacheDiscoveredPeripheral() retains replacements and drops stale ones.
            me.cacheDiscoveredPeripheral(address, peripheral);

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
            String disconnectMessage = localizedError(error, "Peripheral disconnected");
            boolean wasConnected;
            synchronized (me.connectionIoLock) {
                wasConnected = me.connected;
                me.evictCachedPeripheral(peripheral);
                me.connected = false;
                me.stopPolling();
                me.connectedPeripheral = 0;
                me.fromRadioCharacteristic = 0;
                me.toRadioCharacteristic = 0;
                me.fromNumCharacteristic = 0;
                me.drainInProgress.set(false);
            }
            if (!wasConnected) {
                // Disconnect during connect/discovery must wake the waiting thread so it cannot
                // continue into a false-positive "connected" state after the peripheral is gone.
                if (me.connectErrorMessage == null) {
                    me.connectErrorMessage = disconnectMessage;
                }
                if (me.serviceDiscoveryErrorMessage == null) {
                    me.serviceDiscoveryErrorMessage = disconnectMessage;
                }
                if (me.characteristicDiscoveryErrorMessage == null) {
                    me.characteristicDiscoveryErrorMessage = disconnectMessage;
                }
                CountDownLatch connectLatch = me.connectLatch;
                if (connectLatch != null) { connectLatch.countDown(); }
                CountDownLatch serviceLatch = me.serviceDiscoveryLatch;
                if (serviceLatch != null) { serviceLatch.countDown(); }
                CountDownLatch characteristicLatch = me.characteristicDiscoveryLatch;
                if (characteristicLatch != null) { characteristicLatch.countDown(); }
                CountDownLatch notifyLatch = me.notifyLatch;
                if (notifyLatch != null) { notifyLatch.countDown(); }
            }
            CountDownLatch drainLatch = me.drainLatch;
            if (drainLatch != null) { drainLatch.countDown(); }
            Consumer<BleState> listener = me.stateListener;
            if (listener != null) {
                listener.accept(new BleState.Disconnected());
            }
        };
        addMethod(cls, "centralManager:didDisconnectPeripheral:error:", cbDidDisconnect, "v@:@@@");

        cbDidFailToConnect = (CentralDidDisconnectCallback) (self, cmd, central, peripheral, error) -> {
            MacOsBle me = resolve(self);
            if (me == null) { return; }
            String msg = localizedError(error, "Failed to connect");
            log.error("CBCentralManager: didFailToConnect — {}", msg);
            me.connectErrorMessage = msg;
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
                me.serviceDiscoveryErrorMessage = localizedError(arg, "Unknown service discovery error");
                log.error("GATT service discovery error: {}", me.serviceDiscoveryErrorMessage);
            } else {
                long services = msgSend(peripheral, "services");
                long count = msgSend(services, "count");
                log.info("Discovered {} GATT services", count);
            }
            CountDownLatch latch = me.serviceDiscoveryLatch;
            if (latch != null) { latch.countDown(); }
        };
        addMethod(cls, "peripheral:didDiscoverServices:", cbDidDiscoverServices, "v@:@@");

        cbDidDiscoverCharacteristics = (PeripheralServiceDelegateWithErrorCallback)
                (self, cmd, peripheral, service, error) -> {
            MacOsBle me = resolve(self);
            if (me == null) { return; }
            // This selector has five native arguments; using a 4-arg callback here corrupts the
            // JNA/Objective-C call boundary on arm64 and can abort the JVM.
            if (error != 0) {
                me.characteristicDiscoveryErrorMessage =
                        localizedError(error, "Unknown characteristic discovery error");
                log.error("GATT characteristic discovery error: {}",
                        me.characteristicDiscoveryErrorMessage);
                CountDownLatch latch = me.characteristicDiscoveryLatch;
                if (latch != null) { latch.countDown(); }
                return;
            }
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
                    if (lower.equals(me.profile.inboundCharacteristicUuid())) {
                        me.fromRadioCharacteristic = characteristic;
                        log.info("Found inbound characteristic");
                    } else if (lower.equals(me.profile.outboundCharacteristicUuid())) {
                        me.toRadioCharacteristic = characteristic;
                        log.info("Found outbound characteristic");
                    } else if (me.profile.hasNotifyTriggerCharacteristic()
                            && lower.equals(me.profile.notifyTriggerCharacteristicUuid())) {
                        me.fromNumCharacteristic = characteristic;
                        log.info("Found notification trigger characteristic");
                    }
                }
            }
            CountDownLatch latch = me.characteristicDiscoveryLatch;
            if (latch != null) { latch.countDown(); }
        };
        addMethod(cls, "peripheral:didDiscoverCharacteristicsForService:error:",
                cbDidDiscoverCharacteristics, "v@:@@@");

        cbDidUpdateValue = (PeripheralDelegateWithErrorCallback)
                (self, cmd, peripheral, characteristic, error) -> {
            MacOsBle me = resolve(self);
            if (me == null) { return; }
            if (error != 0) {
                String message = localizedError(error, "Unknown read error");
                log.error("[BLE] Read error: {}", message);
                me.drainInProgress.set(false);
                CountDownLatch latch = me.drainLatch;
                if (latch != null) { latch.countDown(); }
                return;
            }

            // Сравнение по pointer характеристики — без ObjC-аллокаций на hot path
            if (characteristic == me.fromNumCharacteristic) {
                log.debug("[BLE] fromNum notification → triggering drain...");
                me.triggerDrain();
            } else if (characteristic == me.fromRadioCharacteristic) {
                long value = msgSend(characteristic, "value");
                byte[] data = toBytes(value);
                if (data.length > 0) {
                    log.debug("[BLE] Received {} bytes from inbound characteristic", data.length);
                    Consumer<byte[]> listener = me.fromRadioListener;
                    if (listener != null) {
                        try {
                            listener.accept(data);
                        } catch (Exception e) {
                            log.error("[BLE] Error in fromRadioListener", e);
                        }
                    }
                    if (me.profile.hasNotifyTriggerCharacteristic()) {
                        // Продолжаем чтение — chain drain (без guard, мы уже в drain)
                        me.drainFromRadio();
                    }
                } else {
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

    /** Peripheral delegate callback with service and error: self, _cmd, peripheral, service, error. */
    interface PeripheralServiceDelegateWithErrorCallback extends Callback {
        void callback(long self, long cmd, long peripheral, long service, long error);
    }

    /** Peripheral delegate callback with error: self, _cmd, peripheral, characteristic, error. */
    interface PeripheralDelegateWithErrorCallback extends Callback {
        void callback(long self, long cmd, long peripheral, long characteristic, long error);
    }
}
