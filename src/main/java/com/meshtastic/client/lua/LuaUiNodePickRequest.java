package com.meshtastic.client.lua;

/**
 * Запрос Lua-скрипта на интерактивный выбор ноды в UI.
 *
 * @param scriptId id Lua-скрипта
 * @param requestId id запроса внутри сессии
 * @param source Lua API, который создал запрос
 * @param name имя запроса, заданное скриптом
 * @param prompt текст заголовка/подсказки
 * @param query начальная строка поиска
 * @param chatType тип чата, связанного с запросом
 * @param chatKey ключ чата, связанного с запросом
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaUiNodePickRequest(long scriptId,
                                   String requestId,
                                   String source,
                                   String name,
                                   String prompt,
                                   String query,
                                   String chatType,
                                   String chatKey) {
}
