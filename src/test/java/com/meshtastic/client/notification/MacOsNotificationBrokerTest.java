package com.meshtastic.client.notification;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacOsNotificationBrokerTest {

    @Test
    void clientFromRejectsInvalidEnvironment() {
        assertFalse(MacOsNotificationBroker.clientFrom(null, "token").isPresent());
        assertFalse(MacOsNotificationBroker.clientFrom("abc", "token").isPresent());
        assertFalse(MacOsNotificationBroker.clientFrom("65536", "token").isPresent());
        assertFalse(MacOsNotificationBroker.clientFrom("1234", "").isPresent());
    }

    @Test
    void clientFromAcceptsValidEnvironment() {
        Optional<MacOsNotificationBroker.Client> client =
                MacOsNotificationBroker.clientFrom("1234", "token");

        assertTrue(client.isPresent());
        assertEquals(1234, client.get().port());
        assertEquals("token", client.get().token());
    }

    @Test
    void clientSendsNotificationToLoopbackBroker() throws Exception {
        AtomicReference<String> title = new AtomicReference<>();
        AtomicReference<String> message = new AtomicReference<>();
        NotificationService service = (t, m) -> {
            title.set(t);
            message.set(m);
        };

        try (MacOsNotificationBroker.Server server = MacOsNotificationBroker.start(service)) {
            MacOsNotificationBroker.Endpoint endpoint = server.endpoint();
            MacOsNotificationBroker.Client client =
                    new MacOsNotificationBroker.Client(endpoint.port(), endpoint.token());

            assertTrue(client.showNotification("MeshApp", "Connected"));
            assertEquals("MeshApp", title.get());
            assertEquals("Connected", message.get());
        }
    }

    @Test
    void brokerRejectsInvalidToken() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NotificationService service = (title, message) -> calls.incrementAndGet();

        try (MacOsNotificationBroker.Server server = MacOsNotificationBroker.start(service)) {
            MacOsNotificationBroker.Client client =
                    new MacOsNotificationBroker.Client(server.endpoint().port(), "wrong-token");

            assertFalse(client.showNotification("MeshApp", "Connected"));
            assertEquals(0, calls.get());
        }
    }
}
