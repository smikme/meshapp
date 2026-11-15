package com.meshtastic.client.themes;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.utils.AppPreferences;
import java.util.EnumMap;
import java.util.Locale;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Application-modal editor for global appearance and individual chat wallpapers.
 *
 * <p>Global editing exposes the palette, day/night mode and shared wallpaper.
 * Per-chat editing exposes a wallpaper override and an option to inherit the global
 * wallpaper. Controls update an isolated preview; preferences and other windows
 * change only when the user presses Apply.
 *
 * <p>Image imports create managed copies before Apply so they can be previewed.
 * Closing the editor schedules cleanup of its unreferenced copies, including
 * imports that finish after the window closes. Open the editor on the JavaFX
 * application thread.
 */
public final class AppearanceDialog {
    private final Stage stage = new Stage();
    private final String ownerId;
    private final String chatId;
    private final ToggleGroup palettes = new ToggleGroup();
    private final ToggleGroup kinds = new ToggleGroup();
    private final EnumMap<ThemePalette, ToggleButton> paletteButtons = new EnumMap<>(ThemePalette.class);
    private final EnumMap<ChatBackground.Kind, ToggleButton> kindButtons = new EnumMap<>(ChatBackground.Kind.class);
    private final CheckBox dark = new CheckBox(I18n.t("appearance.dark"));
    private final CheckBox inherit = new CheckBox(I18n.t("appearance.inherit"));
    private final CheckBox repeat = new CheckBox(I18n.t("appearance.repeat"));
    private final ColorPicker color = new ColorPicker();
    private final ColorPicker secondColor = new ColorPicker();
    private final Slider dim = new Slider(0, 85, 0);
    private final Slider blur = new Slider(0, 30, 0);
    private final ChatBackgroundView wallpaper = new ChatBackgroundView();
    private final VBox preview = new VBox();
    private final Label status = new Label();
    private final Button apply = new Button(I18n.t("appearance.apply"));
    private final Button upload = new Button(I18n.t("appearance.image.choose"));
    private String imageId = "";
    private final java.util.Set<String> managedImages = new java.util.HashSet<>();
    private boolean updating;
    private boolean closed;

