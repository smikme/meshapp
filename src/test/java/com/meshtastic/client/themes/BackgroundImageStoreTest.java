package com.meshtastic.client.themes;

import com.meshtastic.client.TestEnvironmentSupport;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundImageStoreTest {
    @TempDir Path home;

    @BeforeEach void setUp() { TestEnvironmentSupport.setUserHome(home); }

    @Test void largeImageIsCopiedResizedAndSurvivesRemovalOfOriginal() throws Exception {
        Path source = home.resolve("original.png");
        BufferedImage picture = new BufferedImage(4800, 1200, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(picture, "png", source.toFile());
        picture.flush();
        String id = BackgroundImageStore.importImage(source).get(10, TimeUnit.SECONDS);
        Files.delete(source);
        var image = BackgroundImageStore.load(id).get(10, TimeUnit.SECONDS);
        assertNotNull(image);
        assertTrue(image.getWidth() <= BackgroundImageStore.MAX_EDGE);
        assertEquals(4, image.getWidth() / image.getHeight(), .01);
        assertTrue(Files.exists(BackgroundImageStore.directory().resolve(id)));
    }

    @Test void cleanupKeepsSharedReferencesAndOnlyDeletesKnownManagedCopies() throws Exception {
        Path source = home.resolve("original.png");
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", source.toFile());
        String id = BackgroundImageStore.importImage(source).get(10, TimeUnit.SECONDS);
        String draft = BackgroundImageStore.importImage(source).get(10, TimeUnit.SECONDS);
        ChatBackground background = new ChatBackground(ChatBackground.Kind.IMAGE, null, null, id, 0, 0, false);
        AppearancePreferences.setGlobalBackground(background);
        AppearancePreferences.setChatBackground("owner", "dm:peer", background);
        AppearancePreferences.setGlobalBackground(ChatBackground.defaults());
        BackgroundImageStore.removeUnused(Set.of(id)).get(10, TimeUnit.SECONDS);
        assertTrue(Files.exists(BackgroundImageStore.directory().resolve(id)));
        assertTrue(Files.exists(BackgroundImageStore.directory().resolve(draft)), "another draft must not be removed");
        AppearancePreferences.setChatBackground("owner", "dm:peer", null);
        BackgroundImageStore.removeUnused(Set.of(id, draft)).get(10, TimeUnit.SECONDS);
        assertFalse(Files.exists(BackgroundImageStore.directory().resolve(id)));
        assertFalse(Files.exists(BackgroundImageStore.directory().resolve(draft)));
        assertTrue(Files.exists(source));
    }

    @Test void corruptUnsupportedAndOversizedFilesAreRejectedWithoutCreatingCopies() throws Exception {
        Path invalid = home.resolve("invalid.png");
        Files.writeString(invalid, "not an image");
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> BackgroundImageStore.importImage(invalid).get(10, TimeUnit.SECONDS));
        Path gif = home.resolve("test.gif");
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "gif", gif.toFile());
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> BackgroundImageStore.importImage(gif).get(10, TimeUnit.SECONDS));
        Path large = home.resolve("large.png");
        try (var channel = Files.newByteChannel(large, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(BackgroundImageStore.MAX_FILE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> BackgroundImageStore.importImage(large).get(10, TimeUnit.SECONDS));
        assertFalse(Files.exists(BackgroundImageStore.directory()));
        assertNull(BackgroundImageStore.load("../../original.png").get(10, TimeUnit.SECONDS));
        assertNull(BackgroundImageStore.load("00000000-0000-0000-0000-000000000000.png").get(10, TimeUnit.SECONDS));
    }
}
