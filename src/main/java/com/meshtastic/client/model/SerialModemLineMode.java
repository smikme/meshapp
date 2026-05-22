package com.meshtastic.client.model;

/**
 * Режим управления modem lines для одного serial-профиля подключения.
 * <p>
 * Значение сохраняется по имени в {@code ~/.meshapp/connections.json}. {@link #AUTO}
 * оставляет прежнюю эвристику по типу адаптера, а явные режимы позволяют настроить
 * проблемные USB-UART платы с нестандартной разводкой DTR/RTS.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum SerialModemLineMode {
    /** Автоматический выбор по имени порта и описанию адаптера. */
    AUTO(null, null),

    /** DTR и RTS явно отключены. */
    DTR_OFF_RTS_OFF(false, false),

    /** DTR отключён, RTS включён. */
    DTR_OFF_RTS_ON(false, true),

    /** DTR включён, RTS отключён. */
    DTR_ON_RTS_OFF(true, false),

    /** DTR и RTS явно включены. */
    DTR_ON_RTS_ON(true, true);

    private final Boolean assertDtr;
    private final Boolean assertRts;

    SerialModemLineMode(Boolean assertDtr, Boolean assertRts) {
        this.assertDtr = assertDtr;
        this.assertRts = assertRts;
    }

    /**
     * Проверяет, нужно ли использовать автоматическую эвристику адаптера.
     *
     * @return {@code true}, если режим не задаёт DTR/RTS явно
     */
    public boolean isAuto() {
        return this == AUTO;
    }

    /**
     * Возвращает требуемое состояние DTR для явного режима.
     *
     * @return {@code true}, если DTR должен быть включён
     */
    public boolean assertDtr() {
        return Boolean.TRUE.equals(assertDtr);
    }

    /**
     * Возвращает требуемое состояние RTS для явного режима.
     *
     * @return {@code true}, если RTS должен быть включён
     */
    public boolean assertRts() {
        return Boolean.TRUE.equals(assertRts);
    }
}
