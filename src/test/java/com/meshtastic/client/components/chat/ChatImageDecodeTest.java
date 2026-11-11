package com.meshtastic.client.components.chat;

import com.meshtastic.client.TestEnvironmentSupport;
import javafx.scene.image.Image;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatImageDecodeTest {
    @BeforeAll
    static void startJavaFx() { TestEnvironmentSupport.ensureJavaFxStarted(); }

    @Test
    void decodesMeshpicBinaryResponseWithoutWebpPlugin() throws Exception {
        // Captured from our synthetic 2x2 upload, not from a user's image.
        // Live PNG and WebP probes both returned image/jpeg on 2026-11-11.
        try (var data = getClass().getResourceAsStream("/images/meshpic-response.jpg")) {
            assertNotNull(data);
            Image image = new Image(data);
            assertFalse(image.isError());
            assertEquals(2, image.getWidth());
            assertEquals(2, image.getHeight());
        }
    }
}
