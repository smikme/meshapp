package com.meshtastic.client.lua;

/**
 * Параметры Canvas-окна, создаваемого из Lua.
 *
 * @param title      заголовок окна
 * @param width      ширина Canvas
 * @param height     высота Canvas
 * @param background стартовый цвет фона или пустая строка
 * @param resizable  должен ли Canvas масштабироваться вместе с окном
 * @param fps        частота {@code on_canvas_frame}, 0 выключает таймер
 */
public record LuaCanvasOptions(String title,
                               double width,
                               double height,
                               String background,
                               boolean resizable,
                               double fps) {
}
