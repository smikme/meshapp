package com.meshtastic.client.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Сервис управления избранными нодами.
 * Делегирует персистентность в {@link NodeCacheService} (H2, колонка {@code favorite}).
 */
public final class FavoriteNodeService {

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
        fireListeners();
    }

    public void removeFavorite(String nodeId) {
        if (nodeId == null) { return; }
        NodeCacheService.getInstance().setFavorite(nodeId, false);
        fireListeners();
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

    private void fireListeners() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception ignored) { }
        }
    }
}