    private AppearanceDialog(Window owner, String ownerId, String chatId) {
        this.ownerId = ownerId;
        this.chatId = chatId;
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(I18n.t(chatId == null ? "appearance.title" : "appearance.chatTitle"));

        VBox settings = new VBox(16);
        settings.setPadding(new Insets(16));
        settings.setPrefWidth(370);
        if (chatId == null) settings.getChildren().addAll(section("appearance.theme"), paletteCards(), dark);
        else settings.getChildren().add(inherit);

        VBox backgroundSettings = new VBox(12, section("appearance.background"), backgroundCards(),
                labeled("appearance.color", color), labeled("appearance.secondColor", secondColor),
                upload, repeat, slider("appearance.dim", dim, "%"), slider("appearance.blur", blur, ""));
        backgroundSettings.disableProperty().bind(inherit.selectedProperty());
        settings.getChildren().add(backgroundSettings);
        Label imageHelp = new Label(I18n.t("appearance.image.help"));
        imageHelp.setWrapText(true);
        imageHelp.getStyleClass().add("muted-note-label");
        status.setWrapText(true);
        status.getStyleClass().add("appearance-status");
        settings.getChildren().addAll(imageHelp, status);

        ScrollPane settingsScroll = new ScrollPane(settings);
        settingsScroll.setFitToWidth(true);
        settingsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        settingsScroll.setPrefWidth(395);
        settingsScroll.setMinWidth(300);

        buildPreview();
        VBox previewColumn = new VBox(10, section("appearance.preview"), preview);
        previewColumn.setPadding(new Insets(16));
        previewColumn.setMinWidth(280);
        HBox.setHgrow(previewColumn, Priority.ALWAYS);
        VBox.setVgrow(preview, Priority.ALWAYS);
        HBox center = new HBox(settingsScroll, previewColumn);

        Button reset = new Button(I18n.t("common.reset"));
        reset.setId("appearance-reset");
        reset.setOnAction(_ -> {
            if (chatId == null) {
                palettes.selectToggle(paletteButtons.get(ThemePalette.STANDARD));
                dark.setSelected(false);
            }
            inherit.setSelected(chatId != null);
            load(ChatBackground.defaults());
        });
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setCancelButton(true);
        cancel.setId("appearance-cancel");
        cancel.setOnAction(_ -> stage.close());
        apply.setId("appearance-apply");
        apply.setDefaultButton(true);
        apply.getStyleClass().add("accent");
        apply.setOnAction(_ -> save());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(10, reset, spacer, cancel, apply);
        actions.setPadding(new Insets(12, 16, 16, 16));
        BorderPane root = new BorderPane(center);
        root.setBottom(actions);
        root.setId("appearance-dialog");
        Scene scene = new Scene(root, 850, 660);
        stage.setScene(scene);
        stage.setMinWidth(720);
        stage.setMinHeight(480);
        ThemeManager.styleScene(scene);
        stage.setOnHidden(_ -> {
            closed = true;
            ThemeManager.unregisterScene(scene);
            BackgroundImageStore.removeUnused(managedImages);
        });

        dark.setSelected(AppPreferences.isDarkMode());
        dark.setId("appearance-dark");
        inherit.setId("appearance-inherit");
        if (chatId == null) palettes.selectToggle(paletteButtons.get(AppearancePreferences.palette()));
        ChatBackground override = chatId == null ? null : AppearancePreferences.chatOverride(ownerId, chatId);
        inherit.setSelected(chatId != null && override == null);
        load(override == null ? AppearancePreferences.globalBackground() : override);
        palettes.selectedToggleProperty().addListener(_ -> refreshPreview());
        kinds.selectedToggleProperty().addListener(_ -> refreshPreview());
        dark.selectedProperty().addListener(_ -> refreshPreview());
        inherit.selectedProperty().addListener(_ -> refreshPreview());
        repeat.selectedProperty().addListener(_ -> refreshPreview());
        color.valueProperty().addListener(_ -> refreshPreview());
        secondColor.valueProperty().addListener(_ -> refreshPreview());
        dim.valueProperty().addListener(_ -> refreshPreview());
        blur.valueProperty().addListener(_ -> refreshPreview());
        upload.setOnAction(_ -> chooseImage());
    }

    /**
     * Opens the palette, day/night mode and global wallpaper editor.
     * The application-modal window is shown without blocking the calling method.
     *
     * @param owner parent window, or {@code null} for an unowned editor
     */
    public static void showGlobal(Window owner) {
        new AppearanceDialog(owner, null, null).stage.show();
    }

    /**
     * Opens a wallpaper editor for a captured chat identity.
     * Applying an inherited wallpaper removes the chat override; cancelling leaves
     * saved preferences unchanged. The method returns after showing the window.
     *
     * @param owner parent window, or {@code null} for an unowned editor
     * @param ownerId non-null local owner identity or remote chat owner ID
     * @param chatId non-null stable chat preference ID from
     *               {@link com.meshtastic.client.components.chat.ChatDbKey}
     */
    public static void showChat(Window owner, String ownerId, String chatId) {
        new AppearanceDialog(owner, ownerId, chatId).stage.show();
    }

    private FlowPane paletteCards() {
        FlowPane cards = new FlowPane(8, 8);
        for (ThemePalette palette : ThemePalette.values()) {
            ToggleButton button = card(I18n.t(palette.displayKey()), palettes, palette);
            Region swatch = new Region();
            swatch.setPrefSize(72, 20);
            swatch.setStyle("-fx-background-radius: 6; -fx-background-color: " + switch (palette) {
                case STANDARD -> "linear-gradient(to right, #f2f2f7 50%, #2c2c2e 50%)";
                case BLUE -> "linear-gradient(to right, #dcecff 50%, #243c58 50%)";
                case GREEN -> "linear-gradient(to right, #d8efdf 50%, #244538 50%)";
            } + ";");
            button.setGraphic(swatch);
            button.setId("appearance-palette-" + palette.name());
            paletteButtons.put(palette, button);
            cards.getChildren().add(button);
        }
        return cards;
    }

