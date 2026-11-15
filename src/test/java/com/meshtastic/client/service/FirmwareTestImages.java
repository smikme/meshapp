package com.meshtastic.client.service;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;

final class FirmwareTestImages {
    static byte[] esp() {
        byte[] b = new byte[304];
        var header = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        b[0] = (byte)0xe9; b[1] = 1; header.putShort(12, (short)9);
        header.putInt(24, 0x3c000020); header.putInt(28, 256); header.putInt(32, 0xabcd5432);
        System.arraycopy("2.7.18".getBytes(StandardCharsets.US_ASCII), 0, b, 48, 6);
        System.arraycopy("meshtastic".getBytes(StandardCharsets.US_ASCII), 0, b, 80, 10);
        int crc = 0xef; for (int i = 32; i < 288; i++) crc ^= b[i] & 255;
        b[303] = (byte)crc;
        return b;
    }
    static byte[] uf2() {
        byte[] b = new byte[512]; var h = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0,0x0a324655); h.putInt(4,0x9e5d5157); h.putInt(8,0x2000);
        h.putInt(12,0x26000); h.putInt(16,256); h.putInt(20,0); h.putInt(24,1);
        h.putInt(28,0xada52840); h.putInt(508,0x0ab16f30); return b;
    }
    static Path nordic(Path path) throws Exception {
        byte[] bin = new byte[220]; ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20040000).putInt(0x26001);
        byte[] dat = new byte[14]; var h = ByteBuffer.wrap(dat).order(ByteOrder.LITTLE_ENDIAN);
        h.putShort(0,(short)0xffff); h.putShort(2,(short)0xffff); h.putShort(8,(short)1);
        h.putShort(10,(short)0x100); h.putShort(12,(short)FirmwarePayload.crc16(bin));
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            entry(zip,"manifest.json", "{\"manifest\":{\"application\":{\"bin_file\":\"application.bin\",\"dat_file\":\"application.dat\"}}}".getBytes(StandardCharsets.UTF_8));
            entry(zip,"application.bin",bin); entry(zip,"application.dat",dat);
        }
        return path;
    }
    static void entry(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(content); zip.closeEntry();
    }
}
