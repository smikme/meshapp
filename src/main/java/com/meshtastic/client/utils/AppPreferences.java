package com.meshtastic.client.utils;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.platform.OsDetect;

import java.util.Collections;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
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
    public static final String KEY_ENABLE_EFFECTS = "enableEffects";
    public static final String KEY_DISABLE_EFFECTS = "disableEffects";
    public static final String KEY_SOFTWARE_RENDERING = "softwareRendering";
    public static final String KEY_CHECK_UPDATES = "checkUpdates";
    public static final String KEY_MINIMIZE_TO_TRAY = "minimizeToTray";
    public static final String KEY_JFR_DIAGNOSTICS = "jfrDiagnostics";
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_MEMORY_LIMIT_MB = "memoryLimitMb";
    public static final String KEY_APP_FONT_SIZE = "appFontSize";
    public static final String KEY_CHAT_FONT_SIZE = "chatFontSize";
    public static final String KEY_PACKET_MONITOR_WINDOW_X = "packetMonitorWindowX";
    public static final String KEY_PACKET_MONITOR_WINDOW_Y = "packetMonitorWindowY";
    public static final String KEY_PACKET_MONITOR_WINDOW_WIDTH = "packetMonitorWindowWidth";
    public static final String KEY_PACKET_MONITOR_WINDOW_HEIGHT = "packetMonitorWindowHeight";
    public static final String KEY_PACKET_MONITOR_WINDOW_MAXIMIZED = "packetMonitorWindowMaximized";
    public static final String KEY_PACKET_MONITOR_COLUMN_TIME_WIDTH = "packetMonitorColumnTimeWidth";
    public static final String KEY_PACKET_MONITOR_COLUMN_TYPE_WIDTH = "packetMonitorColumnTypeWidth";
    public static final String KEY_PACKET_MONITOR_COLUMN_TRANSPORT_WIDTH = "packetMonitorColumnTransportWidth";
    public static final String KEY_PACKET_MONITOR_COLUMN_FROM_WIDTH = "packetMonitorColumnFromWidth";
    public static final String KEY_PACKET_MONITOR_COLUMN_TO_WIDTH = "packetMonitorColumnToWidth";
    public static final String KEY_PACKET_MONITOR_COLUMN_PAYLOAD_WIDTH = "packetMonitorColumnPayloadWidth";
    public static final String KEY_LUA_DEV_WINDOW_X = "luaDevWindowX";
    public static final String KEY_LUA_DEV_WINDOW_Y = "luaDevWindowY";
    public static final String KEY_LUA_DEV_WINDOW_WIDTH = "luaDevWindowWidth";
    public static final String KEY_LUA_DEV_WINDOW_HEIGHT = "luaDevWindowHeight";
    public static final String KEY_LUA_DEV_WINDOW_MAXIMIZED = "luaDevWindowMaximized";
    public static final String KEY_LUA_DEV_FUNCTION_OUTLINE_VISIBLE = "luaDevFunctionOutlineVisible";
    public static final String KEY_LUA_DEV_FUNCTION_OUTLINE_DIVIDER = "luaDevFunctionOutlineDividerPos";
    public static final String KEY_LUA_DEV_FUNCTION_OUTLINE_WIDTH = "luaDevFunctionOutlineWidth";
    public static final String KEY_MAP_CENTER_LATITUDE = "mapCenterLatitude";
    public static final String KEY_MAP_CENTER_LONGITUDE = "mapCenterLongitude";
    public static final String KEY_MAP_ZOOM = "mapZoom";
    public static final String KEY_MAP_OFFLINE_MODE = "mapOfflineMode";
    public static final String KEY_MAP_NIGHT_MODE = "mapNightMode";
    public static final String KEY_MAP_TILE_DIRECTORY = "mapTileDirectory";
    public static final String KEY_TELEMETRY_DOCK_LAYOUT = "telemetryDockLayout";
    public static final String KEY_REMOTE_RPC_SERVER_ENABLED = "remoteRpcServerEnabled";
    public static final String KEY_REMOTE_RPC_SERVER_BIND_ADDRESS = "remoteRpcServerBindAddress";
    public static final String KEY_REMOTE_RPC_SERVER_PORT = "remoteRpcServerPort";
    public static final String KEY_REMOTE_RPC_ACCESS_KEY = "remoteRpcAccessKey";
    public static final String KEY_REMOTE_RPC_ROUTER_ENABLED = "remoteRpcRouterEnabled";
    public static final String KEY_REMOTE_RPC_ROUTER_SERVER = "remoteRpcRouterServer";
    public static final String KEY_MQTT_DOWNLINK_FILTER_MODE = "mqttDownlinkFilterMode";

    public static final int DEFAULT_MEMORY_LIMIT_MB = 512;
    public static final int MIN_MEMORY_LIMIT_MB = 128;
    public static final int MAX_MEMORY_LIMIT_MB = 65536;
    public static final int DEFAULT_REMOTE_RPC_SERVER_PORT = 44030;

    private static Preferences state;

    public enum MqttDownlinkFilterMode {
        NO_FILTER("none", "settings.mqttFilter.mode.none"),
        FILTERED("filtered", "settings.mqttFilter.mode.filtered"),
        FILTERED_WITH_ENCRYPTED(
            "filtered_with_encrypted",
            "settings.mqttFilter.mode.filteredWithEncrypted"
        );

        private final String preferenceValue;
        private final String displayKey;

        MqttDownlinkFilterMode(String preferenceValue, String displayKey) {
            this.preferenceValue = preferenceValue;
            this.displayKey = displayKey;
        }

        public String preferenceValue() {
            return preferenceValue;
        }

        public String displayKey() {
            return displayKey;
        }

        static MqttDownlinkFilterMode fromPreferenceValue(String value) {
            if (value == null || value.isBlank()) {
                return NO_FILTER;
            }
            for (MqttDownlinkFilterMode mode : values()) {
                if (
                    mode.preferenceValue.equals(value) ||
                    mode.name().equalsIgnoreCase(value)
                ) {
                    return mode;
                }
            }
            return NO_FILTER;
        }
    }

    public static void init() {
        state = Preferences.userRoot().node(PREFERENCES_ROOT_PATH);
    }

    private static Preferences state() {
        if (state == null) {
            init();
        }
        return state;
    }

    public static Preferences getState() {
        return state();
    }

    public static boolean isDarkMode() {
        return state().getBoolean(KEY_DARK_MODE, false);
    }

    public static void setDarkMode(boolean dark) {
        state().putBoolean(KEY_DARK_MODE, dark);
    }

    public static boolean isNotificationsEnabled() {
        return state().getBoolean(KEY_NOTIFICATIONS_ENABLED, true);
    }

    public static void setNotificationsEnabled(boolean enabled) {
        state().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled);
    }

    public static boolean isNativeWindow() {
        return state().getBoolean(KEY_NATIVE_WINDOW, false);
    }

    public static void setNativeWindow(boolean value) {
        state().putBoolean(KEY_NATIVE_WINDOW, value);
    }

    public static boolean isDisableTransparency() {
        return state().getBoolean(KEY_DISABLE_TRANSPARENCY, false);
    }

    public static void setDisableTransparency(boolean value) {
        state().putBoolean(KEY_DISABLE_TRANSPARENCY, value);
    }

    public static boolean isVisualEffectsEnabled() {
        String value = state().get(KEY_ENABLE_EFFECTS, null);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }

        String legacyDisabledValue = state().get(KEY_DISABLE_EFFECTS, null);
        if (legacyDisabledValue != null) {
            return !Boolean.parseBoolean(legacyDisabledValue);
        }

        return false;
    }

    public static boolean isVisualEffectsEnabledEffective() {
        return isVisualEffectsEnabled() && !OsDetect.isWindows10();
    }

    public static void setVisualEffectsEnabled(boolean value) {
        state().putBoolean(KEY_ENABLE_EFFECTS, value);
    }

    public static boolean isDisableEffects() {
        return !isVisualEffectsEnabled();
    }

    public static boolean isDisableEffectsEffective() {
        return !isVisualEffectsEnabledEffective();
    }

    public static void setDisableEffects(boolean value) {
        setVisualEffectsEnabled(!value);
    }

    public static boolean isSoftwareRendering() {
        return state().getBoolean(KEY_SOFTWARE_RENDERING, false);
    }

    public static void setSoftwareRendering(boolean value) {
        state().putBoolean(KEY_SOFTWARE_RENDERING, value);
    }

    public static boolean isCheckUpdates() {
        return state().getBoolean(KEY_CHECK_UPDATES, true);
    }

    public static void setCheckUpdates(boolean value) {
        state().putBoolean(KEY_CHECK_UPDATES, value);
    }

    public static boolean isMinimizeToTray() {
        return state().getBoolean(KEY_MINIMIZE_TO_TRAY, false);
    }

    public static void setMinimizeToTray(boolean value) {
        state().putBoolean(KEY_MINIMIZE_TO_TRAY, value);
    }

    public static boolean isJfrDiagnosticsEnabled() {
        return state().getBoolean(KEY_JFR_DIAGNOSTICS, false);
    }

    public static void setJfrDiagnosticsEnabled(boolean value) {
        state().putBoolean(KEY_JFR_DIAGNOSTICS, value);
    }

    public static boolean isRemoteRpcServerEnabled() {
        return state().getBoolean(KEY_REMOTE_RPC_SERVER_ENABLED, false);
    }

    public static void setRemoteRpcServerEnabled(boolean enabled) {
        state().putBoolean(KEY_REMOTE_RPC_SERVER_ENABLED, enabled);
        flushState();
    }

    public static String getRemoteRpcServerBindAddress() {
        return state().get(KEY_REMOTE_RPC_SERVER_BIND_ADDRESS, "127.0.0.1");
    }

    public static void setRemoteRpcServerBindAddress(String value) {
        state().put(KEY_REMOTE_RPC_SERVER_BIND_ADDRESS,
                value == null || value.isBlank() ? "127.0.0.1" : value.trim());
        flushState();
    }

    public static int getRemoteRpcServerPort() {
        int port = state().getInt(KEY_REMOTE_RPC_SERVER_PORT, DEFAULT_REMOTE_RPC_SERVER_PORT);
        return port >= 1 && port <= 65_535 ? port : DEFAULT_REMOTE_RPC_SERVER_PORT;
    }

    public static void setRemoteRpcServerPort(int port) {
        state().putInt(KEY_REMOTE_RPC_SERVER_PORT,
                port >= 1 && port <= 65_535 ? port : DEFAULT_REMOTE_RPC_SERVER_PORT);
        flushState();
    }

    public static String getRemoteRpcAccessKey() {
        return state().get(KEY_REMOTE_RPC_ACCESS_KEY, "");
    }

    public static void setRemoteRpcAccessKey(String value) {
        if (value == null || value.isBlank()) {
            state().remove(KEY_REMOTE_RPC_ACCESS_KEY);
        } else {
            state().put(KEY_REMOTE_RPC_ACCESS_KEY, value.trim());
        }
        flushState();
    }

    public static boolean isRemoteRpcRouterEnabled() {
        return state().getBoolean(KEY_REMOTE_RPC_ROUTER_ENABLED, false);
    }

    public static void setRemoteRpcRouterEnabled(boolean enabled) {
        state().putBoolean(KEY_REMOTE_RPC_ROUTER_ENABLED, enabled);
        flushState();
    }

    public static String getRemoteRpcRouterServer() {
        return state().get(KEY_REMOTE_RPC_ROUTER_SERVER, ConnectionEntry.CLOUD_RPC_ROUTER_SERVER);
    }

    public static void setRemoteRpcRouterServer(String value) {
        if (value == null || value.isBlank()) {
            state().remove(KEY_REMOTE_RPC_ROUTER_SERVER);
        } else {
            state().put(KEY_REMOTE_RPC_ROUTER_SERVER, value.trim());
        }
        flushState();
    }

    public static MqttDownlinkFilterMode getMqttDownlinkFilterMode() {
        return MqttDownlinkFilterMode.fromPreferenceValue(
            state().get(
                KEY_MQTT_DOWNLINK_FILTER_MODE,
                MqttDownlinkFilterMode.NO_FILTER.preferenceValue()
            )
        );
    }

    public static void setMqttDownlinkFilterMode(MqttDownlinkFilterMode mode) {
        MqttDownlinkFilterMode safeMode = mode != null ? mode : MqttDownlinkFilterMode.NO_FILTER;
        state().put(KEY_MQTT_DOWNLINK_FILTER_MODE, safeMode.preferenceValue());
        flushState();
    }

    public static String getLanguageTag() {
        return state().get(KEY_LANGUAGE, "system");
    }

    public static void setLanguageTag(String value) {
        state().put(KEY_LANGUAGE, value == null || value.isBlank() ? "system" : value.trim());
    }

    public static int getMemoryLimitMb() {
        return clampMemoryLimitMb(
            state().getInt(KEY_MEMORY_LIMIT_MB, DEFAULT_MEMORY_LIMIT_MB)
        );
    }

    public static void setMemoryLimitMb(int value) {
        state().putInt(KEY_MEMORY_LIMIT_MB, clampMemoryLimitMb(value));
        flushState();
    }

    public static int clampMemoryLimitMb(int value) {
        return Math.max(
            MIN_MEMORY_LIMIT_MB,
            Math.min(MAX_MEMORY_LIMIT_MB, value)
        );
    }

    public static int getAppFontSize() {
        return state().getInt(KEY_APP_FONT_SIZE, 13);
    }

    public static void setAppFontSize(int value) {
        state().putInt(KEY_APP_FONT_SIZE, value);
    }

    public static int getChatFontSize() {
        return state().getInt(KEY_CHAT_FONT_SIZE, 13);
    }

    public static void setChatFontSize(int value) {
        state().putInt(KEY_CHAT_FONT_SIZE, value);
    }

    public static List<String> getRecentSearch(boolean favorite) {
        String stringArr = state().get(favorite ? KEY_RECENT_SEARCH_FAVORITE : KEY_RECENT_SEARCH, null);
        if (stringArr == null || stringArr.trim().isEmpty()) { return Collections.emptyList(); }
        return List.of(stringArr.trim().split(","));
    }

    // ==================== Window Bounds ====================

    public static boolean hasWindowBounds() {
        return !Double.isNaN(state().getDouble(KEY_WINDOW_WIDTH, Double.NaN));
    }

    public static double getWindowX() { return state().getDouble(KEY_WINDOW_X, Double.NaN); }
    public static double getWindowY() { return state().getDouble(KEY_WINDOW_Y, Double.NaN); }
    public static double getWindowWidth() { return state().getDouble(KEY_WINDOW_WIDTH, Double.NaN); }
    public static double getWindowHeight() { return state().getDouble(KEY_WINDOW_HEIGHT, Double.NaN); }
    public static boolean isWindowMaximized() { return state().getBoolean(KEY_WINDOW_MAXIMIZED, false); }

    public static void saveWindowBounds(double x, double y, double w, double h, boolean maximized) {
        state().putDouble(KEY_WINDOW_X, x);
        state().putDouble(KEY_WINDOW_Y, y);
        state().putDouble(KEY_WINDOW_WIDTH, w);
        state().putDouble(KEY_WINDOW_HEIGHT, h);
        state().putBoolean(KEY_WINDOW_MAXIMIZED, maximized);
        flushState();
    }

    /**
     * @return {@code true} if the LoRa packet monitor already has saved bounds
     */
    public static boolean hasPacketMonitorWindowBounds() {
        return !Double.isNaN(state().getDouble(KEY_PACKET_MONITOR_WINDOW_WIDTH, Double.NaN));
    }

    /**
     * @return saved X coordinate for the LoRa packet monitor window, or {@link Double#NaN}
     */
    public static double getPacketMonitorWindowX() {
        return state().getDouble(KEY_PACKET_MONITOR_WINDOW_X, Double.NaN);
    }

    /**
     * @return saved Y coordinate for the LoRa packet monitor window, or {@link Double#NaN}
     */
    public static double getPacketMonitorWindowY() {
        return state().getDouble(KEY_PACKET_MONITOR_WINDOW_Y, Double.NaN);
    }

    /**
     * @return saved width for the LoRa packet monitor window, or {@link Double#NaN}
     */
    public static double getPacketMonitorWindowWidth() {
        return state().getDouble(KEY_PACKET_MONITOR_WINDOW_WIDTH, Double.NaN);
    }

    /**
     * @return saved height for the LoRa packet monitor window, or {@link Double#NaN}
     */
    public static double getPacketMonitorWindowHeight() {
        return state().getDouble(KEY_PACKET_MONITOR_WINDOW_HEIGHT, Double.NaN);
    }

    /**
     * @return saved maximized state for the LoRa packet monitor window
     */
    public static boolean isPacketMonitorWindowMaximized() {
        return state().getBoolean(KEY_PACKET_MONITOR_WINDOW_MAXIMIZED, false);
    }

    /**
     * Saves the LoRa packet monitor window position, size, and maximized state.
 *
     * @param x         window X coordinate
     * @param y         window Y coordinate
     * @param w         window width
     * @param h         window height
     * @param maximized whether the window is maximized
     */
    public static void savePacketMonitorWindowBounds(double x, double y, double w, double h, boolean maximized) {
        state().putDouble(KEY_PACKET_MONITOR_WINDOW_X, x);
        state().putDouble(KEY_PACKET_MONITOR_WINDOW_Y, y);
        state().putDouble(KEY_PACKET_MONITOR_WINDOW_WIDTH, w);
        state().putDouble(KEY_PACKET_MONITOR_WINDOW_HEIGHT, h);
        state().putBoolean(KEY_PACKET_MONITOR_WINDOW_MAXIMIZED, maximized);
        flushState();
    }

    /**
     * @return {@code true} if the MeshApp IDE window already has saved bounds
     */
    public static boolean hasLuaDevWindowBounds() {
        return !Double.isNaN(state().getDouble(KEY_LUA_DEV_WINDOW_WIDTH, Double.NaN));
    }

    public static double getLuaDevWindowX() { return state().getDouble(KEY_LUA_DEV_WINDOW_X, Double.NaN); }
    public static double getLuaDevWindowY() { return state().getDouble(KEY_LUA_DEV_WINDOW_Y, Double.NaN); }
    public static double getLuaDevWindowWidth() { return state().getDouble(KEY_LUA_DEV_WINDOW_WIDTH, Double.NaN); }
    public static double getLuaDevWindowHeight() { return state().getDouble(KEY_LUA_DEV_WINDOW_HEIGHT, Double.NaN); }
    public static boolean isLuaDevWindowMaximized() {
        return state().getBoolean(KEY_LUA_DEV_WINDOW_MAXIMIZED, false);
    }

    /**
     * Saves the MeshApp IDE window position, size, and maximized state.
     */
    public static void saveLuaDevWindowBounds(double x, double y, double w, double h, boolean maximized) {
        state().putDouble(KEY_LUA_DEV_WINDOW_X, x);
        state().putDouble(KEY_LUA_DEV_WINDOW_Y, y);
        state().putDouble(KEY_LUA_DEV_WINDOW_WIDTH, w);
        state().putDouble(KEY_LUA_DEV_WINDOW_HEIGHT, h);
        state().putBoolean(KEY_LUA_DEV_WINDOW_MAXIMIZED, maximized);
        flushState();
    }

    // ==================== Map ====================

    /**
     * Returns the saved map center latitude.
     */
    public static double getMapCenterLatitude() {
        return state().getDouble(KEY_MAP_CENTER_LATITUDE, 20);
    }

    /**
     * Returns the saved map center longitude.
     */
    public static double getMapCenterLongitude() {
        return state().getDouble(KEY_MAP_CENTER_LONGITUDE, 0);
    }

    /**
     * Returns the saved map zoom level.
     */
    public static int getMapZoom() {
        return state().getInt(KEY_MAP_ZOOM, 2);
    }

    /**
     * Checks whether a user-selected map center has already been saved.
     */
    public static boolean hasMapView() {
        return !Double.isNaN(state().getDouble(KEY_MAP_CENTER_LATITUDE, Double.NaN))
                && !Double.isNaN(state().getDouble(KEY_MAP_CENTER_LONGITUDE, Double.NaN));
    }

    /**
     * Saves the current map center and zoom level.
     */
    public static void saveMapView(double latitude, double longitude, int zoom) {
        state().putDouble(KEY_MAP_CENTER_LATITUDE, latitude);
        state().putDouble(KEY_MAP_CENTER_LONGITUDE, longitude);
        state().putInt(KEY_MAP_ZOOM, zoom);
        flushState();
    }

    /**
     * Returns whether the map is restricted to local tiles.
     */
    public static boolean isMapOfflineMode() {
        return state().getBoolean(KEY_MAP_OFFLINE_MODE, false);
    }

    /**
     * Saves whether the map should use only local tiles.
     */
    public static void setMapOfflineMode(boolean offline) {
        state().putBoolean(KEY_MAP_OFFLINE_MODE, offline);
        flushState();
    }

    /**
     * Returns whether map night mode is enabled.
     */
    public static boolean isMapNightMode() {
        return state().getBoolean(KEY_MAP_NIGHT_MODE, false);
    }

    /**
     * Saves whether map night mode is enabled.
     */
    public static void setMapNightMode(boolean nightMode) {
        state().putBoolean(KEY_MAP_NIGHT_MODE, nightMode);
        flushState();
    }

    /**
     * Returns the saved external directory for offline map tiles.
     */
    public static String getMapTileDirectory() {
        return state().get(KEY_MAP_TILE_DIRECTORY, "");
    }

    /**
     * Saves the external offline-tile directory, or clears the setting when the value is blank.
     */
    public static void setMapTileDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            state().remove(KEY_MAP_TILE_DIRECTORY);
        } else {
            state().put(KEY_MAP_TILE_DIRECTORY, directory);
        }
        flushState();
    }

    public static String getTelemetryDockLayout() {
        return state().get(KEY_TELEMETRY_DOCK_LAYOUT, "");
    }

    public static void saveTelemetryDockLayout(String layout) {
        if (layout == null || layout.isBlank()) {
            state().remove(KEY_TELEMETRY_DOCK_LAYOUT);
        } else {
            state().put(KEY_TELEMETRY_DOCK_LAYOUT, layout);
        }
        flushState();
    }

    private static void flushState() {
        try {
            state().flush();
        } catch (BackingStoreException ignored) {
            // Best-effort persist for shutdown-sensitive window state.
        }
    }

    // ==================== Recent Search ====================

    // ==================== Nodes Sort & Filter ====================

    public static final String KEY_NODES_SORT = "nodesSort";
    public static final String KEY_NODES_FILTER_UNKNOWN = "nodesFilterUnknown";
    public static final String KEY_NODES_FILTER_DETAILS = "nodesFilterDetails";
    public static final String KEY_NODES_FILTER_HIDE_OFFLINE = "nodesFilterHideOffline";
    public static final String KEY_NODES_FILTER_FAVORITES = "nodesFilterFavorites";
    public static final String KEY_NODES_FILTER_DIRECT = "nodesFilterDirect";
    public static final String KEY_NODES_FILTER_IGNORED = "nodesFilterIgnored";
    public static final String KEY_MAP_FILTER_UNKNOWN = "mapFilterUnknown";
    public static final String KEY_MAP_FILTER_HIDE_OFFLINE = "mapFilterHideOffline";
    public static final String KEY_MAP_FILTER_FAVORITES = "mapFilterFavorites";
    public static final String KEY_MAP_FILTER_DIRECT = "mapFilterDirect";
    public static final String KEY_MAP_FILTER_IGNORED = "mapFilterIgnored";

    public static String getNodesSort() { return state().get(KEY_NODES_SORT, "LAST_HEARD_NEW"); }
    public static void setNodesSort(String sort) { state().put(KEY_NODES_SORT, sort); }

    public static boolean isNodesFilterUnknown() { return state().getBoolean(KEY_NODES_FILTER_UNKNOWN, false); }
    public static void setNodesFilterUnknown(boolean v) { state().putBoolean(KEY_NODES_FILTER_UNKNOWN, v); }

    public static boolean isNodesFilterDetails() { return state().getBoolean(KEY_NODES_FILTER_DETAILS, false); }
    public static void setNodesFilterDetails(boolean v) { state().putBoolean(KEY_NODES_FILTER_DETAILS, v); }

    public static boolean isNodesFilterHideOffline() { return state().getBoolean(KEY_NODES_FILTER_HIDE_OFFLINE, false); }
    public static void setNodesFilterHideOffline(boolean v) { state().putBoolean(KEY_NODES_FILTER_HIDE_OFFLINE, v); }

    public static boolean isNodesFilterFavorites() { return state().getBoolean(KEY_NODES_FILTER_FAVORITES, false); }
    public static void setNodesFilterFavorites(boolean v) { state().putBoolean(KEY_NODES_FILTER_FAVORITES, v); }

    public static boolean isNodesFilterDirect() { return state().getBoolean(KEY_NODES_FILTER_DIRECT, false); }
    public static void setNodesFilterDirect(boolean v) { state().putBoolean(KEY_NODES_FILTER_DIRECT, v); }

    public static boolean isNodesFilterIgnored() { return state().getBoolean(KEY_NODES_FILTER_IGNORED, false); }
    public static void setNodesFilterIgnored(boolean v) { state().putBoolean(KEY_NODES_FILTER_IGNORED, v); }

    public static boolean isMapFilterUnknown() { return state().getBoolean(KEY_MAP_FILTER_UNKNOWN, false); }
    public static void setMapFilterUnknown(boolean v) { state().putBoolean(KEY_MAP_FILTER_UNKNOWN, v); }

    public static boolean isMapFilterHideOffline() { return state().getBoolean(KEY_MAP_FILTER_HIDE_OFFLINE, false); }
    public static void setMapFilterHideOffline(boolean v) { state().putBoolean(KEY_MAP_FILTER_HIDE_OFFLINE, v); }

    public static boolean isMapFilterFavorites() { return state().getBoolean(KEY_MAP_FILTER_FAVORITES, false); }
    public static void setMapFilterFavorites(boolean v) { state().putBoolean(KEY_MAP_FILTER_FAVORITES, v); }

    public static boolean isMapFilterDirect() { return state().getBoolean(KEY_MAP_FILTER_DIRECT, false); }
    public static void setMapFilterDirect(boolean v) { state().putBoolean(KEY_MAP_FILTER_DIRECT, v); }

    public static boolean isMapFilterIgnored() { return state().getBoolean(KEY_MAP_FILTER_IGNORED, false); }
    public static void setMapFilterIgnored(boolean v) { state().putBoolean(KEY_MAP_FILTER_IGNORED, v); }

    // ==================== SplitPane Divider Positions ====================

    public static final String KEY_CHAT_DIVIDER = "chatDividerPos";
    public static final String KEY_CHAT_LIST_WIDTH = "chatListWidth";
    public static final String KEY_NODES_DIVIDER = "nodesDividerPos";
    public static final String KEY_PACKET_MONITOR_DIVIDER = "packetMonitorDividerPos";
    public static final String KEY_LUA_DEV_MAIN_DIVIDER = "luaDevMainDividerPos";
    public static final String KEY_LUA_DEV_EDITOR_DIVIDER = "luaDevEditorDividerPos";
    public static final String KEY_LUA_DEV_INFO_DIVIDER = "luaDevInfoDividerPos";
    private static final String NODE_CHAT_SCROLL = "chatScroll";
    private static final String NODE_CHAT_NOTIFICATIONS = "chatNotifications";
    private static final String NODE_CHAT_SELECTION = "chatSelection";

    public static double getChatDividerPos() { return state().getDouble(KEY_CHAT_DIVIDER, 0.35); }
    public static void setChatDividerPos(double pos) { state().putDouble(KEY_CHAT_DIVIDER, pos); }

    public static double getChatListWidth(double fallback) {
        double width = state().getDouble(KEY_CHAT_LIST_WIDTH, fallback);
        return Double.isFinite(width) && width > 0 ? width : fallback;
    }

    public static void setChatListWidth(double width) {
        if (Double.isFinite(width) && width > 0) {
            state().putDouble(KEY_CHAT_LIST_WIDTH, width);
        }
    }

    public static double getNodesDividerPos() { return state().getDouble(KEY_NODES_DIVIDER, 0.38); }
    public static void setNodesDividerPos(double pos) { state().putDouble(KEY_NODES_DIVIDER, pos); }

    /**
     * @return saved vertical divider position for the LoRa packet monitor window
     */
    public static double getPacketMonitorDividerPos() { return state().getDouble(KEY_PACKET_MONITOR_DIVIDER, 0.58); }

    /**
     * Saves the vertical divider position for the LoRa packet monitor window.
 *
     * @param pos divider position in the {@code 0..1} range
     */
    public static void setPacketMonitorDividerPos(double pos) { state().putDouble(KEY_PACKET_MONITOR_DIVIDER, pos); }

    public static double getLuaDevMainDividerPos() {
        return state().getDouble(KEY_LUA_DEV_MAIN_DIVIDER, 0.76);
    }

    public static void setLuaDevMainDividerPos(double pos) {
        state().putDouble(KEY_LUA_DEV_MAIN_DIVIDER, normalizeDividerPosition(pos, 0.76));
    }

    public static double getLuaDevEditorDividerPos() {
        return state().getDouble(KEY_LUA_DEV_EDITOR_DIVIDER, 0.72);
    }

    public static void setLuaDevEditorDividerPos(double pos) {
        state().putDouble(KEY_LUA_DEV_EDITOR_DIVIDER, normalizeDividerPosition(pos, 0.72));
    }

    public static double getLuaDevInfoDividerPos() {
        return state().getDouble(KEY_LUA_DEV_INFO_DIVIDER, 0.62);
    }

    public static void setLuaDevInfoDividerPos(double pos) {
        state().putDouble(KEY_LUA_DEV_INFO_DIVIDER, normalizeDividerPosition(pos, 0.62));
    }

    /**
     * @return whether the MeshApp IDE function outline pane should be restored as visible
     */
    public static boolean isLuaDevFunctionOutlineVisible() {
        return state().getBoolean(KEY_LUA_DEV_FUNCTION_OUTLINE_VISIBLE, true);
    }

    /**
     * Persists the visibility state of the MeshApp IDE function outline pane immediately.
     *
     * @param visible {@code true} to restore the function outline as expanded
     */
    public static void setLuaDevFunctionOutlineVisible(boolean visible) {
        state().putBoolean(KEY_LUA_DEV_FUNCTION_OUTLINE_VISIBLE, visible);
        flushState();
    }

    /**
     * @return saved divider position between the MeshApp IDE function outline and code editor
     */
    public static double getLuaDevFunctionOutlineDividerPos() {
        return state().getDouble(KEY_LUA_DEV_FUNCTION_OUTLINE_DIVIDER, 0.22);
    }

    /**
     * Saves the divider position between the MeshApp IDE function outline and code editor.
     *
     * @param pos divider position in the {@code 0..1} range
     */
    public static void setLuaDevFunctionOutlineDividerPos(double pos) {
        state().putDouble(KEY_LUA_DEV_FUNCTION_OUTLINE_DIVIDER, normalizeDividerPosition(pos, 0.22));
    }

    /**
     * @return saved function outline pane width in pixels
     */
    public static double getLuaDevFunctionOutlineWidth() {
        return state().getDouble(KEY_LUA_DEV_FUNCTION_OUTLINE_WIDTH, 230.0);
    }

    /**
     * Saves the last measured function outline pane width.
     *
     * @param width pane width in pixels; non-finite and non-positive values are ignored
     */
    public static void setLuaDevFunctionOutlineWidth(double width) {
        if (Double.isFinite(width) && width > 0) {
            state().putDouble(KEY_LUA_DEV_FUNCTION_OUTLINE_WIDTH, width);
        }
    }

    /**
     * Saves the MeshApp IDE SplitPane layout and flushes it to the backing store immediately.
     *
     * @param mainPos divider between editor area and right-side state panes
     * @param editorPos divider between code editor and console
     * @param infoPos divider between variables and selected-script KV
     */
    public static void saveLuaDevDividerPositions(double mainPos, double editorPos, double infoPos) {
        setLuaDevMainDividerPos(mainPos);
        setLuaDevEditorDividerPos(editorPos);
        setLuaDevInfoDividerPos(infoPos);
        flushState();
    }

    /**
     * Saves the complete MeshApp IDE layout, including the function outline pane.
     *
     * @param mainPos divider between editor area and right-side state panes
     * @param editorPos divider between code editor and console
     * @param infoPos divider between variables and selected-script KV
     * @param functionOutlinePos divider between function outline and code editor
     * @param functionOutlineWidth measured function outline pane width in pixels
     * @param functionOutlineVisible whether the function outline pane is expanded
     */
    public static void saveLuaDevDividerPositions(double mainPos, double editorPos, double infoPos,
                                                  double functionOutlinePos, double functionOutlineWidth,
                                                  boolean functionOutlineVisible) {
        setLuaDevMainDividerPos(mainPos);
        setLuaDevEditorDividerPos(editorPos);
        setLuaDevInfoDividerPos(infoPos);
        setLuaDevFunctionOutlineDividerPos(functionOutlinePos);
        setLuaDevFunctionOutlineWidth(functionOutlineWidth);
        state().putBoolean(KEY_LUA_DEV_FUNCTION_OUTLINE_VISIBLE, functionOutlineVisible);
        flushState();
    }

    private static double normalizeDividerPosition(double pos, double fallback) {
        if (!Double.isFinite(pos)) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, pos));
    }

    /**
     * Returns the saved LoRa monitor table-column width.
     * If the user has not resized the column yet, the supplied default is returned.
 *
     * @param key          preference key for the specific column
     * @param defaultWidth initial width used as a fallback
     * @return saved column width, or the initial width
     */
    public static double getPacketMonitorColumnWidth(String key, double defaultWidth) {
        return state().getDouble(key, defaultWidth);
    }

    /**
     * Saves the current LoRa monitor table-column width.
 *
     * @param key   preference key for the specific column
     * @param width actual column width in pixels
     */
    public static void setPacketMonitorColumnWidth(String key, double width) {
        state().putDouble(key, width);
    }

    public static final class ChatScrollState {
        private final long anchorDbId;
        private final double anchorOffset;
        private final boolean atBottom;

        public ChatScrollState(long anchorDbId, double anchorOffset, boolean atBottom) {
            this.anchorDbId = anchorDbId;
            this.anchorOffset = anchorOffset;
            this.atBottom = atBottom;
        }

        public long getAnchorDbId() { return anchorDbId; }
        public double getAnchorOffset() { return anchorOffset; }
        public boolean isAtBottom() { return atBottom; }
    }

    public static void saveChatScrollState(String ownerId, String chatId,
                                           long anchorDbId, double anchorOffset, boolean atBottom) {
        chatScrollNode().put(composeChatScrollKey(ownerId, chatId),
                anchorDbId + "|" + anchorOffset + "|" + (atBottom ? "1" : "0"));
    }

    public static ChatScrollState loadChatScrollState(String ownerId, String chatId) {
        String raw = chatScrollNode().get(composeChatScrollKey(ownerId, chatId), null);
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String[] parts = raw.split("\\|", -1);
        if (parts.length != 3) {
            return null;
        }

        try {
            long anchorDbId = Long.parseLong(parts[0]);
            double anchorOffset = Double.parseDouble(parts[1]);
            boolean atBottom = "1".equals(parts[2]);
            return new ChatScrollState(anchorDbId, anchorOffset, atBottom);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static void removeChatScrollState(String ownerId, String chatId) {
        chatScrollNode().remove(composeChatScrollKey(ownerId, chatId));
    }

    public static void saveSelectedChat(String connectionId, String selectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        if (selectionId == null || selectionId.isBlank()) {
            removeSelectedChat(connectionId);
            return;
        }
        chatSelectionNode().put(connectionId, selectionId.trim());
        flushState();
    }

    public static String loadSelectedChat(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return null;
        }
        String value = chatSelectionNode().get(connectionId, null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static void removeSelectedChat(String connectionId) {
        if (connectionId != null && !connectionId.isBlank()) {
            chatSelectionNode().remove(connectionId);
            flushState();
        }
    }

    public static boolean isChatMuted(String ownerId, String chatId) {
        return chatNotificationsNode().getBoolean(composeChatScrollKey(ownerId, chatId), false);
    }

    public static boolean isChatMuted(String ownerId, String chatType, String chatKey) {
        return isChatMuted(ownerId, composeChatPreferenceId(chatType, chatKey));
    }

    public static void setChatMuted(String ownerId, String chatId, boolean muted) {
        chatNotificationsNode().putBoolean(composeChatScrollKey(ownerId, chatId), muted);
    }

    public static void setChatMuted(String ownerId, String chatType, String chatKey, boolean muted) {
        setChatMuted(ownerId, composeChatPreferenceId(chatType, chatKey), muted);
    }

    public static String composeChatPreferenceId(String chatType, String chatKey) {
        if ("channel".equals(chatType)) {
            return "channel:" + (chatKey != null ? chatKey : "");
        }
        if ("dm".equals(chatType)) {
            return "dm:" + (chatKey != null ? chatKey : "");
        }
        return (chatType != null ? chatType : "") + ":" + (chatKey != null ? chatKey : "");
    }

    private static Preferences chatScrollNode() {
        return state().node(NODE_CHAT_SCROLL);
    }

    private static Preferences chatNotificationsNode() {
        return state().node(NODE_CHAT_NOTIFICATIONS);
    }

    private static Preferences chatSelectionNode() {
        return state().node(NODE_CHAT_SELECTION);
    }

    private static String composeChatScrollKey(String ownerId, String chatId) {
        return (ownerId != null ? ownerId : "") + "|" + (chatId != null ? chatId : "");
    }

}
