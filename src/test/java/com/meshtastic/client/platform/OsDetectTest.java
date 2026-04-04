package com.meshtastic.client.platform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsDetectTest {

    @Test
    void detectsAppImageByAppImageVariable() {
        assertTrue(OsDetect.isLinuxAppImage(
                OsDetect.OsType.LINUX,
                Map.of("APPIMAGE", "/tmp/MeshApp-x86_64.AppImage")
        ));
    }

    @Test
    void detectsAppImageByAppDirVariable() {
        assertTrue(OsDetect.isLinuxAppImage(
                OsDetect.OsType.LINUX,
                Map.of("APPDIR", "/tmp/.mount_MeshApp")
        ));
    }

    @Test
    void ignoresAppImageVariablesOutsideLinux() {
        assertFalse(OsDetect.isLinuxAppImage(
                OsDetect.OsType.MACOS,
                Map.of("APPIMAGE", "/tmp/MeshApp-x86_64.AppImage")
        ));
    }

    @Test
    void returnsFalseWhenAppImageVariablesAreMissing() {
        assertFalse(OsDetect.isLinuxAppImage(OsDetect.OsType.LINUX, Map.of()));
    }
}
