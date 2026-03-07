package com.meshtastic.client.notification;

/**
 * Платформенная доставка OS-уведомлений.
 * Реализации должны быть потокобезопасными.
 */
public interface NotificationService {

    /**
     * Показать системное уведомление.
     *
     * @param title   заголовок (имя отправителя / канала)
     * @param message текст сообщения (обрезанный)
     */
    void showNotification(String title, String message);

    /**
     * Освободить ресурсы (например, SystemTray иконку).
     */
    default void dispose() {}
}
