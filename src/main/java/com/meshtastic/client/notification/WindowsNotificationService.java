package com.meshtastic.client.notification;

import com.meshtastic.client.tray.AppTrayManager;

/**
 * Windows-уведомления через уже созданный tray icon приложения.
 */
public class WindowsNotificationService implements NotificationService {

    @Override
    public void showNotification(String title, String message) {
        AppTrayManager.getInstance().showNotification(title, message);
    }
}
