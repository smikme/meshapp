package com.meshtastic.client.model;

import java.nio.charset.StandardCharsets;

/**
 * Full payload artifact used by the non-privileged self-update flow.
 */
public class SelfUpdateArtifact {

    private String type;
    private String format;
    private String url;
    private String sha256;
    private long size;
    private String signature;
    private String version;

    public String getType() { return type; }
    public String getFormat() { return format; }
    public String getUrl() { return url; }
    public String getSha256() { return sha256; }
    public long getSize() { return size; }
    public String getSignature() { return signature; }
    public String getVersion() { return version; }

    public boolean isFullArchive() {
        return type == null
                || type.isBlank()
                || "full-archive".equalsIgnoreCase(type.trim());
    }

    public boolean isZip() {
        return format == null
                || format.isBlank()
                || "zip".equalsIgnoreCase(format.trim());
    }

    public boolean hasDownload() {
        return url != null && !url.isBlank()
                && sha256 != null && !sha256.isBlank();
    }

    /**
     * Stable payload signed by release automation for this artifact.
     */
    public byte[] signaturePayload(String manifestVersion, int manifestVersionCode) {
        String payload = String.join("\n",
                "meshapp-self-update-v1",
                manifestVersion != null ? manifestVersion : "",
                Integer.toString(manifestVersionCode),
                normalize(type, "full-archive"),
                normalize(format, "zip"),
                normalize(version, ""),
                normalize(url, ""),
                normalize(sha256, ""),
                Long.toString(size)
        ) + "\n";
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
