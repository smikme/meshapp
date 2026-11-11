package com.meshtastic.client.forms.settings;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CyrillicCompactionSettingTest {
    @Test
    void visibilityFollowsEffectiveLocaleAndDoesNotResetSavedChoice(@TempDir Path home) throws Exception {
        TestEnvironmentSupport.setUserHome(home);
        TestEnvironmentSupport.ensureJavaFxStarted();
        FutureTask<Void> check = new FutureTask<>(() -> {
            String previous = I18n.getLanguageTag();
            Locale previousSystem = Locale.getDefault(Locale.Category.DISPLAY);
            try {
                I18n.setLanguageTagForTests("ru-RU");
                var group = ApplicationSettingsPanelFactory.createCyrillicCompactionSetting();
                var enabled = (CheckBox) group.getChildren().getFirst();
                assertTrue(group.isVisible());
                assertTrue(group.isManaged());
                assertFalse(enabled.isSelected());
                assertEquals("Экономия байтов для кириллицы", enabled.getText());
                enabled.setSelected(true);
                AppPreferences.getState().flush();
                AppPreferences.init();
                assertTrue(AppPreferences.isCyrillicCompactionEnabled());
                for (String language : new String[]{"en", "de", "ru"}) {
                    I18n.setLanguageTagForTests(language);
                    assertEquals(language.equals("ru"), group.isVisible());
                    assertEquals(language.equals("ru"), group.isManaged());
                    assertTrue(enabled.isSelected());
                    assertTrue(AppPreferences.isCyrillicCompactionEnabled());
                }
                assertFalse(((Label) group.getChildren().get(1)).getText().isBlank());
                for (Locale locale : new Locale[]{Locale.GERMAN, Locale.forLanguageTag("ru-RU"), Locale.ENGLISH}) {
                    Locale.setDefault(Locale.Category.DISPLAY, locale);
                    I18n.setLanguageTagForTests(I18n.LANGUAGE_SYSTEM);
                    assertEquals(locale.getLanguage().equals("ru"), group.isVisible());
                    assertEquals(group.isVisible(), group.isManaged());
                }
            } finally {
                AppPreferences.setCyrillicCompactionEnabled(false);
                Locale.setDefault(Locale.Category.DISPLAY, previousSystem);
                I18n.setLanguageTagForTests(previous);
            }
            return null;
        });
        Platform.runLater(check);
        check.get(10, TimeUnit.SECONDS);
    }
}
