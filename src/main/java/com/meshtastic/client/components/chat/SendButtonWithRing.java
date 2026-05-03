package com.meshtastic.client.components.chat;

import javafx.collections.ListChangeListener;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;

/**
 * Кнопка отправки сообщения с кольцевым индикатором заполненности.
 *
 * <p>Кольцо вписано внутрь кнопки "➤" по её краю и показывает долю
 * использованных байт от максимума. При ≥ 90% заполнения кольцо становится красным.
 * Фоновый track всегда виден полупрозрачной окружностью.
 * Размер компонента фиксирован (36×36) и не меняется при вводе.
 *
 * <p>Рисование выполняется на {@link Canvas} поверх кнопки — гарантированная
 * отрисовка независимо от z-order Button/Region.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class SendButtonWithRing extends StackPane {

    /** Размер кнопки — совпадает с .chat-send-btn в CSS. */
    private static final double BUTTON_SIZE = 36;
    /** Толщина штриха кольца. */
    private static final double RING_STROKE = 3;
    /** Отступ кольца от края кнопки (центр штриха). */
    private static final double RING_INSET = 2.5;

    private static final Color TRACK_COLOR_DARK = Color.web("#FFFFFF", 0.20);
    private static final Color TRACK_COLOR_LIGHT = Color.web("#000000", 0.15);
    private static final Color ARC_COLOR = Color.WHITE;
    private static final Color LIMIT_COLOR = Color.web("#E53935");
    private static final String LIGHT_THEME_CLASS = "light-theme";

    private final Canvas canvas;
    private final Button sendButton;

    private boolean lightTheme = false;
    private double currentRatio = 0;
    private boolean nearLimit = false;

    public SendButtonWithRing(Runnable onSend) {
        // Кнопка отправки
        sendButton = new Button("➤");
        sendButton.getStyleClass().add("chat-send-btn");
        sendButton.setTooltip(new Tooltip("Отправить"));
        sendButton.setOnAction(e -> onSend.run());
        sendButton.setDisable(true);

        // Canvas поверх кнопки для рисования кольца
        canvas = new Canvas(BUTTON_SIZE, BUTTON_SIZE);
        canvas.setMouseTransparent(true);

        // Фиксированный размер — ровно как у кнопки, не меняется при вводе
        setMinSize(BUTTON_SIZE, BUTTON_SIZE);
        setPrefSize(BUTTON_SIZE, BUTTON_SIZE);
        setMaxSize(BUTTON_SIZE, BUTTON_SIZE);

        // Кнопка внизу, canvas поверх
        getChildren().addAll(sendButton, canvas);

        // Начальная отрисовка (только track)
        drawRing();

        // Слушаем тему
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.getRoot().getStyleClass().addListener(
                        (ListChangeListener<String>) change -> {
                            lightTheme = newScene.getRoot().getStyleClass()
                                    .contains(LIGHT_THEME_CLASS);
                            drawRing();
                        }
                );
                lightTheme = newScene.getRoot().getStyleClass()
                        .contains(LIGHT_THEME_CLASS);
                drawRing();
            }
        });
    }

    /**
     * Обновить кольцевой индикатор.
     *
     * @param currentBytes текущая длина текста в байтах UTF-8
     * @param maxBytes     максимально допустимое количество байт
     */
    public void update(int currentBytes, int maxBytes) {
        if (maxBytes <= 0) {
            currentRatio = 0;
            nearLimit = false;
        } else {
            currentRatio = Math.min(1.0, (double) currentBytes / maxBytes);
            nearLimit = currentRatio >= 0.9;
        }
        drawRing();
    }

    /** Включить / отключить кнопку отправки. */
    public void setSendDisable(boolean disable) {
        sendButton.setDisable(disable);
    }

    /** Доступ к внутренней кнопке. */
    public Button getSendButton() {
        return sendButton;
    }

    /**
     * Перерисовать кольцо на Canvas: track (полная окружность) + прогресс (дуга).
     */
    private void drawRing() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, BUTTON_SIZE, BUTTON_SIZE);

        double diameter = BUTTON_SIZE - RING_INSET * 2;
        double x = RING_INSET;
        double y = RING_INSET;

        gc.setLineWidth(RING_STROKE);
        gc.setLineCap(StrokeLineCap.ROUND);

        // Track — полная окружность
        gc.setStroke(lightTheme ? TRACK_COLOR_LIGHT : TRACK_COLOR_DARK);
        gc.strokeOval(x, y, diameter, diameter);

        // Прогресс — дуга от 12 часов по часовой стрелке
        if (currentRatio > 0) {
            double sweepDeg = currentRatio * 360.0;
            gc.setStroke(nearLimit ? LIMIT_COLOR : ARC_COLOR);
            // strokeArc: startAngle=90 (12 часов), extent=negative (clockwise)
            gc.strokeArc(x, y, diameter, diameter, 90, -sweepDeg, ArcType.OPEN);
        }
    }
}
