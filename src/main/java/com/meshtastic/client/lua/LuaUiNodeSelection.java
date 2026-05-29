package com.meshtastic.client.lua;

import com.meshtastic.client.model.NodeData;

/**
 * Результат интерактивного выбора ноды для Lua-скрипта.
 *
 * @param requestId id исходного запроса
 * @param source Lua API, который создал исходный запрос
 * @param name имя исходного запроса, заданное скриптом
 * @param selected {@code true}, если пользователь выбрал ноду
 * @param node выбранная нода или {@code null}
 * @param chatType тип чата исходного запроса
 * @param chatKey ключ чата исходного запроса
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaUiNodeSelection(String requestId,
                                 String source,
                                 String name,
                                 boolean selected,
                                 NodeData node,
                                 String chatType,
                                 String chatKey) {

    public static LuaUiNodeSelection selected(LuaUiNodePickRequest request, NodeData node) {
        return new LuaUiNodeSelection(
                request.requestId(),
                request.source(),
                request.name(),
                true,
                node,
                request.chatType(),
                request.chatKey());
    }

    public static LuaUiNodeSelection cancelled(LuaUiNodePickRequest request) {
        return new LuaUiNodeSelection(
                request.requestId(),
                request.source(),
                request.name(),
                false,
                null,
                request.chatType(),
                request.chatKey());
    }
}
