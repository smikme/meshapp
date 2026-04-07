package com.meshtastic.client.components;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketMonitorWindowTest {

    @Test
    void shouldKeepSelectedTypeInOptionsWhenFilterListRefreshes() {
        List<String> options = PacketMonitorWindow.buildTypeFilterOptions(
                List.of("NODEINFO_APP"),
                "TEXT_MESSAGE_APP");

        assertEquals(List.of("Все типы", "NODEINFO_APP", "TEXT_MESSAGE_APP"), options);
        assertEquals("TEXT_MESSAGE_APP",
                PacketMonitorWindow.resolveTypeFilterSelection("TEXT_MESSAGE_APP", options));
    }

    @Test
    void shouldAvoidDuplicatingSelectedTypeWhenItAlreadyExists() {
        List<String> options = PacketMonitorWindow.buildTypeFilterOptions(
                List.of("TEXT_MESSAGE_APP", "NODEINFO_APP"),
                "TEXT_MESSAGE_APP");

        assertEquals(List.of("Все типы", "TEXT_MESSAGE_APP", "NODEINFO_APP"), options);
    }
}
