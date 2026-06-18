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
 * Manages the set of ignored nodes.
 * <p>
 * Persistence is delegated to {@link NodeCacheService} through the H2
 * {@code ignored} column. UI-initiated changes are also sent to the device as
 * admin messages so firmware state can stay in sync with the local cache.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class IgnoredNodeService {

    private static final Logger log = LoggerFactory.getLogger(IgnoredNodeService.class);

    private static IgnoredNodeService instance;

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Nodes the user unignored locally but the device has not confirmed yet. */
    private final Set<String> pendingUnignores = ConcurrentHashMap.newKeySet();

    private IgnoredNodeService() {}

    public static synchronized IgnoredNodeService getInstance() {
        if (instance == null) {
            instance = new IgnoredNodeService();
        }
        return instance;
    }

    public boolean isIgnored(String nodeId) {
        return nodeId != null && NodeCacheService.getInstance().isIgnored(nodeId);
    }

    public boolean isIgnored(String nodeId, String ownerNodeId) {
        return nodeId != null && NodeCacheService.getInstance().isIgnored(nodeId, ownerNodeId);
    }

    public void addIgnored(String nodeId) {
        addIgnored(nodeId, "");
    }

    public void addIgnored(String nodeId, String ownerNodeId) {
        if (nodeId == null) { return; }
        pendingUnignores.remove(pendingKey(ownerNodeId, nodeId));
        NodeCacheService.getInstance().setIgnored(nodeId, ownerNodeId, true);
        sendToDevice(nodeId, ownerNodeId, true);
        fireListeners();
    }

    public void removeIgnored(String nodeId) {
        removeIgnored(nodeId, "");
    }

    public void removeIgnored(String nodeId, String ownerNodeId) {
        if (nodeId == null) { return; }
        pendingUnignores.add(pendingKey(ownerNodeId, nodeId));
        log.info("Added pending unignore for owner {} node {}", ownerNodeId, nodeId);
        NodeCacheService.getInstance().setIgnored(nodeId, ownerNodeId, false);
        sendToDevice(nodeId, ownerNodeId, false);
        fireListeners();
    }

    /**
     * Updates H2 without notifying listeners or sending anything to the device.
     * This path is used during config exchange, when the data already came from
     * the device.
     * <p>
     * If a node has a pending unignore, the user removed the ignore locally but
     * firmware has not persisted it yet. In that case the device value is not
     * allowed to overwrite local intent; {@code remove_ignored_node} is sent
     * again instead.
     */
    public void setIgnoredQuiet(String nodeId, boolean ignored) {
        setIgnoredQuiet(nodeId, "", ignored);
    }

    public void setIgnoredQuiet(String nodeId, String ownerNodeId, boolean ignored) {
        if (nodeId == null) { return; }
        String pendingKey = pendingKey(ownerNodeId, nodeId);
        if (ignored && pendingUnignores.contains(pendingKey)) {
            log.info("Skipping setIgnoredQuiet(true) for owner {} node {} — pending unignore, re-sending admin message",
                    ownerNodeId, nodeId);
            sendToDevice(nodeId, ownerNodeId, false);
            return;
        }
        if (!ignored && pendingUnignores.contains(pendingKey)) {
            log.info("Device confirmed unignore for owner {} node {}, clearing pending state", ownerNodeId, nodeId);
            pendingUnignores.remove(pendingKey);
        }
        NodeCacheService.getInstance().setIgnored(nodeId, ownerNodeId, ignored);
    }

    public boolean toggleIgnored(String nodeId) {
        return toggleIgnored(nodeId, "");
    }

    public boolean toggleIgnored(String nodeId, String ownerNodeId) {
        if (nodeId == null) { return false; }
        boolean was = isIgnored(nodeId, ownerNodeId);
        if (was) {
            removeIgnored(nodeId, ownerNodeId);
        } else {
            addIgnored(nodeId, ownerNodeId);
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

    private void sendToDevice(String nodeId, String ownerNodeId, boolean ignored) {
        try {
            ConnectionManager cm = ConnectionManager.getInstance();
            String owner = ownerNodeId != null ? ownerNodeId.toLowerCase(Locale.ROOT) : "";
            boolean sent = false;
            for (ConnectionEntry entry : cm.getEntries()) {
                if (!entry.isConnected()) { continue; }
                String entryOwner = cm.getOwnerNodeId(entry.getId());
                if (!owner.isBlank()
                        && (entryOwner == null || !owner.equals(entryOwner.toLowerCase(Locale.ROOT)))) {
                    continue;
                }
                ProtocolHandler handler = cm.getProtocolHandler(entry.getId());
                DeviceState state = cm.getDeviceState(entry.getId());
                if (handler == null || state == null) { continue; }
                int nodeNum = (int) Long.parseLong(nodeId.substring(1), 16);
                if (ignored) {
                    MessageService.setIgnoredNode(handler, state, nodeNum);
                } else {
                    MessageService.removeIgnoredNode(handler, state, nodeNum);
                }
                log.info("Sent ignored change to device: nodeId={}, ignored={}, via='{}'",
                        nodeId, ignored, entry.getName());
                sent = true;
                break; // Send the update only through the first active connection.
            }
            if (!sent) {
                log.warn("No active connection found to send ignored change for owner {} node {}", ownerNodeId, nodeId);
            }
        } catch (Exception e) {
            log.warn("Failed to send ignored change to device for owner {} node {}", ownerNodeId, nodeId, e);
        }
    }

    private static String pendingKey(String ownerNodeId, String nodeId) {
        return (ownerNodeId != null ? ownerNodeId.toLowerCase(Locale.ROOT) : "") + "\u0000" + nodeId;
    }
}
