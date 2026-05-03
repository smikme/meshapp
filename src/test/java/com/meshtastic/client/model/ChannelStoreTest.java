package com.meshtastic.client.model;

import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ChannelProtos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class ChannelStoreTest {

    @Test
    void getChannelsReturnsEmptyListByDefault() {
        ChannelStore store = new ChannelStore();
        
        List<ChannelProtos.Channel> channels = store.getChannels();
        assertNotNull(channels);
        assertTrue(channels.isEmpty());
    }

    @Test
    void addChannelAddsNewChannel() {
        ChannelStore store = new ChannelStore();
        
        ChannelProtos.Channel channel = createChannel(1, ChannelProtos.Channel.Role.SECONDARY);
        store.addChannel(channel);
        
        List<ChannelProtos.Channel> channels = store.getChannels();
        assertEquals(1, channels.size());
        assertEquals(1, channels.getFirst().getIndex());
    }

    @Test
    void addChannelReplacesExistingChannelWithSameIndex() {
        ChannelStore store = new ChannelStore();
        
        ChannelProtos.Channel channel1 = createChannel(2, ChannelProtos.Channel.Role.SECONDARY);
        store.addChannel(channel1);
        
        ChannelProtos.Channel channel2 = createChannel(2, ChannelProtos.Channel.Role.DISABLED);
        store.addChannel(channel2);
        
        List<ChannelProtos.Channel> channels = store.getChannels();
        assertEquals(1, channels.size());
        assertEquals(ChannelProtos.Channel.Role.DISABLED, channels.getFirst().getRole());
    }

    @Test
    void updateChannelAddsNewChannel() {
        ChannelStore store = new ChannelStore();
        
        ChannelProtos.Channel channel = createChannel(1, ChannelProtos.Channel.Role.SECONDARY);
        store.updateChannel(channel);
        
        List<ChannelProtos.Channel> channels = store.getChannels();
        assertEquals(1, channels.size());
    }

    @Test
    void updateChannelUpdatesExistingChannel() {
        ChannelStore store = new ChannelStore();
        
        ChannelProtos.Channel channel1 = createChannel(3, ChannelProtos.Channel.Role.SECONDARY);
        store.addChannel(channel1);
        
        ChannelProtos.Channel channel2 = createChannel(3, ChannelProtos.Channel.Role.PRIMARY);
        store.updateChannel(channel2);
        
        List<ChannelProtos.Channel> channels = store.getChannels();
        assertEquals(1, channels.size());
        assertEquals(ChannelProtos.Channel.Role.PRIMARY, channels.getFirst().getRole());
    }

    @Test
    void getChannelByIndexReturnsExistingChannel() {
        ChannelStore store = new ChannelStore();
        
        ChannelProtos.Channel channel = createChannel(4, ChannelProtos.Channel.Role.SECONDARY);
        store.addChannel(channel);
        
        ChannelProtos.Channel found = store.getChannelByIndex(4);
        assertNotNull(found);
        assertEquals(4, found.getIndex());
    }

    @Test
    void getChannelByIndexReturnsNullForNonExistent() {
        ChannelStore store = new ChannelStore();
        
        assertNull(store.getChannelByIndex(99));
    }

    @Test
    void hasEnabledChannelReturnsTrueForActiveChannel() {
        ChannelStore store = new ChannelStore();
        
        store.addChannel(createChannel(1, ChannelProtos.Channel.Role.SECONDARY));
        
        assertTrue(store.hasEnabledChannel(1));
    }

    @Test
    void hasEnabledChannelReturnsFalseForDisabled() {
        ChannelStore store = new ChannelStore();
        
        store.addChannel(createChannel(2, ChannelProtos.Channel.Role.DISABLED));
        
        assertFalse(store.hasEnabledChannel(2));
    }

    @Test
    void findFirstAvailableChannelSlotReturnsFirstFreeSlot() {
        ChannelStore store = new ChannelStore();
        
        // Заняты 1,2,3 - свободен 4
        store.addChannel(createChannel(1, ChannelProtos.Channel.Role.SECONDARY));
        store.addChannel(createChannel(2, ChannelProtos.Channel.Role.SECONDARY));
        store.addChannel(createChannel(3, ChannelProtos.Channel.Role.SECONDARY));
        
        assertEquals(4, store.findFirstAvailableChannelSlot());
    }

    @Test
    void findFirstAvailableChannelSlotReturnsMinusOneWhenAllFull() {
        ChannelStore store = new ChannelStore();
        
        // Заняты 1,2,3,4,5,6,7
        for (int i = 1; i <= 7; i++) {
            store.addChannel(createChannel(i, ChannelProtos.Channel.Role.SECONDARY));
        }
        
        assertEquals(-1, store.findFirstAvailableChannelSlot());
    }

    @Test
    void isChannelCatalogReadyDefaultsToFalse() {
        ChannelStore store = new ChannelStore();
        
        assertFalse(store.isChannelCatalogReady());
    }

    @Test
    void setChannelCatalogReadyUpdatesFlag() {
        ChannelStore store = new ChannelStore();
        
        store.setChannelCatalogReady(true);
        
        assertTrue(store.isChannelCatalogReady());
    }

    @Test
    void getChannelCatalogEpochDefaultsToZero() {
        ChannelStore store = new ChannelStore();
        
        assertEquals(0, store.getChannelCatalogEpoch());
    }

    @Test
    void incrementChannelCatalogEpochIncreasesValue() {
        ChannelStore store = new ChannelStore();
        
        long initial = store.getChannelCatalogEpoch();
        store.incrementChannelCatalogEpoch();
        
        assertEquals(initial + 1, store.getChannelCatalogEpoch());
    }

    @Test
    void clearResetsAllState() {
        ChannelStore store = new ChannelStore();
        
        store.addChannel(createChannel(1, ChannelProtos.Channel.Role.SECONDARY));
        store.setChannelCatalogReady(true);
        store.incrementChannelCatalogEpoch();
        
        store.clear();
        
        assertTrue(store.getChannels().isEmpty());
        assertFalse(store.isChannelCatalogReady());
        assertEquals(0, store.getChannelCatalogEpoch());
    }

    private static ChannelProtos.Channel createChannel(int index, ChannelProtos.Channel.Role role) {
        return ChannelProtos.Channel.newBuilder()
                .setIndex(index)
                .setRole(role)
                .build();
    }
}