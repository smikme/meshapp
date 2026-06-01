package com.meshtastic.client.notification;

import com.meshtastic.client.tray.AppTrayManager;

/**
 * Windows notifications delivered through the application's existing tray icon.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class WindowsNotificationService implements NotificationService {

    @Override
    public void showNotification(String title, String message) {
        AppTrayManager.getInstance().showNotification(title, message);
    }
}
