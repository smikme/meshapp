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
                "--rpc-router", "127.0.0.1:8080",
                "--print-rpc-key",
                "--no-autoconnect"
        });

        assertEquals("0.0.0.0", options.bindAddressOverride());
        assertEquals(44031, options.portOverride());
        assertEquals("mra1_test", options.accessKeyOverride());
        assertTrue(options.routerEnabledOverride());
        assertEquals("127.0.0.1:8080", options.routerServerOverride());
        assertTrue(options.printAccessKey());
        assertFalse(options.autoconnect());
    }

    @Test
    void parsesRouterAliasAndDisableOverride() {
        RpcServerOptions enabled = RpcServerOptions.parse(new String[] {
                "--rpc-server",
                "--rpc-router-server",
                "router.example.org:8080"
        });

        assertTrue(enabled.routerEnabledOverride());
        assertEquals("router.example.org:8080", enabled.routerServerOverride());

        RpcServerOptions disabled = RpcServerOptions.parse(new String[] {
                "--rpc-server",
                "--no-rpc-router"
        });

        assertFalse(disabled.routerEnabledOverride());
        assertNull(disabled.routerServerOverride());
    }

    @Test
    void acceptsRouterEnableWithoutServerOverride() {
        RpcServerOptions options = RpcServerOptions.parse(new String[] {
                "--rpc-server",
                "--print-rpc-key",
                "--rpc-router"
        });

        assertTrue(options.routerEnabledOverride());
        assertNull(options.routerServerOverride());
        assertTrue(options.printAccessKey());
    }

    @Test
    void defaultsToAutoconnectWithoutOverrides() {
        RpcServerOptions options = RpcServerOptions.parse(new String[] {
                "--rpc-server"
        });

        assertNull(options.bindAddressOverride());
        assertNull(options.portOverride());
        assertNull(options.accessKeyOverride());
        assertNull(options.routerEnabledOverride());
        assertNull(options.routerServerOverride());
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
