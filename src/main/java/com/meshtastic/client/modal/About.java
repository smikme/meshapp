package com.meshtastic.client.modal;

import com.meshtastic.client.MeshApp;
import javafx.geometry.Insets;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.awt.Desktop;
import java.net.URI;

public class About extends VBox {

    public About() {
        setSpacing(10);
        setPadding(new Insets(20, 30, 20, 30));
        setPrefWidth(400);

        Label title = new Label("Клиент для mesh сети Meshtastic");
        title.setFont(Font.font("Roboto", FontWeight.BOLD, 18));

        Text author = new Text("Данный проект разрабатывает и поддерживает ");
        Text authorName = new Text("Константин А. Смирнов");
        authorName.setStyle("-fx-font-weight: bold;");
        Text authorSuffix = new Text(" (coVox).\n");
        Text emailPrefix = new Text("Вы можете связаться с ним по e-mail: ");

        Hyperlink emailLink = new Hyperlink("covox@covox.ru");
        emailLink.setOnAction(e -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().mail(new URI("mailto:covox@covox.ru"));
                }
            } catch (Exception ignored) {
            }
        });

        TextFlow description = new TextFlow(author, authorName, authorSuffix, emailPrefix, emailLink);

        VBox sysInfo = new VBox(5);
        sysInfo.setPadding(new Insets(10));
        sysInfo.setStyle("-fx-border-color: -color-border-default; -fx-border-radius: 5; -fx-padding: 10;");

        Label sysTitle = new Label("Системная информация");
        sysTitle.setFont(Font.font("Roboto", FontWeight.BOLD, 13));

        String version = MeshApp.APPLICATION_VERSION;
        String java = System.getProperty("java.vendor") + " - v" + System.getProperty("java.version");
        String system = System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " - v" + System.getProperty("os.version");

        Label sysVersion = new Label("Version: " + version);
        Label sysJava = new Label("Java: " + java);
        Label sysOs = new Label("System: " + system);

        sysInfo.getChildren().addAll(sysTitle, sysVersion, sysJava, sysOs);

        getChildren().addAll(title, description, sysInfo);
    }
}
