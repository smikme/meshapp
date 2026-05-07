package com.meshtastic.client.themes;

import com.meshtastic.client.utils.AppPreferences;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class TypographyManager {

    public static final int DEFAULT_APP_FONT_SIZE = 13;
    public static final int MIN_APP_FONT_SIZE = 11;
    public static final int MAX_APP_FONT_SIZE = 18;

    public static final int DEFAULT_CHAT_FONT_SIZE = 13;
    public static final int MIN_CHAT_FONT_SIZE = 11;
    public static final int MAX_CHAT_FONT_SIZE = 20;

    private static final IntegerProperty appFontSize =
            new SimpleIntegerProperty(clampAppFontSize(AppPreferences.getAppFontSize()));
    private static final IntegerProperty chatFontSize =
            new SimpleIntegerProperty(clampChatFontSize(AppPreferences.getChatFontSize()));

    private TypographyManager() {}

    public static int getAppFontSize() {
        return appFontSize.get();
    }

    public static ReadOnlyIntegerProperty appFontSizeProperty() {
        return appFontSize;
    }

    public static void setAppFontSize(double value) {
        int clamped = clampAppFontSize(value);
        if (appFontSize.get() == clamped) {
            return;
        }
        appFontSize.set(clamped);
        AppPreferences.setAppFontSize(clamped);
        ThemeManager.refreshTypography();
    }

    public static void resetAppFontSize() {
        setAppFontSize(DEFAULT_APP_FONT_SIZE);
    }

    public static int getChatFontSize() {
        return chatFontSize.get();
    }

    public static ReadOnlyIntegerProperty chatFontSizeProperty() {
        return chatFontSize;
    }

    public static void setChatFontSize(double value) {
        int clamped = clampChatFontSize(value);
        if (chatFontSize.get() == clamped) {
            return;
        }
        chatFontSize.set(clamped);
        AppPreferences.setChatFontSize(clamped);
        ThemeManager.refreshTypography();
    }

    public static void resetChatFontSize() {
        setChatFontSize(DEFAULT_CHAT_FONT_SIZE);
    }

    public static double scaleApp(double baseSize) {
        return baseSize * getAppFontSize() / (double) DEFAULT_APP_FONT_SIZE;
    }

    public static double scaleChat(double baseSize) {
        return baseSize * getChatFontSize() / (double) DEFAULT_CHAT_FONT_SIZE;
    }

    public static int clampAppFontSize(double value) {
        return clamp(value, MIN_APP_FONT_SIZE, MAX_APP_FONT_SIZE, DEFAULT_APP_FONT_SIZE);
    }

    public static int clampChatFontSize(double value) {
        return clamp(value, MIN_CHAT_FONT_SIZE, MAX_CHAT_FONT_SIZE, DEFAULT_CHAT_FONT_SIZE);
    }

    private static int clamp(double value, int min, int max, int fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return (int) Math.max(min, Math.min(max, Math.round(value)));
    }
}
