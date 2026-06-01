package com.meshtastic.client.components.chat;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.NodeData;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;

/**
 * Утилита форматирования времени для чата (русская локаль, Telegram-стиль).
 *
 * <p>Два режима:
 * <ul>
 *   <li>{@link #formatChatTime} — список чатов: сегодня HH:mm, вчера «вчера»,
 *       в течение недели — день недели, старше — дата.</li>
 *   <li>{@link #formatMessageTime} — пузыри сообщений: сегодня HH:mm,
 *       старые dd.MM HH:mm.</li>
 * </ul>
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ChatTimeFormatter {

    private static final long SECONDS_IN_DAY = 86_400;
    private static final long SECONDS_IN_TWO_DAYS = 172_800;
    private static final long SECONDS_IN_WEEK = 604_800;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private ChatTimeFormatter() {}

    /**
     * Форматирование времени для списка чатов (Telegram-стиль).
     *
     * @param epochSeconds Unix timestamp в секундах
     * @return отформатированная строка или пустая строка если epochSeconds {@code <= 0}
     */
    public static String formatChatTime(long epochSeconds) {
        if (epochSeconds <= 0) {
            return "";
        }
        long now = System.currentTimeMillis() / 1000;
        long diff = now - epochSeconds;

        if (diff < SECONDS_IN_DAY) {
            return Instant.ofEpochSecond(epochSeconds)
                    .atZone(ZoneId.systemDefault())
                    .toLocalTime()
                    .format(TIME_FORMAT);
        }
        if (diff < SECONDS_IN_TWO_DAYS) {
            return I18n.t("chat.time.yesterday");
        }
        if (diff < SECONDS_IN_WEEK) {
            return Instant.ofEpochSecond(epochSeconds)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .getDayOfWeek()
                    .getDisplayName(TextStyle.SHORT, I18n.locale());
        }
        return NodeData.formatTime((int) epochSeconds);
    }

    /**
     * Форматирование времени для пузырей сообщений.
     *
     * @param epochSeconds Unix timestamp в секундах
     * @return отформатированная строка или пустая строка если epochSeconds {@code <= 0}
     */
    public static String formatMessageTime(long epochSeconds) {
        if (epochSeconds <= 0) {
            return "";
        }
        long now = System.currentTimeMillis() / 1000;
        long diff = now - epochSeconds;
        var zdt = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault());
        if (diff < SECONDS_IN_DAY) {
            return zdt.toLocalTime().format(TIME_FORMAT);
        }
        return zdt.toLocalDateTime().format(DATE_TIME_FORMAT);
    }
}
