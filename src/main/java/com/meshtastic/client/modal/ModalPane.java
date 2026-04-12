package com.meshtastic.client.modal;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.model.UpdateInfo;
import com.meshtastic.client.utils.ExternalUrlLauncher;
import java.util.function.Consumer;

/**
 * In-scene модальная панель — полупрозрачный оверлей с контентом справа.
 * Контент выезжает справа с анимацией slide + fade.
 * Используется для всех встроенных диалогов (confirm, info, error, about).
 */
public class ModalPane extends StackPane {

    private static final Duration ANIM_DURATION = Duration.millis(250);

    private static ModalPane instance;
    private Node currentContent;
    private Runnable onHidden;
    private boolean dismissOnBackdrop = true;
    private boolean dismissOnEscape = true;

    /** Scene-level фильтр: закрытие по клику вне контента */
    private final EventHandler<MouseEvent> sceneClickFilter = e -> {
        if (currentContent != null && isVisible()) {
            // layoutBounds — только размеры самого Region, без overflow детей и эффектов
            Bounds contentBounds = currentContent.localToScene(currentContent.getLayoutBounds());
            if (contentBounds != null && !contentBounds.contains(e.getSceneX(), e.getSceneY())) {
                hide();
                e.consume();
            }
        }
    };

    /** Scene-level фильтр: закрытие по ESC независимо от focus owner внутри модалки */
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
     * Установить callback, вызываемый при закрытии панели (для очистки ресурсов).
     */
    public void setOnHidden(Runnable callback) {
        this.onHidden = callback;
    }

    /**
     * Показать контент — выезжает справа с fade-in.
     */
    public void show(Node content) {
        show(content, true, true);
    }

    /**
     * Показать контент с явным управлением dismiss-поведением.
     */
    public void show(Node content, boolean dismissOnBackdrop, boolean dismissOnEscape) {
        currentContent = content;
        onHidden = null;
        this.dismissOnBackdrop = dismissOnBackdrop;
        this.dismissOnEscape = dismissOnEscape;
        getChildren().setAll(content);
        setVisible(true);

        // Scene-level фильтр для закрытия по клику вне контента
        if (dismissOnBackdrop && getScene() != null) {
            getScene().addEventFilter(MouseEvent.MOUSE_PRESSED, sceneClickFilter);
        }
        if (dismissOnEscape && getScene() != null) {
            getScene().addEventFilter(KeyEvent.KEY_PRESSED, sceneKeyFilter);
        }

        // Фон: fade-in
        setOpacity(0);
        FadeTransition bgFade = new FadeTransition(ANIM_DURATION, this);
        bgFade.setFromValue(0);
        bgFade.setToValue(1);

        // Контент: slide справа
        content.setTranslateX(300);
        TranslateTransition slide = new TranslateTransition(ANIM_DURATION, content);
        slide.setFromX(300);
        slide.setToX(0);

        new ParallelTransition(bgFade, slide).play();

    }

    /**
     * Скрыть — контент уезжает вправо с fade-out.
     */
    public void hide() {
        if (currentContent == null) { return; }

        // Снять scene-level фильтр
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
            getChildren().clear();
            currentContent = null;
            dismissOnBackdrop = true;
            dismissOnEscape = true;
            Runnable hiddenCallback = onHidden;
            onHidden = null;
            if (hiddenCallback != null) {
                hiddenCallback.run();
            }
        });
        anim.play();
    }

    // ── Статические методы для диалогов ──────────────────────────

    /**
     * Диалог подтверждения с кнопками Да / Нет.
     */
    public static void showConfirm(String title, String message, Consumer<Boolean> callback) {
        ModalPane pane = getInstance();
        if (pane == null) { return; }

        Button btnYes = new Button("Да");
        btnYes.getStyleClass().add("accent");
        btnYes.setOnAction(e -> {
            pane.hide();
            callback.accept(true);
        });

        Button btnNo = new Button("Нет");
        btnNo.setOnAction(e -> {
            pane.hide();
            callback.accept(false);
        });

        pane.show(buildPanel(title, message, btnNo, btnYes));
    }

    /**
     * Информационное сообщение с кнопкой ОК.
     */
    public static void showInfo(String title, String message) {
        ModalPane pane = getInstance();
        if (pane == null) { return; }

        Button btnOk = new Button("ОК");
        btnOk.getStyleClass().add("accent");
        btnOk.setOnAction(e -> pane.hide());

        pane.show(buildPanel(title, message, btnOk));
    }

    /**
     * Сообщение об ошибке с кнопкой ОК.
     */
    public static void showError(String title, String message) {
        ModalPane pane = getInstance();
        if (pane == null) { return; }

        Button btnOk = new Button("ОК");
        btnOk.getStyleClass().add("accent");
        btnOk.setOnAction(e -> pane.hide());

        VBox panel = buildPanel(title, message, btnOk);
        panel.getStyleClass().add("modal-dialog-error");
        pane.show(panel);
    }

    /**
     * Окно «О программе».
     */
    public static void showAbout() {
        ModalPane pane = getInstance();
        if (pane == null) { return; }

        About about = new About();
        about.getStyleClass().add("modal-side-panel");

        Button btnClose = new Button("Закрыть");
        btnClose.getStyleClass().add("accent");
        btnClose.setOnAction(e -> pane.hide());

        HBox btnRow = new HBox(btnClose);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));
        about.getChildren().add(btnRow);

        pane.show(about);
    }

    /**
     * Диалог обновления — информация о новой версии с кнопкой скачивания.
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

        Label lblTitle = new Label("Доступно обновление");
        lblTitle.setFont(Font.font("Roboto", FontWeight.BOLD, 15));

        Label lblCurrent = new Label("Текущая версия: " + MeshApp.APPLICATION_VERSION);
        Label lblNew = new Label("Новая версия: " + info.getVersion());
        lblNew.setStyle("-fx-font-weight: bold;");

        VBox versionBox = new VBox(4, lblCurrent, lblNew);

        Button btnDownload = new Button("Скачать");
        btnDownload.getStyleClass().add("accent");
        btnDownload.setOnAction(e -> {
            pane.hide();
            String url = info.getDownloadUrl();
            if (url != null) {
                ExternalUrlLauncher.open(url);
            }
        });

        Button btnLater = new Button("Позже");
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

    // ── Построение панели ────────────────────────────────────────

    private static VBox buildPanel(String title, String message, Button... buttons) {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(20, 30, 20, 30));
        panel.setPrefWidth(340);
        panel.setMaxWidth(340);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.getStyleClass().add("modal-side-panel");

        Label lblTitle = new Label(title);
        lblTitle.setFont(Font.font("Roboto", FontWeight.BOLD, 15));

        Label lblMessage = new Label(message);
        lblMessage.setWrapText(true);

        HBox btnRow = new HBox(10, buttons);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));

        panel.getChildren().addAll(lblTitle, new Separator(), lblMessage, btnRow);
        return panel;
    }
}
