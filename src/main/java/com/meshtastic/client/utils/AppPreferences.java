package com.meshtastic.client.utils;

import java.util.Collections;
import java.util.List;
import java.util.prefs.Preferences;

public class AppPreferences {

    private AppPreferences() {} // utility class

    public static final String PREFERENCES_ROOT_PATH = "/meshapp";
    public static final String KEY_DARK_MODE = "darkMode";
    public static final String KEY_RECENT_SEARCH = "recentSearch";
    public static final String KEY_RECENT_SEARCH_FAVORITE = "recentSearchFavorite";
    public static final String KEY_WINDOW_X = "windowX";
    public static final String KEY_WINDOW_Y = "windowY";
    public static final String KEY_WINDOW_WIDTH = "windowWidth";
    public static final String KEY_WINDOW_HEIGHT = "windowHeight";
    public static final String KEY_WINDOW_MAXIMIZED = "windowMaximized";
    public static final String KEY_NOTIFICATIONS_ENABLED = "notificationsEnabled";
    public static final String KEY_NATIVE_WINDOW = "nativeWindow";
    public static final String KEY_DISABLE_TRANSPARENCY = "disableTransparency";

    private static Preferences state;

    public static void init() {
        state = Preferences.userRoot().node(PREFERENCES_ROOT_PATH);
    }

    public static Preferences getState() {
        return state;
    }

    public static boolean isDarkMode() {
        return state.getBoolean(KEY_DARK_MODE, false);
    }

    public static void setDarkMode(boolean dark) {
        state.putBoolean(KEY_DARK_MODE, dark);
    }

    public static boolean isNotificationsEnabled() {
        return state.getBoolean(KEY_NOTIFICATIONS_ENABLED, true);
    }

    public static void setNotificationsEnabled(boolean enabled) {
        state.putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled);
    }

    public static boolean isNativeWindow() {
        return state.getBoolean(KEY_NATIVE_WINDOW, false);
    }

    public static void setNativeWindow(boolean value) {
        state.putBoolean(KEY_NATIVE_WINDOW, value);
    }

    public static boolean isDisableTransparency() {
        return state.getBoolean(KEY_DISABLE_TRANSPARENCY, false);
    }

    public static void setDisableTransparency(boolean value) {
        state.putBoolean(KEY_DISABLE_TRANSPARENCY, value);
    }

    public static List<String> getRecentSearch(boolean favorite) {
        String stringArr = state.get(favorite ? KEY_RECENT_SEARCH_FAVORITE : KEY_RECENT_SEARCH, null);
        if (stringArr == null || stringArr.trim().isEmpty()) { return Collections.emptyList(); }
        return List.of(stringArr.trim().split(","));
    }

    // ==================== Window Bounds ====================

    public static boolean hasWindowBounds() {
        return !Double.isNaN(state.getDouble(KEY_WINDOW_WIDTH, Double.NaN));
    }

    public static double getWindowX() { return state.getDouble(KEY_WINDOW_X, Double.NaN); }
    public static double getWindowY() { return state.getDouble(KEY_WINDOW_Y, Double.NaN); }
    public static double getWindowWidth() { return state.getDouble(KEY_WINDOW_WIDTH, Double.NaN); }
    public static double getWindowHeight() { return state.getDouble(KEY_WINDOW_HEIGHT, Double.NaN); }
    public static boolean isWindowMaximized() { return state.getBoolean(KEY_WINDOW_MAXIMIZED, false); }

    public static void saveWindowBounds(double x, double y, double w, double h, boolean maximized) {
        state.putDouble(KEY_WINDOW_X, x);
        state.putDouble(KEY_WINDOW_Y, y);
        state.putDouble(KEY_WINDOW_WIDTH, w);
        state.putDouble(KEY_WINDOW_HEIGHT, h);
        state.putBoolean(KEY_WINDOW_MAXIMIZED, maximized);
    }

    // ==================== Recent Search ====================

    // ==================== Nodes Sort & Filter ====================

    public static final String KEY_NODES_SORT = "nodesSort";
    public static final String KEY_NODES_FILTER_UNKNOWN = "nodesFilterUnknown";
    public static final String KEY_NODES_FILTER_DETAILS = "nodesFilterDetails";
    public static final String KEY_NODES_FILTER_HIDE_OFFLINE = "nodesFilterHideOffline";
    public static final String KEY_NODES_FILTER_FAVORITES = "nodesFilterFavorites";
    public static final String KEY_NODES_FILTER_DIRECT = "nodesFilterDirect";

    public static String getNodesSort() { return state.get(KEY_NODES_SORT, "LAST_HEARD_NEW"); }
    public static void setNodesSort(String sort) { state.put(KEY_NODES_SORT, sort); }

    public static boolean isNodesFilterUnknown() { return state.getBoolean(KEY_NODES_FILTER_UNKNOWN, false); }
    public static void setNodesFilterUnknown(boolean v) { state.putBoolean(KEY_NODES_FILTER_UNKNOWN, v); }

    public static boolean isNodesFilterDetails() { return state.getBoolean(KEY_NODES_FILTER_DETAILS, false); }
    public static void setNodesFilterDetails(boolean v) { state.putBoolean(KEY_NODES_FILTER_DETAILS, v); }

    public static boolean isNodesFilterHideOffline() { return state.getBoolean(KEY_NODES_FILTER_HIDE_OFFLINE, false); }
    public static void setNodesFilterHideOffline(boolean v) { state.putBoolean(KEY_NODES_FILTER_HIDE_OFFLINE, v); }

    public static boolean isNodesFilterFavorites() { return state.getBoolean(KEY_NODES_FILTER_FAVORITES, false); }
    public static void setNodesFilterFavorites(boolean v) { state.putBoolean(KEY_NODES_FILTER_FAVORITES, v); }

    public static boolean isNodesFilterDirect() { return state.getBoolean(KEY_NODES_FILTER_DIRECT, false); }
    public static void setNodesFilterDirect(boolean v) { state.putBoolean(KEY_NODES_FILTER_DIRECT, v); }

}
