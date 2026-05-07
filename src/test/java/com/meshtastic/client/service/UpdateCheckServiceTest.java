package com.meshtastic.client.service;

import com.meshtastic.client.platform.OsDetect.OsType;
import com.meshtastic.client.platform.OsDetect.PackageFormat;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class UpdateCheckServiceTest {

    @Test
    void requestCarriesPlatformAttributes() {
        HttpRequest request = UpdateCheckService.buildRequest(
                URI.create("https://example.invalid/meshapp.json"),
                OsType.LINUX,
                PackageFormat.FLATPAK,
                "x86_64",
                "1.2.3",
                123
        );

        assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
        assertEquals("linux", request.headers().firstValue("X-MeshApp-OS").orElseThrow());
        assertEquals("flatpak", request.headers().firstValue("X-MeshApp-Package").orElseThrow());
        assertEquals("x86_64", request.headers().firstValue("X-MeshApp-Arch").orElseThrow());
        assertEquals("1.2.3", request.headers().firstValue("X-MeshApp-Version").orElseThrow());
        assertEquals("123", request.headers().firstValue("X-MeshApp-Version-Code").orElseThrow());
    }
}
