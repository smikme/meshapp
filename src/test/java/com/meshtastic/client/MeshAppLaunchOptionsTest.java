package com.meshtastic.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class MeshAppLaunchOptionsTest {

    @Test
    void noSingleInstanceArgumentDisablesGuard() {
        assertTrue(MeshApp.isSingleInstanceGuardDisabled(new String[] {
                MeshApp.ARG_NO_SINGLE_INSTANCE
        }));
    }

    @Test
    void allowMultipleInstancesArgumentDisablesGuard() {
        assertTrue(MeshApp.isSingleInstanceGuardDisabled(new String[] {
                MeshApp.ARG_ALLOW_MULTIPLE_INSTANCES
        }));
    }

    @Test
    void unrelatedArgumentsKeepGuardEnabled() {
        assertFalse(MeshApp.isSingleInstanceGuardDisabled(new String[] {
                "--host",
                "127.0.0.1"
        }));
    }

    @Test
    void systemPropertyDisablesGuard() {
        String previous = System.getProperty(MeshApp.PROP_DISABLE_SINGLE_INSTANCE);
        try {
            System.setProperty(MeshApp.PROP_DISABLE_SINGLE_INSTANCE, "true");
            assertTrue(MeshApp.isSingleInstanceGuardDisabled(new String[0]));
        } finally {
            if (previous == null) {
                System.clearProperty(MeshApp.PROP_DISABLE_SINGLE_INSTANCE);
            } else {
                System.setProperty(MeshApp.PROP_DISABLE_SINGLE_INSTANCE, previous);
            }
        }
    }

    @Test
    void internalArgumentsAreStrippedBeforeAppArgumentParsing() {
        assertArrayEquals(
                new String[] {"--terminal", "--host", "127.0.0.1"},
                MeshApp.stripSingleInstanceArguments(new String[] {
                        "--terminal",
                        MeshApp.ARG_NO_SINGLE_INSTANCE,
                        "--host",
                        "127.0.0.1"
                })
        );
    }
}
