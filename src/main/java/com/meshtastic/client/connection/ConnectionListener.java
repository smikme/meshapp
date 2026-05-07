package com.meshtastic.client.connection;

/**
 * Слушатель событий жизненного цикла соединения с Meshtastic-устройством.
 * Callback-методы вызываются из потока, управляющего соединением (не из UI-потока).
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface ConnectionListener {

    /** Вызывается после успешного установления соединения. */
    void onConnected();

    /** Вызывается после штатного разрыва соединения. */
    void onDisconnected();

    /**
     * Вызывается при ошибке соединения (таймаут, обрыв, ошибка записи/чтения).
     *
     * @param message описание ошибки
     * @param cause   исключение-причина (может быть {@code null})
     */
    void onConnectionError(String message, Throwable cause);
}
