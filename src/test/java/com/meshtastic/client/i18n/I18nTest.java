package com.meshtastic.client.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nTest {

    @Test
    void returnsTranslatedEnglishTextAndFormatsArguments() {
        String previous = I18n.getLanguageTag();
        try {
            I18n.setLanguageTagForTests(I18n.LANGUAGE_EN);

            assertEquals("Close", I18n.t("common.close"));
            assertEquals("Current version: 1.2.3", I18n.t("modal.update.currentVersion", "1.2.3"));
        } finally {
            I18n.setLanguageTagForTests(previous);
        }
    }

    @Test
    void returnsVisibleFallbackForMissingKey() {
        String previous = I18n.getLanguageTag();
        try {
            I18n.setLanguageTagForTests(I18n.LANGUAGE_EN);

            assertEquals("!missing.key!", I18n.t("missing.key"));
        } finally {
            I18n.setLanguageTagForTests(previous);
        }
    }

    @Test
    void localizedBundlesContainAllFallbackKeys() throws IOException {
        Properties fallback = loadProperties("/i18n/messages.properties");
        Properties russian = loadProperties("/i18n/messages_ru.properties");
        Properties english = loadProperties("/i18n/messages_en.properties");

        assertTrue(russian.keySet().containsAll(fallback.keySet()));
        assertTrue(english.keySet().containsAll(fallback.keySet()));
    }

    private static Properties loadProperties(String resourcePath) throws IOException {
        Properties properties = new Properties();
        try (var reader = new InputStreamReader(
                I18nTest.class.getResourceAsStream(resourcePath),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
