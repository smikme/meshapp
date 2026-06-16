package com.meshtastic.client.lua;

/**
 * Event emitted by an embedded extension-form component.
 */
public record LuaFormEvent(long scriptId,
                           String componentId,
                           String type,
                           Object value,
                           String text) {
}
