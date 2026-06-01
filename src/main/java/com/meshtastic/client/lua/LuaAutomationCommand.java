package com.meshtastic.client.lua;

import java.util.List;

/**
 * Контекст запуска Lua-автоматизации из чат-команды.
 *
 * @param chatType тип чата, из которого вызвана команда
 * @param chatKey ключ чата, из которого вызвана команда
 * @param handle имя команды, например {@code @tracebot}
 * @param text полный текст пользовательской команды
 * @param arguments строка аргументов после имени команды
 * @param argumentTokens аргументы, разбитые так же, как chat command parser
 * @param requestId id вызова команды внутри UI/рантайма
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaAutomationCommand(String chatType,
                                   String chatKey,
                                   String handle,
                                   String text,
                                   String arguments,
                                   List<String> argumentTokens,
                                   String requestId) {

    public LuaAutomationCommand {
        argumentTokens = argumentTokens != null ? List.copyOf(argumentTokens) : List.of();
        requestId = requestId != null ? requestId : "";
    }

    public LuaAutomationCommand(String chatType,
                                String chatKey,
                                String handle,
                                String text,
                                String arguments,
                                List<String> argumentTokens) {
        this(chatType, chatKey, handle, text, arguments, argumentTokens, "");
    }
}
