package com.meshtastic.client.utils;

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
    public static final String KEY_DISABLE_EFFECTS = "disableEffects";
    public static final String KEY_SOFTWARE_RENDERING = "softwareRendering";
    public static final String KEY_CHECK_UPDATES = "checkUpdates";
    public static final String KEY_MINIMIZE_TO_TRAY = "minimizeToTray";
    public static final String KEY_JFR_DIAGNOSTICS = "jfrDiagnostics";
    public static final String KEY_LANGUAGE = "language";
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
    public static final String KEY_MAP_CENTER_LATITUDE = "mapCenterLatitude";
    public static final String KEY_MAP_CENTER_LONGITUDE = "mapCenterLongitude";
    public static final String KEY_MAP_ZOOM = "mapZoom";
    public static final String KEY_MAP_OFFLINE_MODE = "mapOfflineMode";
    public static final String KEY_MAP_NIGHT_MODE = "mapNightMode";
    public static final String KEY_MAP_TILE_DIRECTORY = "mapTileDirectory";

    private static Preferences state;

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

    public static boolean isDisableEffects() {
        return state().getBoolean(KEY_DISABLE_EFFECTS, false);
    }

    public static boolean isDisableEffectsEffective() {
        return isDisableEffects() || OsDetect.isWindows10();
    }

    public static void setDisableEffects(boolean value) {
        state().putBoolean(KEY_DISABLE_EFFECTS, value);
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

    public static String getLanguageTag() {
        return state().get(KEY_LANGUAGE, "system");
    }

    public static void setLanguageTag(String value) {
        state().put(KEY_LANGUAGE, value == null || value.isBlank() ? "system" : value.trim());
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
     * @return {@code true}, если для окна мониторинга LoRa-пакетов уже сохранены координаты и размер
     */
    public static boolean hasPacketMonitorWindowBounds() {
        return !Double.isNaN(state().getDouble(KEY_PACKET_MONITOR_WINDOW_WIDTH, Double.NaN));
    }

    /**
     * @return сохранённая X-координата окна мониторинга LoRa-пакетов или {@link Double#NaN}
     */
    public static double getPacketMonitorWindowX() {
        return state().getDouble(KEY_PACKET_MONITOR_WINDOW_X, Double.NaN);
    }

    /**
     * @return сохранённая Y-координата окна мониторинга LoRa-пакетов или {@link Double#NaN}
     */
    public static double getPacketMonitorWindowY() {
        return state().getDouble(KEY_PACKET_MONITOR_WINDOW_Y, Double.NaN);
    }

    /**
     * @return сохранённая ширина окна мониторинга LoRa-пакетов или {@link Double#NaN}
     */
    public static double getPacketMonitorWindowWidth() {
        return state().getDouble(KEY_PACKET_MONITOR_WINDOW_WIDTH, Double.NaN);
    }

    /**
     * @return сохранённая высота окна мониторинга LoRa-пакетов или {@link Double#NaN}
     */
    public static double getPacketMonitorWindowHeight() {
        return state().getDouble(KEY_PACKET_MONITOR_WINDOW_HEIGHT, Double.NaN);
    }

    /**
     * @return сохранённое состояние максимизации окна мониторинга LoRa-пакетов
     */
    public static boolean isPacketMonitorWindowMaximized() {
        return state().getBoolean(KEY_PACKET_MONITOR_WINDOW_MAXIMIZED, false);
    }

    /**
     * Сохраняет положение, размер и состояние максимизации окна мониторинга LoRa-пакетов.
     *
     * @param x         X-координата окна
     * @param y         Y-координата окна
     * @param w         ширина окна
     * @param h         высота окна
     * @param maximized признак максимизации
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
     * @return {@code true}, если для окна MeshApp IDE уже сохранены координаты и размер
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
     * Сохраняет положение, размер и состояние максимизации окна MeshApp IDE.
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
     * Возвращает сохранённую широту центра карты.
     */
    public static double getMapCenterLatitude() {
        return state().getDouble(KEY_MAP_CENTER_LATITUDE, 20);
    }

    /**
     * Возвращает сохранённую долготу центра карты.
     */
    public static double getMapCenterLongitude() {
        return state().getDouble(KEY_MAP_CENTER_LONGITUDE, 0);
    }

    /**
     * Возвращает сохранённый масштаб карты.
     */
    public static int getMapZoom() {
        return state().getInt(KEY_MAP_ZOOM, 2);
    }

    /**
     * Проверяет, был ли пользовательский центр карты уже сохранён.
     */
    public static boolean hasMapView() {
        return !Double.isNaN(state().getDouble(KEY_MAP_CENTER_LATITUDE, Double.NaN))
                && !Double.isNaN(state().getDouble(KEY_MAP_CENTER_LONGITUDE, Double.NaN));
    }

    /**
     * Сохраняет текущий центр и масштаб карты.
     */
    public static void saveMapView(double latitude, double longitude, int zoom) {
        state().putDouble(KEY_MAP_CENTER_LATITUDE, latitude);
        state().putDouble(KEY_MAP_CENTER_LONGITUDE, longitude);
        state().putInt(KEY_MAP_ZOOM, zoom);
        flushState();
    }

    /**
     * Возвращает состояние режима «только локальные тайлы».
     */
    public static boolean isMapOfflineMode() {
        return state().getBoolean(KEY_MAP_OFFLINE_MODE, false);
    }

    /**
     * Сохраняет состояние режима «только локальные тайлы».
     */
    public static void setMapOfflineMode(boolean offline) {
        state().putBoolean(KEY_MAP_OFFLINE_MODE, offline);
        flushState();
    }

    /**
     * Возвращает состояние ночного режима карты.
     */
    public static boolean isMapNightMode() {
        return state().getBoolean(KEY_MAP_NIGHT_MODE, false);
    }

    /**
     * Сохраняет состояние ночного режима карты.
     */
    public static void setMapNightMode(boolean nightMode) {
        state().putBoolean(KEY_MAP_NIGHT_MODE, nightMode);
        flushState();
    }

    /**
     * Возвращает сохранённый внешний каталог оффлайн-тайлов.
     */
    public static String getMapTileDirectory() {
        return state().get(KEY_MAP_TILE_DIRECTORY, "");
    }

    /**
     * Сохраняет внешний каталог оффлайн-тайлов или очищает настройку при пустом значении.
     */
    public static void setMapTileDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            state().remove(KEY_MAP_TILE_DIRECTORY);
        } else {
            state().put(KEY_MAP_TILE_DIRECTORY, directory);
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

    // ==================== SplitPane Divider Positions ====================

    public static final String KEY_CHAT_DIVIDER = "chatDividerPos";
    public static final String KEY_NODES_DIVIDER = "nodesDividerPos";
    public static final String KEY_PACKET_MONITOR_DIVIDER = "packetMonitorDividerPos";
    public static final String KEY_LUA_DEV_MAIN_DIVIDER = "luaDevMainDividerPos";
    public static final String KEY_LUA_DEV_EDITOR_DIVIDER = "luaDevEditorDividerPos";
    public static final String KEY_LUA_DEV_INFO_DIVIDER = "luaDevInfoDividerPos";
    private static final String NODE_CHAT_SCROLL = "chatScroll";
    private static final String NODE_CHAT_NOTIFICATIONS = "chatNotifications";

    public static double getChatDividerPos() { return state().getDouble(KEY_CHAT_DIVIDER, 0.35); }
    public static void setChatDividerPos(double pos) { state().putDouble(KEY_CHAT_DIVIDER, pos); }

    public static double getNodesDividerPos() { return state().getDouble(KEY_NODES_DIVIDER, 0.38); }
    public static void setNodesDividerPos(double pos) { state().putDouble(KEY_NODES_DIVIDER, pos); }

    /**
     * @return сохранённая позиция вертикального разделителя окна мониторинга LoRa-пакетов
     */
    public static double getPacketMonitorDividerPos() { return state().getDouble(KEY_PACKET_MONITOR_DIVIDER, 0.58); }

    /**
     * Сохраняет позицию вертикального разделителя окна мониторинга LoRa-пакетов.
     *
     * @param pos позиция разделителя в диапазоне {@code 0..1}
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
     * Сохраняет компоновку SplitPane в окне MeshApp IDE и сразу сбрасывает её в backing store.
     */
    public static void saveLuaDevDividerPositions(double mainPos, double editorPos, double infoPos) {
        setLuaDevMainDividerPos(mainPos);
        setLuaDevEditorDividerPos(editorPos);
        setLuaDevInfoDividerPos(infoPos);
        flushState();
    }

    private static double normalizeDividerPosition(double pos, double fallback) {
        if (!Double.isFinite(pos)) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, pos));
    }

    /**
     * Возвращает сохранённую ширину колонки таблицы LoRa-мониторинга.
     * Если пользователь ещё не менял размер, возвращается переданное стартовое значение.
     *
     * @param key          preference-key конкретной колонки
     * @param defaultWidth стартовая ширина, используемая как fallback
     * @return сохранённая либо стартовая ширина колонки
     */
    public static double getPacketMonitorColumnWidth(String key, double defaultWidth) {
        return state().getDouble(key, defaultWidth);
    }

    /**
     * Сохраняет текущую ширину колонки таблицы LoRa-мониторинга.
     *
     * @param key   preference-key конкретной колонки
     * @param width фактическая ширина колонки в пикселях
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

    private static String composeChatScrollKey(String ownerId, String chatId) {
        return (ownerId != null ? ownerId : "") + "|" + (chatId != null ? chatId : "");
    }

}
