package com.meshtastic.client.lua;

/**
 * Мост из Lua sandbox к встроенному Canvas-окну приложения.
 *
 * <p>Окно создается только по явному вызову Lua API и не участвует в
 * навигации бокового меню.
 */
public interface LuaCanvasBridge {

    /**
     * Открывает или обновляет Canvas-окно текущего скрипта.
     *
     * @param options параметры окна
     */
    void openCanvas(LuaCanvasOptions options);

    /**
     * Закрывает Canvas-окно текущего скрипта.
     */
    void closeCanvas();

    /**
     * Добавляет команду рисования в очередь JavaFX Canvas.
     *
     * @param command команда рисования
     */
    void enqueueCanvasDraw(LuaCanvasDrawCommand command);

    /**
     * Устанавливает частоту callback {@code on_canvas_frame(event)}.
     *
     * @param fps кадров в секунду, 0 выключает таймер
     */
    void setCanvasFrameRate(double fps);

    /**
     * Возвращает текущее состояние мыши внутри Canvas.
     *
     * @return snapshot состояния мыши
     */
    LuaCanvasMouseState canvasMouseState();

    /**
     * Возвращает текущее состояние клавиатуры для Canvas-окна.
     *
     * @return snapshot состояния клавиатуры
     */
    LuaCanvasKeyState canvasKeyState();

    /**
     * Возвращает текущий размер Canvas.
     *
     * @return snapshot размера
     */
    LuaCanvasSize canvasSize();
}
