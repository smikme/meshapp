package com.meshtastic.client.lua;

/**
 * Мост из Lua sandbox к traceroute-функциям активного подключения.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface LuaTracerouteBridge {

    /**
     * @return {@code true}, если traceroute доступен для текущего runtime target.
     */
    boolean isTracerouteAvailable();

    /**
     * Создает уникальный request id для traceroute.
     *
     * @return request id
     */
    String nextTracerouteRequestId();

    /**
     * Отправляет traceroute-запрос и доставляет результат в {@code on_traceroute(event)}.
     *
     * @param request параметры запроса
     */
    void requestTraceroute(LuaTracerouteRequest request);
}
