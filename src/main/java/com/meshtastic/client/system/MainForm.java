package com.meshtastic.client.system;

import javafx.scene.layout.Priority;
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
