package com.meshtastic.client.modal;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.components.CrashReportFlow;
import com.meshtastic.client.utils.ExternalUrlLauncher;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.stage.Window;

import com.meshtastic.client.components.MemoryBar;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class About extends VBox {

    private static final String BORDER_STYLE =
            "-fx-border-color: -color-border-default; -fx-border-radius: 5; -fx-padding: 10;";

    public About() {
        setSpacing(10);
        setPadding(new Insets(20, 30, 20, 30));
        setPrefWidth(400);

        Label title = new Label("Клиент для mesh сети Meshtastic");
        title.getStyleClass().add("hero-title");

        // ── О проекте ────────────────────────────────────────────
        VBox projectBox = new VBox(8);
        projectBox.setPadding(new Insets(10));
        projectBox.setStyle(BORDER_STYLE);

        Region projectLogo = createMeshIcon(42);

        Label projectName = new Label("MeshApp");
        projectName.getStyleClass().add("item-title");

        Label projectDesc = new Label("Кроссплатформенный desktop-клиент\nдля управления сетями Meshtastic");
        projectDesc.setWrapText(true);

        VBox projectTitleBox = new VBox(2, projectName, projectDesc);

        HBox projectHeader = new HBox(12, projectLogo, projectTitleBox);
        projectHeader.setAlignment(Pos.TOP_LEFT);

        Hyperlink siteLink = createLink("meshapp.ru", "https://meshapp.ru");
        Hyperlink codeLink = createLink("Исходный код", "https://git.privatepractice.app/covox/meshapp");
        Hyperlink issuesLink = createLink("Багтрекер", "https://git.privatepractice.app/covox/meshapp/issues");

        HBox linksRow = new HBox(12, siteLink, codeLink, issuesLink);
        linksRow.setAlignment(Pos.CENTER_LEFT);

        projectBox.getChildren().addAll(projectHeader, linksRow);

        // ── Партнёр проекта ──────────────────────────────────────
        VBox partnerBox = new VBox(8);
        partnerBox.setPadding(new Insets(10));
        partnerBox.setStyle(BORDER_STYLE);

        Label partnerTitle = new Label("Партнёр проекта");
        partnerTitle.getStyleClass().add("section-title");

        ImageView partnerLogo = new ImageView(
                new Image(About.class.getResourceAsStream("/icons/onemesh.png")));
        partnerLogo.setFitWidth(36);
        partnerLogo.setFitHeight(36);
        partnerLogo.setPreserveRatio(true);
        partnerLogo.setSmooth(true);

        Label partnerName = new Label("OneMesh — Карта Meshtastic");
        partnerName.getStyleClass().add("section-title");

        Label partnerDesc = new Label("Интерактивная карта устройств Meshtastic в России");
        partnerDesc.setWrapText(true);

        Hyperlink partnerLink = createLink("map.onemesh.ru", "https://map.onemesh.ru/");

        VBox partnerInfo = new VBox(2, partnerName, partnerDesc, partnerLink);

        HBox partnerContent = new HBox(12, partnerLogo, partnerInfo);
        partnerContent.setAlignment(Pos.TOP_LEFT);

        partnerBox.getChildren().addAll(partnerTitle, partnerContent);

        // ── Системная информация ─────────────────────────────────
        VBox sysInfo = new VBox(5);
        sysInfo.setPadding(new Insets(10));
        sysInfo.setStyle(BORDER_STYLE);

        Label sysTitle = new Label("Системная информация");
        sysTitle.getStyleClass().add("section-title");

        String version = MeshApp.APPLICATION_VERSION;
        String build = MeshApp.VERSION_CODE > 0 ? Integer.toString(MeshApp.VERSION_CODE) : "dev";
        String java = System.getProperty("java.vendor") + " - v" + System.getProperty("java.version");
        String system = System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " - v" + System.getProperty("os.version");

        Label sysVersion = new Label("Version: " + version);
        Label sysBuild = new Label("Build: " + build);
        Label sysJava = new Label("Java: " + java);
        Label sysOs = new Label("System: " + system);

        Label memTitle = new Label("Память:");
        MemoryBar memoryBar = new MemoryBar();

        sysInfo.getChildren().addAll(sysTitle, sysVersion, sysBuild, sysJava, sysOs, memTitle, memoryBar);

        VBox reportBox = new VBox(8);
        reportBox.setPadding(new Insets(10));
        reportBox.setStyle(BORDER_STYLE);

        Label reportTitle = new Label("Поддержка");
        reportTitle.getStyleClass().add("section-title");

        Label reportDesc = new Label("Если приложение работает некорректно, отправьте технический лог текущей сессии разработчикам.");
        reportDesc.setWrapText(true);

        Button reportButton = new Button("Сообщить о проблеме");
        reportButton.getStyleClass().add("report-problem-button");
        reportButton.setMaxWidth(Double.MAX_VALUE);
        reportButton.setOnAction(event -> openProblemReportFlow());

        reportBox.getChildren().addAll(reportTitle, reportDesc, reportButton);

        getChildren().addAll(title, projectBox, partnerBox, sysInfo, reportBox);
    }

    /**
     * Mesh-сеть логотип MeshApp (как на meshapp.ru).
     * Три основных узла, три вторичных, соединения между ними.
     */
    private Region createMeshIcon(double size) {
        double s = size / 32.0;

        // Вторичные линии (рисуем первыми — под узлами)
        Line l4 = line(16, 6, 26, 14, s, 1.0, 0.3);
        Line l5 = line(16, 6, 6, 14, s, 1.0, 0.3);
        Line l6 = line(6, 14, 6, 26, s, 1.0, 0.3);
        Line l7 = line(26, 14, 26, 26, s, 1.0, 0.3);
        Line l8 = line(6, 26, 16, 28, s, 1.0, 0.3);
        Line l9 = line(26, 26, 16, 28, s, 1.0, 0.3);

        // Основные линии
        Line l1 = line(16, 6, 6, 26, s, 1.5, 0.4);
        Line l2 = line(16, 6, 26, 26, s, 1.5, 0.4);
        Line l3 = line(6, 26, 26, 26, s, 1.5, 0.4);

        // Основные узлы
        Circle c1 = circle(16, 6, 3, s, 1.0);
        Circle c2 = circle(6, 26, 3, s, 1.0);
        Circle c3 = circle(26, 26, 3, s, 1.0);

        // Вторичные узлы
        Circle c4 = circle(26, 14, 2.5, s, 0.6);
        Circle c5 = circle(6, 14, 2.5, s, 0.6);
        Circle c6 = circle(16, 28, 2.5, s, 0.6);

        Group group = new Group(l4, l5, l6, l7, l8, l9, l1, l2, l3, c1, c2, c3, c4, c5, c6);

        StackPane wrapper = new StackPane(group);
        wrapper.setMinSize(size, size);
        wrapper.setPrefSize(size, size);
        wrapper.setMaxSize(size, size);
        return wrapper;
    }

    private Circle circle(double cx, double cy, double r, double scale, double opacity) {
        Circle c = new Circle(cx * scale, cy * scale, r * scale);
        c.setStyle("-fx-fill: -color-fg-muted;");
        c.setOpacity(opacity);
        return c;
    }

    private Line line(double x1, double y1, double x2, double y2,
                      double scale, double strokeWidth, double opacity) {
        Line l = new Line(x1 * scale, y1 * scale, x2 * scale, y2 * scale);
        l.setStyle("-fx-stroke: -color-fg-muted;");
        l.setStrokeWidth(strokeWidth * scale);
        l.setOpacity(opacity);
        return l;
    }

    private Hyperlink createLink(String text, String url) {
        Hyperlink link = new Hyperlink(text);
        link.setPadding(new Insets(0));
        link.setOnAction(e -> openUrl(url));
        return link;
    }

    private static void openUrl(String url) {
        ExternalUrlLauncher.open(url);
    }

    private void openProblemReportFlow() {
        Window owner = getScene() != null ? getScene().getWindow() : MeshApp.getPrimaryStage();
        Runnable openFlow = () -> CrashReportFlow.showProblemReport(owner);

        ModalPane pane = ModalPane.getInstance();
        if (pane != null && pane.isVisible()) {
            pane.setOnHidden(openFlow);
            pane.hide();
            return;
        }

        openFlow.run();
    }
}
