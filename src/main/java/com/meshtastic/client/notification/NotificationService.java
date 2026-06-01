package com.meshtastic.client.notification;

/**
 * Platform-specific delivery of operating-system notifications.
 * Implementations must be thread-safe.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface NotificationService {

    /**
     * Shows a system notification.
     *
     * @param title   notification title, typically sender or channel name
     * @param message truncated notification body
     */
    void showNotification(String title, String message);

    /**
     * Releases notification resources, such as a {@code SystemTray} icon.
     */
    default void dispose() {}
}
