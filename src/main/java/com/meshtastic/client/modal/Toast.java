package com.meshtastic.client.modal;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.Locale;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class Toast {

    private Toast() {}

    public enum Type {
        SUCCESS, ERROR, INFO, WARNING
    }

    private static StackPane overlay;

    public static void setOverlay(StackPane overlayPane) {
        overlay = overlayPane;
    }

    public static void show(Type type, String message) {
        if (overlay == null) { return; }

        Label toast = new Label(message);
        toast.getStyleClass().addAll("toast", "toast-" + type.name().toLowerCase(Locale.ROOT));
        toast.setMaxWidth(500);
        toast.setWrapText(true);
        StackPane.setAlignment(toast, Pos.TOP_CENTER);

        toast.setOpacity(0);
        overlay.getChildren().add(toast);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(300), toast);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);

        PauseTransition hold = new PauseTransition(Duration.seconds(3));

        FadeTransition fadeOut = new FadeTransition(Duration.millis(300), toast);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> overlay.getChildren().remove(toast));

        new SequentialTransition(fadeIn, hold, fadeOut).play();
    }
}
