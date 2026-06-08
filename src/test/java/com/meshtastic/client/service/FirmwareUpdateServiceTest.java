package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class FirmwareUpdateServiceTest {

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void validateAcceptsBinForOtaMode() throws Exception {
        FirmwareUpdateService service = new FirmwareUpdateService();
        DeviceState state = connectedState();
        try {
            Path firmware = tempHome.resolve("firmware.bin");
            Files.write(firmware, new byte[] { 1, 2, 3, 4 });

            FirmwareValidationResult result = service.validate(
                firmware,
                FirmwareUpdateMode.OTA_BLE,
                connectedEntry(),
                state
            );

            assertTrue(result.valid());
            assertEquals(FirmwareImageType.ESP32_BIN, result.image().type());
            assertEquals(32, result.image().sha256().length);
        } finally {
            state.shutdown();
        }
    }

    @Test
    void validateRejectsUf2ForOtaMode() throws Exception {
        FirmwareUpdateService service = new FirmwareUpdateService();
        DeviceState state = connectedState();
        try {
            Path firmware = tempHome.resolve("firmware.uf2");
            Files.write(firmware, new byte[] { 1, 2, 3, 4 });

            FirmwareValidationResult result = service.validate(
                firmware,
                FirmwareUpdateMode.OTA_WIFI,
                connectedEntry(),
                state
            );

            assertFalse(result.valid());
            assertFalse(result.errors().isEmpty());
        } finally {
            state.shutdown();
        }
    }

    @Test
    void validateAllowsZipForDfuModeAndReadsEntries() throws Exception {
        FirmwareUpdateService service = new FirmwareUpdateService();
        DeviceState state = connectedState();
        try {
            Path firmware = tempHome.resolve("firmware.zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(firmware))) {
                zip.putNextEntry(new ZipEntry("firmware.uf2"));
                zip.write(new byte[] { 1, 2, 3, 4 });
                zip.closeEntry();
            }

            FirmwareValidationResult result = service.validate(
                firmware,
                FirmwareUpdateMode.DFU,
                connectedEntry(),
                state
            );

            assertTrue(result.valid());
            assertEquals(FirmwareImageType.ZIP, result.image().type());
            assertTrue(result.image().zipContainsUf2());
            assertFalse(result.warnings().isEmpty());
        } finally {
            state.shutdown();
        }
    }

    private static ConnectionEntry connectedEntry() {
        ConnectionEntry entry = new ConnectionEntry("test", "localhost", 4403);
        entry.setConnected(true);
        return entry;
    }

    private static DeviceState connectedState() {
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        return state;
    }
}
