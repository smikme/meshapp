package com.meshtastic.client.i18n;

import com.meshtastic.client.utils.AppPreferences;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Центральная точка доступа к локализованным UI-строкам.
 */
public final class I18n {

    public static final String LANGUAGE_SYSTEM = "system";
    public static final String LANGUAGE_RU = "ru";
    public static final String LANGUAGE_EN = "en";

    private static final String BUNDLE_BASE_NAME = "i18n.messages";
    private static final List<LanguageOption> SUPPORTED_LANGUAGES = List.of(
            new LanguageOption(LANGUAGE_SYSTEM, "language.system"),
            new LanguageOption(LANGUAGE_RU, "language.ru"),
            new LanguageOption(LANGUAGE_EN, "language.en")
    );

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
        if (LANGUAGE_RU.equals(normalized)) {
            return Locale.forLanguageTag(LANGUAGE_RU);
        }
        if (LANGUAGE_EN.equals(normalized)) {
            return Locale.ENGLISH;
        }
        Locale defaultLocale = Locale.getDefault(Locale.Category.DISPLAY);
        String language = defaultLocale.getLanguage();
        return LANGUAGE_EN.equals(language)
                ? Locale.ENGLISH
                : Locale.forLanguageTag(LANGUAGE_RU);
    }

    private static String normalizeLanguageTag(String tag) {
        if (tag == null || tag.isBlank() || LANGUAGE_SYSTEM.equalsIgnoreCase(tag.trim())) {
            return LANGUAGE_SYSTEM;
        }
        String language = Locale.forLanguageTag(tag.trim()).getLanguage();
        return switch (language) {
            case LANGUAGE_RU -> LANGUAGE_RU;
            case LANGUAGE_EN -> LANGUAGE_EN;
            default -> LANGUAGE_SYSTEM;
        };
    }

    public record LanguageOption(String tag, String displayKey) {}

    private I18n() {}
}
