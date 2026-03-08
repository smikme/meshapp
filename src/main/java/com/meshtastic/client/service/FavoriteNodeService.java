package com.meshtastic.client.service;

import com.meshtastic.client.utils.AppPreferences;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

public final class FavoriteNodeService {

    private static final String SEPARATOR = ",";

    private static FavoriteNodeService instance;

    private final Set<String> favorites = new CopyOnWriteArraySet<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private FavoriteNodeService() {
        load();
    }

    public static synchronized FavoriteNodeService getInstance() {
        if (instance == null) {
            instance = new FavoriteNodeService();
        }
        return instance;
    }

    public boolean isFavorite(String nodeId) {
        return nodeId != null && favorites.contains(nodeId);
    }

    public void addFavorite(String nodeId) {
        if (nodeId != null && favorites.add(nodeId)) {
            persist();
            fireListeners();
        }
    }

    public void removeFavorite(String nodeId) {
        if (nodeId != null && favorites.remove(nodeId)) {
            persist();
            fireListeners();
        }
    }

    public boolean toggleFavorite(String nodeId) {
        if (nodeId == null) return false;
        boolean added;
        if (favorites.contains(nodeId)) {
            favorites.remove(nodeId);
            added = false;
        } else {
            favorites.add(nodeId);
            added = true;
        }
        persist();
        fireListeners();
        return added;
    }

    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    private void fireListeners() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception ignored) { }
        }
    }

    private void load() {
        String raw = AppPreferences.getState().get(AppPreferences.KEY_FAVORITE_NODES, "");
        if (!raw.isEmpty()) {
            favorites.addAll(Arrays.asList(raw.split(SEPARATOR)));
        }
    }

    private void persist() {
        AppPreferences.getState().put(AppPreferences.KEY_FAVORITE_NODES, String.join(SEPARATOR, favorites));
    }
}
