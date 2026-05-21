package com.meshtastic.client.lua.api;

import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.meshcore.MeshCoreCompanionProtocolRuntime;

import java.util.function.Consumer;

/**
 * Контекст выполнения прикладного API Lua-песочницы.
 * <p>
 * Содержит только те сервисы и состояние приложения, которые разрешенные
 * расширения {@code mesh.*} могут использовать во время выполнения скрипта.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaSandboxContext(long scriptId,
                                String connectionId,
                                DeviceState state,
                                ProtocolHandler handler,
                                MeshCoreCompanionProtocolRuntime meshCoreRuntime,
                                String ownerNodeId,
                                LuaScriptService scriptService,
                                Consumer<String> outputSink) {

    /**
     * Проверяет, есть ли активный транспорт для отправки сообщений.
     *
     * @return {@code true}, если доступен обычный протокол или MeshCore Companion
     */
    public boolean hasChatTransport() {
        return state != null && (handler != null || meshCoreRuntime != null);
    }

    /**
     * Возвращает node_id владельца подключения или пустую строку.
     *
     * @return безопасное значение owner node id
     */
    public String ownerNodeIdOrEmpty() {
        return ownerNodeId != null ? ownerNodeId : "";
    }

    /**
     * Передает строку вывода из sandbox API в рантайм скрипта.
     *
     * @param message строка вывода
     */
    public void emitOutput(String message) {
        if (outputSink != null) {
            outputSink.accept(message);
        }
    }
}
