package com.meshtastic.client.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.meshtastic.client.connection.*;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.protocol.ProtocolRegistry;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionState;
import com.meshtastic.client.protocol.meshcore.MeshCoreKissState;
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
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
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
    private final Map<String, Long> connectionGenerations = new ConcurrentHashMap<>();
    private final Set<String> userDisconnectedIds = ConcurrentHashMap.newKeySet();
    private final Set<String> expectedDeviceRebootIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> userDisconnectReasons = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path configPath;
    private volatile String selectedConnectionId;

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
     * Обновляет сохранённые параметры существующего профиля подключения.
     * Активные и переподключающиеся профили не изменяются, чтобы не менять
     * transport-параметры под уже открытым runtime.
     *
     * @param updated профиль с тем же id и новыми параметрами
     */
    public void updateEntry(ConnectionEntry updated) {
        if (updated == null || updated.getId() == null || updated.getId().isBlank()) {
            throw new IllegalArgumentException("Connection entry id is required");
        }

        synchronized (connectionLock) {
            ConnectionEntry existing = findEntry(updated.getId());
            if (existing == null) {
                throw new IllegalArgumentException("Connection entry not found: " + updated.getId());
            }
            if (existing.isConnected()
                    || existing.isReconnecting()
                    || activeConnections.containsKey(updated.getId())
                    || pendingConnections.containsKey(updated.getId())) {
                throw new IllegalStateException(
                        "Нельзя редактировать активное подключение. Отключитесь перед изменением параметров.");
            }

            updated.setConnected(existing.isConnected());
            updated.setReconnecting(existing.isReconnecting());
            if (updated.getNodeId() == null || updated.getNodeId().isBlank()) {
                updated.setNodeId(existing.getNodeId());
            }

            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).getId().equals(updated.getId())) {
                    entries.set(i, updated);
                    break;
                }
            }
        }
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
        clearExpectedDeviceReboot(id);
        disconnect(id, "entry removal");
        connectionGenerations.remove(id);
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
            expectedDeviceRebootIds.remove(id);
            entry = findEntry(id);
            if (entry == null) {
                throw new ConnectionException("Connection entry not found: " + id);
            }
            if (activeConnections.containsKey(id) || pendingConnections.containsKey(id)) {
                return;
            }
            ensureBleConcurrencyAllowedLocked(id, entry);
            ensureNoDuplicateNodeConnectionLocked(id, entry);
            validateProtocolTransportCombination(entry, entry.getEffectiveProtocol());
            try {
                conn = createConnection(entry);
            } catch (RuntimeException e) {
                throw new ConnectionException("Не удалось создать транспорт подключения: " + e.getMessage(), e);
            }
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
                boolean connectionWasCurrent = cleanupConnection(id, conn);
                if (!connectionWasCurrent) {
                    log.debug("Ignoring stale disconnect callback for '{}'", entry.getName());
                    clearUserDisconnectStateIfIdle(id);
                    return;
                }
                boolean expectedDeviceReboot = expectedDeviceRebootIds.remove(id);
                if (userInitiated) {
                    if (disconnectReason != null && !disconnectReason.isBlank()) {
                        log.info("Connection '{}' disconnected by user ({})", entry.getName(), disconnectReason);
                    } else {
                        log.info("Connection '{}' disconnected by user", entry.getName());
                    }
                } else if (expectedDeviceReboot) {
                    log.info("Connection '{}' disconnected during expected device reboot", entry.getName());
                } else {
                    log.warn("Connection '{}' disconnected unexpectedly", entry.getName());
                }
                entry.setConnected(false);
                selectConnectionIfNeeded();
                fireChanged();
                if (!userInitiated) {
                    log.info("Scheduling auto-reconnect for '{}' after disconnect{}", entry.getName(),
                            expectedDeviceReboot ? " (device reboot)" : "");
                    if (expectedDeviceReboot) {
                        ReconnectService.getInstance().startReconnectAfterDeviceReboot(id);
                    } else {
                        ReconnectService.getInstance().startReconnect(id);
                    }
                }
                clearUserDisconnectStateIfIdle(id);
            }

            @Override
            public void onConnectionError(String message, Throwable cause) {
                boolean userInitiated = userDisconnectedIds.contains(id);
                String disconnectReason = userDisconnectReasons.get(id);
                boolean connectionWasCurrent = cleanupConnection(id, conn);
                if (!connectionWasCurrent) {
                    log.debug("Ignoring stale connection error callback for '{}': {}", entry.getName(), message);
                    clearUserDisconnectStateIfIdle(id);
                    return;
                }
                boolean expectedDeviceReboot = expectedDeviceRebootIds.remove(id);
                if (cause != null) {
                    if (userInitiated && disconnectReason != null && !disconnectReason.isBlank()) {
                        log.warn("Connection '{}' error during requested disconnect ({}): {}",
                                entry.getName(), disconnectReason, message, cause);
                    } else if (expectedDeviceReboot) {
                        log.info("Connection '{}' error during expected device reboot: {}",
                                entry.getName(), message);
                    } else {
                        log.warn("Connection '{}' error: {}", entry.getName(), message, cause);
                    }
                } else {
                    if (userInitiated && disconnectReason != null && !disconnectReason.isBlank()) {
                        log.warn("Connection '{}' error during requested disconnect ({}): {}",
                                entry.getName(), disconnectReason, message);
                    } else if (expectedDeviceReboot) {
                        log.info("Connection '{}' error during expected device reboot: {}",
                                entry.getName(), message);
                    } else {
                        log.warn("Connection '{}' error: {}", entry.getName(), message);
                    }
                }
                entry.setConnected(false);
                selectConnectionIfNeeded();
                fireChanged();
                if (!userInitiated) {
                    log.info("Scheduling auto-reconnect for '{}' after connection error{}", entry.getName(),
                            expectedDeviceReboot ? " (device reboot)" : "");
                    if (expectedDeviceReboot) {
                        ReconnectService.getInstance().startReconnectAfterDeviceReboot(id);
                    } else {
                        ReconnectService.getInstance().startReconnect(id);
                    }
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
        ProtocolType resolvedProtocolType;
        boolean cancelledDuringConnect = false;

        try {
            resolvedProtocolType = resolveProtocolType(id, entry, conn);
        } catch (ConnectionException e) {
            abortPendingConnection(id, conn);
            conn.setConnectionListener(null);
            conn.disconnect();
            throw e;
        } catch (RuntimeException e) {
            abortPendingConnection(id, conn);
            conn.setConnectionListener(null);
            conn.disconnect();
            throw e;
        }

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

                protocolRuntime = createProtocolRuntime(id, entry, conn, resolvedProtocolType);
                protocolRuntimes.put(id, protocolRuntime);
                future = protocolRuntime.start();
                protocolReadyFutures.put(id, future);
                cacheMeshtasticRuntime(id, protocolRuntime);
                connectionGenerations.merge(id, 1L, Long::sum);

                entry.setConnected(true);
                if (selectedConnectionId == null || !isSelectableConnectionIdLocked(selectedConnectionId)) {
                    selectedConnectionId = id;
                }
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
                save();

                ConnectionEntry duplicateEntry = findDuplicateNodeConnection(id, nodeId);
                if (duplicateEntry != null) {
                    log.warn("Connection '{}' resolved to duplicate nodeId {} already active as '{}'; disconnecting new connection",
                            entry.getName(), nodeId, duplicateEntry.getName());
                    disconnect(id, "duplicate node id " + nodeId + " already active as " + duplicateEntry.getName());
                    return;
                }
            }
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
            expectedDeviceRebootIds.remove(id);
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
            selectConnectionIfNeededLocked();
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
    public boolean disconnectForDeviceReboot(String id) {
        return disconnectForDeviceReboot(id, -1);
    }

    /**
     * Разрывает соединение для reboot только если это всё ещё та же transport-сессия,
     * которую видел вызывающий код. Это защищает от позднего save-handoff, который
     * иначе мог бы отключить уже свежее соединение после самостоятельного reconnect.
     */
    public boolean disconnectForDeviceReboot(String id, long expectedConnectionGeneration) {
        return disconnectForDeviceReboot(id, expectedConnectionGeneration, null);
    }

    /**
     * Разрывает соединение для reboot только если сигнал пришёл от текущего runtime-а.
     * Это защищает свежее переподключение от поздних {@code FromRadio.REBOOTED}
     * из уже закрытой протокольной сессии.
     */
    public boolean disconnectForDeviceRebootFromRuntime(String id, ProtocolRuntime<?> expectedRuntime) {
        if (expectedRuntime == null) {
            return false;
        }
        return disconnectForDeviceReboot(id, -1, expectedRuntime);
    }

    private boolean disconnectForDeviceReboot(String id,
                                              long expectedConnectionGeneration,
                                              ProtocolRuntime<?> expectedRuntime) {
        ConnectionEntry entry;
        TransportConnection conn;
        ProtocolRuntime<?> protocolRuntime;
        boolean pendingConnection;
        long currentGeneration;
        synchronized (connectionLock) {
            userDisconnectedIds.remove(id);
            userDisconnectReasons.remove(id);
            entry = findEntry(id);
            if (entry == null) {
                return false;
            }
            currentGeneration = connectionGenerations.getOrDefault(id, 0L);
            if (expectedConnectionGeneration >= 0 && currentGeneration != expectedConnectionGeneration) {
                expectedDeviceRebootIds.remove(id);
                log.info("Skipping stale reboot reconnect handoff for '{}' (expected generation {}, current {})",
                        entry.getName(), expectedConnectionGeneration, currentGeneration);
                return false;
            }
            conn = activeConnections.get(id);
            protocolRuntime = protocolRuntimes.get(id);
            if (expectedRuntime != null && protocolRuntime != expectedRuntime) {
                log.info("Skipping stale protocol reboot signal for '{}': runtime is no longer current",
                        entry.getName());
                return false;
            }
            pendingConnection = pendingConnections.containsKey(id);
            if (conn != null) {
                expectedDeviceRebootIds.add(id);
            } else {
                expectedDeviceRebootIds.remove(id);
            }
        }

        if (protocolRuntime instanceof MeshtasticProtocolRuntime meshtasticRuntime) {
            log.info("Preparing Meshtastic runtime for '{}' before reboot reconnect handoff", entry.getName());
            meshtasticRuntime.prepareForReconnectHandoff();
        }

        if (conn != null) {
            // Не вызываем cleanupConnection() здесь: нам нужно, чтобы normal
            // onDisconnected()/onConnectionError() path запустил auto-reconnect.
            conn.disconnect();
            return true;
        }

        if (!pendingConnection) {
            entry.setConnected(false);
            selectConnectionIfNeeded();
            fireChanged();
            ReconnectService.getInstance().startReconnectAfterDeviceReboot(id);
            return true;
        }
        return false;
    }

    /**
     * Помечает ближайший разрыв соединения как ожидаемый reboot устройства.
     * Это нужно, когда прошивка может закрыть transport сама ещё до того, как
     * UI успеет вызвать {@link #disconnectForDeviceReboot(String)}.
     */
    public void expectDeviceReboot(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        expectedDeviceRebootIds.add(id);
    }

    public void clearExpectedDeviceReboot(String id) {
        if (id != null) {
            expectedDeviceRebootIds.remove(id);
        }
    }

    public long getConnectionGeneration(String id) {
        if (id == null) {
            return 0L;
        }
        return connectionGenerations.getOrDefault(id, 0L);
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
     * Проверяет, занято ли конкретное подключение активным или pending transport-ом.
     *
     * @param id идентификатор профиля
     * @return {@code true}, если это подключение уже открыто или открывается
     */
    public boolean isConnectionActiveOrPending(String id) {
        return activeConnections.containsKey(id) || pendingConnections.containsKey(id);
    }

    /**
     * Проверяет, есть ли открытое или открывающееся BLE transport-подключение.
     */
    public boolean hasActiveBleTransport() {
        synchronized (connectionLock) {
            return findActiveBleTransportLocked(null) != null;
        }
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
     * Возвращает активные для UI подключения: подключённые, открывающиеся
     * и находящиеся в auto-reconnect.
     *
     * @return список активных профилей в порядке сохранённых подключений
     */
    public List<ConnectionEntry> getActiveConnectionEntries() {
        List<ConnectionEntry> result = new ArrayList<>();
        for (ConnectionEntry entry : entries) {
            if (isSelectableConnection(entry)) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Возвращает выбранное в UI подключение или первый доступный активный профиль.
     *
     * @return выбранный профиль подключения или {@code null}
     */
    public ConnectionEntry getSelectedConnectionEntry() {
        synchronized (connectionLock) {
            if (selectedConnectionId != null && isSelectableConnectionIdLocked(selectedConnectionId)) {
                return findEntry(selectedConnectionId);
            }
            return firstSelectableConnectionLocked();
        }
    }

    /**
     * Возвращает id выбранного UI-подключения.
     */
    public String getSelectedConnectionId() {
        ConnectionEntry entry = getSelectedConnectionEntry();
        return entry != null ? entry.getId() : null;
    }

    /**
     * Выбирает активное подключение для всех форм приложения.
     *
     * @param id идентификатор профиля подключения
     */
    public void setSelectedConnectionId(String id) {
        boolean changed;
        synchronized (connectionLock) {
            String previous = selectedConnectionId;
            if (id != null && isSelectableConnectionIdLocked(id)) {
                selectedConnectionId = id;
            } else {
                selectConnectionIfNeededLocked();
            }
            changed = !Objects.equals(previous, selectedConnectionId);
        }
        if (changed) {
            fireChanged();
        }
    }

    /**
     * Возвращает UI-совместимый {@link DeviceState} для активного подключения.
     * <p>
     * Для Meshtastic это нативное состояние protobuf runtime-а. Для MeshCore
     * Companion Protocol возвращается bridge-состояние, которое заполняется
     * контактами, каналами, сообщениями и телеметрией MeshCore.
     *
     * @param id идентификатор профиля подключения
     * @return состояние устройства для UI или {@code null}
     */
    public DeviceState getDeviceState(String id) {
        MeshtasticProtocolRuntime runtime = getMeshtasticRuntime(id);
        if (runtime != null) {
            return runtime.getDeviceState();
        }
        ProtocolRuntime<?> activeRuntime = protocolRuntimes.get(id);
        if (activeRuntime != null && activeRuntime.getState() instanceof MeshCoreCompanionState meshCoreState) {
            return meshCoreState.getDeviceState();
        }
        return deviceStates.get(id);
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
     * Возвращает активный protocol runtime любого поддерживаемого типа.
     *
     * @param id идентификатор профиля подключения
     * @return runtime активного протокола или {@code null}
     */
    public ProtocolRuntime<?> getProtocolRuntime(String id) {
        return protocolRuntimes.get(id);
    }

    /**
     * Возвращает future готовности активного protocol runtime-а.
     *
     * @param id идентификатор профиля подключения
     * @return future готовности или {@code null}
     */
    public CompletableFuture<?> getProtocolReadyFuture(String id) {
        return protocolReadyFutures.get(id);
    }

    /**
     * Возвращает runtime-состояние MeshCore KISS, если подключение использует этот протокол.
     *
     * @param id идентификатор профиля подключения
     * @return состояние MeshCore KISS или {@code null}
     */
    public MeshCoreKissState getMeshCoreKissState(String id) {
        ProtocolRuntime<?> runtime = protocolRuntimes.get(id);
        return runtime != null && runtime.getState() instanceof MeshCoreKissState meshCoreState
                ? meshCoreState
                : null;
    }

    /**
     * Возвращает runtime-состояние MeshCore Companion, если подключение использует Companion Protocol.
     *
     * @param id идентификатор профиля подключения
     * @return состояние MeshCore Companion или {@code null}
     */
    public MeshCoreCompanionState getMeshCoreCompanionState(String id) {
        ProtocolRuntime<?> runtime = protocolRuntimes.get(id);
        return runtime != null && runtime.getState() instanceof MeshCoreCompanionState meshCoreState
                ? meshCoreState
                : null;
    }

    /**
     * Возвращает фактически активный протокол или сохранённый выбор профиля.
     */
    public ProtocolType getActiveProtocolType(String id) {
        ProtocolRuntime<?> runtime = protocolRuntimes.get(id);
        if (runtime != null) {
            return runtime.getProtocolType();
        }
        ConnectionEntry entry = findEntry(id);
        return entry != null ? entry.getEffectiveProtocol() : null;
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
     *
     * @return {@code true}, если callback относится к текущему active/pending transport-у
     */
    private boolean cleanupConnection(String id, TransportConnection expectedConnection) {
        synchronized (connectionLock) {
            boolean removedActive = activeConnections.remove(id, expectedConnection);
            boolean removedPending = pendingConnections.remove(id, expectedConnection);
            if (removedActive) {
                cleanupRuntimeState(id);
            } else if (!removedPending) {
                return false;
            }
            return true;
        }
    }

    private void selectConnectionIfNeeded() {
        synchronized (connectionLock) {
            selectConnectionIfNeededLocked();
        }
    }

    private void ensureNoDuplicateNodeConnectionLocked(String id, ConnectionEntry entry) throws ConnectionException {
        String nodeId = normalizeNodeId(entry != null ? entry.getNodeId() : null);
        if (nodeId == null) {
            return;
        }

        ConnectionEntry duplicateEntry = findDuplicateNodeConnectionLocked(id, nodeId);
        if (duplicateEntry == null) {
            return;
        }

        throw new ConnectionException("Нода " + entry.getNodeId()
                + " уже подключена через \"" + duplicateEntry.getName()
                + "\" (" + duplicateEntry.getEffectiveType() + ")");
    }

    private void ensureBleConcurrencyAllowedLocked(String id, ConnectionEntry entry) throws ConnectionException {
        if (entry == null || entry.getEffectiveType() != ConnectionType.BLE) {
            return;
        }
        BleDeviceDiscoveryService discovery = BleDeviceDiscoveryService.getInstance();
        if (discovery.supportsParallelConnections()) {
            return;
        }
        ConnectionEntry activeBle = findActiveBleTransportLocked(id);
        if (activeBle == null) {
            return;
        }
        throw new ConnectionException("Параллельные BLE-подключения на этой платформе пока не поддерживаются. "
                + "Отключите \"" + activeBle.getName() + "\" перед подключением \"" + entry.getName() + "\".");
    }

    private ConnectionEntry findActiveBleTransportLocked(String excludeId) {
        for (ConnectionEntry candidate : entries) {
            if (candidate == null || candidate.getEffectiveType() != ConnectionType.BLE) {
                continue;
            }
            if (excludeId != null && excludeId.equals(candidate.getId())) {
                continue;
            }
            if (activeConnections.containsKey(candidate.getId()) || pendingConnections.containsKey(candidate.getId())) {
                return candidate;
            }
        }
        return null;
    }

    private ConnectionEntry findDuplicateNodeConnection(String id, String nodeId) {
        String normalizedNodeId = normalizeNodeId(nodeId);
        if (normalizedNodeId == null) {
            return null;
        }
        synchronized (connectionLock) {
            return findDuplicateNodeConnectionLocked(id, normalizedNodeId);
        }
    }

    private ConnectionEntry findDuplicateNodeConnectionLocked(String id, String normalizedNodeId) {
        for (ConnectionEntry entry : entries) {
            if (entry.getId().equals(id) || !isSelectableConnection(entry)) {
                continue;
            }

            String entryNodeId = normalizeNodeId(firstText(
                    getOwnerNodeId(entry.getId()),
                    entry.getNodeId()));
            if (normalizedNodeId.equals(entryNodeId)) {
                return entry;
            }
        }
        return null;
    }

    private void selectConnectionIfNeededLocked() {
        if (selectedConnectionId != null && isSelectableConnectionIdLocked(selectedConnectionId)) {
            return;
        }
        ConnectionEntry fallback = firstSelectableConnectionLocked();
        selectedConnectionId = fallback != null ? fallback.getId() : null;
    }

    private ConnectionEntry firstSelectableConnectionLocked() {
        for (ConnectionEntry entry : entries) {
            if (isSelectableConnection(entry)) {
                return entry;
            }
        }
        return null;
    }

    private boolean isSelectableConnectionIdLocked(String id) {
        ConnectionEntry entry = findEntry(id);
        return entry != null && isSelectableConnection(entry);
    }

    private boolean isSelectableConnection(ConnectionEntry entry) {
        return entry != null
                && (entry.isConnected()
                || entry.isReconnecting()
                || activeConnections.containsKey(entry.getId())
                || pendingConnections.containsKey(entry.getId()));
    }

    private static String normalizeNodeId(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        String normalized = nodeId.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() || "?".equals(normalized) ? null : normalized;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"?".equals(value.trim())) {
                return value.trim();
            }
        }
        return null;
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
        BleDeviceDiscoveryService discovery = BleDeviceDiscoveryService.getInstance();
        return TransportConnectionFactory.create(
                entry,
                discovery::createConnectionPlatform,
                discovery.shouldDisposeConnectionPlatform());
    }

    /**
     * Создаёт protocol runtime поверх уже открытого transport-а.
     */
    private ProtocolType resolveProtocolType(String id, ConnectionEntry entry, TransportConnection conn) throws ConnectionException {
        ProtocolType requestedProtocol = entry.getEffectiveProtocol();
        validateProtocolTransportCombination(entry, requestedProtocol);
        configureFrameFormat(conn, requestedProtocol);
        return requestedProtocol;
    }

    private void validateProtocolTransportCombination(ConnectionEntry entry,
                                                      ProtocolType requestedProtocol) throws ConnectionException {
        switch (entry.getEffectiveType()) {
            case BLE -> {
                if (requestedProtocol == ProtocolType.MESHCORE_KISS) {
                    throw new ConnectionException("MeshCore KISS не поддерживается по BLE. Выберите MeshCore Companion.");
                }
            }
            case TCP, SERIAL -> {
                // MeshCore Companion can run on byte streams when the endpoint carries raw Companion packets.
            }
        }
    }

    /**
     * Создаёт protocol runtime поверх уже открытого transport-а.
     */
    private ProtocolRuntime<?> createProtocolRuntime(String id,
                                                     ConnectionEntry entry,
                                                     TransportConnection conn,
                                                     ProtocolType protocolType) {
        ProtocolRuntimeContext context = new ProtocolRuntimeContext(
                id,
                entry,
                conn,
                formatConnectionParams(entry)
        );
        return ProtocolRegistry.get(protocolType).createRuntime(context);
    }

    private void configureFrameFormat(TransportConnection conn, ProtocolType protocolType) {
        if (conn instanceof com.meshtastic.client.connection.FrameFormatAwareConnection frameAware) {
            frameAware.setFrameFormat(com.meshtastic.client.connection.FrameFormat.forProtocol(protocolType));
        }
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
