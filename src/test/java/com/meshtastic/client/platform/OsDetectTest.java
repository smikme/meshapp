package com.meshtastic.client.platform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
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

    @Test
    void detectsFlatpakByFlatpakIdVariable() {
        assertTrue(OsDetect.isLinuxFlatpak(
                OsDetect.OsType.LINUX,
                Map.of("FLATPAK_ID", "com.meshtastic.meshapp")
        ));
    }

    @Test
    void detectsFlatpakByContainerVariable() {
        assertTrue(OsDetect.isLinuxFlatpak(
                OsDetect.OsType.LINUX,
                Map.of("container", "flatpak")
        ));
    }

    @Test
    void flatpakTakesPriorityOverAppImageWhenBothMarkersExist() {
        assertEquals(
                OsDetect.PackageFormat.FLATPAK,
                OsDetect.detectPackageFormat(
                        OsDetect.OsType.LINUX,
                        Map.of(
                                "FLATPAK_ID", "com.meshtastic.meshapp",
                                "APPIMAGE", "/tmp/MeshApp-x86_64.AppImage"
                        )
                )
        );
    }

    @Test
    void normalizesArchitectureAliases() {
        assertEquals("x86_64", OsDetect.normalizeArch("amd64"));
        assertEquals("aarch64", OsDetect.normalizeArch("arm64"));
    }
}
