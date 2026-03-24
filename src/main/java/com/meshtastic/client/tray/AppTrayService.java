package com.meshtastic.client.tray;

/**
 * Платформенный tray/status item приложения.
 */
public interface AppTrayService {

    /**
     * Установить tray icon/status item.
     *
     * @param onActivate показать главное окно приложения
     * @param onExit     завершить приложение
     * @return {@code true}, если tray успешно создан
     */
    boolean install(Runnable onActivate, Runnable onExit);

    /**
     * Показать системное сообщение через tray, если платформа это поддерживает.
     */
    default void showNotification(String title, String message) {}

    /**
     * Освободить ресурсы tray.
     */
    default void dispose() {}
}
