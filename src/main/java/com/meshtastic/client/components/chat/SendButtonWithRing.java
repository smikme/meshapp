package com.meshtastic.client.components.chat;

import com.meshtastic.client.i18n.I18n;
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
 * Send-message button with a circular fill indicator.
 *
 * <p>The ring sits inside the "➤" button along its edge and shows the share of
 * used bytes. At 90% or more, the ring turns red. The background track remains
 * visible as a translucent circle. Component size is fixed at 36x36 and does
 * not change while typing.
 *
 * <p>Drawing is performed on a {@link Canvas} over the button, guaranteeing
 * rendering independent of Button/Region z-order.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class SendButtonWithRing extends StackPane {

    /** Button size, matching .chat-send-btn in CSS. */
    private static final double BUTTON_SIZE = 36;
    /** Ring stroke width. */
    private static final double RING_STROKE = 3;
    /** Ring inset from the button edge, measured at stroke center. */
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
        // Send button.
        sendButton = new Button("➤");
        sendButton.getStyleClass().add("chat-send-btn");
        sendButton.setTooltip(new Tooltip(I18n.t("chat.send")));
        sendButton.setOnAction(e -> onSend.run());
        sendButton.setDisable(true);

        // Canvas above the button for drawing the ring.
        canvas = new Canvas(BUTTON_SIZE, BUTTON_SIZE);
        canvas.setMouseTransparent(true);

        // Fixed size, exactly matching the button and stable while typing.
        setMinSize(BUTTON_SIZE, BUTTON_SIZE);
        setPrefSize(BUTTON_SIZE, BUTTON_SIZE);
        setMaxSize(BUTTON_SIZE, BUTTON_SIZE);

        // Button below, canvas above.
        getChildren().addAll(sendButton, canvas);

        // Initial rendering with only the track.
        drawRing();

        // Track theme changes.
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
     * Updates the circular indicator.
 *
     * @param currentBytes current text length in UTF-8 bytes
     * @param maxBytes     maximum allowed byte count
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

    /** Enables or disables the send button. */
    public void setSendDisable(boolean disable) {
        sendButton.setDisable(disable);
    }

    /** Returns the inner button. */
    public Button getSendButton() {
        return sendButton;
    }

    /**
     * Repaints the ring on Canvas: track as a full circle plus progress as an arc.
     */
    private void drawRing() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, BUTTON_SIZE, BUTTON_SIZE);

        double diameter = BUTTON_SIZE - RING_INSET * 2;
        double x = RING_INSET;
        double y = RING_INSET;

        gc.setLineWidth(RING_STROKE);
        gc.setLineCap(StrokeLineCap.ROUND);

        // Track: full circle.
        gc.setStroke(lightTheme ? TRACK_COLOR_LIGHT : TRACK_COLOR_DARK);
        gc.strokeOval(x, y, diameter, diameter);

        // Progress: arc from 12 o'clock clockwise.
        if (currentRatio > 0) {
            double sweepDeg = currentRatio * 360.0;
            gc.setStroke(nearLimit ? LIMIT_COLOR : ARC_COLOR);
            // strokeArc: startAngle=90 means 12 o'clock; negative extent draws clockwise.
            gc.strokeArc(x, y, diameter, diameter, 90, -sweepDeg, ArcType.OPEN);
        }
    }
}
