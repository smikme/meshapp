package com.meshtastic.client.service;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.model.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages favorite nodes.
 * Persistence is delegated to {@link NodeCacheService} in H2, and UI changes
 * are mirrored to the device through AdminMessage for two-way synchronization.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class FavoriteNodeService {

    private static final Logger log = LoggerFactory.getLogger(FavoriteNodeService.class);

    private static FavoriteNodeService instance;

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Nodes unfavorited locally but not yet confirmed by the device. */
    private final Set<String> pendingUnfavorites = ConcurrentHashMap.newKeySet();

    private FavoriteNodeService() {}

    public static synchronized FavoriteNodeService getInstance() {
        if (instance == null) {
            instance = new FavoriteNodeService();
        }
        return instance;
    }

    public boolean isFavorite(String nodeId) {
        return nodeId != null && NodeCacheService.getInstance().isFavorite(nodeId);
    }

    public boolean isFavorite(String nodeId, String ownerNodeId) {
        return nodeId != null && NodeCacheService.getInstance().isFavorite(nodeId, ownerNodeId);
    }

    public void addFavorite(String nodeId) {
        addFavorite(nodeId, "");
    }

    public void addFavorite(String nodeId, String ownerNodeId) {
        addFavorite(nodeId, ownerNodeId, null);
    }

    public void addFavorite(String nodeId, String ownerNodeId, String connectionId) {
        if (nodeId == null) { return; }
        pendingUnfavorites.remove(pendingKey(ownerNodeId, nodeId));
        NodeCacheService.getInstance().setFavorite(nodeId, ownerNodeId, true);
        sendToDevice(nodeId, ownerNodeId, connectionId, true);
        fireListeners();
    }

    public void removeFavorite(String nodeId) {
        removeFavorite(nodeId, "");
    }

    public void removeFavorite(String nodeId, String ownerNodeId) {
        removeFavorite(nodeId, ownerNodeId, null);
    }

    public void removeFavorite(String nodeId, String ownerNodeId, String connectionId) {
        if (nodeId == null) { return; }
        pendingUnfavorites.add(pendingKey(ownerNodeId, nodeId));
        log.info("Added pending unfavorite for owner {} node {}", ownerNodeId, nodeId);
        NodeCacheService.getInstance().setFavorite(nodeId, ownerNodeId, false);
        sendToDevice(nodeId, ownerNodeId, connectionId, false);
        fireListeners();
    }

    /**
     * Updates H2 without notifying listeners or sending a device command.
     * Used during config exchange when the value already came from the device.
     * <p>
     * If a node has a pending local unfavorite that firmware has not stored yet,
     * the overwrite is skipped and {@code remove_favorite_node} is sent again.
     */
    public void setFavoriteQuiet(String nodeId, boolean favorite) {
        setFavoriteQuiet(nodeId, "", favorite);
    }

    public void setFavoriteQuiet(String nodeId, String ownerNodeId, boolean favorite) {
        if (nodeId == null) { return; }
        String pendingKey = pendingKey(ownerNodeId, nodeId);
        if (favorite && pendingUnfavorites.contains(pendingKey)) {
            log.info("Skipping setFavoriteQuiet(true) for owner {} node {} — pending unfavorite, re-sending admin message",
                    ownerNodeId, nodeId);
            sendToDevice(nodeId, ownerNodeId, false);
            return;
        }
        if (!favorite && pendingUnfavorites.contains(pendingKey)) {
            // The device confirmed the unfavorite request; clear the pending marker.
            log.info("Device confirmed unfavorite for owner {} node {}, clearing pending state", ownerNodeId, nodeId);
            pendingUnfavorites.remove(pendingKey);
        }
        NodeCacheService.getInstance().setFavorite(nodeId, ownerNodeId, favorite);
    }

    public boolean toggleFavorite(String nodeId) {
        return toggleFavorite(nodeId, "");
    }

    public boolean toggleFavorite(String nodeId, String ownerNodeId) {
        if (nodeId == null) { return false; }
        boolean was = isFavorite(nodeId, ownerNodeId);
        if (was) {
            removeFavorite(nodeId, ownerNodeId);
        } else {
            addFavorite(nodeId, ownerNodeId);
        }
        return !was;
    }

    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    public void fireListeners() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception ignored) { }
        }
    }

    private void sendToDevice(String nodeId, String ownerNodeId, boolean favorite) {
        sendToDevice(nodeId, ownerNodeId, null, favorite);
    }

    private void sendToDevice(String nodeId, String ownerNodeId, String connectionId, boolean favorite) {
        try {
            ConnectionManager cm = ConnectionManager.getInstance();
            String owner = ownerNodeId != null ? ownerNodeId.toLowerCase(Locale.ROOT) : "";
            String requiredConnectionId = connectionId != null ? connectionId.trim() : "";
            boolean sent = false;
            for (ConnectionEntry entry : cm.getEntries()) {
                if (!entry.isConnected()) { continue; }
                if (!requiredConnectionId.isBlank() && !requiredConnectionId.equals(entry.getId())) {
                    continue;
                }
                String entryOwner = cm.getOwnerNodeId(entry.getId());
                if (requiredConnectionId.isBlank()
                        && !owner.isBlank()
                        && (entryOwner == null || !owner.equals(entryOwner.toLowerCase(Locale.ROOT)))) {
                    continue;
                }
                ProtocolHandler handler = cm.getProtocolHandler(entry.getId());
                DeviceState state = cm.getDeviceState(entry.getId());
                if (handler == null || state == null) { continue; }
                int nodeNum = (int) Long.parseLong(nodeId.substring(1), 16);
                if (favorite) {
                    MessageService.setFavoriteNode(handler, state, nodeNum);
                } else {
                    MessageService.removeFavoriteNode(handler, state, nodeNum);
                }
                log.info("Sent favorite change to device: nodeId={}, favorite={}, via='{}'",
                        nodeId, favorite, entry.getName());
                sent = true;
                break; // Send through the first active connection only.
            }
            if (!sent) {
                log.warn("No active connection found to send favorite change for owner {} node {} connection {}",
                        ownerNodeId, nodeId, requiredConnectionId);
            }
        } catch (Exception e) {
            log.warn("Failed to send favorite change to device for owner {} node {} connection {}",
                    ownerNodeId, nodeId, connectionId, e);
        }
    }

    private static String pendingKey(String ownerNodeId, String nodeId) {
        return (ownerNodeId != null ? ownerNodeId.toLowerCase(Locale.ROOT) : "") + "\u0000" + nodeId;
    }
}
