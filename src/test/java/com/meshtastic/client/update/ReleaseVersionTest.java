package com.meshtastic.client.update;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseVersionTest {
    @Test
    void ordersBuildsNumericallyAndStableAfterUnstable() {
        var versions = Stream.of("v2.4.0-unstable.9", "v2.4.0-unstable.10", "v2.4.0",
                "v2.4.0+build.11", "v2.4.0+build.12", "v2.4.1-unstable.13")
                .map(v -> ReleaseVersion.parse(v).orElseThrow()).toList();
        assertEquals(versions, versions.stream().sorted().toList());
        assertEquals(2003999, versions.getFirst().legacyCode());
        assertEquals(2004000, versions.get(3).legacyCode());
        assertEquals(11, versions.get(3).buildNumber());
        assertEquals("stable", versions.get(3).channel());
    }

    @Test
    void rejectsInvalidAndOverflowingVersions() {
        for (String value : List.of("dev", "2.4.0+local.1", "2.4.0+build.0", "2.4.0+build.01",
                "2.4.0-unstable.0", "2.04.0", "256.0.0", "2.256.0", "2.4.1000", "0.0.0",
                "2.4.0+build.4244635648", "9999999999999.0.0")) {
            assertTrue(ReleaseVersion.parse(value).isEmpty(), value);
        }
    }

    @Test
    void preservesLegacyCodeComparisonWhenANameIsUnknown() {
        assertTrue(ReleaseVersion.compare("new-dev-name", 201, "legacy", 200) > 0);
        assertEquals(0, ReleaseVersion.compare("unknown", 200, "legacy", 200));
    }
}
