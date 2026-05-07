package com.meshtastic.client.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Linux-уведомления через {@code notify-send} (libnotify).
 * <p>
 * {@code notify-send} доступен на всех основных DE (GNOME, KDE, XFCE и др.).
 * При отсутствии утилиты ошибка логируется, приложение продолжает работу.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class LinuxNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(LinuxNotificationService.class);

    @Override
    public void showNotification(String title, String message) {
        try {
            new ProcessBuilder("notify-send",
                    "--app-name=MeshApp",
                    "--icon=dialog-information",
                    title != null ? title : "MeshApp",
                    message != null ? message : "")
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception e) {
            log.warn("Failed to show Linux notification (is notify-send installed?)", e);
        }
    }
}
