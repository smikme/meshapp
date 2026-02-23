package com.meshtastic.client.connection;

/**
 * Исключение, возникающее при невозможности установить соединение
 * с Meshtastic-устройством (таймаут, отказ, некорректный адрес).
 */
public class ConnectionException extends Exception {

    public ConnectionException(String message) {
        super(message);
    }

    public ConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
