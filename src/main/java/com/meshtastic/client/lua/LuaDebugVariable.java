package com.meshtastic.client.lua;

/**
 * Переменная, показанная в панели отладки MeshApp IDE.
 *
 * @param scope область видимости переменной
 * @param name  имя переменной
 * @param value строковое представление значения
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaDebugVariable(String scope, String name, String value) {}
