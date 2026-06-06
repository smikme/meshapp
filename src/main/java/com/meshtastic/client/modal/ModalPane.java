package com.meshtastic.client.modal;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.binding.Bindings;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.UpdateInfo;
import com.meshtastic.client.utils.ExternalUrlLauncher;
import java.util.function.Consumer;

/**
 * In-scene modal panel: a translucent overlay with right-side content.
 * Content enters from the right with slide and fade animation. Used by all
 * built-in dialogs: confirm, info, error, and about.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ModalPane extends StackPane {

    private static final Duration ANIM_DURATION = Duration.millis(250);

    private static ModalPane instance;
    private Node currentContent;
    private Region currentScrollableRegion;
    private double currentScrollableRegionMinHeight;
    private Runnable onHidden;
    private boolean dismissOnEscape = true;

    /** Scene-level filter that closes the modal when the user clicks outside the content. */
    private final EventHandler<MouseEvent> sceneClickFilter = e -> {
        if (currentContent != null && isVisible()) {
            // layoutBounds covers only the Region itself, excluding child overflow and effects.
            Bounds contentBounds = currentContent.localToScene(currentContent.getLayoutBounds());
            if (contentBounds != null && !contentBounds.contains(e.getSceneX(), e.getSceneY())) {
                hide();
                e.consume();
            }
        }
    };

    /** Scene-level filter that closes on ESC regardless of the focus owner inside the modal. */
    private final EventHandler<KeyEvent> sceneKeyFilter = e -> {
        if (dismissOnEscape && isVisible() && e.getCode() == KeyCode.ESCAPE) {
            hide();
            e.consume();
        }
    };

    public ModalPane() {
        setVisible(false);
        setPickOnBounds(true);
        getStyleClass().add("modal-overlay");
        setAlignment(Pos.CENTER_RIGHT);
        setFocusTraversable(false);
    }

    public static void install(ModalPane pane) {
        instance = pane;
    }

    public static ModalPane getInstance() {
        return instance;
    }

    /**
     * Sets the callback invoked when the panel closes, usually for resource cleanup.
     */
    public void setOnHidden(Runnable callback) {
        this.onHidden = callback;
    }

    /**
     * Shows content with right-to-left slide and fade-in.
     */
    public void show(Node content) {
        show(content, true, true);
    }

    /**
     * Shows content with explicit dismiss behavior.
     */
    public void show(Node content, boolean dismissOnBackdrop, boolean dismissOnEscape) {
        cleanupCurrentContent();

        Node modalContent = wrapScrollable(content);
        currentContent = modalContent;
        onHidden = null;
        this.dismissOnEscape = dismissOnEscape;
        getChildren().setAll(modalContent);
        setVisible(true);

        // Scene-level filter for closing on backdrop click.
        if (dismissOnBackdrop && getScene() != null) {
            getScene().addEventFilter(MouseEvent.MOUSE_PRESSED, sceneClickFilter);
        }
        if (dismissOnEscape && getScene() != null) {
            getScene().addEventFilter(KeyEvent.KEY_PRESSED, sceneKeyFilter);
        }

        // Backdrop fade-in.
        setOpacity(0);
        FadeTransition bgFade = new FadeTransition(ANIM_DURATION, this);
        bgFade.setFromValue(0);
        bgFade.setToValue(1);

        // Content slides in from the right.
        modalContent.setTranslateX(300);
        TranslateTransition slide = new TranslateTransition(ANIM_DURATION, modalContent);
        slide.setFromX(300);
        slide.setToX(0);

        new ParallelTransition(bgFade, slide).play();

    }

    private void cleanupCurrentContent() {
        if (currentContent == null) {
            return;
        }
        if (getScene() != null) {
            getScene().removeEventFilter(MouseEvent.MOUSE_PRESSED, sceneClickFilter);
            getScene().removeEventFilter(KeyEvent.KEY_PRESSED, sceneKeyFilter);
        }
        restoreScrollableRegion();
    }

    private Node wrapScrollable(Node content) {
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPannable(true);
        scrollPane.setMaxHeight(Double.MAX_VALUE);
        scrollPane.setMaxWidth(Region.USE_PREF_SIZE);
        scrollPane.getStyleClass().addAll("modal-scroll-pane", "edge-to-edge");

        if (content instanceof Region region) {
            double prefWidth = preferredWidth(region);
            if (prefWidth > 0 && Double.isFinite(prefWidth)) {
                scrollPane.setPrefWidth(prefWidth);
            }
            bindContentMinHeight(region, scrollPane);
        }

        return scrollPane;
    }

    private void bindContentMinHeight(Region region, ScrollPane scrollPane) {
        if (region.minHeightProperty().isBound()) {
            return;
        }

        currentScrollableRegion = region;
        currentScrollableRegionMinHeight = region.getMinHeight();
        double originalMinHeight = currentScrollableRegionMinHeight;
        region.minHeightProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(
                        explicitMinHeight(originalMinHeight),
                        scrollPane.getViewportBounds().getHeight()),
                scrollPane.viewportBoundsProperty()));
    }

    private static double preferredWidth(Region region) {
        double prefWidth = region.getPrefWidth();
        if (prefWidth == Region.USE_COMPUTED_SIZE) {
            prefWidth = region.prefWidth(Region.USE_COMPUTED_SIZE);
        }
        return prefWidth;
    }

    private static double explicitMinHeight(double minHeight) {
        return minHeight >= 0 && Double.isFinite(minHeight) ? minHeight : 0;
    }

    /**
     * Hides the modal; content slides out to the right with fade-out.
     */
    public void hide() {
        if (currentContent == null) { return; }

        // Remove scene-level filters.
        if (getScene() != null) {
            getScene().removeEventFilter(MouseEvent.MOUSE_PRESSED, sceneClickFilter);
            getScene().removeEventFilter(KeyEvent.KEY_PRESSED, sceneKeyFilter);
        }

        FadeTransition bgFade = new FadeTransition(ANIM_DURATION, this);
        bgFade.setFromValue(1);
        bgFade.setToValue(0);

        TranslateTransition slide = new TranslateTransition(ANIM_DURATION, currentContent);
        slide.setToX(300);

        ParallelTransition anim = new ParallelTransition(bgFade, slide);
        anim.setOnFinished(e -> {
            setVisible(false);
            restoreScrollableRegion();
            getChildren().clear();
            currentContent = null;
            dismissOnEscape = true;
            Runnable hiddenCallback = onHidden;
            onHidden = null;
            if (hiddenCallback != null) {
                hiddenCallback.run();
            }
        });
        anim.play();
    }

    private void restoreScrollableRegion() {
        if (currentScrollableRegion != null && currentScrollableRegion.minHeightProperty().isBound()) {
            currentScrollableRegion.minHeightProperty().unbind();
            currentScrollableRegion.setMinHeight(currentScrollableRegionMinHeight);
        }
        currentScrollableRegion = null;
        currentScrollableRegionMinHeight = Region.USE_COMPUTED_SIZE;
    }

    // Static dialog helpers

    /**
     * Confirmation dialog with Yes/No buttons.
     */
    public static void showConfirm(String title, String message, Consumer<Boolean> callback) {
        ModalPane pane = getInstance();
        if (pane == null) { return; }

        Button btnYes = new Button(I18n.t("common.yes"));
        btnYes.getStyleClass().add("accent");
        btnYes.setOnAction(e -> {
            pane.hide();
            callback.accept(true);
        });

        Button btnNo = new Button(I18n.t("common.no"));
        btnNo.setOnAction(e -> {
            pane.hide();
            callback.accept(false);
        });

        pane.show(buildPanel(title, message, btnNo, btnYes));
    }

    /**
     * Informational message with an OK button.
     */
    public static void showInfo(String title, String message) {
        ModalPane pane = getInstance();
        if (pane == null) { return; }

        Button btnOk = new Button(I18n.t("common.ok"));
        btnOk.getStyleClass().add("accent");
        btnOk.setOnAction(e -> pane.hide());

        pane.show(buildPanel(title, message, btnOk));
    }

    /**
     * Error message with an OK button.
     */
    public static void showError(String title, String message) {
        ModalPane pane = getInstance();
        if (pane == null) { return; }

        Button btnOk = new Button(I18n.t("common.ok"));
        btnOk.getStyleClass().add("accent");
        btnOk.setOnAction(e -> pane.hide());

        VBox panel = buildPanel(title, message, btnOk);
        panel.getStyleClass().add("modal-dialog-error");
        pane.show(panel);
    }

    /**
     * About window.
     */
    public static void showAbout() {
        ModalPane pane = getInstance();
        if (pane == null) { return; }

        About about = new About();
        about.getStyleClass().add("modal-side-panel");

        Button btnClose = new Button(I18n.t("common.close"));
        btnClose.getStyleClass().add("accent");
        btnClose.setOnAction(e -> pane.hide());

        HBox btnRow = new HBox(btnClose);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));
        about.getChildren().add(btnRow);

        pane.show(about);
    }

    /**
     * Update dialog with new-version information and a download button.
     */
    public static void showUpdateAvailable(UpdateInfo info) {
        ModalPane pane = getInstance();
        if (pane == null) return;

        VBox panel = new VBox(8);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(380);
        panel.setMaxWidth(380);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        Label lblTitle = new Label(I18n.t("modal.update.title"));
        lblTitle.getStyleClass().add("dialog-title");

        Label lblCurrent = new Label(I18n.t("modal.update.currentVersion", MeshApp.APPLICATION_VERSION));
        Label lblNew = new Label(I18n.t("modal.update.newVersion", info.getVersion()));
        lblNew.setStyle("-fx-font-weight: bold;");

        VBox versionBox = new VBox(4, lblCurrent, lblNew);

        Button btnDownload = new Button(I18n.t("common.download"));
        btnDownload.getStyleClass().add("accent");
        btnDownload.setOnAction(e -> {
            pane.hide();
            String url = info.getDownloadUrl();
            if (url != null) {
                ExternalUrlLauncher.open(url);
            }
        });

        Button btnLater = new Button(I18n.t("common.later"));
        btnLater.setOnAction(e -> pane.hide());

        HBox btnRow = new HBox(10, btnLater, btnDownload);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));

        if (info.getReleaseNotes() != null && !info.getReleaseNotes().isBlank()) {
            EmojiTextFlow notesFlow = new EmojiTextFlow(info.getReleaseNotes(), 16);
            notesFlow.setMinHeight(Region.USE_PREF_SIZE);
            ScrollPane notesScroll = new ScrollPane(notesFlow);
            notesScroll.setFitToWidth(true);
            notesScroll.setMaxHeight(300);
            notesScroll.getStyleClass().add("edge-to-edge");
            VBox.setVgrow(notesScroll, Priority.ALWAYS);
            panel.getChildren().addAll(lblTitle, new Separator(), versionBox, notesScroll, btnRow);
        } else {
            panel.getChildren().addAll(lblTitle, new Separator(), versionBox, btnRow);
        }

        pane.show(panel);
    }

    // Panel construction

    private static VBox buildPanel(String title, String message, Button... buttons) {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(340);
        panel.setMaxWidth(340);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        Label lblTitle = new Label(title);
        lblTitle.getStyleClass().add("dialog-title");

        Label lblMessage = new Label(message);
        lblMessage.setWrapText(true);

        HBox btnRow = new HBox(10, buttons);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));

        panel.getChildren().addAll(lblTitle, new Separator(), lblMessage, btnRow);
        return panel;
    }
}
