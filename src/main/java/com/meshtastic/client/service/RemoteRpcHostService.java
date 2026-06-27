package com.meshtastic.client.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.meshtastic.client.MeshApp;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MessageChangeEvent;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.ProtocolRuntime;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime;
import com.meshtastic.client.protocol.rpc.RemoteAdminRpcJson;
import com.meshtastic.client.protocol.rpc.RemoteNodeJson;
import com.meshtastic.client.protocol.rpc.RemoteTelemetryJson;
import com.meshtastic.client.rpc.DirectRpcServer;
import com.meshtastic.client.rpc.RpcAccessKey;
import com.meshtastic.client.rpc.RpcMethodRegistry;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.NodeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;

import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.AdminProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;

/**
 * Owns the optional direct RPC server running inside MeshApp Host.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RemoteRpcHostService {

    private static final Logger log = LoggerFactory.getLogger(RemoteRpcHostService.class);
    private static final int TRACEROUTE_TIMEOUT_SECONDS = 360;

    private static RemoteRpcHostService instance;

    private final Object lock = new Object();
    private final ConcurrentMap<String, PendingTraceroute> pendingTraceroutes = new ConcurrentHashMap<>();
    private volatile DirectRpcServer server;
    private volatile String lastError;

    private RemoteRpcHostService() {
    }

    public static synchronized RemoteRpcHostService getInstance() {
        if (instance == null) {
            instance = new RemoteRpcHostService();
        }
        return instance;
    }

    /**
     * Starts or stops the server according to saved preferences.
     */
    public void applyPreferences() {
        if (!AppPreferences.isRemoteRpcServerEnabled()) {
            stop();
            return;
        }
        start(
                AppPreferences.getRemoteRpcServerBindAddress(),
                AppPreferences.getRemoteRpcServerPort(),
                AppPreferences.getRemoteRpcAccessKey()
        );
    }

    /**
     * Starts the direct RPC server.
     *
     * @param bindAddress bind address, for example {@code 127.0.0.1} or {@code 0.0.0.0}
     * @param port TCP port
     * @param accessKey access key generated on the host
     */
    public void start(String bindAddress, int port, String accessKey) {
        synchronized (lock) {
            stopLocked();
            lastError = null;
            try {
                RpcAccessKey parsedKey = RpcAccessKey.parse(accessKey);
                server = DirectRpcServer.start(
                        InetAddress.getByName(bindAddress),
                        port,
                        parsedKey,
                        createRegistry(),
                        ForkJoinPool.commonPool());
                log.info("Remote RPC host server started on {}:{}", bindAddress, server.getPort());
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("Failed to start Remote RPC host server", e);
            }
        }
    }

    /**
     * Stops the direct RPC server.
     */
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    /**
     * @return whether the direct server is currently running
     */
    public boolean isRunning() {
        DirectRpcServer current = server;
        return current != null;
    }

    /**
     * @return actual local port, or {@code 0} when stopped
     */
    public int getPort() {
        DirectRpcServer current = server;
        return current != null ? current.getPort() : 0;
    }

    /**
     * @return last startup error, if any
     */
    public String getLastError() {
        return lastError;
    }

    private void stopLocked() {
        DirectRpcServer current = server;
        server = null;
        clearPendingTraceroutes();
        if (current != null) {
            current.close();
            log.info("Remote RPC host server stopped");
        }
    }

    private RpcMethodRegistry createRegistry() {
        return new RpcMethodRegistry()
                .register("system.ping", (params, context) -> {
                    JsonObject result = new JsonObject();
                    result.addProperty("app", "MeshApp");
                    result.addProperty("version", MeshApp.APPLICATION_VERSION);
                    result.addProperty("versionCode", MeshApp.VERSION_CODE);
                    result.addProperty("remoteRpc", true);
                    result.addProperty("activeConnections",
                            ConnectionManager.getInstance().getActiveConnectionEntries().size());
                    return CompletableFuture.completedFuture(result);
                })
                .register("connection.list", (params, context) ->
                        CompletableFuture.completedFuture(connectionList()))
                .register("connection.connect", (params, context) -> {
                    String id = requiredId(params);
                    ConnectionManager.getInstance().connect(id);
                    ConnectionManager.getInstance().setSelectedConnectionId(id);
                    return CompletableFuture.completedFuture(connectionActionResult(id));
                })
                .register("connection.disconnect", (params, context) -> {
                    String id = requiredId(params);
                    ConnectionManager.getInstance().disconnect(id);
                    return CompletableFuture.completedFuture(connectionActionResult(id));
                })
                .register("connection.select", (params, context) -> {
                    String id = requiredId(params);
                    ConnectionManager.getInstance().setSelectedConnectionId(id);
                    return CompletableFuture.completedFuture(connectionActionResult(id));
                })
                .register("chat.list", (params, context) ->
                        CompletableFuture.completedFuture(chatList()))
                .register("chat.messages", (params, context) ->
                        CompletableFuture.completedFuture(chatMessages(params)))
                .register("chat.markRead", (params, context) ->
                        CompletableFuture.completedFuture(chatMarkRead(params)))
                .register("chat.send", (params, context) ->
                        CompletableFuture.completedFuture(chatSend(params)))
                .register("chat.react", (params, context) ->
                        CompletableFuture.completedFuture(chatReact(params)))
                .register("node.list", (params, context) ->
                        CompletableFuture.completedFuture(nodeList(params)))
                .register("node.get", (params, context) ->
                        CompletableFuture.completedFuture(nodeGet(params)))
                .register("node.traceroute", (params, context) ->
                        CompletableFuture.completedFuture(nodeTraceroute(params)))
                .register("node.refresh", (params, context) ->
                        CompletableFuture.completedFuture(nodeRefresh(params)))
                .register("node.delete", (params, context) ->
                        CompletableFuture.completedFuture(nodeDelete(params)))
                .register("node.favorite", (params, context) ->
                        CompletableFuture.completedFuture(nodeFavorite(params)))
                .register("node.ignored", (params, context) ->
                        CompletableFuture.completedFuture(nodeIgnored(params)))
                .register("telemetry.dashboard", (params, context) ->
                        CompletableFuture.completedFuture(telemetryDashboard(params)))
                .register("admin.load", (params, context) ->
                        remoteAdminLoad(params))
                .register("admin.requestConfig", (params, context) ->
                        remoteAdminRequestConfig(params))
                .register("admin.requestModuleConfig", (params, context) ->
                        remoteAdminRequestModuleConfig(params))
                .register("admin.saveOwner", (params, context) ->
                        remoteAdminSaveOwner(params))
                .register("admin.saveConfigChanges", (params, context) ->
                        remoteAdminSaveConfigChanges(params))
                .register("admin.setFixedPosition", (params, context) ->
                        remoteAdminSetFixedPosition(params))
                .register("admin.removeFixedPosition", (params, context) ->
                        remoteAdminRemoveFixedPosition(params))
                .register("admin.setRingtone", (params, context) ->
                        remoteAdminSetRingtone(params))
                .register("admin.setCannedMessages", (params, context) ->
                        remoteAdminSetCannedMessages(params))
                .register("admin.command", (params, context) ->
                        remoteAdminCommand(params))
                .register("admin.refreshConnectionStatus", (params, context) ->
                        remoteAdminRefreshConnectionStatus(params));
    }

    /**
     * Publishes a host-side incoming-message event to the active remote client.
     */
    public void publishIncomingMessage(MeshMessage message, String chatType, String chatKey) {
        DirectRpcServer current = server;
        if (current == null || message == null) {
            return;
        }
        JsonObject event = new JsonObject();
        event.addProperty("chatType", firstText(chatType, ""));
        event.addProperty("chatKey", firstText(chatKey, ""));
        DeviceState state = activeHostStateOrNull();
        String ownerId = state != null ? state.getOwnerNodeId() : "";
        prepareMessagesForRemote(state, chatType, chatKey, ownerId, List.of(message));
        event.add("message", messageToJson(message, state));
        event.addProperty("title", firstText(resolveSenderName(state, message), message.getFromNodeId()));
        event.addProperty("body", firstText(message.getText(), ""));
        current.publishEvent("message.incoming", event);
    }

    /**
     * Publishes a non-notification chat update, such as reaction or metadata changes.
     */
    public void publishChatChanged(String chatType, String chatKey, int targetPacketId) {
        DirectRpcServer current = server;
        if (current == null) {
            return;
        }
        JsonObject event = new JsonObject();
        event.addProperty("chatType", firstText(chatType, ""));
        event.addProperty("chatKey", firstText(chatKey, ""));
        event.addProperty("targetPacketId", targetPacketId);
        DeviceState state = activeHostStateOrNull();
        String ownerId = state != null ? state.getOwnerNodeId() : "";
        MeshMessage message = MessageDbService.getInstance()
                .findByPacketId(targetPacketId, chatType, chatKey, ownerId);
        if (message != null) {
            prepareMessagesForRemote(state, chatType, chatKey, ownerId, List.of(message));
            event.add("message", messageToJson(message, state));
        }
        current.publishEvent("message.changed", event);
    }

    /**
     * Publishes an outgoing-message delivery status update to the active remote client.
     */
    public void publishMessageStatusChanged(MessageChangeEvent change) {
        DirectRpcServer current = server;
        if (current == null
                || change == null
                || change.kind() != MessageChangeEvent.Kind.STATUS_CHANGED
                || change.message() == null) {
            return;
        }
        MeshMessage message = change.message();
        JsonObject event = new JsonObject();
        event.addProperty("chatType", firstText(change.chatType(), ""));
        event.addProperty("chatKey", firstText(change.chatKey(), ""));
        event.addProperty("packetId", message.getPacketId());
        event.addProperty("status", message.getStatus() != null ? message.getStatus().name() : null);
        event.addProperty("errorReason", message.getErrorReason());
        DeviceState state = activeHostStateOrNull();
        prepareMessagesForRemote(
                state,
                change.chatType(),
                change.chatKey(),
                firstText(change.ownerNodeId(), state != null ? state.getOwnerNodeId() : ""),
                List.of(message));
        event.add("message", messageToJson(message, state));
        current.publishEvent("message.status", event);
    }

    private JsonObject connectionList() {
        ConnectionManager manager = ConnectionManager.getInstance();
        JsonArray items = new JsonArray();
        for (ConnectionEntry entry : manager.getEntries()) {
            items.add(connectionToJson(entry, manager));
        }
        JsonObject result = new JsonObject();
        result.addProperty("selectedConnectionId", manager.getSelectedConnectionId());
        result.add("items", items);
        return result;
    }

    private JsonObject connectionActionResult(String id) {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = manager.findEntry(id);
        JsonObject result = connectionList();
        if (entry != null) {
            result.add("connection", connectionToJson(entry, manager));
        }
        return result;
    }

    private static JsonObject connectionToJson(ConnectionEntry entry, ConnectionManager manager) {
        JsonObject item = new JsonObject();
        String selectedConnectionId = manager.getSelectedConnectionId();
        item.addProperty("id", entry.getId());
        item.addProperty("name", entry.getName());
        item.addProperty("type", entry.getEffectiveType().name());
        var protocolType = manager.getActiveProtocolType(entry.getId());
        item.addProperty("protocol", protocolType != null ? protocolType.name() : entry.getEffectiveProtocol().name());
        item.addProperty("connected", entry.isConnected());
        item.addProperty("reconnecting", entry.isReconnecting());
        item.addProperty("selected", entry.getId() != null && entry.getId().equals(selectedConnectionId));
        item.addProperty("nodeId", entry.getNodeId());
        item.addProperty("address", connectionAddress(entry));
        return item;
    }

    private static String connectionAddress(ConnectionEntry entry) {
        return switch (entry.getEffectiveType()) {
            case BLE -> firstText(entry.getBleDeviceName(), "BLE") + " (" + firstText(entry.getBleAddress(), "?") + ")";
            case SERIAL -> firstText(entry.getPortName(), "?") + " @" + entry.getBaudRate();
            case TCP -> firstText(entry.getHost(), "?") + ":" + entry.getPort();
            case REMOTE_RPC -> firstText(entry.getHost(), "?") + ":" + entry.getPort();
        };
    }

    private static String requiredId(JsonObject params) {
        if (params == null || !params.has("id") || params.get("id").isJsonNull()) {
            throw new IllegalArgumentException("connection id is required");
        }
        String id = params.get("id").getAsString();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("connection id is required");
        }
        return id.trim();
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private JsonObject chatList() {
        ActiveHostConnection active = activeHostConnection();
        DeviceState state = active.state();
        String ownerId = state.getOwnerNodeId();
        MessageDbService db = MessageDbService.getInstance();
        Map<String, MeshMessage> channelLastMessages = db.getLastMessagePerChat("channel", ownerId);
        Map<String, MeshMessage> dmLastMessages = db.getLastMessagePerChat("dm", ownerId);
        Map<String, Integer> readCounts = db.loadAllReadCounts(ownerId);

        JsonArray items = new JsonArray();
        for (ChannelProtos.Channel channel : state.getChannels()) {
            if (channel.getRole() == ChannelProtos.Channel.Role.DISABLED) {
                continue;
            }
            String chatKey = String.valueOf(channel.getIndex());
            ChatItem item = ChatItem.fromChannel(
                    channel,
                    channelLastMessages.get(chatKey),
                    unreadCount(db, readCounts, "channel", chatKey, ownerId),
                    false);
            items.add(chatItemToJson(item));
        }

        Set<String> dmPeers = new LinkedHashSet<>(db.getDistinctDmPeers(ownerId));
        dmPeers.addAll(state.getAllDirectMessages().keySet());
        for (String peerNodeId : dmPeers) {
            ChatItem item = ChatItem.fromDirectMessage(
                    peerNodeId,
                    resolvePeerNode(state, peerNodeId),
                    dmLastMessages.get(peerNodeId),
                    unreadCount(db, readCounts, "dm", peerNodeId, ownerId),
                    false);
            items.add(chatItemToJson(item));
        }

        JsonObject result = new JsonObject();
        result.addProperty("ownerNodeId", ownerId);
        result.addProperty("connectionId", active.entry().getId());
        result.add("items", items);
        return result;
    }

    private JsonObject chatMarkRead(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        String chatType = requiredText(params, "chatType");
        String chatKey = requiredText(params, "chatKey");
        String ownerId = active.state().getOwnerNodeId();
        MessageDbService db = MessageDbService.getInstance();
        int count = db.getUnreadEligibleMessageCount(chatType, chatKey, ownerId);
        db.saveReadCount(chatType, chatKey, count, ownerId);
        return chatList();
    }

    private JsonObject chatMessages(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        String chatType = requiredText(params, "chatType");
        String chatKey = requiredText(params, "chatKey");
        int limit = boundedInt(params, "limit", 1, 200, 50);
        long beforeDbId = longField(params, "beforeDbId");
        long afterDbId = longField(params, "afterDbId");
        String ownerId = active.state().getOwnerNodeId();
        MessageDbService db = MessageDbService.getInstance();

        List<MeshMessage> messages;
        if (beforeDbId > 0) {
            messages = db.loadBefore(chatType, chatKey, beforeDbId, limit, ownerId);
        } else if (afterDbId > 0) {
            messages = db.loadAfter(chatType, chatKey, afterDbId, limit, ownerId);
        } else {
            messages = db.loadLast(chatType, chatKey, limit, ownerId);
        }

        JsonArray items = new JsonArray();
        prepareMessagesForRemote(active.state(), chatType, chatKey, ownerId, messages);
        for (MeshMessage message : messages) {
            items.add(messageToJson(message, active.state()));
        }
        JsonObject result = new JsonObject();
        result.addProperty("ownerNodeId", ownerId);
        result.add("items", items);
        return result;
    }

    private JsonObject chatSend(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        String chatType = requiredText(params, "chatType");
        String chatKey = requiredText(params, "chatKey");
        String text = requiredText(params, "text");
        int replyId = boundedInt(params, "replyId", Integer.MIN_VALUE, Integer.MAX_VALUE, 0);

        MeshMessage sent;
        if (active.meshCoreRuntime() != null) {
            sent = "channel".equals(chatType)
                    ? active.meshCoreRuntime().sendChannelMessage(Integer.parseInt(chatKey), text, replyId)
                    : active.meshCoreRuntime().sendDirectMessage(chatKey, text, replyId);
        } else {
            ProtocolHandler handler = active.handler();
            if (handler == null) {
                throw new IllegalStateException("selected host connection cannot send messages");
            }
            sent = "channel".equals(chatType)
                    ? MessageService.sendChannelMessage(handler, active.state(), Integer.parseInt(chatKey), text, replyId)
                    : MessageService.sendDirectMessage(handler, active.state(), chatKey, text, replyId);
        }
        if (sent == null) {
            throw new IllegalStateException("message was not sent");
        }
        prepareMessagesForRemote(active.state(), chatType, chatKey, active.state().getOwnerNodeId(), List.of(sent));
        JsonObject result = new JsonObject();
        result.add("message", messageToJson(sent, active.state()));
        result.add("chat", chatList());
        return result;
    }

    private JsonObject chatReact(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        String chatType = requiredText(params, "chatType");
        String chatKey = requiredText(params, "chatKey");
        int targetPacketId = boundedInt(params, "targetPacketId", Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
        String emoji = requiredText(params, "emoji");
        if (targetPacketId == 0) {
            throw new IllegalArgumentException("targetPacketId is required");
        }
        if (active.meshCoreRuntime() != null) {
            throw new IllegalStateException("selected host connection cannot send reactions");
        }

        String ownerId = active.state().getOwnerNodeId();
        MeshMessage target = MessageDbService.getInstance()
                .findByPacketId(targetPacketId, chatType, chatKey, ownerId);
        if (target == null) {
            throw new IllegalArgumentException("reaction target message was not found");
        }

        ProtocolHandler handler = active.handler();
        if (handler == null) {
            throw new IllegalStateException("selected host connection cannot send reactions");
        }

        boolean sent = "channel".equals(chatType)
                ? MessageService.sendChannelReaction(handler, active.state(), Integer.parseInt(chatKey), target, emoji)
                : MessageService.sendDirectReaction(handler, active.state(), chatKey, target, emoji);
        if (!sent) {
            throw new IllegalStateException("reaction was not sent");
        }

        MeshMessage updated = MessageDbService.getInstance()
                .findByPacketId(targetPacketId, chatType, chatKey, ownerId);
        MeshMessage resultMessage = updated != null ? updated : target;
        prepareMessagesForRemote(active.state(), chatType, chatKey, ownerId, List.of(resultMessage));
        publishChatChanged(chatType, chatKey, targetPacketId);

        JsonObject result = new JsonObject();
        result.add("message", messageToJson(resultMessage, active.state()));
        result.add("chat", chatList());
        return result;
    }

    private JsonObject nodeList(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        DeviceState state = active.state();
        String ownerId = state.getOwnerNodeId();
        boolean includeFavorites = booleanField(params, "includeFavorites");
        boolean includeIgnored = booleanField(params, "includeIgnored");

        JsonArray items = new JsonArray();
        Set<String> addedNodeIds = new LinkedHashSet<>();
        for (NodeData node : state.getNodeDb().values()) {
            addNodeJson(items, addedNodeIds, node, ownerId, true);
        }
        if (includeFavorites) {
            for (NodeData node : NodeCacheService.getInstance().loadFavoriteNodes(ownerId)) {
                addNodeJson(items, addedNodeIds, node, ownerId, false);
            }
        }
        if (includeIgnored) {
            for (NodeData node : NodeCacheService.getInstance().loadIgnoredNodes(ownerId)) {
                addNodeJson(items, addedNodeIds, node, ownerId, false);
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("ownerNodeId", ownerId);
        result.add("items", items);
        return result;
    }

    private JsonObject nodeGet(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        String nodeId = requiredText(params, "nodeId");
        String ownerId = active.state().getOwnerNodeId();
        NodeData node = resolvePeerNode(active.state(), nodeId);
        if (node == null) {
            throw new IllegalArgumentException("node was not found: " + nodeId);
        }

        JsonArray items = new JsonArray();
        addNodeJson(items, new LinkedHashSet<>(), node, ownerId, false);

        JsonObject result = new JsonObject();
        result.addProperty("ownerNodeId", ownerId);
        result.add("items", items);
        return result;
    }

    private JsonObject nodeTraceroute(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        ProtocolHandler handler = active.handler();
        if (handler == null) {
            throw new IllegalStateException("selected host connection cannot send traceroute");
        }

        DeviceState state = active.state();
        String requestedNodeId = textField(params, "nodeId");
        int requestedNodeNum = boundedInt(params, "nodeNum", Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
        NodeData node = !requestedNodeId.isBlank() ? resolvePeerNode(state, requestedNodeId) : null;
        if (node == null && requestedNodeNum != 0) {
            node = state.getNodeDb().get(requestedNodeNum);
            if (node != null) {
                NodeCacheService.getInstance().enrichFromCache(node);
            }
        }
        int targetNodeNum = node != null ? node.getNodeNum() : requestedNodeNum;
        if (targetNodeNum == 0) {
            throw new IllegalArgumentException("nodeNum is required");
        }

        String targetNodeId = node != null && node.getNodeId() != null && !node.getNodeId().isBlank()
                ? node.getNodeId()
                : nodeIdFromNum(targetNodeNum);
        String targetName = node != null ? nodeTitle(node) : targetNodeId;
        String requestId = firstText(textField(params, "requestId"), UUID.randomUUID().toString());

        BiConsumer<Integer, MeshProtos.RouteDiscovery> listener =
                (fromNodeNum, route) -> publishRemoteTracerouteResult(
                        requestId,
                        state,
                        targetNodeNum,
                        targetNodeId,
                        targetName,
                        fromNodeNum,
                        route);
        cleanupPendingTraceroute(requestId);
        pendingTraceroutes.put(requestId, new PendingTraceroute(state, listener));
        state.addTracerouteListener(listener);
        schedulePendingTracerouteCleanup(requestId);

        try {
            MessageService.requestTraceroute(handler, state, targetNodeNum);
        } catch (Throwable error) {
            cleanupPendingTraceroute(requestId);
            throw error;
        }

        JsonObject result = new JsonObject();
        result.addProperty("requestId", requestId);
        result.addProperty("targetNodeNum", targetNodeNum);
        result.addProperty("targetNodeId", targetNodeId);
        result.addProperty("targetName", targetName);
        result.add("nodeNames", nodeNamesJson(state, targetNodeNum, 0, MeshProtos.RouteDiscovery.newBuilder().build()));
        return result;
    }

    private void publishRemoteTracerouteResult(String requestId,
                                               DeviceState state,
                                               int targetNodeNum,
                                               String targetNodeId,
                                               String targetName,
                                               int fromNodeNum,
                                               MeshProtos.RouteDiscovery route) {
        if (!matchesPendingTracerouteResponse(targetNodeNum, fromNodeNum)) {
            return;
        }
        PendingTraceroute pending = pendingTraceroutes.remove(requestId);
        if (pending == null) {
            return;
        }
        pending.state().removeTracerouteListener(pending.listener());

        MeshProtos.RouteDiscovery safeRoute = route != null
                ? route
                : MeshProtos.RouteDiscovery.newBuilder().build();
        long timestamp = System.currentTimeMillis() / 1000;
        JsonObject nodeNames = nodeNamesJson(state, targetNodeNum, fromNodeNum, safeRoute);
        String formattedText = formatTracerouteText(
                targetName,
                safeRoute,
                nodeNum -> nodeNameFromJson(nodeNames, nodeNum));
        MessageDbService.getInstance().saveTracerouteResult(
                state.getOwnerNodeId() != null ? state.getOwnerNodeId() : "",
                "",
                "",
                "rpc.node.traceroute",
                requestId,
                0,
                Integer.toUnsignedLong(targetNodeNum),
                targetNodeId,
                targetName,
                fromNodeNum != 0 ? Integer.toUnsignedLong(fromNodeNum) : 0,
                fromNodeNum != 0 ? nodeIdFromNum(fromNodeNum) : null,
                safeRoute.toByteArray(),
                formattedText,
                timestamp);

        JsonObject event = new JsonObject();
        event.addProperty("requestId", requestId);
        event.addProperty("status", "ok");
        event.addProperty("targetNodeNum", targetNodeNum);
        event.addProperty("targetNodeId", targetNodeId);
        event.addProperty("targetName", targetName);
        event.addProperty("responseFromNodeNum", fromNodeNum);
        event.addProperty("responseFromNodeId", fromNodeNum != 0 ? nodeIdFromNum(fromNodeNum) : "");
        event.addProperty("routeData", Base64.getEncoder().encodeToString(safeRoute.toByteArray()));
        event.addProperty("formattedText", formattedText);
        event.addProperty("timestamp", timestamp);
        event.add("nodeNames", nodeNames);

        DirectRpcServer current = server;
        if (current != null) {
            current.publishEvent("node.traceroute.result", event);
        }
    }

    private boolean matchesPendingTracerouteResponse(int targetNodeNum, int fromNodeNum) {
        return fromNodeNum == targetNodeNum || pendingTraceroutes.size() == 1;
    }

    private void schedulePendingTracerouteCleanup(String requestId) {
        CompletableFuture.delayedExecutor(TRACEROUTE_TIMEOUT_SECONDS + 5L, TimeUnit.SECONDS)
                .execute(() -> cleanupPendingTraceroute(requestId));
    }

    private void cleanupPendingTraceroute(String requestId) {
        PendingTraceroute pending = pendingTraceroutes.remove(requestId);
        if (pending != null) {
            pending.state().removeTracerouteListener(pending.listener());
        }
    }

    private void clearPendingTraceroutes() {
        pendingTraceroutes.forEach((requestId, pending) ->
                pending.state().removeTracerouteListener(pending.listener()));
        pendingTraceroutes.clear();
    }

    private JsonObject nodeRefresh(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        ProtocolHandler handler = active.handler();
        if (handler == null) {
            throw new IllegalStateException("selected host connection cannot refresh node info");
        }
        int nodeNum = boundedInt(params, "nodeNum", Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
        if (nodeNum == 0) {
            throw new IllegalArgumentException("nodeNum is required");
        }
        MessageService.exchangeNodeUserInfo(handler, active.state(), nodeNum);
        return nodeList(params);
    }

    private JsonObject nodeDelete(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        String nodeId = requiredText(params, "nodeId");
        int nodeNum = boundedInt(params, "nodeNum", Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
        if (nodeNum != 0) {
            active.state().removeNode(nodeNum);
        }
        NodeCacheService.getInstance().deleteNode(nodeId, active.state().getOwnerNodeId());
        return nodeList(params);
    }

    private JsonObject nodeFavorite(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        String nodeId = requiredText(params, "nodeId");
        boolean enabled = booleanField(params, "enabled");
        String ownerId = active.state().getOwnerNodeId();
        if (enabled) {
            FavoriteNodeService.getInstance().addFavorite(nodeId, ownerId);
        } else {
            FavoriteNodeService.getInstance().removeFavorite(nodeId, ownerId);
        }
        return nodeList(params);
    }

    private JsonObject nodeIgnored(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        String nodeId = requiredText(params, "nodeId");
        boolean enabled = booleanField(params, "enabled");
        String ownerId = active.state().getOwnerNodeId();
        if (enabled) {
            IgnoredNodeService.getInstance().addIgnored(nodeId, ownerId);
        } else {
            IgnoredNodeService.getInstance().removeIgnored(nodeId, ownerId);
        }
        return nodeList(params);
    }

    private JsonObject telemetryDashboard(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        DeviceState state = active.state();
        String ownerId = state.getOwnerNodeId();
        String nodeId = textField(params, "nodeId");
        if (nodeId.isBlank()) {
            nodeId = localNodeId(state);
        }
        if (nodeId.isBlank()) {
            throw new IllegalStateException("active host connection has no local node id yet");
        }

        long now = System.currentTimeMillis() / 1000;
        long sinceEpoch = Math.max(0, longField(params, "sinceEpoch"));
        long maxFutureTs = longField(params, "maxFutureTs");
        if (maxFutureTs <= 0) {
            maxFutureTs = now + 300;
        }

        List<TelemetryEntry> entries = NodeCacheService.getInstance()
                .loadTelemetryForNode(nodeId, sinceEpoch, maxFutureTs, ownerId);
        List<TelemetryEntry> qualityEntries = NodeCacheService.getInstance()
                .loadTelemetryQuality(sinceEpoch, maxFutureTs, ownerId);
        return RemoteTelemetryJson.dashboardResult(ownerId, nodeId, entries, qualityEntries);
    }

    private CompletableFuture<JsonElement> remoteAdminLoad(JsonObject params) {
        RemoteAdminService service = createRemoteAdminService(params);
        return service.loadSnapshot()
                .thenApply(session -> (JsonElement) RemoteAdminRpcJson.sessionToJson(session))
                .whenComplete((ignored, error) -> service.close());
    }

    private CompletableFuture<JsonElement> remoteAdminRequestConfig(JsonObject params) {
        AdminProtos.AdminMessage.ConfigType type = configType(params);
        RemoteAdminService service = createRemoteAdminService(params);
        return service.requestConfigSection(type)
                .thenApply(ignored -> (JsonElement) RemoteAdminRpcJson.sessionToJson(service.session()))
                .whenComplete((ignored, error) -> service.close());
    }

    private CompletableFuture<JsonElement> remoteAdminRequestModuleConfig(JsonObject params) {
        AdminProtos.AdminMessage.ModuleConfigType type = moduleConfigType(params);
        RemoteAdminService service = createRemoteAdminService(params);
        return service.requestModuleConfigSection(type)
                .thenApply(ignored -> (JsonElement) RemoteAdminRpcJson.sessionToJson(service.session()))
                .whenComplete((ignored, error) -> service.close());
    }

    private CompletableFuture<JsonElement> remoteAdminSaveOwner(JsonObject params) {
        String longName = rawTextField(params, "longName");
        String shortName = rawTextField(params, "shortName");
        boolean isLicensed = booleanField(params, "isLicensed");
        RemoteAdminService service = createRemoteAdminService(params);
        return service.saveOwner(longName, shortName, isLicensed)
                .thenApply(ignored -> okResult())
                .whenComplete((ignored, error) -> service.close());
    }

    private CompletableFuture<JsonElement> remoteAdminSaveConfigChanges(JsonObject params) {
        List<ConfigProtos.Config> configs = RemoteAdminRpcJson.configsFromJson(params, "configs");
        List<ModuleConfigProtos.ModuleConfig> moduleConfigs =
                RemoteAdminRpcJson.moduleConfigsFromJson(params, "moduleConfigs");
        List<ChannelProtos.Channel> channels = RemoteAdminRpcJson.channelsFromJson(params, "channels");
        RemoteAdminService service = createRemoteAdminService(params);
        return service.saveConfigChanges(configs, moduleConfigs, channels)
                .thenApply(ignored -> okResult())
                .whenComplete((ignored, error) -> service.close());
    }

    private CompletableFuture<JsonElement> remoteAdminSetFixedPosition(JsonObject params) {
        double latDegrees = doubleField(params, "latDegrees");
        double lonDegrees = doubleField(params, "lonDegrees");
        int altMeters = boundedInt(params, "altMeters", Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
        RemoteAdminService service = createRemoteAdminService(params);
        return service.setFixedPosition(latDegrees, lonDegrees, altMeters)
                .thenApply(ignored -> okResult())
                .whenComplete((ignored, error) -> service.close());
    }

    private CompletableFuture<JsonElement> remoteAdminRemoveFixedPosition(JsonObject params) {
        RemoteAdminService service = createRemoteAdminService(params);
        return service.removeFixedPosition()
                .thenApply(ignored -> okResult())
                .whenComplete((ignored, error) -> service.close());
    }

    private CompletableFuture<JsonElement> remoteAdminSetRingtone(JsonObject params) {
        String ringtone = rawTextField(params, "ringtone");
        RemoteAdminService service = createRemoteAdminService(params);
        return service.setRingtone(ringtone)
                .thenApply(ignored -> okResult())
                .whenComplete((ignored, error) -> service.close());
    }

    private CompletableFuture<JsonElement> remoteAdminSetCannedMessages(JsonObject params) {
        String messages = rawTextField(params, "messages");
        RemoteAdminService service = createRemoteAdminService(params);
        return service.setCannedMessages(messages)
                .thenApply(ignored -> okResult())
                .whenComplete((ignored, error) -> service.close());
    }

    private CompletableFuture<JsonElement> remoteAdminCommand(JsonObject params) {
        String command = requiredText(params, "command");
        RemoteAdminService service = createRemoteAdminService(params);
        CompletableFuture<Void> future;
        try {
            future = switch (command) {
                case "reboot" -> service.reboot(boundedInt(params, "delaySeconds", 0, Integer.MAX_VALUE, 0));
                case "shutdown" -> service.shutdown(boundedInt(params, "delaySeconds", 0, Integer.MAX_VALUE, 0));
                case "syncTime" -> service.syncTime(longField(params, "epochSeconds"));
                case "backupPreferences" -> service.backupPreferences(backupLocation(params));
                case "restorePreferences" -> service.restorePreferences(backupLocation(params));
                case "removeBackupPreferences" -> service.removeBackupPreferences(backupLocation(params));
                case "factoryResetConfig" -> service.factoryResetConfig();
                case "factoryResetDevice" -> service.factoryResetDevice();
                case "resetNodeDb" -> service.resetNodeDb(booleanField(params, "preserveFavorites"));
                case "enterDfuMode" -> service.enterDfuMode();
                default -> CompletableFuture.failedFuture(
                        new IllegalArgumentException("Unsupported remote admin command: " + command));
            };
        } catch (RuntimeException e) {
            service.close();
            throw e;
        }
        return future.thenApply(ignored -> okResult())
                .whenComplete((ignored, error) -> service.close());
    }

    private CompletableFuture<JsonElement> remoteAdminRefreshConnectionStatus(JsonObject params) {
        RemoteAdminService service = createRemoteAdminService(params);
        return service.refreshConnectionStatus()
                .thenApply(adminMessage -> {
                    JsonObject result = RemoteAdminRpcJson.sessionToJson(service.session());
                    result.addProperty("adminMessage", RemoteAdminRpcJson.encodeAdminMessage(adminMessage));
                    return (JsonElement) result;
                })
                .whenComplete((ignored, error) -> service.close());
    }

    private RemoteAdminService createRemoteAdminService(JsonObject params) {
        ActiveHostConnection active = activeHostConnection();
        ProtocolHandler handler = active.handler();
        if (handler == null) {
            throw new IllegalStateException("selected host connection cannot send remote admin packets");
        }
        NodeData node = resolveAdminNode(active.state(), params);
        return new RemoteAdminService(handler, active.state(), node);
    }

    private NodeData resolveAdminNode(DeviceState state, JsonObject params) {
        String requestedNodeId = textField(params, "nodeId");
        int requestedNodeNum = boundedInt(params, "nodeNum", Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
        NodeData node = !requestedNodeId.isBlank() ? resolvePeerNode(state, requestedNodeId) : null;
        if (node == null && requestedNodeNum != 0) {
            node = state.getNodeDb().get(requestedNodeNum);
            if (node != null) {
                NodeCacheService.getInstance().enrichFromCache(node);
            }
        }
        if (node == null) {
            throw new IllegalArgumentException("node was not found");
        }
        if (state.getMyNodeNum() != 0 && node.getNodeNum() == state.getMyNodeNum()) {
            throw new IllegalArgumentException("remote admin cannot target the local host node over RPC");
        }
        if (node.getPublicKey() == null || node.getPublicKey().length == 0) {
            throw new IllegalArgumentException("remote admin requires the target node public key");
        }
        return node;
    }

    private static JsonElement okResult() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private static AdminProtos.AdminMessage.ConfigType configType(JsonObject params) {
        String value = requiredText(params, "type");
        try {
            AdminProtos.AdminMessage.ConfigType type = AdminProtos.AdminMessage.ConfigType.valueOf(value);
            if (type == AdminProtos.AdminMessage.ConfigType.UNRECOGNIZED) {
                throw new IllegalArgumentException("Unsupported remote admin config type: " + value);
            }
            return type;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported remote admin config type: " + value, e);
        }
    }

    private static AdminProtos.AdminMessage.ModuleConfigType moduleConfigType(JsonObject params) {
        String value = requiredText(params, "type");
        try {
            AdminProtos.AdminMessage.ModuleConfigType type =
                    AdminProtos.AdminMessage.ModuleConfigType.valueOf(value);
            if (type == AdminProtos.AdminMessage.ModuleConfigType.UNRECOGNIZED) {
                throw new IllegalArgumentException("Unsupported remote admin module config type: " + value);
            }
            return type;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported remote admin module config type: " + value, e);
        }
    }

    private static AdminProtos.AdminMessage.BackupLocation backupLocation(JsonObject params) {
        String value = requiredText(params, "location");
        try {
            AdminProtos.AdminMessage.BackupLocation location =
                    AdminProtos.AdminMessage.BackupLocation.valueOf(value);
            if (location == AdminProtos.AdminMessage.BackupLocation.UNRECOGNIZED) {
                throw new IllegalArgumentException("Unsupported backup location: " + value);
            }
            return location;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported backup location: " + value, e);
        }
    }

    private static void addNodeJson(JsonArray items,
                                    Set<String> addedNodeIds,
                                    NodeData node,
                                    String ownerId,
                                    boolean enrichFromCache) {
        if (node == null || node.getNodeId() == null || node.getNodeId().isBlank()) {
            return;
        }
        if (!addedNodeIds.add(node.getNodeId().trim().toLowerCase())) {
            return;
        }
        if (enrichFromCache) {
            NodeCacheService.getInstance().enrichFromCache(node);
        }
        boolean favorite = FavoriteNodeService.getInstance().isFavorite(node.getNodeId(), ownerId);
        boolean ignored = IgnoredNodeService.getInstance().isIgnored(node.getNodeId(), ownerId);
        items.add(RemoteNodeJson.nodeToJson(node, favorite, ignored));
    }

    private static JsonObject nodeNamesJson(DeviceState state,
                                            int targetNodeNum,
                                            int responseFromNodeNum,
                                            MeshProtos.RouteDiscovery route) {
        JsonObject names = new JsonObject();
        if (state != null && state.getMyNodeNum() != 0) {
            addNodeName(names, state, state.getMyNodeNum());
        }
        addNodeName(names, state, targetNodeNum);
        addNodeName(names, state, responseFromNodeNum);
        if (route != null) {
            route.getRouteList().forEach(nodeNum -> addNodeName(names, state, nodeNum));
            route.getRouteBackList().forEach(nodeNum -> addNodeName(names, state, nodeNum));
        }
        return names;
    }

    private static void addNodeName(JsonObject names, DeviceState state, int nodeNum) {
        if (nodeNum == 0) {
            return;
        }
        names.addProperty(unsignedNodeKey(nodeNum), resolveNodeName(state, nodeNum));
    }

    private static String resolveNodeName(DeviceState state, int nodeNum) {
        NodeData node = NodeUtils.resolveNode(state, nodeNum);
        return node != null ? nodeTitle(node) : nodeIdFromNum(nodeNum);
    }

    private static String nodeNameFromJson(JsonObject names, int nodeNum) {
        JsonElement element = names != null ? names.get(unsignedNodeKey(nodeNum)) : null;
        if (element != null && !element.isJsonNull() && element.isJsonPrimitive()) {
            String value = element.getAsString();
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return nodeIdFromNum(nodeNum);
    }

    private static String formatTracerouteText(String targetName,
                                               MeshProtos.RouteDiscovery route,
                                               IntFunction<String> nodeNameResolver) {
        StringBuilder sb = new StringBuilder();
        sb.append(TracerouteView.TRACEROUTE_PREFIX).append(targetName).append("\n");
        sb.append(I18n.t("chat.self.avatar"));
        List<Integer> hops = route.getRouteList();
        int snrCount = route.getSnrTowardsCount();
        for (int i = 0; i <= hops.size(); i++) {
            if (i < snrCount) {
                sb.append(String.format(Locale.US, " \u2192%.1fdB\u2192 ", route.getSnrTowards(i) / 4.0));
            } else {
                sb.append(" \u2192 ");
            }
            sb.append(i < hops.size() ? nodeNameResolver.apply(hops.get(i)) : targetName);
        }
        if (route.getRouteBackCount() > 0 || route.getSnrBackCount() > 0) {
            sb.append("\n").append(targetName);
            List<Integer> backHops = route.getRouteBackList();
            int snrBackCount = route.getSnrBackCount();
            for (int i = 0; i <= backHops.size(); i++) {
                if (i < snrBackCount) {
                    sb.append(String.format(Locale.US, " \u2192%.1fdB\u2192 ", route.getSnrBack(i) / 4.0));
                } else {
                    sb.append(" \u2192 ");
                }
                sb.append(i < backHops.size() ? nodeNameResolver.apply(backHops.get(i)) : I18n.t("chat.self.avatar"));
            }
        }
        return sb.toString();
    }

    private static String nodeTitle(NodeData node) {
        if (node.getLongName() != null && !node.getLongName().isBlank()) {
            return node.getLongName().trim();
        }
        if (node.getShortName() != null && !node.getShortName().isBlank()) {
            return node.getShortName().trim();
        }
        if (node.getNodeId() != null && !node.getNodeId().isBlank()) {
            return node.getNodeId().trim();
        }
        return nodeIdFromNum(node.getNodeNum());
    }

    private static String localNodeId(DeviceState state) {
        if (state == null) {
            return "";
        }
        if (state.getMyNodeNum() != 0) {
            NodeData localNode = state.getNodeDb().get(state.getMyNodeNum());
            if (localNode != null && localNode.getNodeId() != null && !localNode.getNodeId().isBlank()) {
                return localNode.getNodeId().trim();
            }
        }
        String ownerNodeId = state.getOwnerNodeId();
        if (ownerNodeId != null && !ownerNodeId.isBlank()) {
            return ownerNodeId.trim();
        }
        return state.getMyNodeNum() != 0 ? nodeIdFromNum(state.getMyNodeNum()) : "";
    }

    private static String unsignedNodeKey(int nodeNum) {
        return Long.toUnsignedString(Integer.toUnsignedLong(nodeNum));
    }

    private static String nodeIdFromNum(int nodeNum) {
        return String.format("!%08x", nodeNum);
    }

    private ActiveHostConnection activeHostConnection() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = manager.getSelectedConnectionEntry();
        if (entry == null || !entry.isConnected()) {
            throw new IllegalStateException("no active host connection selected");
        }
        DeviceState state = manager.getDeviceState(entry.getId());
        if (state == null) {
            throw new IllegalStateException("active host connection has no device state yet");
        }
        ProtocolRuntime<?> runtime = manager.getProtocolRuntime(entry.getId());
        MeshCoreCompanionProtocolRuntime meshCoreRuntime =
                runtime instanceof MeshCoreCompanionProtocolRuntime companionRuntime ? companionRuntime : null;
        return new ActiveHostConnection(
                entry,
                state,
                manager.getProtocolHandler(entry.getId()),
                meshCoreRuntime);
    }

    private DeviceState activeHostStateOrNull() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = manager.getSelectedConnectionEntry();
        return entry != null && entry.isConnected()
                ? manager.getDeviceState(entry.getId())
                : null;
    }

    private static NodeData resolvePeerNode(DeviceState state, String peerNodeId) {
        if (state == null || peerNodeId == null || peerNodeId.isEmpty()) {
            return null;
        }

        NodeData peerNode = state.getNodeByNodeId(peerNodeId);
        if (peerNode != null) {
            NodeCacheService.getInstance().enrichFromCache(peerNode);
            return peerNode;
        }

        if (peerNodeId.length() > 1 && peerNodeId.charAt(0) == '!') {
            try {
                int peerNodeNum = (int) Long.parseUnsignedLong(peerNodeId.substring(1), 16);
                NodeData resolvedByNum = state.getNodeDb().get(peerNodeNum);
                if (resolvedByNum != null) {
                    NodeCacheService.getInstance().enrichFromCache(resolvedByNum);
                    return resolvedByNum;
                }
            } catch (NumberFormatException e) {
                log.debug("Remote chat peer nodeId '{}' is not a valid hex node number", peerNodeId);
            }
        }

        return NodeCacheService.getInstance().get(peerNodeId);
    }

    private static JsonObject chatItemToJson(ChatItem item) {
        JsonObject object = new JsonObject();
        object.addProperty("type", item.getType().name());
        object.addProperty("displayName", item.getDisplayName());
        object.addProperty("avatarText", item.getAvatarText());
        object.addProperty("avatarColor", item.getAvatarColor());
        object.addProperty("lastMessageText", item.getLastMessageText());
        object.addProperty("lastMessageTime", item.getLastMessageTime());
        object.addProperty("unreadCount", item.getUnreadCount());
        object.addProperty("channelIndex", item.getChannelIndex());
        object.addProperty("peerNodeId", item.getPeerNodeId());
        object.addProperty("muted", item.isMuted());
        return object;
    }

    private static int unreadCount(MessageDbService db,
                                   Map<String, Integer> readCounts,
                                   String chatType,
                                   String chatKey,
                                   String ownerId) {
        int total = db.getUnreadEligibleMessageCount(chatType, chatKey, ownerId);
        int lastRead = readCounts != null ? readCounts.getOrDefault(readKey(chatType, chatKey), 0) : 0;
        return Math.max(0, total - lastRead);
    }

    private static String readKey(String chatType, String chatKey) {
        return "dm".equals(chatType) ? "dm:" + chatKey : "ch:" + chatKey;
    }

    private static JsonObject messageToJson(MeshMessage message, DeviceState state) {
        JsonObject object = new JsonObject();
        object.addProperty("fromNodeId", message.getFromNodeId());
        object.addProperty("toNodeId", message.getToNodeId());
        object.addProperty("channelIndex", message.getChannelIndex());
        object.addProperty("text", message.getText());
        object.addProperty("timestamp", message.getTimestamp());
        object.addProperty("outgoing", message.isOutgoing());
        object.addProperty("status", message.getStatus() != null ? message.getStatus().name() : null);
        object.addProperty("packetId", message.getPacketId());
        object.addProperty("errorReason", message.getErrorReason());
        object.addProperty("replyId", message.getReplyId());
        object.addProperty("replyText", message.getReplyText());
        object.addProperty("replyToOutgoing", message.isReplyToOutgoing());
        object.addProperty("hopStart", message.getHopStart());
        object.addProperty("hopLimit", message.getHopLimit());
        object.addProperty("rxRssi", message.getRxRssi());
        object.addProperty("rxSnr", message.getRxSnr());
        object.addProperty("senderName", resolveSenderName(state, message));
        object.addProperty("viaMqtt", message.isViaMqtt());
        object.addProperty("systemMessage", message.isSystemMessage());
        object.addProperty("dbId", message.getDbId());
        JsonArray reactions = new JsonArray();
        for (MessageReaction reaction : message.getReactions()) {
            reactions.add(reactionToJson(reaction, state));
        }
        object.add("reactions", reactions);
        return object;
    }

    private static JsonObject reactionToJson(MessageReaction reaction, DeviceState state) {
        JsonObject object = new JsonObject();
        object.addProperty("targetPacketId", reaction.getTargetPacketId());
        object.addProperty("fromNodeId", reaction.getFromNodeId());
        object.addProperty("emoji", reaction.getEmoji());
        object.addProperty("timestamp", reaction.getTimestamp());
        object.addProperty("outgoing", reaction.isOutgoing());
        object.addProperty("dbId", reaction.getDbId());
        object.addProperty("packetId", reaction.getPacketId());
        object.addProperty("status", reaction.getStatus() != null ? reaction.getStatus().name() : null);
        object.addProperty("errorReason", reaction.getErrorReason());
        object.addProperty("senderName", resolveReactionSenderName(state, reaction));
        return object;
    }

    private static void prepareMessagesForRemote(DeviceState state,
                                                 String chatType,
                                                 String chatKey,
                                                 String ownerId,
                                                 List<MeshMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        MessageDbService db = MessageDbService.getInstance();
        db.hydrateReplyTexts(messages, chatType, chatKey, ownerId);
        markReplyTargets(messages, db, chatType, chatKey, ownerId);
        Map<Integer, List<MessageReaction>> reactionsByTarget =
                db.loadReactionsByTargetPacketIds(
                        chatType,
                        chatKey,
                        ownerId,
                        messages.stream().map(MeshMessage::getPacketId).toList());
        for (MeshMessage message : messages) {
            List<MessageReaction> reactions = reactionsByTarget.get(message.getPacketId());
            if (reactions != null && !reactions.isEmpty()) {
                reactions.forEach(reaction -> {
                    if (reaction.getSenderName() == null || reaction.getSenderName().isBlank()) {
                        reaction.setSenderName(resolveReactionSenderName(state, reaction));
                    }
                });
                message.setReactions(reactions);
            }
        }
    }

    private static void markReplyTargets(List<MeshMessage> messages,
                                         MessageDbService db,
                                         String chatType,
                                         String chatKey,
                                         String ownerId) {
        for (MeshMessage message : messages) {
            if (message.getReplyId() == 0) {
                message.setReplyToOutgoing(false);
                continue;
            }
            MeshMessage original = db.findByPacketId(message.getReplyId(), chatType, chatKey, ownerId);
            message.setReplyToOutgoing(original != null && original.isOutgoing());
        }
    }

    private static String resolveSenderName(DeviceState state, MeshMessage message) {
        if (message == null) {
            return "";
        }
        String existing = message.getSenderName();
        if (existing != null && !existing.isBlank()) {
            return existing.trim();
        }
        NodeData node = NodeUtils.resolveNode(state, message.getFromNodeId());
        return firstText(
                node != null ? firstText(node.getLongName(), node.getShortName()) : null,
                message.getFromNodeId());
    }

    private static String resolveReactionSenderName(DeviceState state, MessageReaction reaction) {
        if (reaction == null) {
            return "";
        }
        String existing = reaction.getSenderName();
        if (existing != null && !existing.isBlank()) {
            return existing.trim();
        }
        NodeData node = NodeUtils.resolveNode(state, reaction.getFromNodeId());
        return firstText(
                node != null ? firstText(node.getLongName(), node.getShortName()) : null,
                reaction.getFromNodeId());
    }

    private static String requiredText(JsonObject params, String field) {
        JsonElement element = params != null ? params.get(field) : null;
        if (element == null || element == JsonNull.INSTANCE || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String value = element.getAsString();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String textField(JsonObject params, String field) {
        String value = rawTextField(params, field);
        return value == null ? "" : value.trim();
    }

    private static String rawTextField(JsonObject params, String field) {
        JsonElement element = params != null ? params.get(field) : null;
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString();
        return value == null ? "" : value;
    }

    private static int boundedInt(JsonObject params, String field, int min, int max, int fallback) {
        JsonElement element = params != null ? params.get(field) : null;
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        int value = element.getAsInt();
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return value;
    }

    private static long longField(JsonObject params, String field) {
        JsonElement element = params != null ? params.get(field) : null;
        return element != null && !element.isJsonNull() ? element.getAsLong() : 0L;
    }

    private static double doubleField(JsonObject params, String field) {
        JsonElement element = params != null ? params.get(field) : null;
        return element != null && !element.isJsonNull() ? element.getAsDouble() : 0.0;
    }

    private static boolean booleanField(JsonObject params, String field) {
        JsonElement element = params != null ? params.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                && element.getAsBoolean();
    }

    private record ActiveHostConnection(ConnectionEntry entry,
                                        DeviceState state,
                                        ProtocolHandler handler,
                                        MeshCoreCompanionProtocolRuntime meshCoreRuntime) {
    }

    private record PendingTraceroute(DeviceState state,
                                     BiConsumer<Integer, MeshProtos.RouteDiscovery> listener) {
    }
}
