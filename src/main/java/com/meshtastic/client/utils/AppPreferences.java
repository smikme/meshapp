package com.meshtastic.client.utils;

import java.util.prefs.Preferences;

public class AppPreferences {

    public static final String PREFERENCES_ROOT_PATH = "/meshapp";
    public static final String KEY_DARK_MODE = "darkMode";
    public static final String KEY_RECENT_SEARCH = "recentSearch";
    public static final String KEY_RECENT_SEARCH_FAVORITE = "recentSearchFavorite";
    public static final String KEY_WINDOW_X = "windowX";
    public static final String KEY_WINDOW_Y = "windowY";
    public static final String KEY_WINDOW_WIDTH = "windowWidth";
    public static final String KEY_WINDOW_HEIGHT = "windowHeight";
    public static final String KEY_WINDOW_MAXIMIZED = "windowMaximized";

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

    public static String[] getRecentSearch(boolean favorite) {
        String stringArr = state.get(favorite ? KEY_RECENT_SEARCH_FAVORITE : KEY_RECENT_SEARCH, null);
        if (stringArr == null || stringArr.trim().isEmpty()) return null;
        return stringArr.trim().split(",");
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

}
