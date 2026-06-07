package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.utils.SvgIconLoader;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.SVGPath;

/**
 * Builds the configuration toolbar and exposes buttons whose state is managed
 * by the settings form.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigToolbarFactory {

    private ConfigToolbarFactory() {}

    /**
     * Creates a complete configuration toolbar.
     *
     * @param refreshAction        reload action
     * @param syncTimeAction       sync time action
     * @param saveRadioAction      save to radio action
     * @param restartAction        restart device action
     * @param shutdownAction       shutdown device action
     * @param resetDatabaseAction  reset database action
     * @param exportConfigAction   export config snapshot action
     * @param importConfigAction   import config snapshot action
     * @param exportTemplateAction export template snapshot action
     * @param importTemplateAction import template snapshot action
     * @return toolbar controls
     */
    public static Controls create(
        Runnable refreshAction,
        Runnable syncTimeAction,
        Runnable saveRadioAction,
        Runnable restartAction,
        Runnable shutdownAction,
        Runnable resetDatabaseAction,
        Runnable exportConfigAction,
        Runnable importConfigAction,
        Runnable exportTemplateAction,
        Runnable importTemplateAction
    ) {
        Button refreshButton = toolbarButton(
            I18n.t("settings.config.toolbar.refresh.title"),
            I18n.t("settings.config.toolbar.refresh.description"),
            "/icons/refresh.svg",
            refreshAction
        );
        Button syncTimeButton = toolbarButton(
            I18n.t("settings.config.toolbar.syncTime.title"),
            I18n.t("settings.config.toolbar.syncTime.description"),
            "/icons/sync-time.svg",
            syncTimeAction
        );
        syncTimeButton.setDisable(true);
        Button saveRadioButton = toolbarButton(
            I18n.t("settings.config.toolbar.saveRadio.title"),
            I18n.t("settings.config.toolbar.saveRadio.description"),
            "/icons/save-radio.svg",
            saveRadioAction
        );
        saveRadioButton.setDisable(true);
        Button restartButton = toolbarButton(
            I18n.t("settings.config.toolbar.restart.title"),
            I18n.t("settings.config.toolbar.restart.description"),
            "/icons/restart-radio.svg",
            restartAction
        );
        restartButton.setDisable(true);
        Button shutdownButton = toolbarButton(
            I18n.t("settings.config.toolbar.shutdown.title"),
            I18n.t("settings.config.toolbar.shutdown.description"),
            "/icons/shutdown-radio.svg",
            shutdownAction
        );
        shutdownButton.setDisable(true);
        Button resetDatabaseButton = toolbarButton(
            I18n.t("settings.config.toolbar.resetDatabase.title"),
            I18n.t("settings.config.toolbar.resetDatabase.description"),
            "/icons/clear.svg",
            resetDatabaseAction
        );
        Button exportConfigButton = toolbarButton(
            I18n.t("settings.config.toolbar.saveConfig.title"),
            I18n.t("settings.config.toolbar.saveConfig.description"),
            "/icons/save-config.svg",
            exportConfigAction
        );
        Button importConfigButton = toolbarButton(
            I18n.t("settings.config.toolbar.loadConfig.title"),
            I18n.t("settings.config.toolbar.loadConfig.description"),
            "/icons/load-config.svg",
            importConfigAction
        );
        Button exportTemplateButton = toolbarButton(
            I18n.t("settings.config.toolbar.saveTemplate.title"),
            I18n.t("settings.config.toolbar.saveTemplate.description"),
            "/icons/save-template.svg",
            exportTemplateAction
        );
        Button importTemplateButton = toolbarButton(
            I18n.t("settings.config.toolbar.loadTemplate.title"),
            I18n.t("settings.config.toolbar.loadTemplate.description"),
            "/icons/load-template.svg",
            importTemplateAction
        );

        ToolBar toolbar = new ToolBar();
        toolbar.getStyleClass().add("config-toolbar");
        toolbar
            .getItems()
            .addAll(
                refreshButton,
                syncTimeButton,
                saveRadioButton,
                new Separator(Orientation.VERTICAL),
                restartButton,
                shutdownButton,
                new Separator(Orientation.VERTICAL),
                resetDatabaseButton,
                new Separator(Orientation.VERTICAL),
                exportConfigButton,
                importConfigButton,
                new Separator(Orientation.VERTICAL),
                exportTemplateButton,
                importTemplateButton
            );

        return new Controls(
            toolbar,
            refreshButton,
            syncTimeButton,
            saveRadioButton,
            restartButton,
            shutdownButton,
            resetDatabaseButton
        );
    }

    private static Button toolbarButton(
        String title,
        String description,
        String iconPath,
        Runnable action
    ) {
        SVGPath icon = SvgIconLoader.load(iconPath, 18);

        Button button = new Button();
        button.getStyleClass().add("config-toolbar-button");
        button.setMinSize(34, 34);
        button.setPrefSize(34, 34);
        button.setMaxSize(34, 34);
        if (icon != null) {
            button.setGraphic(icon);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            button.setText(title);
        }
        button.setTooltip(new Tooltip(title + "\n" + description));
        button.setOnAction(e -> action.run());
        return button;
    }

    /**
     * Toolbar and stateful buttons used by the settings form.
     *
     * @param toolbar             toolbar node
     * @param refreshButton       refresh button
     * @param syncTimeButton      sync time button
     * @param saveRadioButton     save radio button
     * @param restartButton       restart button
     * @param shutdownButton      shutdown button
     * @param resetDatabaseButton reset database button
     */
    public record Controls(
        ToolBar toolbar,
        Button refreshButton,
        Button syncTimeButton,
        Button saveRadioButton,
        Button restartButton,
        Button shutdownButton,
        Button resetDatabaseButton
    ) {}
}
