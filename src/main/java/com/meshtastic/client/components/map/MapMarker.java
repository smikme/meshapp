package com.meshtastic.client.components.map;

/**
 * Маркер ноды, который отображается поверх тайловой карты.
 *
 * @param id         стабильный идентификатор ноды, используется для связи UI с моделью
 * @param title      полное название для подсказки
 * @param shortTitle короткий текст внутри круглого маркера
 * @param latitude   широта в градусах WGS84
 * @param longitude  долгота в градусах WGS84
 * @param local      {@code true}, если маркер показывает собственную ноду пользователя
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record MapMarker(
        String id,
        String title,
        String shortTitle,
        double latitude,
        double longitude,
        boolean local
) {
}
