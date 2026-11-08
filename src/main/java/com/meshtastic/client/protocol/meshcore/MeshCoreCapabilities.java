package com.meshtastic.client.protocol.meshcore;

import java.util.Set;

/** Advertised limits and operations explicitly rejected by this firmware/build. */
public record MeshCoreCapabilities(
        Integer protocolVersion,
        Integer maxContacts,
        int maxChannels,
        Integer maxTxPowerDbm,
        Set<Integer> disabledCommands) {
    public MeshCoreCapabilities {
        disabledCommands = Set.copyOf(disabledCommands);
    }

    public String unsupportedReason(int command) {
        if (disabledCommands.contains(command))
            return "Operation rejected as unsupported/disabled by this firmware build";
        int minimum =
                switch (command) {
                    case 54, 55, 56 -> 8;
                    case 60 -> 9;
                    case 61 -> 10;
                    case 66 -> 14;
                    default -> 0;
                };
        if (protocolVersion != null && protocolVersion < minimum)
            return "Command requires Companion protocol "
                    + minimum
                    + "; device reports "
                    + protocolVersion;
        return null; // Unknown optional capabilities are probed and can return
                     // ERR_UNSUPPORTED/DISABLED.
    }
}
