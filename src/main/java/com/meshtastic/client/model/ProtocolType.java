package com.meshtastic.client.model;

/**
 * Коммуникационный протокол, который работает поверх выбранного транспорта.
 * <p>
 * Значение сохраняется по имени в {@code ~/.meshapp/connections.json}. Новые
 * протокольные адаптеры добавляются сюда без изменения модели TCP/Serial/BLE
 * транспортов.
 */
public enum ProtocolType {
    /** Автоматическое определение протокола при подключении. */
    AUTO,

    /** Протокол Meshtastic поверх TCP, Serial или BLE транспорта. */
    MESHTASTIC,

    /** MeshCore KISS modem protocol поверх TCP или Serial byte stream. */
    MESHCORE_KISS,

    /** MeshCore Companion Protocol поверх BLE RX/TX или raw TCP/Serial byte stream. */
    MESHCORE_COMPANION
}
