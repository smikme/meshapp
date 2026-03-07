package com.meshtastic.client.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;

/**
 * Windows toast-уведомления через {@link java.awt.SystemTray}.
 * <p>
 * Создаёт единственный {@link TrayIcon} при первом использовании (lazy init).
 * {@code displayMessage()} показывает Windows Toast (Win10+) или Balloon (Win7/8).
 */
public class WindowsNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(WindowsNotificationService.class);

    private volatile TrayIcon trayIcon;

    @Override
    public void showNotification(String title, String message) {
        try {
            TrayIcon icon = getOrCreateTrayIcon();
            if (icon != null) {
                icon.displayMessage(
                        title != null ? title : "MeshApp",
                        message != null ? message : "",
                        TrayIcon.MessageType.INFO);
            }
        } catch (Exception e) {
            log.warn("Failed to show Windows notification", e);
        }
    }

    private TrayIcon getOrCreateTrayIcon() {
        if (trayIcon != null) { return trayIcon; }
        synchronized (this) {
            if (trayIcon != null) { return trayIcon; }
            if (!SystemTray.isSupported()) {
                log.warn("SystemTray not supported on this system");
                return null;
            }
            try {
                Image image = Toolkit.getDefaultToolkit().getImage(
                        getClass().getResource("/logo/icon_32.png"));
                trayIcon = new TrayIcon(image, "MeshApp");
                trayIcon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(trayIcon);
            } catch (AWTException e) {
                log.error("Failed to add tray icon", e);
                return null;
            }
        }
        return trayIcon;
    }

    @Override
    public void dispose() {
        TrayIcon icon = trayIcon;
        if (icon != null) {
            SystemTray.getSystemTray().remove(icon);
            trayIcon = null;
        }
    }
}
