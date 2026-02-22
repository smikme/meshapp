package com.meshtastic.client.simple;

import com.meshtastic.client.model.ConnectionEntry;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.function.Consumer;

public class SimpleConnectionForm extends VBox {

    private final TextField txtName;
    private final TextField txtHost;
    private final TextField txtPort;

    private Consumer<ConnectionEntry> onSave;

    public SimpleConnectionForm() {
        setSpacing(8);
        setPadding(new Insets(20, 30, 20, 30));
        setPrefWidth(340);
        setMaxWidth(340);
        setMaxHeight(Double.MAX_VALUE);
        getStyleClass().add("modal-side-panel");

        Label title = new Label("Новое подключение");
        title.setFont(Font.font("Roboto", FontWeight.BOLD, 15));

        txtName = new TextField();
        txtName.setPromptText("Например: Дом, Офис");

        txtHost = new TextField();
        txtHost.setPromptText("192.168.1.1");

        txtPort = new TextField("4403");

        Button btnSave = new Button("Сохранить");
        btnSave.getStyleClass().add("accent");
        btnSave.setOnAction(e -> doSave());

        Button btnCancel = new Button("Отмена");
        btnCancel.setOnAction(e -> doCancel());

        HBox buttons = new HBox(10, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        getChildren().addAll(
                title, new Separator(),
                new Label("Название"), txtName,
                new Label("Хост"), txtHost,
                new Label("Порт"), txtPort,
                buttons
        );
    }

    public void setOnSave(Consumer<ConnectionEntry> onSave) {
        this.onSave = onSave;
    }

    private void doSave() {
        ConnectionEntry entry = getConnectionEntry();
        if (entry != null && onSave != null) {
            onSave.accept(entry);
        }
    }

    private void doCancel() {
        var modalPane = com.meshtastic.client.modal.ModalPane.getInstance();
        if (modalPane != null) {
            modalPane.hide();
        }
    }

    public ConnectionEntry getConnectionEntry() {
        String name = txtName.getText().trim();
        String host = txtHost.getText().trim();
        String portText = txtPort.getText().trim();
        if (name.isEmpty() || host.isEmpty()) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return null;
        }
        return new ConnectionEntry(name, host, port);
    }

    public void formOpen() {
        txtName.requestFocus();
    }
}
