package com.meshtastic.client.service;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
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
    void validateAcceptsBinForWifiOtaMode() throws Exception {
        FirmwareUpdateService service = new FirmwareUpdateService();
        DeviceState state = connectedState();
        try {
            Path firmware = tempHome.resolve("firmware.bin");
            Files.write(firmware, FirmwareTestImages.esp());

            FirmwareValidationResult result = service.validate(
                firmware,
                FirmwareUpdateMode.OTA_WIFI,
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
    void validateAcceptsBleOta() throws Exception {
        FirmwareUpdateService service = new FirmwareUpdateService();
        DeviceState state = connectedState();
        try {
            Path firmware = tempHome.resolve("firmware.bin");
            Files.write(firmware, FirmwareTestImages.esp());

            FirmwareValidationResult result = service.validate(
                firmware,
                FirmwareUpdateMode.OTA_BLE,
                connectedBleEntry(),
                state
            );

            assertTrue(result.valid(), result.errors().toString());
            assertTrue(result.errors().isEmpty());
        } finally {
            state.shutdown();
        }
    }

    @Test
    void validateRejectsMalformedUf2InsideArchive() throws Exception {
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

            assertFalse(result.valid());
            assertEquals(FirmwareImageType.ZIP, result.image().type());
            assertTrue(result.image().zipContainsUf2());
            assertFalse(result.errors().isEmpty());
        } finally {
            state.shutdown();
        }
    }

    @Test
    void autoSelectsSerialAndAllowsUpdatingOldFirmwareOverUsb() throws Exception {
        DeviceState state = connectedState();
        try {
            state.setDeviceMetadata(state.getDeviceMetadata().toBuilder().setFirmwareVersion("2.5.0").build());
            Path path = tempHome.resolve("firmware.bin"); Files.write(path, FirmwareTestImages.esp());
            var entry = new ConnectionEntry("usb", "/dev/test", 115200, ConnectionType.SERIAL);
            entry.setConnected(true);
            var service = new FirmwareUpdateService();
            assertEquals(FirmwareUpdateMode.SERIAL, service.resolveMode(FirmwareImage.analyze(path), FirmwareUpdateMode.AUTO, entry, state));
            assertTrue(service.validate(path, FirmwareUpdateMode.AUTO, entry, state).valid());
            assertFalse(service.validate(path, FirmwareUpdateMode.OTA_WIFI, connectedEntry(), state).valid());
        } finally { state.shutdown(); }
    }

    @Test
    void rejectsInvalidBinAndIncompatibleOtaDevice() throws Exception {
        DeviceState state = connectedState();
        try {
            Path path = tempHome.resolve("firmware.bin"); Files.write(path, new byte[]{1,2,3,4});
            var service = new FirmwareUpdateService();
            assertFalse(service.validate(path, FirmwareUpdateMode.AUTO, connectedEntry(), state).valid());
            Files.write(path, FirmwareTestImages.esp());
            state.setDeviceMetadata(state.getDeviceMetadata().toBuilder().setHasWifi(false).build());
            assertFalse(service.validate(path, FirmwareUpdateMode.AUTO, connectedEntry(), state).valid());
        } finally { state.shutdown(); }
    }

    @Test
    void acceptsNordicPackageOverBleAndUf2OverUsb() throws Exception {
        DeviceState state = connectedState();
        try {
            state.setDeviceMetadata(state.getDeviceMetadata().toBuilder()
                    .setHwModel(org.meshtastic.proto.MeshProtos.HardwareModel.RAK4631).setHasWifi(false).build());
            Path zip = FirmwareTestImages.nordic(tempHome.resolve("nordic.zip"));
            var service = new FirmwareUpdateService();
            assertTrue(service.validate(zip, FirmwareUpdateMode.AUTO, connectedBleEntry(), state).valid());
            assertEquals(FirmwareUpdateMode.DFU, service.resolveMode(FirmwareImage.analyze(zip), FirmwareUpdateMode.AUTO, connectedBleEntry(), state));
            var usb = new ConnectionEntry("usb", "/dev/test", 115200, ConnectionType.SERIAL); usb.setConnected(true);
            Path uf2 = tempHome.resolve("nordic.uf2"); Files.write(uf2, FirmwareTestImages.uf2());
            assertTrue(service.validate(uf2, FirmwareUpdateMode.AUTO, usb, state).valid());
            assertFalse(service.validate(uf2, FirmwareUpdateMode.AUTO, connectedBleEntry(), state).valid());
        } finally { state.shutdown(); }
    }

    @Test
    void distinguishesOtaRefusalsFromRebootNotification() {
        var notification = org.meshtastic.proto.MeshProtos.ClientNotification.newBuilder()
                .setLevel(org.meshtastic.proto.MeshProtos.LogRecord.Level.WARNING);
        assertTrue(FirmwareUpdateService.isOtaRefusal(notification.setMessage("Cannot start OTA: Cannot find OTA Loader partition.").build()));
        assertTrue(FirmwareUpdateService.isOtaRefusal(notification.setMessage("OTA Loader does not support WiFi").build()));
        assertFalse(FirmwareUpdateService.isOtaRefusal(notification.setMessage("Rebooting to WiFi OTA").build()));
    }

    private static ConnectionEntry connectedEntry() {
        ConnectionEntry entry = new ConnectionEntry("test", "localhost", 4403);
        entry.setConnected(true);
        return entry;
    }

    private static ConnectionEntry connectedBleEntry() {
        ConnectionEntry entry = new ConnectionEntry(
            "test-ble",
            "00:11:22:33:44:55",
            "test"
        );
        entry.setType(ConnectionType.BLE);
        entry.setConnected(true);
        return entry;
    }

    private static DeviceState connectedState() {
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x04c5b420);
        state.setDeviceMetadata(org.meshtastic.proto.MeshProtos.DeviceMetadata.newBuilder()
                .setFirmwareVersion("2.7.18").setHasWifi(true).setHasBluetooth(true)
                .setHwModel(org.meshtastic.proto.MeshProtos.HardwareModel.HELTEC_V3).build());
        return state;
    }
}
