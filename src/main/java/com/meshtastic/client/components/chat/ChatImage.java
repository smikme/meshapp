package com.meshtastic.client.components.chat;

import java.time.Instant;

/** Public message link and the separate binary resources used by JavaFX. */
public record ChatImage(String id, String shareUrl, String previewUrl, String fullUrl, Instant expiresAt) {
    public boolean isExpired() {
        return expiresAt != null && !expiresAt.isAfter(Instant.now());
    }
}
