package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.rpc.RpcAccessKey;
import com.meshtastic.client.service.RemoteRpcHostService;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.AppPreferences;
import java.util.function.DoubleConsumer;
import javafx.application.Platform;
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
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
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

    private static final String STATUS_CONNECTED_CLASS = "remote-rpc-status-connected";
    private static final String STATUS_CONNECTING_CLASS = "remote-rpc-status-connecting";
    private static final String STATUS_ERROR_CLASS = "remote-rpc-status-error";
    private static final String STATUS_DISABLED_CLASS = "remote-rpc-status-disabled";
    private static final double MQTT_DOWNLINK_FILTER_COMBO_WIDTH = 280;

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

        CheckBox enableEffectsCb = new CheckBox(
            I18n.t("settings.effects.enable")
        );
        enableEffectsCb.setSelected(
            AppPreferences.isVisualEffectsEnabledEffective()
        );
        if (OsDetect.isWindows10()) {
            enableEffectsCb.setDisable(true);
        }
        enableEffectsCb
            .selectedProperty()
            .addListener((obs, old, val) ->
                AppPreferences.setVisualEffectsEnabled(val)
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
            enableEffectsCb,
            softwareRenderingCb,
            minimizeToTrayCb,
            restartNote
        );

        Label runtimeHeader = new Label(I18n.t("settings.runtime.title"));
        runtimeHeader.getStyleClass().add("section-title");

        VBox runtimeGroup = new VBox(
            8,
            runtimeHeader,
            createMemoryLimitSettingRow()
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
            createMqttDownlinkFilterSettingRow(),
            createLuaCurlSecuritySettingRow(),
            checkUpdatesCb,
            jfrDiagnosticsCb,
            diagnosticsNote
        );

        panel
            .getChildren()
            .addAll(
                appearanceGroup,
                new Separator(),
                runtimeGroup,
                new Separator(),
                integrationsGroup
            );
        return panel;
    }

    /**
     * Creates the Remote RPC settings panel.
     *
     * @return JavaFX panel for MeshApp Host RPC and External RPC Router settings
     */
    public static VBox createRemoteRpcSettingsPanel() {
        VBox panel = createRemoteRpcSettingRow();
        panel.setPadding(new Insets(15));
        return panel;
    }

    private static VBox createRemoteRpcSettingRow() {
        Label titleLabel = new Label(I18n.t("settings.remoteRpc.title"));
        titleLabel.getStyleClass().add("item-title");

        Label descriptionLabel = new Label(I18n.t("settings.remoteRpc.description"));
        descriptionLabel.getStyleClass().add("muted-note-label");
        descriptionLabel.setWrapText(true);

        CheckBox enabledBox = new CheckBox(I18n.t("settings.remoteRpc.enabled"));
        enabledBox.setSelected(AppPreferences.isRemoteRpcServerEnabled());
        CheckBox routerEnabledBox = new CheckBox(I18n.t("settings.remoteRpc.router.enabled"));
        routerEnabledBox.setSelected(AppPreferences.isRemoteRpcRouterEnabled());

        TextField bindField = new TextField(AppPreferences.getRemoteRpcServerBindAddress());
        bindField.setPrefColumnCount(14);
        TextField portField = new TextField(Integer.toString(AppPreferences.getRemoteRpcServerPort()));
        portField.setPrefColumnCount(6);
        portField.setMaxWidth(90);
        portField.setTextFormatter(new TextFormatter<>(change -> {
            String text = change.getControlNewText();
            return text.matches("\\d{0,5}") ? change : null;
        }));
        TextField keyField = new TextField(AppPreferences.getRemoteRpcAccessKey());
        keyField.setPrefColumnCount(30);
        Label routerServerLabel = new Label(ConnectionEntry.CLOUD_RPC_ROUTER_DISPLAY_HOST + ":"
                + ConnectionEntry.CLOUD_RPC_ROUTER_PORT);
        routerServerLabel.getStyleClass().add("muted-note-label");

        Button generateButton = new Button(I18n.t("settings.remoteRpc.generateKey"));
        generateButton.setOnAction(event -> keyField.setText(RpcAccessKey.generate().value()));

        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("remote-rpc-status-text");
        statusLabel.setWrapText(true);

        Label statusIndicator = new Label("\u25CF");
        statusIndicator.getStyleClass().add("remote-rpc-status-dot");
        RemoteRpcStatusControls statusControls = new RemoteRpcStatusControls(
                statusIndicator,
                statusLabel
        );
        updateRemoteRpcStatus(statusControls);
        installRemoteRpcStatusListener(statusControls);

        Button applyButton = new Button(I18n.t("settings.remoteRpc.apply"));
        applyButton.setOnAction(event -> {
            int port = parsePortOrDefault(portField.getText());
            portField.setText(Integer.toString(port));
            applyButton.setDisable(true);
            AppPreferences.setRemoteRpcServerEnabled(enabledBox.isSelected());
            AppPreferences.setRemoteRpcServerBindAddress(bindField.getText());
            AppPreferences.setRemoteRpcServerPort(port);
            AppPreferences.setRemoteRpcAccessKey(keyField.getText());
            AppPreferences.setRemoteRpcRouterEnabled(routerEnabledBox.isSelected());
            AppPreferences.setRemoteRpcRouterServer(ConnectionEntry.CLOUD_RPC_ROUTER_SERVER);
            Thread worker = new Thread(() -> {
                try {
                    RemoteRpcHostService.getInstance().applyPreferences();
                } finally {
                    Platform.runLater(() -> {
                        updateRemoteRpcStatus(statusControls);
                        applyButton.setDisable(false);
                    });
                }
            }, "remote-rpc-settings-apply");
            worker.setDaemon(true);
            worker.start();
        });

        HBox bindRow = new HBox(
                8,
                new Label(I18n.t("settings.remoteRpc.bindAddress")),
                bindField,
                new Label(I18n.t("settings.remoteRpc.port")),
                portField
        );
        bindRow.setAlignment(Pos.CENTER_LEFT);

        HBox keyRow = new HBox(
                8,
                new Label(I18n.t("settings.remoteRpc.key")),
                keyField,
                generateButton
        );
        keyRow.setAlignment(Pos.CENTER_LEFT);

        HBox routerRow = new HBox(
                8,
                routerEnabledBox,
                routerServerLabel
        );
        routerRow.setAlignment(Pos.CENTER_LEFT);

        HBox actionRow = new HBox(8, enabledBox, applyButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        HBox statusRow = new HBox(8, statusIndicator, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.getStyleClass().add("remote-rpc-status-row");

        return new VBox(8, titleLabel, descriptionLabel, bindRow, keyRow, routerRow, actionRow, statusRow);
    }

    private static int parsePortOrDefault(String text) {
        try {
            int port = Integer.parseInt(text != null ? text.trim() : "");
            return port >= 1 && port <= 65_535 ? port : AppPreferences.DEFAULT_REMOTE_RPC_SERVER_PORT;
        } catch (NumberFormatException e) {
            return AppPreferences.DEFAULT_REMOTE_RPC_SERVER_PORT;
        }
    }

    private static void updateRemoteRpcStatus(RemoteRpcStatusControls controls) {
        RemoteRpcHostService service = RemoteRpcHostService.getInstance();
        applyRemoteRpcStatusStyle(controls.statusIndicator(), remoteRpcStatusKind(service));
        if (service.isRunning()) {
            String direct = I18n.t("settings.remoteRpc.status.running", service.getPort());
            String router = routerStatusText(service);
            controls.statusLabel().setText(router.isBlank() ? direct : direct + "\n" + router);
            return;
        }
        String error = service.getLastError();
        String direct = error == null || error.isBlank()
                ? I18n.t("settings.remoteRpc.status.stopped")
                : I18n.t("settings.remoteRpc.status.error", error);
        String router = routerStatusText(service);
        controls.statusLabel().setText(router.isBlank() ? direct : direct + "\n" + router);
    }

    private static String routerStatusText(RemoteRpcHostService service) {
        if (!AppPreferences.isRemoteRpcRouterEnabled()) {
            return I18n.t("settings.remoteRpc.router.status.disabled");
        }
        if (service.isRouterConnected()) {
            return I18n.t("settings.remoteRpc.router.status.connected");
        }
        String error = service.getLastRouterError();
        return error == null || error.isBlank()
                ? I18n.t("settings.remoteRpc.router.status.connecting")
                : I18n.t("settings.remoteRpc.router.status.error", error);
    }

    private static RemoteRpcStatusKind remoteRpcStatusKind(RemoteRpcHostService service) {
        if (AppPreferences.isRemoteRpcRouterEnabled()) {
            if (service.isRouterConnected()) {
                return RemoteRpcStatusKind.CONNECTED;
            }
            String routerError = service.getLastRouterError();
            return routerError == null || routerError.isBlank()
                    ? RemoteRpcStatusKind.CONNECTING
                    : RemoteRpcStatusKind.ERROR;
        }
        if (!service.isRunning()) {
            String error = service.getLastError();
            return error == null || error.isBlank()
                    ? RemoteRpcStatusKind.DISABLED
                    : RemoteRpcStatusKind.ERROR;
        }
        return RemoteRpcStatusKind.CONNECTED;
    }

    private static void applyRemoteRpcStatusStyle(Label statusIndicator, RemoteRpcStatusKind statusKind) {
        statusIndicator.getStyleClass().removeAll(
                STATUS_CONNECTED_CLASS,
                STATUS_CONNECTING_CLASS,
                STATUS_ERROR_CLASS,
                STATUS_DISABLED_CLASS
        );
        switch (statusKind) {
            case CONNECTED -> statusIndicator.getStyleClass().add(STATUS_CONNECTED_CLASS);
            case CONNECTING -> statusIndicator.getStyleClass().add(STATUS_CONNECTING_CLASS);
            case ERROR -> statusIndicator.getStyleClass().add(STATUS_ERROR_CLASS);
            case DISABLED -> statusIndicator.getStyleClass().add(STATUS_DISABLED_CLASS);
        }
    }

    private static void installRemoteRpcStatusListener(RemoteRpcStatusControls controls) {
        RemoteRpcHostService service = RemoteRpcHostService.getInstance();
        Runnable listener = () -> Platform.runLater(() -> {
            if (controls.statusLabel().getScene() != null) {
                updateRemoteRpcStatus(controls);
            }
        });
        service.addStatusListener(listener);
        controls.statusLabel().sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null && newScene == null) {
                service.removeStatusListener(listener);
            }
        });
    }

    private enum RemoteRpcStatusKind {
        CONNECTED,
        CONNECTING,
        ERROR,
        DISABLED
    }

    private record RemoteRpcStatusControls(Label statusIndicator, Label statusLabel) {
    }

    private static VBox createMemoryLimitSettingRow() {
        Label titleLabel = new Label(I18n.t("settings.memoryLimit.title"));
        titleLabel.getStyleClass().add("item-title");

        Label descriptionLabel = new Label(
            I18n.t("settings.memoryLimit.description")
        );
        descriptionLabel.getStyleClass().add("muted-note-label");
        descriptionLabel.setWrapText(true);

        TextField valueField = new TextField(
            Integer.toString(AppPreferences.getMemoryLimitMb())
        );
        valueField.setPrefColumnCount(6);
        valueField.setMaxWidth(100);
        valueField.setTextFormatter(new TextFormatter<>(change -> {
            String text = change.getControlNewText();
            return text.matches("\\d{0,5}") ? change : null;
        }));
        valueField
            .textProperty()
            .addListener((obs, oldValue, newValue) ->
                saveMemoryLimitIfComplete(newValue)
            );
        valueField.setOnAction(event -> normalizeMemoryLimitField(valueField));
        valueField
            .focusedProperty()
            .addListener((obs, oldValue, focused) -> {
                if (!focused) {
                    normalizeMemoryLimitField(valueField);
                }
            });

        Label unitLabel = new Label(I18n.t("settings.memoryLimit.unit"));

        Button resetButton = new Button(I18n.t("common.reset"));
        resetButton.setOnAction(event ->
            valueField.setText(
                Integer.toString(AppPreferences.DEFAULT_MEMORY_LIMIT_MB)
            )
        );

        HBox inputRow = new HBox(8, valueField, unitLabel, resetButton);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        Label restartLabel = new Label(
            I18n.t("settings.memoryLimit.restartRequired")
        );
        restartLabel.getStyleClass().add("muted-note-label");
        restartLabel.setWrapText(true);

        return new VBox(6, titleLabel, descriptionLabel, inputRow, restartLabel);
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

    private static VBox createMqttDownlinkFilterSettingRow() {
        Label titleLabel = new Label(I18n.t("settings.mqttFilter.title"));
        titleLabel.getStyleClass().add("item-title");

        Label descriptionLabel = new Label(
            I18n.t("settings.mqttFilter.description")
        );
        descriptionLabel.getStyleClass().add("muted-note-label");
        descriptionLabel.setWrapText(true);

        ComboBox<AppPreferences.MqttDownlinkFilterMode> filterBox =
            new ComboBox<>(
                FXCollections.observableArrayList(
                    AppPreferences.MqttDownlinkFilterMode.values()
                )
            );
        filterBox.setButtonCell(createMqttDownlinkFilterModeCell());
        filterBox.setCellFactory(ignored -> createMqttDownlinkFilterModeCell());
        filterBox.setMinWidth(MQTT_DOWNLINK_FILTER_COMBO_WIDTH);
        filterBox.setPrefWidth(MQTT_DOWNLINK_FILTER_COMBO_WIDTH);
        filterBox
            .getSelectionModel()
            .select(AppPreferences.getMqttDownlinkFilterMode());
        filterBox.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                AppPreferences.setMqttDownlinkFilterMode(newValue);
            }
        });

        return new VBox(6, titleLabel, descriptionLabel, filterBox);
    }

    private static VBox createLuaCurlSecuritySettingRow() {
        Label warningIcon = new Label("!");
        warningIcon.getStyleClass().add("lua-curl-security-warning-icon");

        Label titleLabel = new Label(I18n.t("settings.luaCurlSecurity.title"));
        titleLabel.getStyleClass().add("item-title");

        HBox titleRow = new HBox(8, warningIcon, titleLabel);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label descriptionLabel = new Label(
            I18n.t("settings.luaCurlSecurity.description")
        );
        descriptionLabel.getStyleClass().add("muted-note-label");
        descriptionLabel.setWrapText(true);

        CheckBox disabledBox = new CheckBox(
            I18n.t("settings.luaCurlSecurity.disable")
        );
        disabledBox.setSelected(
            AppPreferences.isLuaCurlSecurityRestrictionsDisabled()
        );
        disabledBox
            .selectedProperty()
            .addListener((obs, old, val) ->
                AppPreferences.setLuaCurlSecurityRestrictionsDisabled(val)
            );

        return new VBox(6, titleRow, descriptionLabel, disabledBox);
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

    private static ListCell<AppPreferences.MqttDownlinkFilterMode> createMqttDownlinkFilterModeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(
                AppPreferences.MqttDownlinkFilterMode item,
                boolean empty
            ) {
                super.updateItem(item, empty);
                String text = empty || item == null
                    ? null
                    : I18n.t(item.displayKey());
                setText(text);
                setTooltip(text == null ? null : new Tooltip(text));
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

    private static void saveMemoryLimitIfComplete(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (
                parsed >= AppPreferences.MIN_MEMORY_LIMIT_MB &&
                parsed <= AppPreferences.MAX_MEMORY_LIMIT_MB
            ) {
                AppPreferences.setMemoryLimitMb(parsed);
            }
        } catch (NumberFormatException ignored) {
            // The text formatter keeps non-numeric values out.
        }
    }

    private static void normalizeMemoryLimitField(TextField field) {
        int value = AppPreferences.getMemoryLimitMb();
        String text = field.getText();
        if (text != null && !text.isBlank()) {
            try {
                value = AppPreferences.clampMemoryLimitMb(
                    Integer.parseInt(text)
                );
            } catch (NumberFormatException ignored) {}
        }
        field.setText(Integer.toString(value));
        AppPreferences.setMemoryLimitMb(value);
    }
}