    private FlowPane backgroundCards() {
        FlowPane cards = new FlowPane(8, 8);
        for (ChatBackground.Kind kind : ChatBackground.Kind.values()) {
            ToggleButton button = card(I18n.t(kind.displayKey()), kinds, kind);
            ChatBackgroundView thumbnail = new ChatBackgroundView();
            thumbnail.setMinSize(86, 40);
            thumbnail.setPrefSize(86, 40);
            thumbnail.setMaxSize(86, 40);
            thumbnail.setConfiguration(new ChatBackground(kind, null, null, null, 0, 0, false));
            button.setGraphic(thumbnail);
            button.setId("appearance-background-" + kind.name());
            kindButtons.put(kind, button);
            cards.getChildren().add(button);
        }
        return cards;
    }

    private static ToggleButton card(String title, ToggleGroup group, Object value) {
        ToggleButton button = new ToggleButton(title);
        button.setToggleGroup(group);
        button.setUserData(value);
        button.setContentDisplay(ContentDisplay.TOP);
        button.getStyleClass().add("appearance-card");
        // A selected card stays selected; keyboard selection follows ToggleGroup as usual.
        button.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!button.isSelected()) { button.setSelected(true); event.consume(); }
        });
        return button;
    }

    private void buildPreview() {
        preview.getStyleClass().add("appearance-preview");
        Label title = new Label(I18n.t("appearance.preview.title"));
        title.getStyleClass().add("appearance-preview-header");
        title.setMaxWidth(Double.MAX_VALUE);
        Label incoming = bubble("appearance.preview.incoming", "chat-bubble-incoming");
        Label outgoing = bubble("appearance.preview.outgoing", "chat-bubble-outgoing");
        HBox left = new HBox(incoming);
        HBox right = new HBox(outgoing);
        right.setAlignment(Pos.CENTER_RIGHT);
        Label detail = new Label(I18n.t("appearance.preview.detail"));
        detail.getStyleClass().add("appearance-preview-detail");
        VBox conversation = new VBox(16, left, right, detail);
        conversation.setPadding(new Insets(24, 16, 24, 16));
        StackPane messages = new StackPane(wallpaper, conversation);
        VBox.setVgrow(messages, Priority.ALWAYS);
        Label input = new Label(I18n.t("appearance.preview.input"));
        input.getStyleClass().add("appearance-preview-header");
        input.setMaxWidth(Double.MAX_VALUE);
        preview.getChildren().addAll(title, messages, input);
    }

    private static Label bubble(String key, String style) {
        Label label = new Label(I18n.t(key));
        label.setWrapText(true);
        label.setMaxWidth(240);
        label.getStyleClass().addAll(style, "appearance-preview-message");
        return label;
    }

    /**
     * Replaces the editor draft and refreshes the preview without saving preferences.
     * Image references are tracked for cleanup when the editor closes.
     *
     * @param background non-null configuration to populate into the controls
     */
    private void load(ChatBackground background) {
        updating = true;
        imageId = background.imageId();
        if (!imageId.isEmpty()) managedImages.add(imageId);
        kinds.selectToggle(kindButtons.get(background.kind()));
        color.setValue(Color.web(background.color()));
        secondColor.setValue(Color.web(background.secondColor()));
        dim.setValue(background.dim() * 100);
        blur.setValue(background.blur());
        repeat.setSelected(background.repeat());
        updating = false;
        refreshPreview();
    }

    private ThemePalette selectedPalette() {
        return chatId == null && palettes.getSelectedToggle() != null
                ? (ThemePalette) palettes.getSelectedToggle().getUserData() : AppearancePreferences.palette();
    }

    /**
     * Builds a normalized configuration from the background controls.
     *
     * @return unsaved wallpaper draft, independent of the inheritance checkbox
     */
    private ChatBackground draft() {
        ChatBackground.Kind kind = kinds.getSelectedToggle() == null ? ChatBackground.Kind.DEFAULT
                : (ChatBackground.Kind) kinds.getSelectedToggle().getUserData();
        return new ChatBackground(kind, hex(color.getValue()), hex(secondColor.getValue()),
                kind == ChatBackground.Kind.IMAGE ? imageId : "", dim.getValue() / 100, blur.getValue(), repeat.isSelected());
    }

    /** Updates only the preview and control availability, resolving inheritance for display. */
    private void refreshPreview() {
        if (updating) return;
        ThemeManager.decorate(preview, selectedPalette(), dark.isSelected());
        ChatBackground background = inherit.isSelected() ? AppearancePreferences.globalBackground() : draft();
        wallpaper.setConfiguration(background);
        ChatBackground.Kind kind = draft().kind();
        color.setDisable(kind == ChatBackground.Kind.DEFAULT || kind == ChatBackground.Kind.IMAGE);
        secondColor.setDisable(kind != ChatBackground.Kind.GRADIENT);
        repeat.setDisable(kind != ChatBackground.Kind.IMAGE);
        kindButtons.get(ChatBackground.Kind.IMAGE).setDisable(imageId.isEmpty());
        ((ChatBackgroundView) kindButtons.get(ChatBackground.Kind.IMAGE).getGraphic()).setConfiguration(
                new ChatBackground(ChatBackground.Kind.IMAGE, null, null, imageId, 0, 0, false));
    }

    /**
     * Chooses and imports an image asynchronously, disabling Apply while the copy is prepared.
     * A failed import keeps the existing draft; an import completed after closure is cleaned up.
     */
    private void chooseImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("appearance.image.choose"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG / JPEG", "*.png", "*.jpg", "*.jpeg", "*.PNG", "*.JPG", "*.JPEG"));
        var file = chooser.showOpenDialog(stage);
        if (file == null) return;
        apply.setDisable(true);
        upload.setDisable(true);
        status.setText(I18n.t("appearance.image.loading"));
        BackgroundImageStore.importImage(file.toPath()).whenComplete((id, error) -> Platform.runLater(() -> {
            if (id != null) managedImages.add(id);
            if (closed) { BackgroundImageStore.removeUnused(managedImages); return; }
            apply.setDisable(false);
            upload.setDisable(false);
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                String key = "appearance.image.tooLarge".equals(cause.getMessage())
                        ? "appearance.image.tooLarge" : "appearance.image.invalid";
                status.setText(I18n.t(key));
                return;
            }
            imageId = id;
            kindButtons.get(ChatBackground.Kind.IMAGE).setDisable(false);
            kinds.selectToggle(kindButtons.get(ChatBackground.Kind.IMAGE));
            status.setText(I18n.t("appearance.image.ready"));
            refreshPreview();
        }));
    }

    /**
     * Commits the draft for the editor's scope, applies global themes when appropriate,
     * and closes the window so unreferenced image copies can be cleaned up.
     */
    private void save() {
        if (chatId == null) {
            AppearancePreferences.setPalette(selectedPalette());
            AppPreferences.setDarkMode(dark.isSelected());
            AppearancePreferences.setGlobalBackground(draft());
            ThemeManager.applyTheme(stage.getScene(), dark.isSelected());
        } else AppearancePreferences.setChatBackground(ownerId, chatId, inherit.isSelected() ? null : draft());
        stage.close();
    }

    private static String hex(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }

    private static Label section(String key) {
        Label label = new Label(I18n.t(key));
        label.getStyleClass().add("section-title");
        return label;
    }

    private static VBox labeled(String key, javafx.scene.Node control) {
        return new VBox(6, new Label(I18n.t(key)), control);
    }

    private static VBox slider(String key, Slider control, String suffix) {
        Label value = new Label();
        value.textProperty().bind(control.valueProperty().asString("%.0f" + (suffix.equals("%") ? "%%" : suffix)));
        HBox row = new HBox(10, control, value);
        HBox.setHgrow(control, Priority.ALWAYS);
        value.setMinWidth(38);
        return labeled(key, row);
    }
}
