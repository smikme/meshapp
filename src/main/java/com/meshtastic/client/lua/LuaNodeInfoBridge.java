package com.meshtastic.client.lua;

/**
 * Мост из Lua sandbox к запросам актуальной информации о ноде.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface LuaNodeInfoBridge {

    /**
     * @return {@code true}, если nodeinfo доступен для текущего runtime target.
     */
    boolean isNodeInfoAvailable();

    /**
     * Создает уникальный request id для nodeinfo.
     *
     * @return request id
     */
    String nextNodeInfoRequestId();

    /**
     * Отправляет запрос nodeinfo и доставляет результат в {@code on_node_info(event)}.
     *
     * @param request параметры запроса
     */
    void requestNodeInfo(LuaNodeInfoRequest request);
}
