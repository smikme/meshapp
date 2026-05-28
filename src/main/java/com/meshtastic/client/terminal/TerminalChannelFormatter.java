package com.meshtastic.client.terminal;

import com.meshtastic.client.model.DeviceState;
import org.meshtastic.proto.ChannelProtos;

import java.util.ArrayList;
import java.util.List;

/**
 * Channel naming and selection helpers for terminal mode.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TerminalChannelFormatter {

    private TerminalChannelFormatter() {
    }

    static List<Integer> availableChannelIndexes(DeviceState state) {
        if (state == null || state.getChannels() == null || state.getChannels().isEmpty()) {
            return List.of();
        }
        return state.getChannels().stream()
                .mapToInt(ChannelProtos.Channel::getIndex)
                .filter(index -> index >= 0)
                .distinct()
                .sorted()
                .boxed()
                .toList();
    }

    static List<Integer> visibleChannelIndexes(DeviceState state, int selectedChannelIndex) {
        List<Integer> channels = new ArrayList<>(availableChannelIndexes(state));
        if (channels.isEmpty()) {
            channels.add(selectedChannelIndex);
        } else if (!channels.contains(selectedChannelIndex)) {
            channels.add(selectedChannelIndex);
            channels.sort(Integer::compareTo);
        }
        return channels;
    }

    static String channelDescription(DeviceState state, int channelIndex) {
        if (state == null) {
            return "waiting for device state";
        }
        String name = channelName(state, channelIndex);
        return name == null ? "index " + channelIndex : "index " + channelIndex + " | " + name;
    }

    static String channelLabel(DeviceState state, int channelIndex) {
        String name = channelName(state, channelIndex);
        return name == null ? "channel " + channelIndex : "channel " + channelIndex + " " + name;
    }

    static String channelName(DeviceState state, int channelIndex) {
        if (state == null || state.getChannels() == null) {
            return null;
        }
        for (ChannelProtos.Channel channel : state.getChannels()) {
            if (channel.getIndex() == channelIndex) {
                String name = channel.getSettings().getName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
                return channelIndex == 0 ? "Primary" : null;
            }
        }
        return null;
    }
}
