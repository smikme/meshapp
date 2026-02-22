package com.meshtastic.client.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;

public class AppPreferences {

    public static final String PREFERENCES_ROOT_PATH = "/meshapp";
    public static final String KEY_DARK_MODE = "darkMode";
    public static final String KEY_RECENT_SEARCH = "recentSearch";
    public static final String KEY_RECENT_SEARCH_FAVORITE = "recentSearchFavorite";

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

    public static void addRecentSearch(String value, boolean favorite) {
        String[] oldRecent = getRecentSearch(false);
        String[] oldFavorite = getRecentSearch(true);
        if (favorite) {
            if (oldRecent != null) {
                List<String> list = new ArrayList<>(Arrays.asList(oldRecent));
                list.remove(value);
                state.put(KEY_RECENT_SEARCH, String.join(",", list));
            }
            if (oldFavorite != null) {
                List<String> list = new ArrayList<>(Arrays.asList(oldFavorite));
                list.remove(value);
                list.add(0, value);
                state.put(KEY_RECENT_SEARCH_FAVORITE, String.join(",", list));
            } else {
                state.put(KEY_RECENT_SEARCH_FAVORITE, value);
            }
        } else {
            if (oldFavorite != null) {
                List<String> list = new ArrayList<>(Arrays.asList(oldFavorite));
                if (list.contains(value)) {
                    return;
                }
            }
            if (oldRecent == null) {
                state.put(KEY_RECENT_SEARCH, value);
            } else {
                List<String> list = new ArrayList<>(Arrays.asList(oldRecent));
                list.remove(value);
                list.add(0, value);
                state.put(KEY_RECENT_SEARCH, String.join(",", list));
            }
        }
    }

    public static void removeRecentSearch(String value, boolean favorite) {
        String[] oldRecent = getRecentSearch(favorite);
        if (oldRecent != null) {
            List<String> list = new ArrayList<>(Arrays.asList(oldRecent));
            list.remove(value);
            state.put(favorite ? KEY_RECENT_SEARCH_FAVORITE : KEY_RECENT_SEARCH, String.join(",", list));
        }
    }
}
