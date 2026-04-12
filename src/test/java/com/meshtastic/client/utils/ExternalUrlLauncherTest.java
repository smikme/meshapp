package com.meshtastic.client.utils;

import com.meshtastic.client.platform.OsDetect;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExternalUrlLauncherTest {

    @Test
    void buildsMacOsOpenCommand() {
        assertEquals(
                List.of("open", "https://meshapp.ru"),
                ExternalUrlLauncher.buildCommand(URI.create("https://meshapp.ru"), OsDetect.OsType.MACOS));
    }

    @Test
    void buildsWindowsOpenCommand() {
        assertEquals(
                List.of("rundll32", "url.dll,FileProtocolHandler", "https://meshapp.ru"),
                ExternalUrlLauncher.buildCommand(URI.create("https://meshapp.ru"), OsDetect.OsType.WINDOWS));
    }

    @Test
    void buildsLinuxOpenCommand() {
        assertEquals(
                List.of("xdg-open", "https://meshapp.ru"),
                ExternalUrlLauncher.buildCommand(URI.create("https://meshapp.ru"), OsDetect.OsType.LINUX));
    }

    @Test
    void returnsNullForUnknownOs() {
        assertNull(ExternalUrlLauncher.buildCommand(URI.create("https://meshapp.ru"), OsDetect.OsType.UNKNOWN));
    }
}
