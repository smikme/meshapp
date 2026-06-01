package com.meshtastic.client.components;

import com.sun.javafx.scene.SceneHelper;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.tray.MacOsNativeTrayService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.beans.value.ChangeListener;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Crash-report панели, встроенные в общую стилистику приложения через ModalPane.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class CrashReportPrompt {

    private CrashReportPrompt() {}

    public static void show(Window owner, Consumer<Decision> callback) {
        show(owner, Content.startupCrash(), callback);
    }

    public static void show(Window owner, Content content, Consumer<Decision> callback) {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            callback.accept(new Decision(false, "", ""));
            return;
        }

        AtomicBoolean handled = new AtomicBoolean(false);
        DecisionHolder result = new DecisionHolder();

        Label title = new Label(content.title());
        title.getStyleClass().add("dialog-title");

        Label lead = new Label(content.lead());
        lead.setWrapText(true);

        Label emailLabel = new Label(content.emailLabel());
        TextField emailField = new TextField();
        emailField.setPromptText(content.emailPrompt());

        Label commentLabel = new Label(content.commentLabel());
        TextArea commentArea = new TextArea();
        commentArea.setPromptText(content.commentPrompt());
        commentArea.setWrapText(true);
        commentArea.setPrefRowCount(5);

        Label privacy = new Label(content.privacy());
        privacy.setWrapText(true);
        privacy.setStyle("-fx-opacity: 0.8;");

        Button cancelButton = new Button(I18n.t("common.cancel"));
        Button sendButton = new Button(content.sendButtonText());
        sendButton.getStyleClass().add("accent");

        HBox buttonRow = new HBox(10, cancelButton, sendButton);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);
        buttonRow.setPadding(new Insets(10, 0, 0, 0));

        List<Node> panelChildren = new ArrayList<>();
        panelChildren.add(title);
        panelChildren.add(new Separator());
        panelChildren.add(lead);
        if (content.collectsEmail()) {
            panelChildren.add(emailLabel);
            panelChildren.add(emailField);
        }
        panelChildren.add(commentLabel);
        panelChildren.add(commentArea);
        panelChildren.add(privacy);
        panelChildren.add(buttonRow);

        VBox panel = new VBox(12);
        panel.getChildren().addAll(panelChildren);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(420);
        panel.setMaxWidth(420);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        cancelButton.setOnAction(event -> {
            result.value = new Decision(false, commentArea.getText(), emailField.getText());
            modalPane.hide();
        });
        sendButton.setOnAction(event -> {
            result.value = new Decision(true, commentArea.getText(), emailField.getText());
            modalPane.hide();
        });

        modalPane.show(panel, false, false);
        activateOwnerWindow(owner, panel);
        modalPane.setOnHidden(() -> {
            if (handled.compareAndSet(false, true)) {
                callback.accept(result.value);
            }
        });

        requestTextInputFocus(owner, panel, commentArea);
        enableTextInputPipeline(panel);
        requestFocusWhenWindowActive(owner, panel, () -> requestTextInputFocus(owner, panel, commentArea));
        requestFocusWhenWindowActive(owner, panel, () -> enableTextInputPipeline(panel));
        requestFocusAfterShow(panel, () -> requestTextInputFocus(owner, panel, commentArea));
        requestFocusAfterShow(panel, () -> enableTextInputPipeline(panel));
        installTextInputGuard(panel, commentArea);
    }

    public static ProgressDialog showProgress(Window owner) {
        ModalPane modalPane = ModalPane.getInstance();
        if (modalPane == null) {
            return ProgressDialog.noop();
        }

        Label title = new Label(I18n.t("crashReport.progress.title"));
        title.getStyleClass().add("dialog-title");

        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setMaxSize(56, 56);

        Label message = new Label(I18n.t("crashReport.progress.message"));
        message.setWrapText(true);

        Label secondary = new Label(I18n.t("crashReport.progress.secondary"));
        secondary.setWrapText(true);
        secondary.setStyle("-fx-opacity: 0.8;");

        Button cancelButton = new Button(I18n.t("common.cancel"));

        HBox progressRow = new HBox(14, indicator, new VBox(8, message, secondary));
        progressRow.setAlignment(Pos.CENTER_LEFT);

        HBox buttonRow = new HBox(cancelButton);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);
        buttonRow.setPadding(new Insets(10, 0, 0, 0));

        VBox panel = new VBox(12,
                title,
                new Separator(),
                progressRow,
                buttonRow
        );
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(380);
        panel.setMaxWidth(380);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        ProgressDialog progressDialog = new ProgressDialog(modalPane);
        cancelButton.setOnAction(event -> modalPane.hide());

        modalPane.show(panel, false, false);
        modalPane.setOnHidden(progressDialog::handleHidden);
        return progressDialog;
    }

    public record Decision(boolean sendReport, String comment, String email) {

        public Decision {
            comment = comment == null ? "" : comment;
            email = email == null ? "" : email.trim();
        }
    }

    public record Content(String title,
                          String lead,
                          String emailLabel,
                          String emailPrompt,
                          String commentLabel,
                          String commentPrompt,
                          String privacy,
                          String sendButtonText) {

        public Content {
            title = requireText(title, "title");
            lead = requireText(lead, "lead");
            emailLabel = normalizeOptionalText(emailLabel);
            emailPrompt = normalizeOptionalText(emailPrompt);
            if (emailLabel.isBlank() != emailPrompt.isBlank()) {
                throw new IllegalArgumentException("emailLabel and emailPrompt must both be blank or both be set");
            }
            commentLabel = requireText(commentLabel, "commentLabel");
            commentPrompt = requireText(commentPrompt, "commentPrompt");
            privacy = requireText(privacy, "privacy");
            sendButtonText = requireText(sendButtonText, "sendButtonText");
        }

        public boolean collectsEmail() {
            return !emailLabel.isBlank();
        }

        public static Content startupCrash() {
            return new Content(
                    I18n.t("crashReport.startup.title"),
                    I18n.t("crashReport.startup.lead"),
                    I18n.t("crashReport.email.label"),
                    I18n.t("crashReport.email.prompt"),
                    I18n.t("crashReport.startup.comment.label"),
                    I18n.t("crashReport.startup.comment.prompt"),
                    I18n.t("crashReport.startup.privacy"),
                    I18n.t("crashReport.startup.send")
            );
        }

        public static Content problemReport() {
            return new Content(
                    I18n.t("crashReport.problem.title"),
                    I18n.t("crashReport.problem.lead"),
                    I18n.t("crashReport.email.label"),
                    I18n.t("crashReport.email.prompt"),
                    I18n.t("crashReport.problem.comment.label"),
                    I18n.t("crashReport.problem.comment.prompt"),
                    I18n.t("crashReport.problem.privacy"),
                    I18n.t("crashReport.problem.send")
            );
        }
    }

    public static final class ProgressDialog {

        private final ModalPane modalPane;
        private final AtomicBoolean closedProgrammatically = new AtomicBoolean(false);
        private final AtomicBoolean handled = new AtomicBoolean(false);
        private volatile Runnable onCancel;
        private volatile Runnable onClosed;

        private ProgressDialog(ModalPane modalPane) {
            this.modalPane = modalPane;
        }

        private static ProgressDialog noop() {
            return new ProgressDialog(null);
        }

        public void setOnCancel(Runnable onCancel) {
            this.onCancel = onCancel;
        }

        public void close() {
            close(null);
        }

        public void close(Runnable onClosed) {
            this.onClosed = onClosed;
            closedProgrammatically.set(true);
            if (modalPane != null) {
                modalPane.hide();
            } else if (onClosed != null) {
                onClosed.run();
            }
        }

        private void handleHidden() {
            if (!handled.compareAndSet(false, true)) {
                return;
            }

            if (closedProgrammatically.get()) {
                Runnable closedAction = onClosed;
                if (closedAction != null) {
                    closedAction.run();
                }
                return;
            }

            Runnable cancelAction = onCancel;
            if (cancelAction != null) {
                cancelAction.run();
            }
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.trim();
    }

    static void requestFocusAfterShow(Node animatedNode, Runnable focusAction) {
        AtomicBoolean requested = new AtomicBoolean(false);
        Runnable request = () -> {
            if (!requested.compareAndSet(false, true)) {
                return;
            }
            Platform.runLater(focusAction);
        };

        ChangeListener<Number> listener = new ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Number> observable,
                                Number oldValue,
                                Number newValue) {
                if (!isShowAnimationComplete(newValue.doubleValue())) {
                    return;
                }
                animatedNode.translateXProperty().removeListener(this);
                request.run();
            }
        };

        animatedNode.translateXProperty().addListener(listener);
        Platform.runLater(() -> {
            if (isShowAnimationComplete(animatedNode.getTranslateX())) {
                animatedNode.translateXProperty().removeListener(listener);
                request.run();
            }
        });
    }

    private static boolean isShowAnimationComplete(double translateX) {
        return Math.abs(translateX) < 0.5;
    }

    static void requestFocusWhenWindowActive(Window owner, Node node, Runnable focusAction) {
        Window window = resolveWindow(owner, node);
        if (window == null) {
            return;
        }

        AtomicBoolean requested = new AtomicBoolean(false);
        Runnable request = () -> {
            if (!requested.compareAndSet(false, true)) {
                return;
            }
            Platform.runLater(focusAction);
        };

        ChangeListener<Boolean> listener = new ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Boolean> observable,
                                Boolean oldValue,
                                Boolean newValue) {
                if (!Boolean.TRUE.equals(newValue)) {
                    return;
                }
                window.focusedProperty().removeListener(this);
                request.run();
            }
        };

        window.focusedProperty().addListener(listener);
        Platform.runLater(() -> {
            if (window.isFocused()) {
                window.focusedProperty().removeListener(listener);
                request.run();
            }
        });
    }

    private static void requestTextInputFocus(Window owner, Node node, TextArea textArea) {
        Window window = resolveWindow(owner, node);
        if (window instanceof Stage stage) {
            if (OsDetect.isMacOs()) {
                MacOsNativeTrayService.activateApplication();
                MacOsNativeTrayService.focusWindow(stage);
            } else if (!stage.isFocused()) {
                stage.toFront();
                stage.requestFocus();
            }
        }
        restoreTextAreaFocus(textArea);
    }

    private static void restoreTextAreaFocus(TextArea textArea) {
        textArea.requestFocus();
        textArea.positionCaret(textArea.getLength());
    }

    private static void activateOwnerWindow(Window owner, Node node) {
        Window window = resolveWindow(owner, node);
        if (!(window instanceof Stage stage)) {
            return;
        }

        if (OsDetect.isMacOs()) {
            MacOsNativeTrayService.activateApplication();
            MacOsNativeTrayService.focusWindow(stage);
            return;
        }

        if (!stage.isFocused()) {
            stage.toFront();
            stage.requestFocus();
        }
    }

    static void installTextInputGuard(Node panel, TextArea textArea) {
        new TextInputGuard(panel, textArea).install();
    }

    static void enableTextInputPipeline(Node node) {
        Scene scene = node.getScene();
        if (scene == null) {
            return;
        }
        SceneHelper.enableInputMethodEvents(scene, true);
    }

    private static Window resolveWindow(Window owner, Node node) {
        if (owner != null) {
            return owner;
        }
        if (node.getScene() != null) {
            return node.getScene().getWindow();
        }
        return null;
    }

    private static final class DecisionHolder {
        private Decision value = new Decision(false, "", "");
    }

    private static final class TextInputGuard {
        private static final Duration FOCUS_RETENTION_INTERVAL = Duration.millis(100);

        private final Node panel;
        private final TextArea textArea;
        private final ChangeListener<Scene> sceneListener = this::handleSceneChanged;
        private final javafx.event.EventHandler<KeyEvent> keyPressedFilter = this::handleKeyPressed;
        private final javafx.event.EventHandler<KeyEvent> keyTypedFilter = this::handleKeyTyped;
        private final Timeline focusRetainer = new Timeline(
                new KeyFrame(FOCUS_RETENTION_INTERVAL, event -> maintainFocus())
        );

        private TextInputGuard(Node panel, TextArea textArea) {
            this.panel = panel;
            this.textArea = textArea;
        }

        private void install() {
            panel.sceneProperty().addListener(sceneListener);
            focusRetainer.setCycleCount(Timeline.INDEFINITE);
            attach(panel.getScene());
            focusRetainer.playFromStart();
        }

        private void handleSceneChanged(javafx.beans.value.ObservableValue<? extends Scene> observable,
                                        Scene oldScene,
                                        Scene newScene) {
            detach(oldScene);
            if (newScene == null) {
                panel.sceneProperty().removeListener(sceneListener);
                return;
            }
            attach(newScene);
            focusRetainer.playFromStart();
        }

        private void attach(Scene scene) {
            if (scene == null) {
                return;
            }
            enableTextInputPipeline(panel);
            scene.addEventFilter(KeyEvent.KEY_PRESSED, keyPressedFilter);
            scene.addEventFilter(KeyEvent.KEY_TYPED, keyTypedFilter);
        }

        private void detach(Scene scene) {
            if (scene == null) {
                return;
            }
            scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyPressedFilter);
            scene.removeEventFilter(KeyEvent.KEY_TYPED, keyTypedFilter);
        }

        private void maintainFocus() {
            if (!isPanelAttached()) {
                dispose();
                return;
            }

            Scene scene = panel.getScene();
            if (scene != null && shouldRestoreFocus(scene.getFocusOwner())) {
                enableTextInputPipeline(panel);
                restoreTextAreaFocus(textArea);
            }
        }

        private void handleKeyPressed(KeyEvent event) {
            if (!isPanelAttached()) {
                dispose();
                return;
            }
            if (!shouldRedirectInput(event)) {
                return;
            }

            enableTextInputPipeline(panel);
            restoreTextAreaFocus(textArea);
            if (redirectControlKey(event)) {
                event.consume();
                return;
            }

            if (isPrintableKeyPress(event)) {
                // Consume keyDown so macOS doesn't emit a system beep before KEY_TYPED arrives.
                event.consume();
            }
        }

        private void handleKeyTyped(KeyEvent event) {
            if (!isPanelAttached()) {
                dispose();
                return;
            }

            if (!shouldRedirectInput(event)) {
                return;
            }

            String typed = event.getCharacter();
            if (!isPrintableText(typed) || event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
                return;
            }

            enableTextInputPipeline(panel);
            restoreTextAreaFocus(textArea);
            textArea.replaceSelection(typed);
            event.consume();
        }

        private void dispose() {
            focusRetainer.stop();
            detach(panel.getScene());
            panel.sceneProperty().removeListener(sceneListener);
        }

        private boolean isPanelAttached() {
            return panel.getScene() != null && panel.getParent() != null;
        }

        private boolean shouldRestoreFocus(Node focusOwner) {
            return focusOwner == null || !isDescendantOf(panel, focusOwner);
        }

        private boolean shouldRedirectInput(KeyEvent event) {
            Scene scene = panel.getScene();
            if (scene == null || !shouldRestoreFocus(scene.getFocusOwner())) {
                return false;
            }
            return !event.isMetaDown() && !event.isAltDown();
        }

        private boolean redirectControlKey(KeyEvent event) {
            KeyCode code = event.getCode();
            return switch (code) {
                case BACK_SPACE -> {
                    textArea.deletePreviousChar();
                    yield true;
                }
                case DELETE -> {
                    textArea.deleteNextChar();
                    yield true;
                }
                case ENTER -> {
                    textArea.replaceSelection(System.lineSeparator());
                    yield true;
                }
                case LEFT -> {
                    textArea.backward();
                    yield true;
                }
                case RIGHT -> {
                    textArea.forward();
                    yield true;
                }
                case HOME -> {
                    textArea.home();
                    yield true;
                }
                case END -> {
                    textArea.end();
                    yield true;
                }
                default -> false;
            };
        }
    }

    private static boolean isDescendantOf(Node ancestor, Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrintableText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.chars().anyMatch(ch -> !Character.isISOControl(ch));
    }

    private static boolean isPrintableKeyPress(KeyEvent event) {
        String text = event.getText();
        return isPrintableText(text) && !event.isControlDown();
    }
}
