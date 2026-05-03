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
 * Сервис управления игнорируемыми нодами.
 * Делегирует персистентность в {@link NodeCacheService} (H2, колонка {@code ignored}).
 * При изменении из UI отправляет AdminMessage на устройство для двусторонней синхронизации.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class IgnoredNodeService {

    private static final Logger log = LoggerFactory.getLogger(IgnoredNodeService.class);

    private static IgnoredNodeService instance;

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Ноды, для которых пользователь снял игнорирование, но устройство ещё не подтвердило изменение. */
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

    public void addIgnored(String nodeId) {
        if (nodeId == null) { return; }
        pendingUnignores.remove(nodeId);
        NodeCacheService.getInstance().setIgnored(nodeId, true);
        sendToDevice(nodeId, true);
        fireListeners();
    }

    public void removeIgnored(String nodeId) {
        if (nodeId == null) { return; }
        pendingUnignores.add(nodeId);
        log.info("Added pending unignore for node {}", nodeId);
        NodeCacheService.getInstance().setIgnored(nodeId, false);
        sendToDevice(nodeId, false);
        fireListeners();
    }

    /**
     * Обновляет H2 без уведомления listeners и без отправки на устройство.
     * Используется при config exchange, когда данные уже пришли с устройства.
     * <p>
     * Если для ноды есть pending unignore (пользователь снял игнорирование,
     * но прошивка не сохранила изменение), пропускаем перезапись и повторно
     * отправляем {@code remove_ignored_node} AdminMessage.
     */
    public void setIgnoredQuiet(String nodeId, boolean ignored) {
        if (nodeId == null) { return; }
        if (ignored && pendingUnignores.contains(nodeId)) {
            log.info("Skipping setIgnoredQuiet(true) for node {} — pending unignore, re-sending admin message", nodeId);
            sendToDevice(nodeId, false);
            return;
        }
        if (!ignored && pendingUnignores.contains(nodeId)) {
            log.info("Device confirmed unignore for node {}, clearing pending state", nodeId);
            pendingUnignores.remove(nodeId);
        }
        NodeCacheService.getInstance().setIgnored(nodeId, ignored);
    }

    public boolean toggleIgnored(String nodeId) {
        if (nodeId == null) { return false; }
        boolean was = isIgnored(nodeId);
        if (was) {
            removeIgnored(nodeId);
        } else {
            addIgnored(nodeId);
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

    private void sendToDevice(String nodeId, boolean ignored) {
        try {
            ConnectionManager cm = ConnectionManager.getInstance();
            boolean sent = false;
            for (ConnectionEntry entry : cm.getEntries()) {
                if (!entry.isConnected()) { continue; }
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
                break; // отправляем только на первое активное соединение
            }
            if (!sent) {
                log.warn("No active connection found to send ignored change for node {}", nodeId);
            }
        } catch (Exception e) {
            log.warn("Failed to send ignored change to device for node {}", nodeId, e);
        }
    }
}
