package com.meshtastic.client.model;

/**
 * Коммуникационный протокол, который работает поверх выбранного транспорта.
 * <p>
 * Значение сохраняется по имени в {@code ~/.meshapp/connections.json}. Новые
 * протокольные адаптеры добавляются сюда без изменения модели TCP/Serial/BLE
 * транспортов.
 */
public enum ProtocolType {
    /** Протокол Meshtastic поверх TCP, Serial или BLE транспорта. */
    MESHTASTIC
}
