package com.meshtastic.client.service;

import com.meshtastic.client.connection.ble.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class FirmwareBleUploaderTest {
    @TempDir Path temp;
    @Test void espHandlesFragmentedHandshakeAndWaitsForEveryAck() throws Exception {
        byte[] bytes = FirmwareTestImages.esp(); Path file = temp.resolve("image.bin"); Files.write(file,bytes);
        var device = new FakeBle() {
            final StringBuilder command = new StringBuilder();
            @Override public boolean writeToRadio(byte[] data) {
                assertTrue(data.length <= 20);
                if (command.indexOf("\n") < 0) {
                    command.append(new String(data, StandardCharsets.UTF_8));
                    if (command.indexOf("\n") >= 0) {
                        assertTrue(command.toString().startsWith("OTA 304 "));
                        reply("ERAS"); reply("ING\nO"); reply("K\n");
                    }
                } else {
                    payload.writeBytes(data);
                    reply(payload.size() == bytes.length ? "OK\n" : "ACK\n");
                }
                return true;
            }
        };
        FirmwareBleUploader.uploadEsp(device,FirmwareImage.analyze(file),null);
        assertArrayEquals(bytes,device.payload.toByteArray());
    }
    @Test void espPreservesBootloaderRefusalWithoutSendingPayload() throws Exception {
        Path file = temp.resolve("image.bin"); Files.write(file,FirmwareTestImages.esp());
        var device = new FakeBle() {
            @Override public boolean writeToRadio(byte[] data) {
                if (data[data.length-1] == '\n') reply("ERR Hash Rejected\n");
                return true;
            }
        };
        IOException error = assertThrows(IOException.class, () -> FirmwareBleUploader.uploadEsp(device,FirmwareImage.analyze(file),null));
        assertTrue(error.getMessage().contains("Hash Rejected")); assertEquals(0,device.payload.size());
    }
    @Test void nordicUsesControlPointAndReceiptNotifications() throws Exception {
        Path archive = FirmwareTestImages.nordic(temp.resolve("dfu.zip"));
        try (var p = FirmwarePayload.prepare(archive,null)) {
            var device = new FakeBle() {
                int stage = 0, packets = 0;
                final List<Integer> commands = new ArrayList<>();
                @Override public boolean writeFirmwareControl(byte[] data) {
                    commands.add((int)data[0]);
                    switch (data[0]) {
                        case 1 -> { assertEquals(4,data[1]); stage = 1; }
                        case 2 -> { stage = 2; if (data[1] == 1) response(2); }
                        case 8 -> assertArrayEquals(new byte[]{8,10,0},data);
                        case 3 -> stage = 3;
                        case 4 -> response(4);
                        case 5 -> stage = 5;
                        default -> fail("Unexpected control opcode");
                    }
                    return true;
                }
                @Override public boolean writeToRadio(byte[] data) {
                    assertTrue(data.length <= 20);
                    if (stage == 1) { assertEquals(220,FirmwarePayload.u32(data,8)); response(1); }
                    else if (stage == 2) assertArrayEquals(p.initPacket,data);
                    else if (stage == 3) {
                        payload.writeBytes(data); packets++;
                        if (packets % 10 == 0) listener.accept(ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte)17).putInt(payload.size()).array());
                        if (payload.size() == p.image.sizeBytes()) response(3);
                    } else fail("Data at unexpected stage");
                    return true;
                }
                void response(int op) { listener.accept(new byte[]{16,(byte)op,1}); }
            };
            FirmwareBleUploader.uploadNordic(device,p,null);
            assertArrayEquals(Files.readAllBytes(p.image.path()),device.payload.toByteArray());
            assertEquals(List.of(1,2,2,8,3,4,5),device.commands); assertEquals(5,device.stage);
        }
    }
    @Test void espReportsResponseOverflowBeforeSendingPayload() throws Exception {
        Path file = temp.resolve("image.bin"); Files.write(file, FirmwareTestImages.esp());
        var device = new FakeBle() {
            boolean commandComplete;
            @Override public boolean writeToRadio(byte[] data) {
                if (commandComplete) payload.writeBytes(data);
                else if (data[data.length - 1] == '\n') {
                    commandComplete = true;
                    reply("OK\n" + "ACK\n".repeat(300));
                }
                return true;
            }
        };
        IOException error = assertThrows(IOException.class,
                () -> FirmwareBleUploader.uploadEsp(device, FirmwareImage.analyze(file), null));
        assertTrue(error.getMessage().contains("response overflow"));
        assertEquals(0, device.payload.size());
    }
    @Test void espDisconnectDuringHandshakeAbortsWithoutSendingPayload() throws Exception {
        Path file = temp.resolve("image.bin"); Files.write(file, FirmwareTestImages.esp());
        var device = new FakeBle() {
            boolean commandComplete;
            @Override public boolean writeToRadio(byte[] data) {
                if (commandComplete) payload.writeBytes(data);
                else if (data[data.length - 1] == '\n') {
                    commandComplete = true;
                    stateListener.accept(new BleState.Disconnected());
                }
                return true;
            }
        };
        IOException error = assertThrows(IOException.class,
                () -> FirmwareBleUploader.uploadEsp(device, FirmwareImage.analyze(file), null));
        assertTrue(error.getMessage().contains("disconnected"));
        assertEquals(0, device.payload.size());
    }
    @Test void nordicRejectsValidationFailureBeforeActivation() throws Exception {
        Path archive = FirmwareTestImages.nordic(temp.resolve("dfu.zip"));
        try (var p = FirmwarePayload.prepare(archive,null)) {
            var device = new FakeBle() {
                @Override public boolean writeFirmwareControl(byte[] data) { return true; }
                @Override public boolean writeToRadio(byte[] data) { listener.accept(new byte[]{16,1,4}); return true; }
            };
            assertThrows(IOException.class, () -> FirmwareBleUploader.uploadNordic(device,p,null));
        }
    }
    @Test void nordicBootloaderAddressUsesFullMacIncrement() {
        assertEquals("AA:BB:CC:DD:EF:00",FirmwareBleUploader.incrementMac("AA:BB:CC:DD:EE:FF"));
        assertEquals("",FirmwareBleUploader.incrementMac("corebluetooth-uuid"));
    }
    private static class FakeBle implements BlePlatform {
        Consumer<byte[]> listener;
        Consumer<BleState> stateListener;
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        void reply(String text) { listener.accept(text.getBytes(StandardCharsets.UTF_8)); }
        public void startScan(Consumer<BleDevice> c) {}
        public void stopScan() {}
        public void connect(String address) {}
        public void disconnect() {}
        public boolean isConnected() { return true; }
        public boolean writeToRadio(byte[] data) { return true; }
        public void setFromRadioListener(Consumer<byte[]> c) { listener = c; }
        public void setStateListener(Consumer<BleState> c) { stateListener = c; }
        public AdapterState getAdapterState() { return AdapterState.POWERED_ON; }
        public void dispose() {}
    }
}
