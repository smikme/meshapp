package com.meshtastic.client.lua;

import java.util.List;

/**
 * Launch context for Lua automation started from a chat command.
 *
 * @param chatType chat type where the command was invoked
 * @param chatKey chat key where the command was invoked
 * @param handle command name, for example {@code @tracebot}
 * @param text full user command text
 * @param arguments raw argument string after the command name
 * @param argumentTokens arguments split the same way as the chat command parser
 * @param requestId command invocation id inside the UI/runtime
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
