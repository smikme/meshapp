package com.meshtastic.client.themes;

import com.google.gson.Gson;
import com.meshtastic.client.utils.AppPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;

/**
 * Stores the local palette, global wallpaper and optional per-chat wallpapers.
 *
 * <p>Settings live under the {@code appearance} child of the active
 * {@link AppPreferences} root. Per-chat keys include the owner identity and chat
 * preference ID and are hashed to fit the platform preference-key length limit.
 * Missing overrides inherit the global wallpaper; an explicit default wallpaper
 * remains an override. Unknown palettes and invalid background JSON use defaults.
 *
 * <p>Wallpaper setters and {@link #changed()} notify observers synchronously and
 * must be called on the JavaFX application thread when used by the UI. Read methods
 * can also be used by the wallpaper store's worker thread.
 */
public final class AppearancePreferences {
    private static final Gson JSON = new Gson();
    private static final ReadOnlyLongWrapper revision = new ReadOnlyLongWrapper();

    private AppearancePreferences() {}

    private static Preferences preferences() {
        return AppPreferences.getState().node("appearance");
    }

    /**
     * Reads the saved palette independently of the existing day/night preference.
     *
     * @return saved palette, or {@link ThemePalette#STANDARD} for a missing or unknown value
     */
    public static ThemePalette palette() {
        try {
            return ThemePalette.valueOf(preferences().get("palette", "STANDARD"));
        } catch (IllegalArgumentException e) {
            return ThemePalette.STANDARD;
        }
    }

    /**
     * Persists a palette without applying CSS or notifying appearance observers.
     * Call {@link ThemeManager#applyTheme(javafx.scene.Scene, boolean)} to apply it
     * together with the desired day/night mode.
     *
     * @param palette non-null palette to save
     */
    public static void setPalette(ThemePalette palette) {
        preferences().put("palette", palette.name());
    }

    /**
     * Reads the wallpaper inherited by chats without an override.
     *
     * @return saved configuration, or {@link ChatBackground#defaults()} if missing or invalid
     */
    public static ChatBackground globalBackground() {
        return decode(preferences().get("background", ""));
    }

    /**
     * Saves the global wallpaper and synchronously notifies appearance observers.
     * Call on the JavaFX application thread; existing per-chat overrides are retained.
     *
     * @param background non-null wallpaper configuration to save
     */
    public static void setGlobalBackground(ChatBackground background) {
        preferences().put("background", JSON.toJson(background));
        changed();
    }

    /**
     * Reads only a chat's explicit override, without resolving inheritance.
     *
     * @param owner non-null local owner identity or remote chat owner ID
     * @param chat non-null chat preference ID, such as {@code channel:0} or {@code dm:!12345678}
     * @return {@code null} when inheriting; otherwise the saved override or a default
     *         configuration if the stored override is invalid
     */
    public static ChatBackground chatOverride(String owner, String chat) {
        String value = preferences().node("chats").get(key(owner, chat), null);
        return value == null ? null : decode(value);
    }

    /**
     * Resolves the effective wallpaper for a chat.
     *
     * @param owner non-null local owner identity or remote chat owner ID
     * @param chat non-null chat preference ID from {@link com.meshtastic.client.components.chat.ChatDbKey}
     * @return the explicit override when present, otherwise the global wallpaper; never {@code null}
     */
    public static ChatBackground backgroundFor(String owner, String chat) {
        ChatBackground override = chatOverride(owner, chat);
        return override == null ? globalBackground() : override;
    }

    /**
     * Saves or removes a chat override and synchronously notifies appearance observers.
     * Call on the JavaFX application thread.
     *
     * @param owner non-null local owner identity or remote chat owner ID
     * @param chat non-null chat preference ID
     * @param background wallpaper to save, or {@code null} to restore global inheritance
     */
    public static void setChatBackground(String owner, String chat, ChatBackground background) {
        Preferences chats = preferences().node("chats");
        if (background == null) chats.remove(key(owner, chat));
        else chats.put(key(owner, chat), JSON.toJson(background));
        changed();
    }

    /**
     * Exposes a revision counter for refreshing existing wallpaper views and theme controls.
     * The counter is an in-memory notification mechanism, not a persisted schema version.
     *
     * @return read-only property incremented by wallpaper setters and {@link #changed()}
     */
    public static ReadOnlyLongProperty revisionProperty() {
        return revision.getReadOnlyProperty();
    }

    /**
     * Synchronously notifies observers that appearance has changed without writing preferences.
     * Used by {@link ThemeManager} after applying a theme. Call on the JavaFX application thread.
     */
    public static void changed() {
        revision.set(revision.get() + 1);
    }

    /**
     * Collects managed image filenames still referenced by global or per-chat preferences.
     * Used off the JavaFX thread to protect shared wallpapers during cleanup.
     *
     * @return a new set of referenced, non-empty image identifiers
     * @throws BackingStoreException if chat preference keys cannot be enumerated
     */
    static Set<String> referencedImages() throws BackingStoreException {
        Set<String> ids = new HashSet<>();
        ids.add(globalBackground().imageId());
        Preferences chats = preferences().node("chats");
        for (String key : chats.keys()) ids.add(decode(chats.get(key, "")).imageId());
        ids.remove("");
        return ids;
    }

    private static ChatBackground decode(String json) {
        try {
            ChatBackground background = JSON.fromJson(json, ChatBackground.class);
            return background == null ? ChatBackground.defaults() : background;
        } catch (RuntimeException e) {
            return ChatBackground.defaults();
        }
    }

    private static String key(String owner, String chat) {
        // Length prefix avoids collisions, and SHA-256 fits Preferences' 80-character limit.
        String identity = owner.length() + ":" + owner + chat;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
