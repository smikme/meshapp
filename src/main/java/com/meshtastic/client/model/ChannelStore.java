package com.meshtastic.client.model;

import org.meshtastic.proto.ChannelProtos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Collections;

/**
 * Thread-safe store for Meshtastic channel configuration.
 * <p>
 * The store keeps channels addressable by index, updates or replaces known
 * entries, finds free SECONDARY slots, and answers whether a channel is active.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ChannelStore {

    /** Channel list guarded by the synchronized list wrapper. */
    private final List<ChannelProtos.Channel> channels = Collections.synchronizedList(new ArrayList<>());

    /** Whether the channel catalog has been fully loaded. */
    private volatile boolean channelCatalogReady = false;

    /** Channel catalog version used by UI caches. */
    private final AtomicLong channelCatalogEpoch = new AtomicLong(0);

    /**
     * Returns the backing channel list.
     *
     * @return channel list
     */
    public List<ChannelProtos.Channel> getChannels() {
        return channels;
    }

    /**
     * Adds a channel, replacing the existing channel with the same index.
     *
     * @param channel channel to add
     */
    public void addChannel(ChannelProtos.Channel channel) {
        synchronized (channels) {
            for (int i = 0; i < channels.size(); i++) {
                ChannelProtos.Channel existing = channels.get(i);
                if (existing.getIndex() == channel.getIndex()) {
                    channels.set(i, preserveExistingPsk(existing, channel));
                    return;
                }
            }
            channels.add(channel);
        }
    }

    /**
     * Updates a channel by index, adding it when no existing entry is present.
     *
     * @param channel channel to update
     */
    public void updateChannel(ChannelProtos.Channel channel) {
        synchronized (channels) {
            boolean updated = false;
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).getIndex() == channel.getIndex()) {
                    channels.set(i, channel);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                channels.add(channel);
            }
        }
    }

    /**
     * Returns a channel by index.
     *
     * @param channelIndex channel index
     * @return channel, or {@code null} when it is unknown
     */
    public ChannelProtos.Channel getChannelByIndex(int channelIndex) {
        synchronized (channels) {
            for (ChannelProtos.Channel ch : channels) {
                if (ch.getIndex() == channelIndex) {
                    return ch;
                }
            }
        }
        return null;
    }

    /**
     * Returns whether an enabled channel exists at the given index.
     *
     * @param channelIndex channel index
     * @return {@code true} when the channel exists and is not disabled
     */
    public boolean hasEnabledChannel(int channelIndex) {
        synchronized (channels) {
            for (ChannelProtos.Channel channel : channels) {
                if (channel.getIndex() == channelIndex
                        && channel.getRole() != ChannelProtos.Channel.Role.DISABLED) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Finds the first available SECONDARY channel slot in the 1-7 range.
     *
     * @return available channel index, or {@code -1} when every slot is occupied
     */
    public int findFirstAvailableChannelSlot() {
        synchronized (channels) {
            Set<Integer> usedIndices = new HashSet<>();
            for (ChannelProtos.Channel ch : channels) {
                if (ch.getRole() != ChannelProtos.Channel.Role.DISABLED) {
                    usedIndices.add(ch.getIndex());
                }
            }
            for (int i = 1; i <= 7; i++) {
                if (!usedIndices.contains(i)) { return i; }
            }
        }
        return -1;
    }

    /**
     * Returns whether the channel catalog is ready.
     *
     * @return {@code true} when the catalog is ready
     */
    public boolean isChannelCatalogReady() {
        return channelCatalogReady;
    }

    /**
     * Sets whether the channel catalog is ready.
     *
     * @param channelCatalogReady new readiness value
     */
    public void setChannelCatalogReady(boolean channelCatalogReady) {
        this.channelCatalogReady = channelCatalogReady;
    }

    /**
     * Returns the channel catalog version used by UI caches.
     *
     * @return current channel catalog epoch
     */
    public long getChannelCatalogEpoch() {
        return channelCatalogEpoch.get();
    }

    /**
     * Increments the channel catalog epoch to invalidate UI caches.
     */
    public void incrementChannelCatalogEpoch() {
        channelCatalogEpoch.incrementAndGet();
    }

    /**
     * Clears all channels and resets catalog state.
     */
    public void clear() {
        channels.clear();
        channelCatalogReady = false;
        channelCatalogEpoch.set(0);
    }

    private static ChannelProtos.Channel preserveExistingPsk(ChannelProtos.Channel existing,
                                                             ChannelProtos.Channel incoming) {
        if (!existing.hasSettings() || !incoming.hasSettings()) {
            return incoming;
        }
        if (incoming.getSettings().getPsk().size() != 0 || existing.getSettings().getPsk().size() == 0) {
            return incoming;
        }
        return incoming.toBuilder()
                .setSettings(incoming.getSettings().toBuilder()
                        .setPsk(existing.getSettings().getPsk()))
                .build();
    }
}
