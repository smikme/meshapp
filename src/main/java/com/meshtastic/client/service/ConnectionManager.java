package com.meshtastic.client.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.meshtastic.client.connection.*;
import com.meshtastic.client.connection.ble.BleConnection;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
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
 * Управляет жизненным циклом соединений (TCP и Serial): хранит профили подключений
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
        disconnect(id);
        entries.removeIf(e -> e.getId().equals(id));
        save();
        fireChanged();
    }

    /**
     * Устанавливает соединение по идентификатору профиля.
     * Создаёт транспорт (TCP или Serial), {@link ProtocolHandler}, {@link DeviceState}
     * и запускает config exchange. Если соединение с этим id уже активно, вызов игнорируется.
     *
     * @param id идентификатор профиля подключения
     * @throws ConnectionException если профиль не найден или соединение не удалось
     */
    public synchronized void connect(String id) throws ConnectionException {
        userDisconnectedIds.remove(id);
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
                entry.setConnected(true);
                fireChanged();
            }

            @Override
            public void onDisconnected() {
                entry.setConnected(false);
                cleanupConnection(id);
                fireChanged();
                if (!userDisconnectedIds.contains(id)) {
                    ReconnectService.getInstance().startReconnect(id);
                }
            }

            @Override
            public void onConnectionError(String message, Throwable cause) {
                entry.setConnected(false);
                cleanupConnection(id);
                fireChanged();
                if (!userDisconnectedIds.contains(id)) {
                    ReconnectService.getInstance().startReconnect(id);
                }
            }
        });
        conn.connect();
        activeConnections.put(id, conn);

        ProtocolHandler protocolHandler = new ProtocolHandler(conn);
        protocolHandlers.put(id, protocolHandler);

        // Heartbeat сразу после connect — устройство закрывает idle TCP через ~7 сек
        protocolHandler.startHeartbeat();

        DeviceState deviceState = new DeviceState();
        deviceStates.put(id, deviceState);

        MessageListenerService messageListener = new MessageListenerService(deviceState);
        messageListenerServices.put(id, messageListener);
        protocolHandler.addListener(messageListener);

        ConfigExchangeService configExchange = new ConfigExchangeService(protocolHandler, deviceState);
        CompletableFuture<DeviceState> future = configExchange.startConfigExchange();
        configFutures.put(id, future);

        future.thenAccept(ds -> {
            String nodeId = String.format("!%08x", ds.getMyNodeNum());
            entry.setNodeId(nodeId);
            save();
            log.info("Learned nodeId {} for connection '{}'", nodeId, entry.getName());
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
        userDisconnectedIds.add(id);
        ReconnectService.getInstance().cancelReconnect(id);
        MeshtasticConnection conn = activeConnections.get(id);
        cleanupConnection(id);
        if (conn != null) {
            conn.disconnect();
        }
        ConnectionEntry entry = findEntry(id);
        if (entry != null) {
            entry.setConnected(false);
        }
        fireChanged();
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
            disconnect(id);
        }
    }

    private synchronized void cleanupConnection(String id) {
        activeConnections.remove(id);
        DeviceState ds = deviceStates.remove(id);
        if (ds != null) {
            ds.failAllPendingAcks("DISCONNECTED");
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
