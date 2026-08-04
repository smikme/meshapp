package com.meshtastic.client.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
    void supportedLanguagesAreLoadedFromResourceManifest() {
        assertTrue(I18n.supportedLanguages().stream().anyMatch(option ->
                option.tag().equals(I18n.LANGUAGE_SYSTEM) &&
                option.displayKey().equals("language.system")));
        assertTrue(I18n.supportedLanguages().stream().anyMatch(option ->
                option.tag().equals(I18n.LANGUAGE_RU) &&
                option.displayKey().equals("language.ru")));
        assertTrue(I18n.supportedLanguages().stream().anyMatch(option ->
                option.tag().equals(I18n.LANGUAGE_EN) &&
                option.displayKey().equals("language.en")));
        assertTrue(I18n.supportedLanguages().stream().anyMatch(option ->
                option.tag().equals(I18n.LANGUAGE_DE) &&
                option.displayKey().equals("language.de")));
        assertTrue(I18n.supportedLanguages().stream().anyMatch(option ->
                option.tag().equals(I18n.LANGUAGE_UK) &&
                option.displayKey().equals("language.uk")));
    }

    @Test
    void regionalLanguageTagsUseConfiguredBaseLanguage() {
        String previous = I18n.getLanguageTag();
        try {
            I18n.setLanguageTagForTests("en-US");

            assertEquals(I18n.LANGUAGE_EN, I18n.getLanguageTag());
            assertEquals("Close", I18n.t("common.close"));
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
    void regionalGermanLanguageTagsUseConfiguredBaseLanguage() {
        String previous = I18n.getLanguageTag();
        try {
            I18n.setLanguageTagForTests("de-DE");

            assertEquals(I18n.LANGUAGE_DE, I18n.getLanguageTag());
            assertEquals("Schließen", I18n.t("common.close"));
        } finally {
            I18n.setLanguageTagForTests(previous);
        }
    }

    @Test
    void regionalUkrainianLanguageTagsUseConfiguredBaseLanguage() {
        String previous = I18n.getLanguageTag();
        try {
            I18n.setLanguageTagForTests("uk-UA");

            assertEquals(I18n.LANGUAGE_UK, I18n.getLanguageTag());
            assertEquals("Закрити", I18n.t("common.close"));
            assertEquals("few", I18n.pluralCategory(2));
        } finally {
            I18n.setLanguageTagForTests(previous);
        }
    }

    @Test
    void systemLanguageUsesGermanForSupportedGermanLocale() {
        String previousLanguage = I18n.getLanguageTag();
        Locale previousLocale = Locale.getDefault(Locale.Category.DISPLAY);
        try {
            Locale.setDefault(Locale.Category.DISPLAY, Locale.GERMAN);
            I18n.setLanguageTagForTests(I18n.LANGUAGE_SYSTEM);

            assertEquals(I18n.LANGUAGE_DE, I18n.locale().getLanguage());
            assertEquals("Schließen", I18n.t("common.close"));
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, previousLocale);
            I18n.setLanguageTagForTests(previousLanguage);
        }
    }

    @Test
    void systemLanguageUsesUkrainianForSupportedUkrainianLocale() {
        String previousLanguage = I18n.getLanguageTag();
        Locale previousLocale = Locale.getDefault(Locale.Category.DISPLAY);
        try {
            Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("uk-UA"));
            I18n.setLanguageTagForTests(I18n.LANGUAGE_SYSTEM);

            assertEquals(I18n.LANGUAGE_UK, I18n.locale().getLanguage());
            assertEquals("Закрити", I18n.t("common.close"));
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, previousLocale);
            I18n.setLanguageTagForTests(previousLanguage);
        }
    }

    @Test
    void systemLanguageFallsBackToEnglishForUnsupportedLocales() {
        String previousLanguage = I18n.getLanguageTag();
        Locale previousLocale = Locale.getDefault(Locale.Category.DISPLAY);
        try {
            Locale.setDefault(Locale.Category.DISPLAY, Locale.ITALIAN);
            I18n.setLanguageTagForTests(I18n.LANGUAGE_SYSTEM);

            assertEquals(Locale.ENGLISH.getLanguage(), I18n.locale().getLanguage());
            assertEquals("Close", I18n.t("common.close"));
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, previousLocale);
            I18n.setLanguageTagForTests(previousLanguage);
        }
    }

    @Test
    void explicitRussianLanguageOverridesUnsupportedSystemLocale() {
        String previousLanguage = I18n.getLanguageTag();
        Locale previousLocale = Locale.getDefault(Locale.Category.DISPLAY);
        try {
            Locale.setDefault(Locale.Category.DISPLAY, Locale.GERMAN);
            I18n.setLanguageTagForTests(I18n.LANGUAGE_RU);

            assertEquals(I18n.LANGUAGE_RU, I18n.locale().getLanguage());
            assertEquals("Закрыть", I18n.t("common.close"));
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, previousLocale);
            I18n.setLanguageTagForTests(previousLanguage);
        }
    }

    @Test
    void localizedBundlesContainAllFallbackKeys() throws IOException {
        Properties fallback = loadProperties("/i18n/messages.properties");
        List<String> languages = I18n.supportedLanguages()
                .stream()
                .map(I18n.LanguageOption::tag)
                .filter(tag -> !tag.equals(I18n.LANGUAGE_SYSTEM))
                .toList();

        for (String language : languages) {
            Properties localized = loadProperties(
                    "/i18n/messages_" + language + ".properties");
            assertTrue(
                    localized.keySet().containsAll(fallback.keySet()),
                    language + " bundle misses fallback keys");
        }
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
