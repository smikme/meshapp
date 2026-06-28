package com.meshtastic.client.system;

import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class MainForm extends VBox {

    private StackPane mainPanel;
    private Form currentForm;

    public MainForm() {
        init();
    }

    private void init() {
        setFillWidth(true);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        getChildren().add(createMain());
    }

    private StackPane createMain() {
        mainPanel = new StackPane();
        mainPanel.getStyleClass().add("content-area");
        mainPanel.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(mainPanel, Priority.ALWAYS);
        return mainPanel;
    }

    public void setForm(Form form) {
        if (currentForm != null) {
            currentForm.prefWidthProperty().unbind();
            currentForm.prefHeightProperty().unbind();
        }
        currentForm = form;
        form.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        form.prefWidthProperty().bind(mainPanel.widthProperty());
        form.prefHeightProperty().bind(mainPanel.heightProperty());
        mainPanel.getChildren().setAll(form);

        // Transparent forms, such as nodes and chat, let vibrancy or the backdrop
        // show through the list area. Remove all copies first, then add one if needed.
        mainPanel.getStyleClass().removeAll("transparent-content");
        if (form.getStyleClass().contains("node-form") || form.getStyleClass().contains("chat-form")) {
            mainPanel.getStyleClass().add("transparent-content");
        }
    }
}
