package com.meshtastic.client.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class RpcServerOptionsTest {

    @Test
    void detectsRpcServerMode() {
        assertTrue(RpcServerOptions.isRpcServerMode(new String[] {
                "--rpc-server"
        }));
        assertFalse(RpcServerOptions.isRpcServerMode(new String[] {
                "--terminal"
        }));
    }

    @Test
    void parsesServerOverrides() {
        RpcServerOptions options = RpcServerOptions.parse(new String[] {
                "--rpc-server",
                "--rpc-bind", "0.0.0.0",
                "--rpc-port", "44031",
                "--rpc-key", "mra1_test",
                "--print-rpc-key",
                "--no-autoconnect"
        });

        assertEquals("0.0.0.0", options.bindAddressOverride());
        assertEquals(44031, options.portOverride());
        assertEquals("mra1_test", options.accessKeyOverride());
        assertTrue(options.printAccessKey());
        assertFalse(options.autoconnect());
    }

    @Test
    void defaultsToAutoconnectWithoutOverrides() {
        RpcServerOptions options = RpcServerOptions.parse(new String[] {
                "--rpc-server"
        });

        assertNull(options.bindAddressOverride());
        assertNull(options.portOverride());
        assertNull(options.accessKeyOverride());
        assertTrue(options.autoconnect());
    }

    @Test
    void acceptsInternalRunIdMarker() {
        RpcServerOptions options = RpcServerOptions.parse(new String[] {
                "--rpc-server",
                "--rpc-run-id",
                "debug-run"
        });

        assertTrue(options.autoconnect());
    }

    @Test
    void stripsRoutingFlag() {
        assertArrayEquals(
                new String[] {"--rpc-bind", "127.0.0.1"},
                RpcServerOptions.stripRpcServerFlag(new String[] {
                        "--rpc-server",
                        "--rpc-bind",
                        "127.0.0.1"
                })
        );
    }

    @Test
    void rejectsInvalidPort() {
        assertThrows(IllegalArgumentException.class, () ->
                RpcServerOptions.parse(new String[] {
                        "--rpc-server",
                        "--rpc-port",
                        "70000"
                }));
    }
}
