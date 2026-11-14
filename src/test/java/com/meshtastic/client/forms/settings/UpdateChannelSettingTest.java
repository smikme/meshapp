package com.meshtastic.client.forms.settings;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.AppPreferences.UpdateChannel;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UpdateChannelSettingTest {
    @TempDir
    Path home;

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(home);
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @AfterEach
    void tearDown() throws Exception {
        AppPreferences.getState().removeNode();
        TestEnvironmentSupport.setUserHome(home);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ru", "en", "de"})
    void settingsOfferBothChannelsAndRememberSelection(String language) throws Exception {
        FutureTask<Void> check = new FutureTask<>(() -> {
            String previous = I18n.getLanguageTag();
            try {
                I18n.setLanguageTagForTests(language);
                // The channel also controls manual checks when startup checks are disabled.
                AppPreferences.setCheckUpdates(false);
                var panel = ApplicationSettingsPanelFactory.create();
                var channel = (ComboBox<?>) panel.lookup("#update-channel");
                assertNotNull(channel);
                assertFalse(channel.isDisabled());
                assertEquals(List.of(UpdateChannel.STABLE, UpdateChannel.UNSTABLE), channel.getItems());
                assertEquals(UpdateChannel.STABLE, channel.getValue());

                channel.getSelectionModel().select(1);
                assertEquals(UpdateChannel.UNSTABLE, AppPreferences.getUpdateChannel());
                AppPreferences.getState().flush();
                AppPreferences.init();

                var reopened = ApplicationSettingsPanelFactory.create();
                var restored = (ComboBox<?>) reopened.lookup("#update-channel");
                assertEquals(UpdateChannel.UNSTABLE, restored.getValue());
                restored.getSelectionModel().select(0);
                assertEquals(UpdateChannel.STABLE, AppPreferences.getUpdateChannel());
                assertFalse(AppPreferences.isCheckUpdates());
                for (UpdateChannel option : UpdateChannel.values()) {
                    String label = I18n.t(option.displayKey());
                    assertFalse(label.isBlank());
                    assertFalse(label.startsWith("!"));
                }
            } finally {
                I18n.setLanguageTagForTests(previous);
            }
            return null;
        });
        Platform.runLater(check);
        check.get(10, TimeUnit.SECONDS);
    }
}
