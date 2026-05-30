package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.themes.TypographyManager;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.text.Font;
import org.meshtastic.proto.MeshProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Визуализация traceroute-маршрутов: цепочки нод с SNR-метками и стрелками.
 *
 * <p>Содержит два режима построения:
 * <ul>
 *   <li>Из protobuf {@link MeshProtos.RouteDiscovery} (live данные от устройства)</li>
 *   <li>Из текста БД (восстановление при загрузке истории)</li>
 * </ul>
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class TracerouteView {

    /** Префикс текста traceroute-сообщений в БД. */
    public static final String TRACEROUTE_PREFIX = "\uD83D\uDD0D Traceroute → ";

    private static final Pattern ROUTE_SEGMENT =
            Pattern.compile(" →(-?\\d+[.,]\\d+)dB→ | → ");

    private static final Color COLOR_SOURCE = Color.web("#64B5F6");
    private static final Color COLOR_TARGET = Color.web("#78909C");
    private static final Color SNR_GOOD = Color.web("#4CAF50");
    private static final Color SNR_MEDIUM = Color.web("#FF9800");
    private static final Color SNR_BAD = Color.web("#F44336");

    private final ReadOnlyDoubleProperty containerWidthProp;
    private final IntFunction<String> nodeNameResolver;
    private final BiConsumer<MeshMessage, HBox> onDeleteMessage;
    private final boolean showAvatar;

    /**
     * @param containerWidthProp ширина контейнера сообщений для binding maxWidth
     * @param nodeNameResolver   функция int -&gt; String, возвращает имя ноды по nodeNum
     * @param onDeleteMessage    колбэк удаления сообщения (msg, bubbleRow)
     */
    public TracerouteView(ReadOnlyDoubleProperty containerWidthProp,
                          IntFunction<String> nodeNameResolver,
                          BiConsumer<MeshMessage, HBox> onDeleteMessage) {
        this(containerWidthProp, nodeNameResolver, onDeleteMessage, true);
    }

    /**
     * Создаёт визуализатор traceroute с настраиваемым отображением системного аватара.
     *
     * @param containerWidthProp ширина контейнера сообщений для binding maxWidth
     * @param nodeNameResolver   функция int -&gt; String, возвращает имя ноды по nodeNum
     * @param onDeleteMessage    колбэк удаления сообщения (msg, bubbleRow), может быть {@code null}
     * @param showAvatar         {@code true}, чтобы показывать системный avatar как в чате;
     *                           {@code false} для standalone-панелей и окон traceroute
     */
    public TracerouteView(ReadOnlyDoubleProperty containerWidthProp,
                          IntFunction<String> nodeNameResolver,
                          BiConsumer<MeshMessage, HBox> onDeleteMessage,
                          boolean showAvatar) {
        this.containerWidthProp = containerWidthProp;
        this.nodeNameResolver = nodeNameResolver;
        this.onDeleteMessage = onDeleteMessage;
        this.showAvatar = showAvatar;
    }

    /**
     * Форматировать traceroute в текст для сохранения в БД.
     *
     * @param targetName имя целевой ноды
     * @param route      protobuf RouteDiscovery
     * @return текстовое представление маршрута
     */
    public String formatText(String targetName, MeshProtos.RouteDiscovery route) {
        StringBuilder sb = new StringBuilder();
        sb.append(TRACEROUTE_PREFIX).append(targetName).append("\n");
        sb.append("Я");
        List<Integer> hops = route.getRouteList();
        int snrCount = route.getSnrTowardsCount();
        for (int i = 0; i <= hops.size(); i++) {
            if (i < snrCount) {
                sb.append(String.format(Locale.US, " →%.1fdB→ ",
                        route.getSnrTowards(i) / 4.0));
            } else {
                sb.append(" → ");
            }
            sb.append((i < hops.size())
                    ? nodeNameResolver.apply(hops.get(i)) : targetName);
        }
        if (hasReverseRoute(route)) {
            sb.append("\n").append(targetName);
            List<Integer> backHops = route.getRouteBackList();
            int snrBackCount = route.getSnrBackCount();
            for (int i = 0; i <= backHops.size(); i++) {
                if (i < snrBackCount) {
                    sb.append(String.format(Locale.US, " →%.1fdB→ ",
                            route.getSnrBack(i) / 4.0));
                } else {
                    sb.append(" → ");
                }
                sb.append((i < backHops.size())
                        ? nodeNameResolver.apply(backHops.get(i)) : "Я");
            }
        }
        return sb.toString();
    }

    /**
     * Построить визуальный пузырь traceroute из protobuf данных.
     *
     * @param targetName имя целевой ноды
     * @param route      protobuf RouteDiscovery
     * @param msg        MeshMessage для времени и контекстного меню
     * @return HBox — готовый row для messageContainer
     */
    public HBox buildFromProto(String targetName,
                               MeshProtos.RouteDiscovery route,
                               MeshMessage msg) {
        VBox content = createBubbleContent();

        EmojiTextFlow header = new EmojiTextFlow(
                TRACEROUTE_PREFIX + targetName,
                TypographyManager.scaleChat(18));
        header.setTextStyleClass("chat-bubble-text-node");
        header.getStyleClass().add("chat-bubble-text");
        content.getChildren().add(header);

        // Прямой маршрут
        content.getChildren().add(buildRouteChain(
                "Я", targetName,
                route.getRouteList(), route.getSnrTowardsList(), true));

        // Обратный маршрут
        if (hasReverseRoute(route)) {
            Label backLabel = new Label("Обратный:");
            backLabel.getStyleClass().addAll(
                    "chat-bubble-text", "traceroute-section-label");
            content.getChildren().add(backLabel);
            content.getChildren().add(buildRouteChain(
                    targetName, "Я",
                    route.getRouteBackList(), route.getSnrBackList(), false));
        }

        addTimestamp(content, msg);
        return wrapWithAvatar(content, msg);
    }

    /**
     * Попытаться восстановить визуальный traceroute-пузырь из текста БД.
     *
     * @param msg системное сообщение с текстом traceroute
     * @return визуальный пузырь или {@code null} если текст не распознан
     */
    public HBox tryBuildFromText(MeshMessage msg) {
        String text = msg.getText();
        String[] lines = text.split("\n");
        if (lines.length < 2) {
            return null;
        }

        // Строка 0: "🔍 Traceroute → TargetName"
        String headerLine = lines[0];
        String targetName = headerLine.substring(headerLine.indexOf("→") + 1).trim();

        // Строка 1: прямой маршрут
        ParsedRoute fwd = parseRouteLine(lines[1]);
        if (fwd == null) {
            return null;
        }

        // Строка 2 (опц.): обратный маршрут
        ParsedRoute back = null;
        if (lines.length >= 3) {
            back = parseRouteLine(lines[2]);
        }

        VBox content = createBubbleContent();

        EmojiTextFlow headerLabel = new EmojiTextFlow(
                TRACEROUTE_PREFIX + targetName,
                TypographyManager.scaleChat(18));
        headerLabel.setTextStyleClass("chat-bubble-text-node");
        headerLabel.getStyleClass().add("chat-bubble-text");
        content.getChildren().add(headerLabel);

        content.getChildren().add(
                buildRouteChainFromNames(fwd.names, fwd.snrValues, true));

        if (back != null) {
            Label backLabel = new Label("Обратный:");
            backLabel.getStyleClass().addAll(
                    "chat-bubble-text", "traceroute-section-label");
            content.getChildren().add(backLabel);
            content.getChildren().add(
                    buildRouteChainFromNames(back.names, back.snrValues, false));
        }

        addTimestamp(content, msg);
        return wrapWithAvatar(content, msg);
    }

    // === Внутренние методы ===

    private VBox createBubbleContent() {
        VBox content = new VBox(6);
        content.getStyleClass().add("chat-bubble-system");
        content.maxWidthProperty().bind(containerWidthProp.multiply(showAvatar ? 0.85 : 0.98));
        content.setMinHeight(Region.USE_PREF_SIZE);
        return content;
    }

    private void addTimestamp(VBox content, MeshMessage msg) {
        Label timeLabel = new Label(
                ChatTimeFormatter.formatMessageTime(msg.getTimestamp()));
        timeLabel.getStyleClass().add("chat-bubble-time");
        content.getChildren().add(timeLabel);
    }

    private HBox wrapWithAvatar(VBox content, MeshMessage msg) {
        if (!showAvatar) {
            HBox row = new HBox(content);
            row.setAlignment(Pos.BOTTOM_LEFT);
            row.getStyleClass().add("chat-message-row-system");
            attachContextMenu(content, msg, row);
            return row;
        }

        StackPane botAvatar = new StackPane();
        botAvatar.setMinSize(28, 28);
        botAvatar.setMaxSize(28, 28);
        botAvatar.setAlignment(Pos.CENTER);
        double botSize = TypographyManager.scaleChat(20);
        ImageView botImg = EmojiImageCache.createImageView("\uD83E\uDD16", botSize);
        if (botImg != null) {
            botAvatar.getChildren().add(botImg);
        } else {
            Label fallback = new Label("\uD83E\uDD16");
            fallback.setFont(Font.font(botSize));
            botAvatar.getChildren().add(fallback);
        }

        HBox row = new HBox(6, botAvatar, content);
        row.setAlignment(Pos.BOTTOM_LEFT);
        row.getStyleClass().add("chat-message-row-system");

        attachContextMenu(content, msg, row);
        return row;
    }

    private void attachContextMenu(VBox content, MeshMessage msg, HBox row) {
        MenuItem copyItem = new MenuItem("Копировать");
        copyItem.setOnAction(ev -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(msg.getText());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        ContextMenu ctxMenu;
        if (onDeleteMessage != null) {
            MenuItem deleteItem = new MenuItem("Удалить");
            deleteItem.setOnAction(ev -> onDeleteMessage.accept(msg, row));
            ctxMenu = new ContextMenu(copyItem, new SeparatorMenuItem(), deleteItem);
        } else {
            ctxMenu = new ContextMenu(copyItem);
        }
        content.setOnContextMenuRequested(ev -> {
            ctxMenu.show(content, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });
    }

    private HBox buildRouteChain(String fromName, String toName,
                                 List<Integer> hops, List<Integer> snrValues,
                                 boolean forward) {
        List<String> names = new ArrayList<>();
        names.add(fromName);
        for (int hop : hops) {
            names.add(nodeNameResolver.apply(hop));
        }
        names.add(toName);

        List<Double> snrs = new ArrayList<>();
        for (int raw : snrValues) {
            snrs.add(raw / 4.0);
        }
        return buildRouteChainFromNames(names, snrs, forward);
    }

    private HBox buildRouteChainFromNames(List<String> names,
                                          List<Double> snrValues,
                                          boolean forward) {
        HBox chain = new HBox(0);
        chain.setAlignment(Pos.CENTER_LEFT);
        chain.setPadding(new Insets(4, 0, 4, 0));

        for (int i = 0; i < names.size(); i++) {
            double snr = (i < snrValues.size())
                    ? snrValues.get(i) : Double.NaN;

            Color nodeColor = resolveNodeColor(i, names.size(), snr, forward);

            VBox nodeBox = new VBox(2);
            nodeBox.setAlignment(Pos.CENTER);
            Circle circle = new Circle(8, nodeColor);
            circle.getStyleClass().add("traceroute-node-circle");

            Label nameLabel = new Label(names.get(i));
            nameLabel.getStyleClass().add("traceroute-hop-name");
            nameLabel.setMaxWidth(80);
            nameLabel.setWrapText(false);
            nameLabel.setEllipsisString("…");
            nodeBox.getChildren().addAll(circle, nameLabel);
            chain.getChildren().add(nodeBox);

            if (i < names.size() - 1) {
                chain.getChildren().add(
                        buildLink(i, snrValues));
            }
        }

        return chain;
    }

    private Color resolveNodeColor(int index, int total,
                                   double snr, boolean forward) {
        if (index == 0) {
            return forward ? COLOR_SOURCE : COLOR_TARGET;
        }
        if (index == total - 1) {
            return forward ? COLOR_TARGET : COLOR_SOURCE;
        }
        if (!Double.isNaN(snr)) {
            return snrColor(snr);
        }
        return COLOR_TARGET;
    }

    private VBox buildLink(int index, List<Double> snrValues) {
        double linkSnr = (index < snrValues.size())
                ? snrValues.get(index) : Double.NaN;

        VBox linkBox = new VBox(1);
        linkBox.setAlignment(Pos.CENTER);
        linkBox.setPadding(new Insets(0, 2, 0, 2));

        Label snrLabel = new Label(Double.isNaN(linkSnr)
                ? "" : String.format("%.1f dB", linkSnr));
        snrLabel.getStyleClass().add("traceroute-snr-label");
        if (!Double.isNaN(linkSnr)) {
            snrLabel.setTextFill(snrColor(linkSnr));
        }

        HBox lineBox = new HBox(0);
        lineBox.setAlignment(Pos.CENTER);

        Line line = new Line(0, 0, 30, 0);
        line.getStyleClass().add("traceroute-line");
        line.setStrokeWidth(2);
        if (!Double.isNaN(linkSnr)) {
            line.setStroke(snrColor(linkSnr).deriveColor(0, 1, 1, 0.6));
        } else {
            line.setStroke(Color.web("#78909C", 0.4));
        }

        Polygon arrow = new Polygon(0, -3, 6, 0, 0, 3);
        arrow.setFill(line.getStroke());

        lineBox.getChildren().addAll(line, arrow);
        linkBox.getChildren().addAll(snrLabel, lineBox);
        return linkBox;
    }

    private static Color snrColor(double snr) {
        if (snr >= 10) {
            return SNR_GOOD;
        }
        if (snr >= 0) {
            return SNR_MEDIUM;
        }
        return SNR_BAD;
    }

    private static boolean hasReverseRoute(MeshProtos.RouteDiscovery route) {
        // route_back stores only intermediate nodes; direct return links are represented by snr_back alone.
        return route.getRouteBackCount() > 0 || route.getSnrBackCount() > 0;
    }

    private static ParsedRoute parseRouteLine(String line) {
        List<String> names = new ArrayList<>();
        List<Double> snrs = new ArrayList<>();
        Matcher m = ROUTE_SEGMENT.matcher(line);
        int lastEnd = 0;
        while (m.find()) {
            names.add(line.substring(lastEnd, m.start()).trim());
            String snrStr = m.group(1);
            snrs.add(snrStr != null
                    ? Double.parseDouble(snrStr.replace(',', '.'))
                    : Double.NaN);
            lastEnd = m.end();
        }
        if (lastEnd < line.length()) {
            names.add(line.substring(lastEnd).trim());
        }
        if (names.size() < 2) {
            return null;
        }
        return new ParsedRoute(names, snrs);
    }

    private record ParsedRoute(List<String> names, List<Double> snrValues) {}
}
