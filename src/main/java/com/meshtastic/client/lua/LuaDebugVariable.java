package com.meshtastic.client.lua;

/**
 * Variable shown in the MeshApp IDE debug panel.
 *
 * @param scope variable scope
 * @param name  variable name
 * @param value string representation of the value
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public record LuaDebugVariable(String scope, String name, String value) {}
