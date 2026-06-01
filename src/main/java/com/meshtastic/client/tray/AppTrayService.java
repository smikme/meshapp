package com.meshtastic.client.tray;

/**
 * Platform integration for the application's tray icon or status item.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface AppTrayService {

    /**
     * Installs the tray icon or status item.
     *
     * @param onActivate callback that brings the main window forward
     * @param onExit     callback that terminates the application
     * @return {@code true} when the tray item was created successfully
     */
    boolean install(Runnable onActivate, Runnable onExit);

    /**
     * Shows a system notification through the tray integration when supported.
     */
    default void showNotification(String title, String message) {}

    /**
     * Releases resources owned by the tray integration.
     */
    default void dispose() {}
}
