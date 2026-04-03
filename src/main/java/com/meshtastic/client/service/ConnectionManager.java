package com.meshtastic.client.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.meshtastic.client.connection.*;
import com.meshtastic.client.connection.ble.BleConnection;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Менеджер соединений с Meshtastic-устройствами (singleton).
 * <p>
 * Управляет жизненным циклом соединений (TCP, Serial и BLE): хранит профили подключений
 * ({@link ConnectionEntry}) в JSON-файле {@code ~/.meshapp/connections.json},
 * создаёт/разрывает соединения, инициирует config exchange
 * и предоставляет доступ к {@link DeviceState} и {@link ProtocolHandler}
 * для каждого активного соединения.
 * <p>
 * Каждое соединение идентифицируется по строковому {@code id} из {@link ConnectionEntry}.
 */
public final class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private static ConnectionManager instance;

    private final List<ConnectionEntry> entries = new CopyOnWriteArrayList<>();
    private final Map<String, MeshtasticConnection> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, DeviceState> deviceStates = new ConcurrentHashMap<>();
    private final Map<String, ProtocolHandler> protocolHandlers = new ConcurrentHashMap<>();
    private final Map<String, MessageListenerService> messageListenerServices = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<DeviceState>> configFutures = new ConcurrentHashMap<>();
    private final Set<String> userDisconnectedIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> userDisconnectReasons = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path configPath;

    private ConnectionManager() {
        String home = System.getProperty("user.home");
        configPath = Paths.get(home, ".meshapp", "connections.json");
        load();
    }

    /**
     * Возвращает единственный экземпляр менеджера соединений.
     * При первом вызове загружает профили из {@code ~/.meshapp/connections.json}.
     *
     * @return экземпляр {@code ConnectionManager}
     */
    public static synchronized ConnectionManager getInstance() {
        if (instance == null) {
            instance = new ConnectionManager();
        }
        return instance;
    }

    public synchronized void load() {
        if (!Files.exists(configPath)) {
            return;
        }
        try (Reader reader = new InputStreamReader(Files.newInputStream(configPath), StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<ConnectionEntry>>() {}.getType();
            List<ConnectionEntry> loaded = gson.fromJson(reader, listType);
            if (loaded != null) {
                entries.clear();
                entries.addAll(loaded);
            }
        } catch (Exception e) {
            log.error("Failed to load connections from {}", configPath, e);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(configPath), StandardCharsets.UTF_8)) {
                gson.toJson(entries, writer);
            }
        } catch (Exception e) {
            log.error("Failed to save connections to {}", configPath, e);
        }
    }

    /**
     * Добавляет новый профиль подключения. Автоматически сохраняет в JSON и оповещает слушателей.
     *
     * @param entry профиль подключения
     */
    public void addEntry(ConnectionEntry entry) {
        entries.add(entry);
        save();
        fireChanged();
    }

    /**
     * Удаляет профиль подключения. Предварительно разрывает соединение,
     * если оно активно. Сохраняет в JSON и оповещает слушателей.
     *
     * @param id идентификатор профиля
     */
    public void removeEntry(String id) {
        ReconnectService.getInstance().cancelReconnect(id);
        disconnect(id, "entry removal");
        entries.removeIf(e -> e.getId().equals(id));
        save();
        fireChanged();
    }

    /**
     * Устанавливает соединение по идентификатору профиля.
     * Создаёт transport (TCP, Serial или BLE), {@link ProtocolHandler}, {@link DeviceState}
     * и запускает config exchange. Если соединение с этим id уже активно, вызов игнорируется.
     *
     * @param id идентификатор профиля подключения
     * @throws ConnectionException если профиль не найден или соединение не удалось
     */
    public synchronized void connect(String id) throws ConnectionException {
        userDisconnectedIds.remove(id);
        userDisconnectReasons.remove(id);
        ConnectionEntry entry = findEntry(id);
        if (entry == null) {
            throw new ConnectionException("Connection entry not found: " + id);
        }
        if (activeConnections.containsKey(id)) {
            return;
        }
        if (!activeConnections.isEmpty()) {
            throw new ConnectionException("Уже есть активное подключение. Отключитесь перед подключением к другому устройству.");
        }
        MeshtasticConnection conn = createConnection(entry);
        conn.setConnectionListener(new ConnectionListener() {
            @Override
            public void onConnected() {
                log.info("Connection '{}' transport connected ({})",
                        entry.getName(), formatConnectionParams(entry));
                entry.setConnected(true);
                fireChanged();
            }

            @Override
            public void onDisconnected() {
                boolean userInitiated = userDisconnectedIds.contains(id);
                String disconnectReason = userDisconnectReasons.get(id);
                if (userInitiated) {
                    if (disconnectReason != null && !disconnectReason.isBlank()) {
                        log.info("Connection '{}' disconnected by user ({})", entry.getName(), disconnectReason);
                    } else {
                        log.info("Connection '{}' disconnected by user", entry.getName());
                    }
                } else {
                    log.warn("Connection '{}' disconnected unexpectedly", entry.getName());
                }
                entry.setConnected(false);
                cleanupConnection(id);
                fireChanged();
                if (!userInitiated) {
                    log.info("Scheduling auto-reconnect for '{}' after disconnect", entry.getName());
                    ReconnectService.getInstance().startReconnect(id);
                }
                clearUserDisconnectState(id);
            }

            @Override
            public void onConnectionError(String message, Throwable cause) {
                boolean userInitiated = userDisconnectedIds.contains(id);
                String disconnectReason = userDisconnectReasons.get(id);
                if (cause != null) {
                    if (userInitiated && disconnectReason != null && !disconnectReason.isBlank()) {
                        log.warn("Connection '{}' error during requested disconnect ({}): {}",
                                entry.getName(), disconnectReason, message, cause);
                    } else {
                        log.warn("Connection '{}' error: {}", entry.getName(), message, cause);
                    }
                } else {
                    if (userInitiated && disconnectReason != null && !disconnectReason.isBlank()) {
                        log.warn("Connection '{}' error during requested disconnect ({}): {}",
                                entry.getName(), disconnectReason, message);
                    } else {
                        log.warn("Connection '{}' error: {}", entry.getName(), message);
                    }
                }
                entry.setConnected(false);
                cleanupConnection(id);
                fireChanged();
                if (!userInitiated) {
                    log.info("Scheduling auto-reconnect for '{}' after connection error", entry.getName());
                    ReconnectService.getInstance().startReconnect(id);
                }
                clearUserDisconnectState(id);
            }
        });
        conn.connect();
        activeConnections.put(id, conn);

        ProtocolHandler protocolHandler = new ProtocolHandler(conn);
        protocolHandlers.put(id, protocolHandler);

        // Heartbeat нужен для transport-ов, которые либо закрываются по idle (TCP),
        // либо требуют периодического keepalive на serial-соединении.
        // В mesh.proto heartbeat отдельно помечен как keepalive для serial.
        if (shouldStartHeartbeat(entry)) {
            protocolHandler.startHeartbeat();
        }

        DeviceState deviceState = new DeviceState();
        deviceStates.put(id, deviceState);

        MessageListenerService messageListener = new MessageListenerService(deviceState, protocolHandler);
        messageListenerServices.put(id, messageListener);
        protocolHandler.addListener(messageListener);

        ConfigExchangeService configExchange = new ConfigExchangeService(protocolHandler, deviceState);
        CompletableFuture<DeviceState> future = configExchange.startConfigExchange();
        configFutures.put(id, future);

        future.thenAccept(ds -> {
            String nodeId = resolveLocalNodeId(ds);
            entry.setNodeId(nodeId);
            save();
            logNodeConnectionContext(entry, ds);
            requestAndLogDeviceMetadata(entry, protocolHandler, ds);
            fireChanged();
        });

        entry.setConnected(true);
        fireChanged();
    }

    /**
     * Разрывает соединение и очищает связанные ресурсы
     * (DeviceState, ProtocolHandler, config future).
     *
     * @param id идентификатор профиля подключения
     */
    public synchronized void disconnect(String id) {
        disconnect(id, "manual disconnect");
    }

    private synchronized void disconnect(String id, String reason) {
        userDisconnectedIds.add(id);
        userDisconnectReasons.put(id, reason);
        ReconnectService.getInstance().cancelReconnect(id);
        ConnectionEntry entry = findEntry(id);
        String connectionName = entry != null ? entry.getName() : id;
        log.info("Disconnect requested for '{}' ({})", connectionName, reason);
        MeshtasticConnection conn = activeConnections.get(id);
        cleanupConnection(id);
        if (conn != null) {
            conn.disconnect();
        } else {
            clearUserDisconnectState(id);
        }
        if (entry != null) {
            entry.setConnected(false);
        }
        fireChanged();
    }

    /**
     * Разрывает соединение как ожидаемую часть reboot/restart цикла устройства.
     * <p>
     * В отличие от {@link #disconnect(String)}, такой разрыв не считается
     * пользовательским отключением: transport освобождается, но auto-reconnect
     * остаётся разрешённым. Это нужно после сохранения конфигурации, когда
     * радио само перезагружается и BLE/TCP/Serial-сессия становится stale.
     *
     * @param id идентификатор профиля подключения
     */
    public synchronized void disconnectForDeviceReboot(String id) {
        userDisconnectedIds.remove(id);
        userDisconnectReasons.remove(id);

        ConnectionEntry entry = findEntry(id);
        if (entry == null) {
            return;
        }

        MeshtasticConnection conn = activeConnections.get(id);
        if (conn != null) {
            // Не вызываем cleanupConnection() здесь: нам нужно, чтобы normal
            // onDisconnected()/onConnectionError() path запустил auto-reconnect.
            conn.disconnect();
            return;
        }

        if (!entry.isReconnecting()) {
            entry.setConnected(false);
            fireChanged();
            ReconnectService.getInstance().startReconnect(id);
        }
    }

    public boolean hasActiveConnection() {
        return !activeConnections.isEmpty();
    }

    public List<ConnectionEntry> getEntries() {
        return new ArrayList<>(entries);
    }

    public DeviceState getDeviceState(String id) {
        return deviceStates.get(id);
    }

    public ProtocolHandler getProtocolHandler(String id) {
        return protocolHandlers.get(id);
    }

    public MessageListenerService getMessageListenerService(String id) {
        return messageListenerServices.get(id);
    }

    public CompletableFuture<DeviceState> getConfigFuture(String id) {
        return configFutures.get(id);
    }

    /**
     * Возвращает nodeId устройства для указанного подключения.
     * Сначала пытается получить из DeviceState (актуальный), затем из ConnectionEntry (кеш).
     */
    public String getOwnerNodeId(String id) {
        DeviceState ds = deviceStates.get(id);
        if (ds != null && ds.getMyNodeNum() != 0) {
            return String.format("!%08x", ds.getMyNodeNum());
        }
        ConnectionEntry entry = findEntry(id);
        return entry != null ? entry.getNodeId() : null;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * Отключает все активные соединения и освобождает ресурсы (tray icon, BLE polling и т.д.).
     * Вызывается при завершении приложения.
     */
    public synchronized void shutdownAll() {
        for (String id : new ArrayList<>(activeConnections.keySet())) {
            disconnect(id, "application shutdown");
        }
    }

    private void clearUserDisconnectState(String id) {
        userDisconnectedIds.remove(id);
        userDisconnectReasons.remove(id);
    }

    private synchronized void cleanupConnection(String id) {
        activeConnections.remove(id);
        DeviceState ds = deviceStates.remove(id);
        if (ds != null) {
            ds.failAllPendingAcks("DISCONNECTED");
            ds.failAllPendingPacketAcks("DISCONNECTED");
            ds.shutdown();
        }
        MessageListenerService mls = messageListenerServices.remove(id);
        if (mls != null) {
            mls.getNotificationManager().dispose();
        }
        ProtocolHandler ph = protocolHandlers.remove(id);
        if (ph != null) {
            ph.shutdown();
        }
        configFutures.remove(id);
    }

    private void requestAndLogDeviceMetadata(ConnectionEntry entry,
                                             ProtocolHandler protocolHandler,
                                             DeviceState deviceState) {
        if (protocolHandler == null || deviceState == null || deviceState.getMyNodeNum() == 0) {
            return;
        }

        Runnable[] listenerHolder = new Runnable[1];
        listenerHolder[0] = () -> {
            MeshProtos.DeviceMetadata metadata = deviceState.getDeviceMetadata();
            if (metadata == null) {
                return;
            }
            deviceState.removeDeviceMetadataListener(listenerHolder[0]);
            log.info("Connection '{}' firmware identified: name='{}', nodeId={}, firmware='{}', params={}",
                    entry.getName(),
                    resolveLocalNodeName(deviceState),
                    resolveLocalNodeId(deviceState),
                    safeText(metadata.getFirmwareVersion()),
                    formatConnectionParams(entry));
        };

        deviceState.addDeviceMetadataListener(listenerHolder[0]);
        if (deviceState.getDeviceMetadata() != null) {
            listenerHolder[0].run();
            return;
        }

        MessageService.requestDeviceMetadata(protocolHandler, deviceState)
                .whenComplete((routingError, throwable) -> {
                    if (throwable != null) {
                        deviceState.removeDeviceMetadataListener(listenerHolder[0]);
                        log.debug("Device metadata request failed for '{}'", entry.getName(), throwable);
                    } else if (routingError != null && routingError != MeshProtos.Routing.Error.NONE) {
                        deviceState.removeDeviceMetadataListener(listenerHolder[0]);
                        log.debug("Device metadata request for '{}' completed with {}",
                                entry.getName(), routingError);
                    }
                });
    }

    private void logNodeConnectionContext(ConnectionEntry entry, DeviceState deviceState) {
        NodeData node = resolveLocalNode(deviceState);
        log.info("Connection '{}' node identified: name='{}', short='{}', nodeId={}, hwModel={}, params={}",
                entry.getName(),
                resolveLocalNodeName(deviceState),
                node != null ? safeText(node.getShortName()) : "?",
                resolveLocalNodeId(deviceState),
                node != null ? safeText(node.getHwModel()) : "?",
                formatConnectionParams(entry));
    }

    private static NodeData resolveLocalNode(DeviceState deviceState) {
        if (deviceState == null || deviceState.getMyNodeNum() == 0) {
            return null;
        }
        return deviceState.getNodeDb().get(deviceState.getMyNodeNum());
    }

    private static String resolveLocalNodeName(DeviceState deviceState) {
        NodeData node = resolveLocalNode(deviceState);
        if (node == null) {
            return resolveLocalNodeId(deviceState);
        }
        if (node.getLongName() != null && !node.getLongName().isBlank()) {
            return node.getLongName().trim();
        }
        if (node.getShortName() != null && !node.getShortName().isBlank()) {
            return node.getShortName().trim();
        }
        if (node.getNodeId() != null && !node.getNodeId().isBlank()) {
            return node.getNodeId().trim();
        }
        return resolveLocalNodeId(deviceState);
    }

    private static String resolveLocalNodeId(DeviceState deviceState) {
        NodeData node = resolveLocalNode(deviceState);
        if (node != null && node.getNodeId() != null && !node.getNodeId().isBlank()) {
            return node.getNodeId().trim();
        }
        int myNodeNum = deviceState != null ? deviceState.getMyNodeNum() : 0;
        return myNodeNum != 0 ? String.format("!%08x", myNodeNum) : "?";
    }

    private static String formatConnectionParams(ConnectionEntry entry) {
        ConnectionType type = entry.getEffectiveType();
        return switch (type) {
            case TCP -> String.format("type=TCP, host=%s, port=%d",
                    safeText(entry.getHost()), entry.getPort());
            case SERIAL -> String.format("type=SERIAL, port=%s, baud=%d",
                    safeText(entry.getPortName()),
                    entry.getBaudRate() > 0 ? entry.getBaudRate() : SerialConnection.DEFAULT_BAUD_RATE);
            case BLE -> String.format("type=BLE, address=%s, deviceName=%s",
                    safeText(entry.getBleAddress()), safeText(entry.getBleDeviceName()));
        };
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "?" : value.trim();
    }

    private MeshtasticConnection createConnection(ConnectionEntry entry) {
        return switch (entry.getEffectiveType()) {
            case TCP -> new TcpConnection(entry.getHost(), entry.getPort());
            case SERIAL -> new SerialConnection(
                    entry.getPortName(),
                    entry.getBaudRate() > 0 ? entry.getBaudRate() : SerialConnection.DEFAULT_BAUD_RATE
            );
            case BLE -> new BleConnection(entry.getBleAddress(),
                    BleDeviceDiscoveryService.getInstance().getPlatform());
        };
    }

    /**
     * Возвращает {@code true} только для transport-ов, которым действительно нужен heartbeat.
     * В текущем протоколе heartbeat нужен для TCP и Serial, но не для BLE.
     */
    static boolean shouldStartHeartbeat(ConnectionEntry entry) {
        ConnectionType type = entry.getEffectiveType();
        return type == ConnectionType.TCP || type == ConnectionType.SERIAL;
    }

    ConnectionEntry findEntry(String id) {
        for (ConnectionEntry e : entries) {
            if (e.getId().equals(id)) {
                return e;
            }
        }
        return null;
    }

    void fireChanged() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
