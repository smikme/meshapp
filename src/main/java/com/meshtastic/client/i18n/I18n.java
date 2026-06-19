package com.meshtastic.client.i18n;

import com.meshtastic.client.utils.AppPreferences;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;

/**
 * Central access point for localized UI strings.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class I18n {

    public static final String LANGUAGE_SYSTEM = "system";
    public static final String LANGUAGE_RU = "ru";
    public static final String LANGUAGE_EN = "en";
    public static final String LANGUAGE_DE = "de";

    private static final String BUNDLE_BASE_NAME = "i18n.messages";
    private static final String LANGUAGE_MANIFEST_RESOURCE =
            "/i18n/languages.properties";
    private static final LanguageMetadata LANGUAGE_METADATA =
            loadLanguageMetadata();
    private static final List<LanguageOption> SUPPORTED_LANGUAGES =
            buildSupportedLanguages();

    private static volatile String languageTag = LANGUAGE_SYSTEM;
    private static volatile ResourceBundle bundle = loadBundle(resolveLocale(LANGUAGE_SYSTEM));

    public static void initFromPreferences() {
        setCurrentLanguageTag(AppPreferences.getLanguageTag());
    }

    public static String getLanguageTag() {
        return languageTag;
    }

    public static Locale locale() {
        return bundle.getLocale();
    }

    public static void setLanguageTag(String tag) {
        String normalized = normalizeLanguageTag(tag);
        AppPreferences.setLanguageTag(normalized);
        setCurrentLanguageTag(normalized);
    }

    public static List<LanguageOption> supportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    public static LanguageOption languageOption(String tag) {
        String normalized = normalizeLanguageTag(tag);
        return SUPPORTED_LANGUAGES.stream()
                .filter(option -> option.tag().equals(normalized))
                .findFirst()
                .orElse(SUPPORTED_LANGUAGES.getFirst());
    }

    public static String t(String key, Object... args) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        return new MessageFormat(pattern, bundle.getLocale()).format(args);
    }

    /**
     * Resolves a localized string without producing the visible missing-key marker.
     *
     * @param key resource bundle key to look up
     * @param args optional {@link MessageFormat} arguments
     * @return formatted localized string, or {@code null} when the key is absent
     */
    public static String tOrNull(String key, Object... args) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException e) {
            return null;
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        return new MessageFormat(pattern, bundle.getLocale()).format(args);
    }

    public static String pluralCategory(long value) {
        long n = Math.abs(value);
        if (!LANGUAGE_RU.equals(locale().getLanguage())) {
            return n == 1 ? "one" : "many";
        }
        n %= 100;
        long n1 = n % 10;
        if (n > 10 && n < 20) { return "many"; }
        if (n1 == 1) { return "one"; }
        if (n1 >= 2 && n1 <= 4) { return "few"; }
        return "many";
    }

    public static void setLanguageTagForTests(String tag) {
        setCurrentLanguageTag(tag);
    }

    private static void setCurrentLanguageTag(String tag) {
        String normalized = normalizeLanguageTag(tag);
        languageTag = normalized;
        bundle = loadBundle(resolveLocale(normalized));
    }

    private static ResourceBundle loadBundle(Locale locale) {
        return ResourceBundle.getBundle(BUNDLE_BASE_NAME, locale);
    }

    private static Locale resolveLocale(String tag) {
        String normalized = normalizeLanguageTag(tag);
        if (!LANGUAGE_SYSTEM.equals(normalized)) {
            return Locale.forLanguageTag(normalized);
        }
        Locale defaultLocale = Locale.getDefault(Locale.Category.DISPLAY);
        String supportedDefault = supportedLanguageTag(
                defaultLocale.toLanguageTag());
        return Locale.forLanguageTag(
                supportedDefault != null
                        ? supportedDefault
                        : LANGUAGE_METADATA.fallbackLanguage()
        );
    }

    private static String normalizeLanguageTag(String tag) {
        if (tag == null || tag.isBlank() || LANGUAGE_SYSTEM.equalsIgnoreCase(tag.trim())) {
            return LANGUAGE_SYSTEM;
        }
        String supported = supportedLanguageTag(tag);
        return supported != null ? supported : LANGUAGE_SYSTEM;
    }

    private static LanguageMetadata loadLanguageMetadata() {
        Properties properties = new Properties();
        try (InputStream input = I18n.class.getResourceAsStream(
                LANGUAGE_MANIFEST_RESOURCE)) {
            if (input != null) {
                properties.load(new InputStreamReader(
                        input,
                        StandardCharsets.UTF_8
                ));
            }
        } catch (IOException ignored) {
            // Missing language metadata falls back to the built-in languages.
        }

        String fallback = canonicalLanguageTag(
                properties.getProperty("fallback", LANGUAGE_EN));
        if (fallback == null) {
            fallback = LANGUAGE_EN;
        }

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String rawTag : properties
                .getProperty("languages", LANGUAGE_RU + "," + LANGUAGE_EN)
                .split(",")) {
            String canonical = canonicalLanguageTag(rawTag);
            if (canonical != null) {
                tags.add(canonical);
            }
        }
        tags.add(fallback);
        return new LanguageMetadata(List.copyOf(tags), fallback);
    }

    private static List<LanguageOption> buildSupportedLanguages() {
        List<LanguageOption> options = new ArrayList<>();
        options.add(new LanguageOption(LANGUAGE_SYSTEM, "language.system"));
        for (String tag : LANGUAGE_METADATA.supportedLanguages()) {
            options.add(new LanguageOption(tag, displayKeyForLanguage(tag)));
        }
        return List.copyOf(options);
    }

    private static String supportedLanguageTag(String tag) {
        String canonical = canonicalLanguageTag(tag);
        if (canonical == null) {
            return null;
        }
        if (LANGUAGE_METADATA.supportedLanguages().contains(canonical)) {
            return canonical;
        }

        String language = Locale.forLanguageTag(canonical).getLanguage();
        List<String> matchingLanguages = LANGUAGE_METADATA.supportedLanguages()
                .stream()
                .filter(supported ->
                        Locale.forLanguageTag(supported)
                                .getLanguage()
                                .equals(language))
                .toList();
        return matchingLanguages.size() == 1
                ? matchingLanguages.getFirst()
                : null;
    }

    private static String canonicalLanguageTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        Locale locale = Locale.forLanguageTag(
                tag.trim().replace('_', '-'));
        String language = locale.getLanguage();
        if (language == null || language.isBlank()) {
            return null;
        }
        return locale.toLanguageTag();
    }

    private static String displayKeyForLanguage(String tag) {
        return "language." + tag.replace('-', '_');
    }

    private record LanguageMetadata(
            List<String> supportedLanguages,
            String fallbackLanguage
    ) {}

    public record LanguageOption(String tag, String displayKey) {}

    private I18n() {}
}
