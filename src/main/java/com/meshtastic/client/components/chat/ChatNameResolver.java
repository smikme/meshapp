package com.meshtastic.client.components.chat;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;

/**
 * Разрешение имён нод и отправителей для чата.
 *
 * <p>Инстанс хранит ссылку на {@link DeviceState}, которая обновляется
 * при переподключении через {@link #setState(DeviceState)}.
 */
public class ChatNameResolver {

    private DeviceState state;

    public ChatNameResolver(DeviceState state) {
        this.state = state;
    }

    /** Обновить DeviceState (при reconnect). */
    public void setState(DeviceState state) {
        this.state = state;
    }

    /**
     * Разрешить имя ноды по nodeNum — longName или fallback {@code !hex}.
     *
     * @param nodeNum номер ноды
     * @return отображаемое имя
     */
    public String resolveNodeName(int nodeNum) {
        return ChatNodeDisplayHelper.resolveNodeName(state, nodeNum);
    }

    /**
     * Определить имя отправителя для цитаты ответа.
     *
     * @param msg сообщение
     * @return «Вы» для исходящих, longName / senderName / nodeId для входящих
     */
    public String resolveSenderName(MeshMessage msg) {
        return ChatNodeDisplayHelper.resolveReplySenderName(state, msg);
    }
}
