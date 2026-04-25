package com.meshtastic.client.components.chat;

import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.utils.AppPreferences;

import java.util.Objects;

/**
 * Стабильные ключи БД и пользовательских настроек для чата.
 *
 * <p>Интерфейс работает с {@link ChatItem}, а слой хранения использует строковые
 * пары вроде {@code channel/0} и {@code dm/!abcd1234}. Запись держит эти
 * преобразования в одном месте, чтобы код формы не дублировал ветвления
 * канал/личный чат.
 */
public record ChatDbKey(
        String dbType,
        String dbKey,
        String readKey,
        String preferenceId,
        String scrollStateKey) {

    /**
     * Создаёт ключи для канального чата.
     *
     * @param channelIndex индекс канала из настроек Meshtastic
     * @return ключи БД и настроек для канала
     */
    public static ChatDbKey channel(int channelIndex) {
        String key = String.valueOf(channelIndex);
        return new ChatDbKey(
                "channel",
                key,
                "ch:" + key,
                AppPreferences.composeChatPreferenceId("channel", key),
                "channel:" + key);
    }

    /**
     * Создаёт ключи для личного чата.
     *
     * @param peerNodeId идентификатор ноды собеседника, например {@code !9e755af0}
     * @return ключи БД и настроек для личного чата
     */
    public static ChatDbKey direct(String peerNodeId) {
        return new ChatDbKey(
                "dm",
                peerNodeId,
                "dm:" + peerNodeId,
                AppPreferences.composeChatPreferenceId("dm", peerNodeId),
                "dm:" + peerNodeId);
    }

    /**
     * Создаёт ключи по элементу из списка чатов.
     *
     * @param item элемент списка чатов
     * @return ключи БД и настроек для элемента
     */
    public static ChatDbKey from(ChatItem item) {
        Objects.requireNonNull(item, "item");
        return switch (item.getType()) {
            case CHANNEL -> channel(item.getChannelIndex());
            case DIRECT_MESSAGE -> direct(item.getPeerNodeId());
        };
    }
}
