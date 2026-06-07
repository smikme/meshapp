package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.AppPreferences;
import java.util.function.DoubleConsumer;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Builds the application-settings tab content.
 * The factory owns preference bindings for appearance, typography, language,
 * integrations, and diagnostics.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ApplicationSettingsPanelFactory {

    private ApplicationSettingsPanelFactory() {}

    /**
     * Creates the application settings panel.
     *
     * @return JavaFX panel for app-level settings
     */
    public static VBox create() {
        VBox panel = new VBox(16);
        panel.setPadding(new Insets(15));

        Label appearanceHeader = new Label(I18n.t("settings.appearance.title"));
        appearanceHeader.getStyleClass().add("section-title");

        CheckBox disableEffectsCb = new CheckBox(
            I18n.t("settings.effects.disable")
        );
        disableEffectsCb.setSelected(
            AppPreferences.isDisableEffectsEffective()
        );
        if (OsDetect.isWindows10()) {
            disableEffectsCb.setDisable(true);
        }
        disableEffectsCb
            .selectedProperty()
            .addListener((obs, old, val) ->
                AppPreferences.setDisableEffects(val)
            );

        CheckBox softwareRenderingCb = new CheckBox(
            I18n.t("settings.rendering.software")
        );
        softwareRenderingCb.setSelected(AppPreferences.isSoftwareRendering());
        softwareRenderingCb
            .selectedProperty()
            .addListener((obs, old, val) ->
                AppPreferences.setSoftwareRendering(val)
            );

        CheckBox minimizeToTrayCb = new CheckBox(
            I18n.t("settings.tray.minimize")
        );
        minimizeToTrayCb.setSelected(AppPreferences.isMinimizeToTray());
        minimizeToTrayCb
            .selectedProperty()
            .addListener((obs, old, val) ->
                AppPreferences.setMinimizeToTray(val)
            );

        VBox typographyGroup = new VBox(
            10,
            createFontSizeSettingRow(
                I18n.t("settings.font.app.title"),
                I18n.t("settings.font.app.description"),
                TypographyManager.MIN_APP_FONT_SIZE,
                TypographyManager.MAX_APP_FONT_SIZE,
                TypographyManager.getAppFontSize(),
                TypographyManager.DEFAULT_APP_FONT_SIZE,
                TypographyManager::setAppFontSize
            ),
            createFontSizeSettingRow(
                I18n.t("settings.font.chat.title"),
                I18n.t("settings.font.chat.description"),
                TypographyManager.MIN_CHAT_FONT_SIZE,
                TypographyManager.MAX_CHAT_FONT_SIZE,
                TypographyManager.getChatFontSize(),
                TypographyManager.DEFAULT_CHAT_FONT_SIZE,
                TypographyManager::setChatFontSize
            )
        );

        Label restartNote = new Label(
            OsDetect.isWindows10()
                ? I18n.t("settings.restart.windows10")
                : I18n.t("settings.restart.required")
        );
        restartNote.getStyleClass().add("muted-note-label");

        VBox appearanceGroup = new VBox(
            8,
            appearanceHeader,
            typographyGroup,
            createLanguageSettingRow(),
            disableEffectsCb,
            softwareRenderingCb,
            minimizeToTrayCb,
            restartNote
        );

        Label integrationsHeader = new Label(
            I18n.t("settings.integrations.title")
        );
        integrationsHeader.getStyleClass().add("section-title");

        CheckBox checkUpdatesCb = new CheckBox(
            I18n.t("settings.updates.checkOnStart")
        );
        checkUpdatesCb.setSelected(AppPreferences.isCheckUpdates());
        checkUpdatesCb
            .selectedProperty()
            .addListener((obs, old, val) ->
                AppPreferences.setCheckUpdates(val)
            );

        CheckBox jfrDiagnosticsCb = new CheckBox(
            I18n.t("settings.diagnostics.jfr")
        );
        jfrDiagnosticsCb.setSelected(AppPreferences.isJfrDiagnosticsEnabled());
        jfrDiagnosticsCb
            .selectedProperty()
            .addListener((obs, old, val) ->
                AppPreferences.setJfrDiagnosticsEnabled(val)
            );

        Label diagnosticsNote = new Label(
            I18n.t("settings.diagnostics.note")
        );
        diagnosticsNote.getStyleClass().add("muted-note-label");
        diagnosticsNote.setWrapText(true);

        VBox integrationsGroup = new VBox(
            8,
            integrationsHeader,
            checkUpdatesCb,
            jfrDiagnosticsCb,
            diagnosticsNote
        );

        panel
            .getChildren()
            .addAll(appearanceGroup, new Separator(), integrationsGroup);
        return panel;
    }

    private static VBox createLanguageSettingRow() {
        Label titleLabel = new Label(I18n.t("settings.language.title"));
        titleLabel.getStyleClass().add("item-title");

        Label descriptionLabel = new Label(
            I18n.t("settings.language.description")
        );
        descriptionLabel.getStyleClass().add("muted-note-label");
        descriptionLabel.setWrapText(true);

        ComboBox<I18n.LanguageOption> languageBox = new ComboBox<>(
            FXCollections.observableArrayList(I18n.supportedLanguages())
        );
        languageBox.setButtonCell(createLanguageCell());
        languageBox.setCellFactory(ignored -> createLanguageCell());
        languageBox
            .getSelectionModel()
            .select(I18n.languageOption(AppPreferences.getLanguageTag()));
        languageBox.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (
                newValue != null &&
                !newValue.tag().equals(I18n.getLanguageTag())
            ) {
                I18n.setLanguageTag(newValue.tag());
            }
        });

        Label restartLabel = new Label(
            I18n.t("settings.language.restartRequired")
        );
        restartLabel.getStyleClass().add("muted-note-label");
        restartLabel.setWrapText(true);

        return new VBox(
            6,
            titleLabel,
            descriptionLabel,
            languageBox,
            restartLabel
        );
    }

    private static ListCell<I18n.LanguageOption> createLanguageCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(I18n.LanguageOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(
                    empty || item == null
                        ? null
                        : I18n.t(item.displayKey())
                );
            }
        };
    }

    private static VBox createFontSizeSettingRow(
        String title,
        String description,
        int min,
        int max,
        int initialValue,
        int defaultValue,
        DoubleConsumer onValueChanged
    ) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("item-title");

        Label descriptionLabel = new Label(description);
        descriptionLabel.getStyleClass().add("muted-note-label");
        descriptionLabel.setWrapText(true);

        Label valueLabel = new Label(formatFontSizeLabel(initialValue));
        valueLabel.getStyleClass().add("section-title");

        HBox header = new HBox(12, titleLabel, valueLabel);
        header.setAlignment(Pos.CENTER_LEFT);

        Slider slider = new Slider(min, max, initialValue);
        slider.setMajorTickUnit(1);
        slider.setMinorTickCount(0);
        slider.setBlockIncrement(1);
        slider.setSnapToTicks(true);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setMinWidth(0);

        Button resetButton = new Button(I18n.t("common.reset"));
        resetButton.setOnAction(event -> slider.setValue(defaultValue));

        HBox sliderRow = new HBox(10, slider, resetButton);
        sliderRow.setAlignment(Pos.CENTER_LEFT);

        slider
            .prefWidthProperty()
            .bind(sliderRow.widthProperty().multiply(0.5));
        slider.maxWidthProperty().bind(sliderRow.widthProperty().multiply(0.5));

        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            int rounded = (int) Math.round(newValue.doubleValue());
            if (rounded == (int) Math.round(oldValue.doubleValue())) {
                return;
            }
            valueLabel.setText(formatFontSizeLabel(rounded));
            onValueChanged.accept(rounded);
        });

        return new VBox(6, header, descriptionLabel, sliderRow);
    }

    private static String formatFontSizeLabel(int value) {
        return value + " px";
    }
}
