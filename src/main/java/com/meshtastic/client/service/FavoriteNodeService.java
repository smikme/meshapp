package com.meshtastic.client.service;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.model.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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

    public void addFavorite(String nodeId) {
        if (nodeId == null) { return; }
        pendingUnfavorites.remove(nodeId);
        NodeCacheService.getInstance().setFavorite(nodeId, true);
        sendToDevice(nodeId, true);
        fireListeners();
    }

    public void removeFavorite(String nodeId) {
        if (nodeId == null) { return; }
        pendingUnfavorites.add(nodeId);
        log.info("Added pending unfavorite for node {}", nodeId);
        NodeCacheService.getInstance().setFavorite(nodeId, false);
        sendToDevice(nodeId, false);
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
        if (nodeId == null) { return; }
        if (favorite && pendingUnfavorites.contains(nodeId)) {
            log.info("Skipping setFavoriteQuiet(true) for node {} — pending unfavorite, re-sending admin message", nodeId);
            sendToDevice(nodeId, false);
            return;
        }
        if (!favorite && pendingUnfavorites.contains(nodeId)) {
            // The device confirmed the unfavorite request; clear the pending marker.
            log.info("Device confirmed unfavorite for node {}, clearing pending state", nodeId);
            pendingUnfavorites.remove(nodeId);
        }
        NodeCacheService.getInstance().setFavorite(nodeId, favorite);
    }

    public boolean toggleFavorite(String nodeId) {
        if (nodeId == null) { return false; }
        boolean was = isFavorite(nodeId);
        if (was) {
            removeFavorite(nodeId);
        } else {
            addFavorite(nodeId);
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

    private void sendToDevice(String nodeId, boolean favorite) {
        try {
            ConnectionManager cm = ConnectionManager.getInstance();
            boolean sent = false;
            for (ConnectionEntry entry : cm.getEntries()) {
                if (!entry.isConnected()) { continue; }
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
                log.warn("No active connection found to send favorite change for node {}", nodeId);
            }
        } catch (Exception e) {
            log.warn("Failed to send favorite change to device for node {}", nodeId, e);
        }
    }
}
