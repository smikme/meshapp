package com.meshtastic.client.system;

import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
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

        // Transparent forms, such as nodes and chat, let vibrancy or the backdrop
        // show through the list area. Remove all copies first, then add one if needed.
        mainPanel.getStyleClass().removeAll("transparent-content");
        if (form.getStyleClass().contains("node-form") || form.getStyleClass().contains("chat-form")) {
            mainPanel.getStyleClass().add("transparent-content");
        }
    }
}
