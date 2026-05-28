package com.meshtastic.client.terminal;

import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;

import java.util.List;

/**
 * Immutable snapshot consumed by {@link TerminalRenderer}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
record TerminalRenderState(boolean showHelp,
                           boolean showLogbackLog,
                           boolean commandMode,
                           String commandBuffer,
                           FocusPane activePane,
                           List<ConnectionEntry> connections,
                           int selectedConnectionIndex,
                           boolean connectedView,
                           ConnectionEntry activeConnectionEntry,
                           ActiveConnection activeConnection,
                           DeviceState boundState,
                           int selectedChannelIndex,
                           String selectedDmPeer,
                           List<TerminalChat> chatItems,
                           int selectedChatIndex,
                           List<MeshMessage> loadedMessages,
                           int selectedMessageIndex,
                           int messageTopIndex,
                           String inputText,
                           int inputCaret,
                           boolean inputEnabled,
                           int maxInputBytes,
                           int inputByteLength,
                           MeshMessage replyToMessage,
                           List<String> activity) {
}
