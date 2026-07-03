package com.meshtastic.client.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.meshtastic.client.connection.*;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.ProtocolType;
import com.meshtastic.client.lua.LuaScriptRuntimeService;
import com.meshtastic.client.protocol.ProtocolRegistry;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.ProtocolRuntimeContext;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionState;
import com.meshtastic.client.protocol.meshcore.MeshCoreKissState;
import com.meshtastic.client.protocol.meshtastic.MeshtasticProtocol;
import com.meshtastic.client.protocol.meshtastic.MeshtasticProtocolRuntime;
import com.meshtastic.client.system.AppUi;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton manager for transport connections and protocol runtime adapters.
 * <p>
 * Owns the lifecycle of TCP, Serial, and BLE connections: stores connection
 * profiles ({@link ConnectionEntry}) in {@code ~/.meshapp/connections.json},
 * creates and closes transports, selects the communication protocol, and starts
 * the matching runtime. For the existing UI, it still exposes Meshtastic
 * {@link DeviceState} and {@link ProtocolHandler}.
 * <p>
 * Each connection is identified by the string {@code id} from {@link ConnectionEntry}.
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
    private final AtomicBoolean startupAutoconnectStarted = new AtomicBoolean(false);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path configPath;
    private volatile String selectedConnectionId;

    private ConnectionManager() {
        String home = System.getProperty("user.home");
        configPath = Paths.get(home, ".meshapp", "connections.json");
        load();
    }

    /**
     * Returns the singleton connection manager.
     * On first call, loads profiles from {@code ~/.meshapp/connections.json}.
     *
     * @return {@code ConnectionManager} instance
     */
    public static synchronized ConnectionManager getInstance() {
        if (instance == null) {
            instance = new ConnectionManager();
        }
        return instance;
    }

    /**
     * Loads saved connection profiles from {@code ~/.meshapp/connections.json}.
     * <p>
     * Runtime fields such as {@code connected} and {@code reconnecting} are not
     * stored in the file and are set only while the application is running.
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
                if (normalizeLoadedEntries()) {
                    save();
                }
            }
        } catch (Exception e) {
            log.error("Failed to load connections from {}", configPath, e);
        }
    }

    private boolean normalizeLoadedEntries() {
        boolean changed = false;
        for (ConnectionEntry entry : entries) {
            if (entry.getEffectiveType() == ConnectionType.REMOTE_RPC) {
                if (entry.getType() != ConnectionType.REMOTE_RPC) {
                    entry.setType(ConnectionType.REMOTE_RPC);
                    changed = true;
                }
                if (entry.getProtocol() != ProtocolType.REMOTE_RPC) {
                    entry.setProtocol(ProtocolType.REMOTE_RPC);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Saves the current connection profile list to {@code ~/.meshapp/connections.json}.
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
     * Adds a new connection profile, saves it to JSON, and notifies listeners.
     *
     * @param entry connection profile
     */
    public void addEntry(ConnectionEntry entry) {
        entries.add(entry);
        save();
        fireChanged();
    }

    /**
     * Updates saved parameters of an existing connection profile.
     * Active and reconnecting profiles are left unchanged so transport parameters
     * are not modified underneath an already open runtime.
     *
     * @param updated profile with the same id and new parameters
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
                throw new IllegalStateException(I18n.t("connection.error.editActive"));
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
     * Deletes a connection profile. Disconnects it first if it is active, then
     * saves JSON and notifies listeners.
     *
     * @param id profile id
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
     * Opens a connection by profile id.
     * <p>
     * The method creates a transport (TCP, Serial, or BLE), opens it, selects a
     * protocol adapter from {@link ProtocolRegistry}, creates {@link ProtocolRuntime},
     * and starts the initial protocol synchronization. If the same id is already
     * active or currently connecting, the call is ignored.
     *
     * @param id connection profile id
     * @throws ConnectionException if the profile is missing or connection fails
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
                throw new ConnectionException(I18n.t("connection.error.entryNotFound", id));
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
                throw new ConnectionException(I18n.t("connection.error.createTransport", e.getMessage()), e);
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
                throw new ConnectionException(I18n.t("connection.error.noActiveTransport", entry.getName()));
            }
        } catch (ConnectionException e) {
            cleanupFailedConnect(id, conn);
            throw e;
        } catch (RuntimeException e) {
            cleanupFailedConnect(id, conn);
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
            String readyNodeId = nodeId != null && !nodeId.isBlank() && !"?".equals(nodeId)
                    ? nodeId
                    : entry.getNodeId();
            activeRuntime.onReady();
            if (activeConnections.get(id) != conn || !entry.isConnected()) {
                log.debug("Skipping Lua autostart for '{}' because transport is no longer active",
                        entry.getName());
                return;
            }
            LuaScriptRuntimeService.getInstance().autostartScriptsForNode(
                    readyNodeId,
                    event -> fireChanged());
            fireChanged();
        });
        fireChanged();
    }

    /**
     * Starts background connections for profiles with auto-connect enabled.
     * Idempotent for the current manager instance and intended to be called once
     * during application startup.
     */
    public void connectAutoconnectEntries() {
        if (!startupAutoconnectStarted.compareAndSet(false, true)) {
            return;
        }
        List<ConnectionEntry> targets = getEntries().stream()
                .filter(ConnectionEntry::isAutoconnect)
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        for (ConnectionEntry entry : targets) {
            Thread worker = new Thread(
                    () -> connectAutoconnectEntry(entry.getId()),
                    "autoconnect-" + entry.getId());
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void connectAutoconnectEntry(String id) {
        ConnectionEntry entry = findEntry(id);
        if (entry == null || !entry.isAutoconnect()) {
            return;
        }
        try {
            connect(id);
            AppUi.showStatus(AppUi.StatusType.SUCCESS, I18n.t("connection.autoconnect.connected", entry.getName()));
            handlePostAutoconnectReady(id, entry);
        } catch (ConnectionException e) {
            log.warn("Autoconnect failed for '{}': {}", entry.getName(), e.getMessage());
            AppUi.showStatus(AppUi.StatusType.ERROR,
                    I18n.t("connection.autoconnect.error", entry.getName(), e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Autoconnect failed for '{}'", entry.getName(), e);
            AppUi.showStatus(AppUi.StatusType.ERROR,
                    I18n.t("connection.autoconnect.error", entry.getName(), e.getMessage()));
        }
    }

    private void handlePostAutoconnectReady(String id, ConnectionEntry entry) {
        CompletableFuture<DeviceState> future = getConfigFuture(id);
        if (future == null) {
            return;
        }
        future.whenComplete((state, ex) -> {
            if (state == null || ex != null) {
                return;
            }
            NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
            if (myNode == null) {
                return;
            }
            String shortName = myNode.getShortName() != null ? myNode.getShortName() : "?";
            String longName = myNode.getLongName() != null ? myNode.getLongName() : "?";
            String nodeId = myNode.getNodeId() != null ? myNode.getNodeId() : "?";
            if (entry.isConnected() && Objects.equals(selectedConnectionId, id)) {
                AppUi.updateHeader(shortName, longName, nodeId);
            }
        });
    }

    /**
     * Disconnects and clears associated resources such as DeviceState,
     * ProtocolHandler, and config future.
     *
     * @param id connection profile id
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
     * Disconnects as an expected part of a device reboot/restart cycle.
     * <p>
     * Unlike {@link #disconnect(String)}, this is not treated as a user disconnect:
     * the transport is released, but auto-reconnect remains allowed. This is needed
     * after saving configuration, when the radio reboots itself and the BLE/TCP/Serial
     * session becomes stale.
     *
     * @param id connection profile id
     */
    public boolean disconnectForDeviceReboot(String id) {
        return disconnectForDeviceReboot(id, -1);
    }

    /**
     * Disconnects for reboot only if this is still the same transport session seen
     * by the caller. This protects against a late save handoff that could otherwise
     * disconnect a fresh connection after an independent reconnect.
     */
    public boolean disconnectForDeviceReboot(String id, long expectedConnectionGeneration) {
        return disconnectForDeviceReboot(id, expectedConnectionGeneration, null);
    }

    /**
     * Disconnects for reboot only if the signal came from the current runtime.
     * This protects a fresh reconnection from late {@code FromRadio.REBOOTED}
     * events emitted by an already closed protocol session.
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
		// Do not call cleanupConnection() here: the normal onDisconnected() /
		// onConnectionError() path must trigger auto-reconnect.
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
     * Marks the next connection drop as an expected device reboot.
     * Needed when firmware can close the transport by itself before the UI calls
     * {@link #disconnectForDeviceReboot(String)}.
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
     * Checks whether any connection is active or still connecting.
     *
     * @return {@code true} if the application is already busy with one transport connection
     */
    public boolean hasActiveConnection() {
        return !activeConnections.isEmpty() || !pendingConnections.isEmpty();
    }

    /**
     * Checks whether a specific connection has an active or pending transport.
     *
     * @param id profile id
     * @return {@code true} if this connection is already open or opening
     */
    public boolean isConnectionActiveOrPending(String id) {
        return activeConnections.containsKey(id) || pendingConnections.containsKey(id);
    }

    /**
     * Checks whether any BLE transport connection is open or opening.
     */
    public boolean hasActiveBleTransport() {
        synchronized (connectionLock) {
            return findActiveBleTransportLocked(null) != null;
        }
    }

    /**
     * Returns a copy of saved connection profiles.
     *
     * @return profile list safe for caller-side reads
     */
    public List<ConnectionEntry> getEntries() {
        return new ArrayList<>(entries);
    }

    /**
     * Returns UI-active connections: connected, opening, and auto-reconnecting.
     *
     * @return active profiles in saved-profile order
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
     * Returns the UI-selected connection, or the first available active profile.
     *
     * @return selected connection profile, or {@code null}
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
     * Returns the selected UI connection id.
     */
    public String getSelectedConnectionId() {
        ConnectionEntry entry = getSelectedConnectionEntry();
        return entry != null ? entry.getId() : null;
    }

    /**
     * Selects the active connection for all application forms.
     *
     * @param id connection profile id
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
     * Returns the UI-compatible {@link DeviceState} for an active connection.
     * <p>
     * For Meshtastic this is the native protobuf runtime state. For MeshCore
     * Companion Protocol this is a bridge state populated with MeshCore contacts,
     * channels, messages, and telemetry.
     *
     * @param id connection profile id
     * @return device state for the UI, or {@code null}
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
     * Returns the Meshtastic protocol handler for an active connection.
     *
     * @param id connection profile id
     * @return Meshtastic protocol handler, or {@code null}
     */
    public ProtocolHandler getProtocolHandler(String id) {
        MeshtasticProtocolRuntime runtime = getMeshtasticRuntime(id);
        return runtime != null ? runtime.getProtocolHandler() : protocolHandlers.get(id);
    }

    /**
     * Returns the service that processes incoming Meshtastic messages.
     *
     * @param id connection profile id
     * @return incoming message service, or {@code null}
     */
    public MessageListenerService getMessageListenerService(String id) {
        MeshtasticProtocolRuntime runtime = getMeshtasticRuntime(id);
        return runtime != null ? runtime.getMessageListenerService() : messageListenerServices.get(id);
    }

    /**
     * Returns the future for Meshtastic config exchange completion.
     *
     * @param id connection profile id
     * @return future with populated {@link DeviceState}, or {@code null}
     */
    public CompletableFuture<DeviceState> getConfigFuture(String id) {
        MeshtasticProtocolRuntime runtime = getMeshtasticRuntime(id);
        return runtime != null ? runtime.getReadyFuture() : configFutures.get(id);
    }

    /**
     * Returns the device nodeId for the given connection.
     * First tries the active protocol runtime, then the compatible Meshtastic
     * cache, and finally the saved {@link ConnectionEntry}.
     *
     * @param id connection profile id
     * @return owner nodeId, or {@code null} when unknown
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
     * Returns the active protocol runtime of any supported type.
     *
     * @param id connection profile id
     * @return active protocol runtime, or {@code null}
     */
    public ProtocolRuntime<?> getProtocolRuntime(String id) {
        return protocolRuntimes.get(id);
    }

    /**
     * Returns the readiness future for the active protocol runtime.
     *
     * @param id connection profile id
     * @return readiness future, or {@code null}
     */
    public CompletableFuture<?> getProtocolReadyFuture(String id) {
        return protocolReadyFutures.get(id);
    }

    /**
     * Returns MeshCore KISS runtime state when the connection uses that protocol.
     *
     * @param id connection profile id
     * @return MeshCore KISS state, or {@code null}
     */
    public MeshCoreKissState getMeshCoreKissState(String id) {
        ProtocolRuntime<?> runtime = protocolRuntimes.get(id);
        return runtime != null && runtime.getState() instanceof MeshCoreKissState meshCoreState
                ? meshCoreState
                : null;
    }

    /**
     * Returns MeshCore Companion runtime state when the connection uses Companion Protocol.
     *
     * @param id connection profile id
     * @return MeshCore Companion state, or {@code null}
     */
    public MeshCoreCompanionState getMeshCoreCompanionState(String id) {
        ProtocolRuntime<?> runtime = protocolRuntimes.get(id);
        return runtime != null && runtime.getState() instanceof MeshCoreCompanionState meshCoreState
                ? meshCoreState
                : null;
    }

    /**
     * Returns the actually active protocol, or the protocol saved in the profile.
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
     * Adds a listener for profile-list or runtime-state changes.
     *
     * @param listener callback invoked after state changes
     */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Removes a previously registered change listener.
     *
     * @param listener callback to remove
     */
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * Disconnects all active connections and releases resources such as tray icon
     * and BLE polling. Called during application shutdown.
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
     * Removes a transport from the pending map after connection failure or cancellation.
     */
    private void abortPendingConnection(String id, TransportConnection conn) {
        synchronized (connectionLock) {
            pendingConnections.remove(id, conn);
        }
        clearUserDisconnectStateIfIdle(id);
    }

    /**
     * Closes a transport that failed to complete connect().
     * This is important for BLE: pairing/connect failures can leave a native
     * backend, BlueZ agent, or worker thread alive unless a normal disconnect runs.
     */
    private void cleanupFailedConnect(String id, TransportConnection conn) {
        abortPendingConnection(id, conn);
        conn.setConnectionListener(null);
        try {
            conn.disconnect();
        } catch (RuntimeException cleanupError) {
            log.warn("Failed to cleanup transport after unsuccessful connect", cleanupError);
        }
    }

    /**
     * Closes a transport that managed to connect after its profile was removed or cancelled.
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
     * Clears user-disconnect flags once an id no longer has a runtime.
     */
    private void clearUserDisconnectStateIfIdle(String id) {
        synchronized (connectionLock) {
            if (!activeConnections.containsKey(id) && !pendingConnections.containsKey(id)) {
                clearUserDisconnectState(id);
            }
        }
    }

    /**
     * Removes a transport from runtime maps after a disconnect/error callback.
     *
     * @return {@code true} if the callback belongs to the current active/pending transport
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

        throw new ConnectionException(I18n.t("connection.error.duplicateNode",
                entry.getNodeId(),
                duplicateEntry.getName(),
                duplicateEntry.getEffectiveType()));
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
        throw new ConnectionException(I18n.t("connection.error.parallelBleUnsupported",
                activeBle.getName(),
                entry.getName()));
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
     * Releases the protocol runtime and compatible Meshtastic caches.
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
     * Delegates transport parameter formatting to the shared transport factory.
     */
    private static String formatConnectionParams(ConnectionEntry entry) {
        return TransportConnectionFactory.describe(entry);
    }

    /**
     * Creates a transport from the connection profile without starting protocol logic.
     */
    private TransportConnection createConnection(ConnectionEntry entry) {
        BleDeviceDiscoveryService discovery = BleDeviceDiscoveryService.getInstance();
        return TransportConnectionFactory.create(
                entry,
                discovery::createConnectionPlatform,
                discovery.shouldDisposeConnectionPlatform());
    }

    /**
     * Creates a protocol runtime on top of an already open transport.
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
                    throw new ConnectionException(I18n.t("connection.error.meshcoreKissBleUnsupported"));
                }
            }
            case TCP, SERIAL -> {
                // MeshCore Companion can run on byte streams when the endpoint carries raw Companion packets.
            }
            case REMOTE_RPC -> {
                if (requestedProtocol != ProtocolType.REMOTE_RPC) {
                    throw new ConnectionException(I18n.t("connection.error.remoteRpcProtocolRequired"));
                }
            }
        }
    }

    /**
     * Creates a protocol runtime on top of an already open transport.
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
     * Returns the Meshtastic runtime if the given connection uses Meshtastic.
     */
    private MeshtasticProtocolRuntime getMeshtasticRuntime(String id) {
        ProtocolRuntime<?> runtime = protocolRuntimes.get(id);
        return runtime instanceof MeshtasticProtocolRuntime meshtasticRuntime ? meshtasticRuntime : null;
    }

    /**
     * Populates the legacy Meshtastic maps still used by forms and tests.
     * <p>
     * These maps are a compatibility layer while the UI is gradually moved to
     * protocol runtime abstractions.
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
     * Returns {@code true} only for transports that genuinely need heartbeat.
     * In the current protocol, heartbeat is needed for TCP and Serial, but not BLE.
     */
    static boolean shouldStartHeartbeat(ConnectionEntry entry) {
        return MeshtasticProtocol.shouldStartHeartbeat(entry);
    }

    /**
     * Finds a saved connection profile by id.
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
     * Notifies UI and services that the connection list or runtime state has changed.
     */
    void fireChanged() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
