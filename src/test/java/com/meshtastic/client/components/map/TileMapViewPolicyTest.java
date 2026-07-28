package com.meshtastic.client.components.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TileMapViewPolicyTest {

    @Test
    void userAgentIdentifiesThePublicProjectAndContact() {
        assertEquals(
                "MeshApp/2.3.10 (+https://github.com/smikme/meshapp; contact: ks@privatepractice.app)",
                TileMapView.userAgent("2.3.10")
        );
    }
}
