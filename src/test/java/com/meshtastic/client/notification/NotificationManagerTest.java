package com.meshtastic.client.notification;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.utils.AppPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class NotificationManagerTest {

    @Test
    void remoteChatMuteSuppressesOnlyMatchingRpcConnection(@TempDir Path tempHome) {
        TestEnvironmentSupport.setUserHome(tempHome);
        AppPreferences.setNotificationsEnabled(true);
        String mutedOwner = AppPreferences.remoteChatOwnerId("rpc-1");
        String otherOwner = AppPreferences.remoteChatOwnerId("rpc-2");
        AppPreferences.setChatMuted(mutedOwner, "dm", "!12345678", true);

        assertFalse(NotificationManager.areChatNotificationsEnabled(
                mutedOwner, "dm", "!12345678"));
        assertTrue(NotificationManager.areChatNotificationsEnabled(
                mutedOwner, "dm", "!87654321"));
        assertTrue(NotificationManager.areChatNotificationsEnabled(
                otherOwner, "dm", "!12345678"));
    }
}
