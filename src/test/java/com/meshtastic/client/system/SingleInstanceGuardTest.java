package com.meshtastic.client.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class SingleInstanceGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void secondAcquireActivatesExistingInstanceAndDoesNotReceiveGuard() throws Exception {
        try (SingleInstanceGuard first = SingleInstanceGuard.acquire(tempDir).orElseThrow()) {
            CountDownLatch activated = new CountDownLatch(1);
            first.setActivationHandler(activated::countDown);

            Optional<SingleInstanceGuard> second = SingleInstanceGuard.acquire(tempDir);

            assertTrue(second.isEmpty());
            assertTrue(activated.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void staleFilesDoNotBlockLaunchAfterPreviousProcessDied() throws Exception {
        Files.writeString(tempDir.resolve("meshapp.lock"), "stale");
        Files.writeString(tempDir.resolve("meshapp.instance"), "port=1\ntoken=stale\n");

        try (SingleInstanceGuard guard = SingleInstanceGuard.acquire(tempDir).orElseThrow()) {
            assertTrue(Files.isRegularFile(tempDir.resolve("meshapp.instance")));
        }
    }
}
