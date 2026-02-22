package com.meshtastic.client.system;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.components.MemoryBar;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class MainForm extends VBox {

    private StackPane mainPanel;

    public MainForm() {
        init();
    }

    private void init() {
        setFillWidth(true);
        getChildren().add(createMain());
    }

    private StackPane createMain() {
        mainPanel = new StackPane();
        mainPanel.getStyleClass().add("content-area");
        VBox.setVgrow(mainPanel, Priority.ALWAYS);
        return mainPanel;
    }

    private HBox createFooter() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(2, 10, 2, 10));
        footer.setPrefHeight(30);
        footer.getStyleClass().add("main-footer");

        Label versionLabel = new Label("\u2699 MeshApp: v" + MeshApp.APPLICATION_VERSION);
        versionLabel.getStyleClass().add("footer-label");

        String javaVersion = System.getProperty("java.version", "").trim();
        Label javaLabel = new Label("\u2699 Среда исполнения: Java  v" + javaVersion);
        javaLabel.getStyleClass().add("footer-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Separator sep = new Separator();
        sep.setOrientation(javafx.geometry.Orientation.VERTICAL);

        MemoryBar memoryBar = new MemoryBar();

        footer.getChildren().addAll(versionLabel, spacer, javaLabel, sep, memoryBar);
        return footer;
    }

    public void setForm(Form form) {
        mainPanel.getChildren().setAll(form);

        // Формы с прозрачным фоном (например, ноды) убирают фон content-area,
        // чтобы vibrancy/backdrop просвечивал под списком.
        // Сначала убираем ВСЕ экземпляры (removeAll), потом добавляем один если нужно.
        mainPanel.getStyleClass().removeAll("transparent-content");
        if (form.getStyleClass().contains("node-form") || form.getStyleClass().contains("chat-form")) {
            mainPanel.getStyleClass().add("transparent-content");
        }
    }
}
