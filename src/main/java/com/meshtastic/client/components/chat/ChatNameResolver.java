package com.meshtastic.client.components.chat;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.utils.NodeUtils;

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
        NodeData node = NodeUtils.resolveNode(state, nodeNum);
        if (node != null
                && node.getLongName() != null
                && !node.getLongName().isEmpty()) {
            return node.getLongName();
        }
        return "!" + String.format("%08x", nodeNum);
    }

    /**
     * Определить имя отправителя для цитаты ответа.
     *
     * @param msg сообщение
     * @return «Вы» для исходящих, longName / senderName / {@code !hex} для входящих
     */
    public String resolveSenderName(MeshMessage msg) {
        if (msg.isOutgoing()) {
            return "Вы";
        }
        NodeData node = NodeUtils.resolveNode(state, msg.getFromNum());
        if (node != null
                && node.getLongName() != null
                && !node.getLongName().isEmpty()) {
            return node.getLongName();
        }
        if (msg.getSenderName() != null && !msg.getSenderName().isEmpty()) {
            return msg.getSenderName();
        }
        return "!" + String.format("%08x", msg.getFromNum());
    }
}
