package com.meshtastic.client.model;

/**
 * Тип транспорта для подключения к Meshtastic-устройству.
 * Gson сериализует по имени — обратная совместимость с существующим JSON.
 */
public enum ConnectionType {
    TCP,
    SERIAL
}
