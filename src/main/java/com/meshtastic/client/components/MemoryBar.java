package com.meshtastic.client.components;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

public class MemoryBar extends StackPane {

    private final ProgressBar progressBar;
    private final Label label;
    private final String format = "%s / %s";

    public MemoryBar() {
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefWidth(150);
        progressBar.setPrefHeight(16);
        progressBar.getStyleClass().add("memory-bar");

        label = new Label();
        label.getStyleClass().add("memory-label");

        setAlignment(Pos.CENTER);
        getChildren().addAll(progressBar, label);

        updateMemoryUsage();

        Timeline timeline = new Timeline(
                new KeyFrame(Duration.seconds(1), e -> updateMemoryUsage())
        );
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    private void updateMemoryUsage() {
        MemoryUsage memoryUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long committed = memoryUsage.getCommitted();
        long used = memoryUsage.getUsed();

        progressBar.setProgress(committed > 0 ? (double) used / committed : 0);
        label.setText(String.format(format, formatSize(used), formatSize(committed)));
    }

    private String formatSize(long bytes) {
        int unit = 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }
}
