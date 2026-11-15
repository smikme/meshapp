package com.meshtastic.client.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class FirmwarePayloadTest {
    @TempDir Path temp;
    @Test void validatesEspSegmentsChecksumAndApplicationHeader() throws Exception {
        byte[] b = FirmwareTestImages.esp(); assertEquals(9,FirmwarePayload.validateEsp(b));
        b[100] ^= 1; assertThrows(IOException.class, () -> FirmwarePayload.validateEsp(b));
        assertThrows(IOException.class, () -> FirmwarePayload.validateEsp(new byte[]{1,2,3,4}));
        byte[] bootloader = FirmwareTestImages.esp(); bootloader[32] = 0;
        assertThrows(IOException.class, () -> FirmwarePayload.validateEsp(bootloader));
    }
    @Test void validatesUf2BlockCompletenessFamilyAndNumbering() throws Exception {
        byte[] b = FirmwareTestImages.uf2(); assertEquals(0xada52840,FirmwarePayload.validateUf2(b));
        byte[] duplicate = new byte[1024]; System.arraycopy(b,0,duplicate,0,512); System.arraycopy(b,0,duplicate,512,512);
        assertThrows(IOException.class, () -> FirmwarePayload.validateUf2(duplicate));
        b[508] = 0; assertThrows(IOException.class, () -> FirmwarePayload.validateUf2(b));
    }
    @Test void snapshotsAndCleansUpExtractedBin() throws Exception {
        Path archive = temp.resolve("release.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            FirmwareTestImages.entry(zip,"../firmware-update.bin",FirmwareTestImages.esp());
        }
        Path snapshot;
        try (var p = FirmwarePayload.prepare(archive,null)) {
            snapshot = p.image.path(); assertTrue(Files.isRegularFile(snapshot));
            assertArrayEquals(FirmwareTestImages.esp(),Files.readAllBytes(snapshot));
            Files.delete(archive); assertTrue(Files.isRegularFile(snapshot));
        }
        assertFalse(Files.exists(snapshot));
        assertFalse(Files.exists(temp.resolve("firmware-update.bin")));
    }
    @Test void rejectsAmbiguousArchivesWithoutChoosingFirstImage() throws Exception {
        Path archive = temp.resolve("release.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            FirmwareTestImages.entry(zip,"a.bin",FirmwareTestImages.esp());
            FirmwareTestImages.entry(zip,"b.bin",FirmwareTestImages.esp());
        }
        assertThrows(IOException.class, () -> {
            try (var _ = FirmwarePayload.prepare(archive, null)) {
                fail("Ambiguous archive must be rejected");
            }
        });
    }
    @Test void checksNordicManifestAndInitCrc() throws Exception {
        Path archive = FirmwareTestImages.nordic(temp.resolve("nordic.zip"));
        try (var p = FirmwarePayload.prepare(archive,null)) {
            assertEquals(14,p.initPacket.length); assertEquals(220,p.image.sizeBytes());
        }
        Path invalid = temp.resolve("invalid.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(invalid))) {
            FirmwareTestImages.entry(zip,"manifest.json", "{\"manifest\":{\"bootloader\":{}}}".getBytes());
        }
        assertThrows(IOException.class, () -> {
            try (var _ = FirmwarePayload.prepare(invalid, null)) {
                fail("Bootloader-only package must be rejected");
            }
        });
        assertEquals(0x29b1,FirmwarePayload.crc16("123456789".getBytes()));
    }
    @Test void uf2OnlyWritesToAnIdentifiedCompatibleVolume() throws Exception {
        Path file = temp.resolve("image.uf2"); Files.write(file,FirmwareTestImages.uf2());
        Path disk = Files.createDirectory(temp.resolve("disk"));
        try (var p = FirmwarePayload.prepare(file,null)) {
            assertThrows(IOException.class, () -> FirmwareUf2Uploader.copy(p,disk,null));
            Files.writeString(disk.resolve("INFO_UF2.TXT"),"Board-ID: RP2040\n");
            assertThrows(IOException.class, () -> FirmwareUf2Uploader.copy(p,disk,null));
            Files.writeString(disk.resolve("INFO_UF2.TXT"),"UF2 Bootloader\nBoard-ID: nRF52840-RAK4631\n");
            FirmwareUf2Uploader.copy(p,disk,null);
            assertArrayEquals(FirmwareTestImages.uf2(),Files.readAllBytes(disk.resolve("MESHAPP.UF2")));
        }
    }
}
