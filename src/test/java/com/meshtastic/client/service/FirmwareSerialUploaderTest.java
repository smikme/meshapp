package com.meshtastic.client.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class FirmwareSerialUploaderTest {
    @TempDir Path temp;
    @Test void usesExplicitPortAndKeepsPathsAsArguments() throws Exception {
        Path file = temp.resolve("image with spaces.bin"); Files.write(file,FirmwareTestImages.esp());
        var image = FirmwareImage.analyze(file);
        var tool = new FirmwareSerialUploader(List.of("/path with spaces/esptool"),true);
        assertEquals(List.of("/path with spaces/esptool","--port","COM12","--baud","115200","write-flash","0x10000",file.toString()),tool.arguments("COM12",image));
        assertThrows(IllegalArgumentException.class, () -> tool.arguments("",image));
        assertTrue(new FirmwareSerialUploader(List.of("python3","-m","esptool"),false).arguments("/dev/ttyUSB0",image).contains("write_flash"));
    }
    @Test void propagatesToolFailureAndBoundsExecutionTime() throws Exception {
        List<String> java = List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(), "-cp",
                Path.of(FirmwareSerialUploaderTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Child.class.getName());
        var ok = new ArrayList<>(java); ok.add("ok");
        assertTrue(FirmwareSerialUploader.run(ok,Duration.ofSeconds(5),null).contains("verified"));
        var fail = new ArrayList<>(java); fail.add("fail");
        assertTrue(assertThrows(IOException.class, () -> FirmwareSerialUploader.run(fail,Duration.ofSeconds(5),null)).getMessage().contains("device refused"));
        var slow = new ArrayList<>(java); slow.add("slow");
        assertThrows(IOException.class, () -> FirmwareSerialUploader.run(slow,Duration.ofMillis(100),null));
    }
    public static class Child {
        static void main(String[] args) throws Exception {
            if (args[0].equals("slow")) Thread.sleep(60000);
            if (args[0].equals("fail")) { System.out.println("device refused"); System.exit(2); }
            System.out.println("Hash of data verified.");
        }
    }
}
