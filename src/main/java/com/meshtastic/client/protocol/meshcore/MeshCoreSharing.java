package com.meshtastic.client.protocol.meshcore;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Official meshcore:// links, as specified in MeshCore/docs/qr_codes.md. */
public final class MeshCoreSharing {
    private MeshCoreSharing() {}

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static String channelLink(MeshCoreChannel channel) {
        return "meshcore://channel/add?name="
                + encode(channel.name())
                + "&secret="
                + HexFormat.of().formatHex(channel.secret());
    }

    public static String contactLink(MeshCoreContact contact) {
        return "meshcore://contact/add?name="
                + encode(contact.name())
                + "&public_key="
                + HexFormat.of().formatHex(contact.publicKey())
                + "&type="
                + contact.type();
    }

    public static MeshCoreChannel channelFromLink(int slot, String link) {
        Map<String, String> q = query(link, "channel");
        if (q.containsKey("region_scope") && !q.get("region_scope").isBlank())
            throw new IllegalArgumentException(
                    "This link includes region_scope; configure the matching flood scope explicitly before importing");
        String name = q.get("name"), key = q.get("secret");
        if (name == null || key == null || !key.matches("[0-9a-fA-F]{32}"))
            throw new IllegalArgumentException(
                    "Channel link requires name and a 16-byte hex secret");
        byte[] secret = HexFormat.of().parseHex(key);
        MeshCoreCommands.channel(slot, name, secret);
        return new MeshCoreChannel(slot, name, secret);
    }

    public static MeshCoreContact contactFromLink(String link) {
        Map<String, String> q = query(link, "contact");
        return MeshCoreContact.create(
                MeshCoreCommands.key(q.get("public_key")),
                q.getOrDefault("name", ""),
                Integer.parseInt(q.getOrDefault("type", "1")),
                0,
                0,
                0);
    }

    private static Map<String, String> query(String link, String host) {
        URI uri = URI.create(link.trim());
        if (!"meshcore".equals(uri.getScheme())
                || !host.equals(uri.getHost())
                || !"/add".equals(uri.getPath())
                || uri.getRawQuery() == null)
            throw new IllegalArgumentException("Invalid MeshCore sharing link");
        Map<String, String> values = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid link parameter");
            String k = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (values.put(k, URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null)
                throw new IllegalArgumentException("Duplicate link parameter");
        }
        return values;
    }
}
