package com.meshtastic.client.modal;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

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

    public ModalPane() {
        setVisible(false);
        setPickOnBounds(true);
        getStyleClass().add("modal-overlay");
        setAlignment(Pos.CENTER_RIGHT);

        // Клик по фону закрывает модалку
        setOnMouseClicked(e -> {
            if (e.getTarget() == this) {
                hide();
            }
        });

        // ESC закрывает модалку
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
                e.consume();
            }
        });
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
        currentContent = content;
        onHidden = null;
        getChildren().setAll(content);
        setVisible(true);

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

        // Фокус на панель для обработки ESC
        requestFocus();
    }

    /**
     * Скрыть — контент уезжает вправо с fade-out.
     */
    public void hide() {
        if (currentContent == null) return;

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
            if (onHidden != null) {
                onHidden.run();
                onHidden = null;
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
        if (pane == null) return;

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
        if (pane == null) return;

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
        if (pane == null) return;

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
        if (pane == null) return;

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
