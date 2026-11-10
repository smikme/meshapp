package com.meshtastic.client.protocol.meshcore;

/** Durable attempt of a logical message, independent of its local database ID and radio ACK hash. */
public record MeshCoreSendAttempt(
        int attempt, long startedAt, String status, Long ackHash, Long timeoutMs, String error) {}
