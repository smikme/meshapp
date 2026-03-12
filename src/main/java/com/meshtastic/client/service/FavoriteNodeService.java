package com.meshtastic.client.service;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.model.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Сервис управления избранными нодами.
 * Делегирует персистентность в {@link NodeCacheService} (H2, колонка {@code favorite}).
 * При изменении из UI отправляет AdminMessage на устройство для двусторонней синхронизации.
 */
public final class FavoriteNodeService {

    private static final Logger log = LoggerFactory.getLogger(FavoriteNodeService.class);

    private static FavoriteNodeService instance;

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

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
        NodeCacheService.getInstance().setFavorite(nodeId, true);
        sendToDevice(nodeId, true);
        fireListeners();
    }

    public void removeFavorite(String nodeId) {
        if (nodeId == null) { return; }
        NodeCacheService.getInstance().setFavorite(nodeId, false);
        sendToDevice(nodeId, false);
        fireListeners();
    }

    /** Обновляет H2 без уведомления listeners и без отправки на устройство.
     *  Используется при config exchange, когда данные уже пришли с устройства. */
    public void setFavoriteQuiet(String nodeId, boolean favorite) {
        if (nodeId == null) { return; }
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
                break; // отправляем только на первое активное соединение
            }
        } catch (Exception e) {
            log.warn("Failed to send favorite change to device for node {}", nodeId, e);
        }
    }
}
