package com.meshtastic.client.lua;

/**
 * NodeInfo-запрос из Lua.
 *
 * @param scriptId id Lua-скрипта
 * @param requestId id запроса внутри сессии
 * @param source источник события
 * @param name имя сценария/запроса
 * @param targetNodeNum node_num целевой ноды
 * @param targetNodeId node_id целевой ноды
 * @param targetName имя целевой ноды для вывода
 * @param chatType тип чата
 * @param chatKey ключ чата
 * @param timeoutSeconds таймаут ожидания ответа
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaNodeInfoRequest(long scriptId,
                                 String requestId,
                                 String source,
                                 String name,
                                 int targetNodeNum,
                                 String targetNodeId,
                                 String targetName,
                                 String chatType,
                                 String chatKey,
                                 int timeoutSeconds) {
}
