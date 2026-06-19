package com.meshtastic.client.components.chat;

/**
 * Public MeshFiles image URLs derived from a chat message or upload response.
 *
 * @param id file identifier
 * @param url public original image URL
 * @param previewUrl public generated preview URL
 */
public record MeshFilesImage(String id, String url, String previewUrl) {}
