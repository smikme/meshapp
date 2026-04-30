package com.meshtastic.client.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.meshtastic.client.connection.*;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.ProtocolRegistry;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.meshtastic.MeshtasticProtocol;
import com.meshtastic.client.protocol.meshtastic.MeshtasticProtocolRuntime;
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
 * Менеджер транспортных подключений и протокольных runtime-адаптеров (singleton).
 * <p>
 * Управляет жизненным циклом соединений (TCP, Serial и BLE): хранит профили подключений
 * ({@link ConnectionEntry}) в JSON-файле {@code ~/.meshapp/connections.json},
 * создаёт/разрывает транспорт, выбирает коммуникационный протокол и поднимает
 * соответствующий runtime. Для существующего UI сохраняет доступ к Meshtastic
 * {@link DeviceState} и {@link ProtocolHandler}.
 * <p>
 * Каждое соединение идентифицируется по строковому {@code id} из {@link ConnectionEntry}.
 */
public final class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private static ConnectionManager instance;

    private final Object connectionLock = new Object();
    private final List<ConnectionEntry> entries = new CopyOnWriteArrayList<>();
    private final Map<String, TransportConnection> pendingConnections = new ConcurrentHashMap<>();
    private final Map<String, TransportConnection> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, ProtocolRuntime<?>> protocolRuntimes = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<?>> protocolReadyFutures = new ConcurrentHashMap<>();
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

    /**
     * Загружает сохранённые профили подключений из {@code ~/.meshapp/connections.json}.
     * <p>
     * Runtime-поля вроде {@code connected} и {@code reconnecting} в файл не входят
     * и выставляются только во время работы приложения.
     */
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

    /**
     * Сохраняет текущий список профилей подключений в {@code ~/.meshapp/connections.json}.
     */
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
     * <p>
     * Метод последовательно создаёт transport (TCP, Serial или BLE), открывает его,
     * выбирает протокольный адаптер из {@link ProtocolRegistry}, создаёт
     * {@link ProtocolRuntime} и запускает начальную синхронизацию протокола.
     * Если соединение с этим id уже активно или находится в процессе подключения,
     * вызов игнорируется.
     *
     * @param id идентификатор профиля подключения
     * @throws ConnectionException если профиль не найден или соединение не удалось
     */
    public void connect(String id) throws ConnectionException {
        ConnectionEntry entry;
        TransportConnection conn;
        synchronized (connectionLock) {
            userDisconnectedIds.remove(id);
            userDisconnectReasons.remove(id);
            entry = findEntry(id);
            if (entry == null) {
                throw new ConnectionException("Connection entry not found: " + id);
            }
            if (activeConnections.containsKey(id) || pendingConnections.containsKey(id)) {
                return;
            }
            if (!activeConnections.isEmpty() || !pendingConnections.isEmpty()) {
                throw new ConnectionException("Уже есть активное подключение. Отключитесь перед подключением к другому устройству.");
            }
            conn = createConnection(entry);
            pendingConnections.put(id, conn);
        }

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
                cleanupConnection(id, conn);
                fireChanged();
                if (!userInitiated) {
                    log.info("Scheduling auto-reconnect for '{}' after disconnect", entry.getName());
                    ReconnectService.getInstance().startReconnect(id);
                }
                clearUserDisconnectStateIfIdle(id);
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
                cleanupConnection(id, conn);
                fireChanged();
                if (!userInitiated) {
                    log.info("Scheduling auto-reconnect for '{}' after connection error", entry.getName());
                    ReconnectService.getInstance().startReconnect(id);
                }
                clearUserDisconnectStateIfIdle(id);
            }
        });

        try {
            conn.connect();
            if (!conn.isConnected()) {
                throw new ConnectionException("Подключение завершилось без активного транспорта: " + entry.getName());
            }
        } catch (ConnectionException e) {
            abortPendingConnection(id, conn);
            throw e;
        } catch (RuntimeException e) {
            abortPendingConnection(id, conn);
            throw e;
        }

        ProtocolRuntime<?> protocolRuntime;
        CompletableFuture<?> future;
        boolean cancelledDuringConnect = false;

        synchronized (connectionLock) {
            TransportConnection pendingConn = pendingConnections.get(id);
            boolean entryRemoved = findEntry(id) == null;
            boolean userCancelled = userDisconnectedIds.contains(id);
            boolean replacedDuringConnect = pendingConn != null && pendingConn != conn;
            boolean pendingClearedDuringConnect = pendingConn == null;
            boolean shouldCancel = entryRemoved
                    || userCancelled
                    || replacedDuringConnect
                    || (pendingClearedDuringConnect && !conn.isConnected());
            pendingConnections.remove(id, conn);
            if (shouldCancel) {
                cancelledDuringConnect = true;
                protocolRuntime = null;
                future = null;
            } else {
                if (pendingClearedDuringConnect) {
                    log.warn("Connection '{}' reported disconnect during connect, but transport remained connected; promoting transport to active",
                            entry.getName());
                }
                activeConnections.put(id, conn);

                protocolRuntime = createProtocolRuntime(id, entry, conn);
                protocolRuntimes.put(id, protocolRuntime);
                future = protocolRuntime.start();
                protocolReadyFutures.put(id, future);
                cacheMeshtasticRuntime(id, protocolRuntime);

                entry.setConnected(true);
            }
        }

        if (cancelledDuringConnect) {
            disconnectStalePendingConnection(id, entry, conn);
            return;
        }

        ReconnectService.getInstance().cancelReconnect(id);
        ProtocolRuntime<?> activeRuntime = protocolRuntime;
        future.thenAccept(ignored -> {
            if (activeConnections.get(id) != conn || !entry.isConnected()) {
                log.debug("Skipping post-connect actions for '{}' because transport is no longer active",
                        entry.getName());
                return;
            }
            String nodeId = activeRuntime.getOwnerId();
            if (nodeId != null && !nodeId.isBlank() && !"?".equals(nodeId)) {
                entry.setNodeId(nodeId);
            }
            save();
            activeRuntime.onReady();
            fireChanged();
        });
        fireChanged();
    }

    /**
     * Разрывает соединение и очищает связанные ресурсы
     * (DeviceState, ProtocolHandler, config future).
     *
     * @param id идентификатор профиля подключения
     */
    public void disconnect(String id) {
        disconnect(id, "manual disconnect");
    }

    private void disconnect(String id, String reason) {
        TransportConnection conn;
        TransportConnection pendingConn;
        ConnectionEntry entry;
        String connectionName;
        synchronized (connectionLock) {
            userDisconnectedIds.add(id);
            userDisconnectReasons.put(id, reason);
            ReconnectService.getInstance().cancelReconnect(id);
            entry = findEntry(id);
            connectionName = entry != null ? entry.getName() : id;
            conn = activeConnections.remove(id);
            pendingConn = pendingConnections.get(id);
            if (conn != null) {
                cleanupRuntimeState(id);
            }
            if (entry != null) {
                entry.setConnected(false);
            }
        }

        log.info("Disconnect requested for '{}' ({})", connectionName, reason);
        if (conn != null) {
            conn.disconnect();
        } else if (pendingConn == null) {
            clearUserDisconnectStateIfIdle(id);
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
    public void disconnectForDeviceReboot(String id) {
        ConnectionEntry entry;
        TransportConnection conn;
        ProtocolRuntime<?> protocolRuntime;
        synchronized (connectionLock) {
            userDisconnectedIds.remove(id);
            userDisconnectReasons.remove(id);
            entry = findEntry(id);
            if (entry == null) {
                return;
            }
            conn = activeConnections.get(id);
            protocolRuntime = protocolRuntimes.get(id);
        }

        if (protocolRuntime instanceof MeshtasticProtocolRuntime meshtasticRuntime) {
            log.info("Preparing Meshtastic runtime for '{}' before reboot reconnect handoff", entry.getName());
            meshtasticRuntime.prepareForReconnectHandoff();
        }

        if (conn != null) {
            // Не вызываем cleanupConnection() здесь: нам нужно, чтобы normal
            // onDisconnected()/onConnectionError() path запустил auto-reconnect.
            conn.disconnect();
            return;
        }

        if (!entry.isReconnecting() && !pendingConnections.containsKey(id)) {
            entry.setConnected(false);
            fireChanged();
            ReconnectService.getInstance().startReconnect(id);
        }
    }

    /**
     * Проверяет, есть ли активное или ещё подключающееся соединение.
     *
     * @return {@code true}, если приложение уже занято одним transport-подключением
     */
    public boolean hasActiveConnection() {
        return !activeConnections.isEmpty() || !pendingConnections.isEmpty();
    }

    /**
     * Возвращает копию списка сохранённых профилей подключений.
     *
     * @return список профилей, безопасный для чтения вызывающим кодом
     */
    public List<ConnectionEntry> getEntries() {
        return new ArrayList<>(entries);
    }

    /**
     * Возвращает Meshtastic {@link DeviceState} для активного подключения.
     * <p>
     * Метод оставлен для совместимости существующих форм. При добавлении новых
     * протоколов они должны предоставлять собственный typed accessor или работать
     * через {@link ProtocolRuntime#getState()}.
     *
     * @param id идентификатор профиля подключения
     * @return состояние Meshtastic-устройства или {@code null}
     */
    public DeviceState getDeviceState(String id) {
        MeshtasticProtocolRuntime runtime = getMeshtasticRuntime(id);
        return runtime != null ? runtime.getDeviceState() : deviceStates.get(id);
    }

    /**
     * Возвращает Meshtastic protocol handler для активного подключения.
     *
     * @param id идентификатор профиля подключения
     * @return handler Meshtastic-протокола или {@code null}
     */
    public ProtocolHandler getProtocolHandler(String id) {
        MeshtasticProtocolRuntime runtime = getMeshtasticRuntime(id);
        return runtime != null ? runtime.getProtocolHandler() : protocolHandlers.get(id);
    }

    /**
     * Возвращает сервис обработки входящих Meshtastic-сообщений.
     *
     * @param id идентификатор профиля подключения
     * @return сервис входящих сообщений или {@code null}
     */
    public MessageListenerService getMessageListenerService(String id) {
        MeshtasticProtocolRuntime runtime = getMeshtasticRuntime(id);
        return runtime != null ? runtime.getMessageListenerService() : messageListenerServices.get(id);
    }

    /**
     * Возвращает future завершения Meshtastic config exchange.
     *
     * @param id идентификатор профиля подключения
     * @return future с заполненным {@link DeviceState} или {@code null}
     */
    public CompletableFuture<DeviceState> getConfigFuture(String id) {
        MeshtasticProtocolRuntime runtime = getMeshtasticRuntime(id);
        return runtime != null ? runtime.getReadyFuture() : configFutures.get(id);
    }

    /**
     * Возвращает nodeId устройства для указанного подключения.
     * Сначала пытается получить id из активного protocol runtime, затем из
     * совместимого Meshtastic-кэша и только потом из сохранённого {@link ConnectionEntry}.
     *
     * @param id идентификатор профиля подключения
     * @return nodeId владельца или {@code null}, если он неизвестен
     */
    public String getOwnerNodeId(String id) {
        ProtocolRuntime<?> runtime = protocolRuntimes.get(id);
        if (runtime != null) {
            String ownerId = runtime.getOwnerId();
            if (ownerId != null && !ownerId.isBlank() && !"?".equals(ownerId)) {
                return ownerId;
            }
        }
        DeviceState ds = deviceStates.get(id);
        if (ds != null && ds.getMyNodeNum() != 0) {
            return String.format("!%08x", ds.getMyNodeNum());
        }
        ConnectionEntry entry = findEntry(id);
        return entry != null ? entry.getNodeId() : null;
    }

    /**
     * Добавляет слушателя изменений списка профилей или runtime-состояния подключений.
     *
     * @param listener callback, вызываемый после изменения состояния
     */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Удаляет ранее зарегистрированный слушатель изменений.
     *
     * @param listener callback для удаления
     */
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * Отключает все активные соединения и освобождает ресурсы (tray icon, BLE polling и т.д.).
     * Вызывается при завершении приложения.
     */
    public void shutdownAll() {
        Set<String> ids = new LinkedHashSet<>(activeConnections.keySet());
        ids.addAll(pendingConnections.keySet());
        for (String id : ids) {
            disconnect(id, "application shutdown");
        }
    }

    private void clearUserDisconnectState(String id) {
        userDisconnectedIds.remove(id);
        userDisconnectReasons.remove(id);
    }

    /**
     * Убирает transport из pending-карты после ошибки или отмены подключения.
     */
    private void abortPendingConnection(String id, TransportConnection conn) {
        synchronized (connectionLock) {
            pendingConnections.remove(id, conn);
        }
        clearUserDisconnectStateIfIdle(id);
    }

    /**
     * Закрывает transport, который успел подключиться уже после удаления/отмены профиля.
     */
    private void disconnectStalePendingConnection(String id, ConnectionEntry entry, TransportConnection conn) {
        log.info("Connection '{}' completed after cancellation; disconnecting stale transport", entry.getName());
        try {
            conn.disconnect();
        } catch (RuntimeException e) {
            log.warn("Failed to disconnect stale transport for '{}'", entry.getName(), e);
        } finally {
            abortPendingConnection(id, conn);
        }
    }

    /**
     * Очищает флаги пользовательского disconnect-а, когда для id больше нет runtime-а.
     */
    private void clearUserDisconnectStateIfIdle(String id) {
        synchronized (connectionLock) {
            if (!activeConnections.containsKey(id) && !pendingConnections.containsKey(id)) {
                clearUserDisconnectState(id);
            }
        }
    }

    /**
     * Удаляет transport из runtime-карт после disconnect/error callback-а.
     */
    private void cleanupConnection(String id, TransportConnection expectedConnection) {
        synchronized (connectionLock) {
            boolean removedActive = activeConnections.remove(id, expectedConnection);
            boolean removedPending = pendingConnections.remove(id, expectedConnection);
            if (removedActive) {
                cleanupRuntimeState(id);
            } else if (!removedPending) {
                return;
            }
        }
    }

    /**
     * Освобождает протокольный runtime и совместимые Meshtastic-кэши.
     */
    private void cleanupRuntimeState(String id) {
        ProtocolRuntime<?> runtime = protocolRuntimes.remove(id);
        if (runtime != null) {
            runtime.close();
        }
        protocolReadyFutures.remove(id);
        deviceStates.remove(id);
        protocolHandlers.remove(id);
        messageListenerServices.remove(id);
        configFutures.remove(id);
    }

    /**
     * Делегирует форматирование параметров транспорта общей transport-фабрике.
     */
    private static String formatConnectionParams(ConnectionEntry entry) {
        return TransportConnectionFactory.describe(entry);
    }

    /**
     * Создаёт transport по профилю подключения, не запуская протокольную логику.
     */
    private TransportConnection createConnection(ConnectionEntry entry) {
        return TransportConnectionFactory.create(entry,
                () -> BleDeviceDiscoveryService.getInstance().getPlatform());
    }

    /**
     * Создаёт protocol runtime поверх уже открытого transport-а.
     */
    private ProtocolRuntime<?> createProtocolRuntime(String id, ConnectionEntry entry, TransportConnection conn) {
        ProtocolType protocolType = entry.getEffectiveProtocol();
        ProtocolRuntimeContext context = new ProtocolRuntimeContext(
                id,
                entry,
                conn,
                formatConnectionParams(entry)
        );
        return ProtocolRegistry.get(protocolType).createRuntime(context);
    }

    /**
     * Возвращает Meshtastic runtime, если указанное подключение использует Meshtastic.
     */
    private MeshtasticProtocolRuntime getMeshtasticRuntime(String id) {
        ProtocolRuntime<?> runtime = protocolRuntimes.get(id);
        return runtime instanceof MeshtasticProtocolRuntime meshtasticRuntime ? meshtasticRuntime : null;
    }

    /**
     * Заполняет старые Meshtastic-карты, которыми ещё пользуются формы и тесты.
     * <p>
     * Эти карты являются compatibility layer на время постепенного перевода UI
     * на протокольные runtime-абстракции.
     */
    private void cacheMeshtasticRuntime(String id, ProtocolRuntime<?> runtime) {
        if (runtime instanceof MeshtasticProtocolRuntime meshtasticRuntime) {
            deviceStates.put(id, meshtasticRuntime.getDeviceState());
            protocolHandlers.put(id, meshtasticRuntime.getProtocolHandler());
            messageListenerServices.put(id, meshtasticRuntime.getMessageListenerService());
            configFutures.put(id, meshtasticRuntime.getReadyFuture());
        }
    }

    /**
     * Возвращает {@code true} только для transport-ов, которым действительно нужен heartbeat.
     * В текущем протоколе heartbeat нужен для TCP и Serial, но не для BLE.
     */
    static boolean shouldStartHeartbeat(ConnectionEntry entry) {
        return MeshtasticProtocol.shouldStartHeartbeat(entry);
    }

    /**
     * Ищет сохранённый профиль подключения по id.
     */
    ConnectionEntry findEntry(String id) {
        for (ConnectionEntry e : entries) {
            if (e.getId().equals(id)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Оповещает UI и сервисы о том, что список подключений или их runtime-состояние изменились.
     */
    void fireChanged() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
