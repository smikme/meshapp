package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.MessageDbService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.MeshProtos;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class NodeDetailContentTest {

    @TempDir
    Path tempHome;
    private String previousLanguage;

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        previousLanguage = I18n.getLanguageTag();
        I18n.setLanguageTagForTests(I18n.LANGUAGE_RU);
    }

    @AfterEach
    void tearDown() {
        I18n.setLanguageTagForTests(previousLanguage);
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void keepsRenderingDetailTableDuringRepeatedUpdates() {
        NodeDetailContent content = onFxThread(() -> new NodeDetailContent(null, node("Node 0", 80, 3.95f), null));

        onFxThread(() -> {
            StackPane root = new StackPane(content);
            Scene scene = new Scene(root, 900, 600);
            assertNotNull(scene);

            root.applyCss();
            root.layout();

            for (int i = 1; i <= 25; i++) {
                content.updateTableData(node("Node " + i, 80 + (i % 10), 3.7f + (i * 0.01f)));
                root.applyCss();
                root.layout();
            }

            return null;
        });

        assertEquals(14, onFxThread(() -> content.lookupAll(".table-row-cell").size()));
    }

    @Test
    void rendersSingleEmojiDetailValueAsCenteredImage() {
        NodeDetailContent content = onFxThread(() -> new NodeDetailContent(null, node("Kitty", "😻", 80, 3.95f), null));

        onFxThread(() -> {
            StackPane root = new StackPane(content);
            Scene scene = new Scene(root, 900, 600);
            EmojiRenderingSupport.install(scene);

            root.applyCss();
            root.layout();

            TableCell<?, ?> emojiCell = root.lookupAll(".table-cell").stream()
                    .filter(TableCell.class::isInstance)
                    .map(node -> (TableCell<?, ?>) node)
                    .filter(cell -> "😻".equals(cell.getText()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(emojiCell);
            assertEquals(ContentDisplay.GRAPHIC_ONLY, emojiCell.getContentDisplay());
            assertTrue(emojiCell.getGraphic() instanceof ImageView);
            return null;
        });
    }

    @Test
    void rendersDisabledTracerouteButtonWithoutActiveHandler() {
        NodeDetailContent content = onFxThread(() -> new NodeDetailContent(null, node("Traceable", 80, 3.95f), null));

        assertTrue(onFxThread(() -> {
            StackPane root = new StackPane(content);
            Scene scene = new Scene(root, 900, 600);
            assertNotNull(scene);
            root.applyCss();
            root.layout();

            return root.lookupAll(".drawer-toolbar-button").stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .anyMatch(button -> button.getTooltip() != null
                            && "Traceroute до ноды".equals(button.getTooltip().getText())
                            && button.isDisabled());
        }));
    }

    @Test
    void tracesTabRendersSavedTraceroutesWithCreationDateTime() {
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x11111111);
        NodeData target = node("Traceable", 80, 3.95f);
        long timestamp = 1_775_588_044L;
        MeshProtos.RouteDiscovery route = MeshProtos.RouteDiscovery.newBuilder()
                .addSnrTowards(24)
                .build();
        MessageDbService.getInstance().saveTracerouteResult(
                state.getOwnerNodeId(),
                "",
                "",
                "test",
                "trace-1",
                0,
                Integer.toUnsignedLong(target.getNodeNum()),
                target.getNodeId(),
                target.getLongName(),
                target.getNodeNum(),
                target.getNodeId(),
                route.toByteArray(),
                null,
                timestamp);

        NodeDetailContent content = onFxThread(() -> new NodeDetailContent(state, target, null));

        assertTrue(onFxThread(() -> {
            StackPane root = new StackPane(content);
            Scene scene = new Scene(root, 900, 600);
            EmojiRenderingSupport.install(scene);
            root.applyCss();
            root.layout();

            TabPane tabPane = findFirst(root, TabPane.class);
            assertNotNull(tabPane);
            Tab tracesTab = tabPane.getTabs().stream()
                    .filter(tab -> "Трейсы".equals(tab.getText()))
                    .findFirst()
                    .orElseThrow();
            tabPane.getSelectionModel().select(tracesTab);
            root.applyCss();
            root.layout();

            String expectedCreatedAt = "Создан: " + NodeTracerouteHistoryPanel.formatTraceTimestamp(timestamp);
            boolean hasDateTime = root.lookupAll(".node-trace-created-label").stream()
                    .filter(Label.class::isInstance)
                    .map(Label.class::cast)
                    .anyMatch(label -> expectedCreatedAt.equals(label.getText()));
            boolean hasTraceBubble = !root.lookupAll(".chat-bubble-system").isEmpty();
            boolean hasMapButton = root.lookupAll(".node-trace-map-button").stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .anyMatch(button -> button.getTooltip() != null
                            && "Показать трейс на карте".equals(button.getTooltip().getText()));
            boolean hasDateFilter = findFirst(root, DatePicker.class) != null;
            return hasDateTime && hasTraceBubble && hasMapButton && hasDateFilter;
        }));
    }

    @Test
    void tracesTabLoadsPagesDynamicallyAndFiltersByDate() {
        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x11111111);
        NodeData target = node("Traceable", 80, 3.95f);
        MeshProtos.RouteDiscovery route = MeshProtos.RouteDiscovery.newBuilder()
                .addSnrTowards(24)
                .build();
        LocalDate mainDate = LocalDate.of(2026, 5, 30);
        LocalDate otherDate = mainDate.minusDays(1);
        long mainStart = mainDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        long otherStart = otherDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        for (int i = 0; i < 25; i++) {
            saveTrace(state, target, route, mainStart + i, "main-" + i);
        }
        saveTrace(state, target, route, otherStart, "other-date");

        NodeDetailContent content = onFxThread(() -> new NodeDetailContent(state, target, null));

        assertTrue(onFxThread(() -> {
            StackPane root = new StackPane(content);
            Scene scene = new Scene(root, 900, 600);
            EmojiRenderingSupport.install(scene);
            root.applyCss();
            root.layout();

            TabPane tabPane = findFirst(root, TabPane.class);
            assertNotNull(tabPane);
            tabPane.getSelectionModel().select(tabPane.getTabs().stream()
                    .filter(tab -> "Трейсы".equals(tab.getText()))
                    .findFirst()
                    .orElseThrow());
            root.applyCss();
            root.layout();

            boolean firstPageHasTwentyRecords = traceRecordCount(root) == 20;

            ScrollPane scrollPane = (ScrollPane) root.lookup(".node-trace-history-scroll");
            assertNotNull(scrollPane);
            scrollPane.setVvalue(1.0);
            root.applyCss();
            root.layout();
            boolean scrollLoadedSecondPage = traceRecordCount(root) == 26;

            DatePicker datePicker = findFirst(root, DatePicker.class);
            assertNotNull(datePicker);
            datePicker.setValue(otherDate);
            root.applyCss();
            root.layout();
            boolean dateFilterShowsOnlySelectedDay = traceRecordCount(root) == 1;

            return firstPageHasTwentyRecords && scrollLoadedSecondPage && dateFilterShowsOnlySelectedDay;
        }));
    }

    private static NodeData node(String longName, int battery, float voltage) {
        return node(longName, "SXT8", battery, voltage);
    }

    private static NodeData node(String longName, String shortName, int battery, float voltage) {
        NodeData node = new NodeData(0x71A67CF5);
        node.setNodeId("!71a67cf5");
        node.setLongName(longName);
        node.setShortName(shortName);
        node.setRole("CLIENT_MUTE");
        node.setHwModel("TRACKER_T1000_E");
        node.setBatteryLevel(battery);
        node.setVoltage(voltage);
        node.setUnmessagable(Boolean.FALSE);
        node.setLastHeard(1_775_588_044);
        return node;
    }

    private static void saveTrace(DeviceState state,
                                  NodeData target,
                                  MeshProtos.RouteDiscovery route,
                                  long timestamp,
                                  String requestId) {
        MessageDbService.getInstance().saveTracerouteResult(
                state.getOwnerNodeId(),
                "",
                "",
                "test",
                requestId,
                0,
                Integer.toUnsignedLong(target.getNodeNum()),
                target.getNodeId(),
                target.getLongName(),
                target.getNodeNum(),
                target.getNodeId(),
                route.toByteArray(),
                null,
                timestamp);
    }

    private static long traceRecordCount(Node root) {
        return root.lookupAll(".node-trace-record").size();
    }

    private static <T> T onFxThread(FxSupplier<T> supplier) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        await(latch);
        if (failure.get() != null) {
            throw new AssertionError("JavaFX task failed", failure.get());
        }
        return result.get();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for JavaFX task");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for JavaFX task", e);
        }
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }

    private static <T> T findFirst(Node root, Class<T> type) {
        return Stream.concat(
                        type.isInstance(root) ? Stream.of(type.cast(root)) : Stream.empty(),
                        childNodes(root)
                                .map(child -> findFirst(child, type))
                                .filter(Objects::nonNull))
                .findFirst()
                .orElse(null);
    }

    private static Stream<Node> childNodes(Node root) {
        return root instanceof Parent parent
                ? parent.getChildrenUnmodifiable().stream()
                : Stream.empty();
    }
}
