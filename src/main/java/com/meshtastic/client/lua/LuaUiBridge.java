package com.meshtastic.client.lua;

/**
 * Мост из Lua sandbox к JavaFX-интерфейсу MeshApp.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface LuaUiBridge {

    /**
     * @return {@code true}, если текущий runtime может показать UI-запрос.
     */
    boolean isAvailable();

    /**
     * Создает уникальный request id внутри текущей Lua-сессии.
     *
     * @return request id
     */
    String nextRequestId();

    /**
     * Передает запрос выбора ноды в UI.
     *
     * @param request запрос из Lua
     */
    void requestNodePick(LuaUiNodePickRequest request);
}
