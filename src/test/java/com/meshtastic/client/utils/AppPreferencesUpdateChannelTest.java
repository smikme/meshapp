package com.meshtastic.client.utils;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.utils.AppPreferences.UpdateChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPreferencesUpdateChannelTest {
    @TempDir
    Path home;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(home);
    }

    @AfterEach
    void tearDown() throws Exception {
        AppPreferences.getState().removeNode();
        TestEnvironmentSupport.setUserHome(home);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "beta", " "})
    void absentOrInvalidPreferenceDefaultsToStable(String saved) {
        if (saved != null) AppPreferences.getState().put(AppPreferences.KEY_UPDATE_CHANNEL, saved);
        assertEquals(UpdateChannel.STABLE, AppPreferences.getUpdateChannel());
    }

    @ParameterizedTest
    @EnumSource(UpdateChannel.class)
    void channelChoiceSurvivesReinitialization(UpdateChannel channel) throws Exception {
        AppPreferences.setUpdateChannel(channel);
        AppPreferences.getState().flush();
        AppPreferences.init();

        assertEquals(channel, AppPreferences.getUpdateChannel());
        assertEquals(channel.preferenceValue(), AppPreferences.getState().get(AppPreferences.KEY_UPDATE_CHANNEL, null));
    }
}
