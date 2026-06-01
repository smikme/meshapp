package com.meshtastic.client.lua;

/**
 * Временное UI-сообщение встроенного бота без записи в историю.
 *
 * @param scriptId id Lua-скрипта
 * @param source источник события
 * @param name имя сценария/запроса
 * @param chatType тип чата
 * @param chatKey ключ чата
 * @param text текст сообщения
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaUiBotNotice(long scriptId,
                             String source,
                             String name,
                             String chatType,
                             String chatKey,
                             String text) {
}
