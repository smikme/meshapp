package com.meshtastic.client.forms;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.MessageReaction;
import com.meshtastic.client.service.DatabaseProvider;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.themes.ThemeManager;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.ChannelProtos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Opt-in benchmark using production controls, CSS, H2 and a real JavaFX scene.
 * Run separately from other tests for comparable cold-start and heap measurements:
 * {@code MESHAPP_CHAT_BENCHMARK=local ./gradlew test --tests '*ChatPerformanceTest' --rerun-tasks}.
 * Results, screenshots and JFR recordings are written to build/reports/chat-performance.
 * Ready times include draining pending callbacks and three layout/render pulses;
 * Continuous scrolling dispatches ScrollEvent through the real ScrollPane skin;
 * frame gaps measure FX pulses, not display presentation. No timing assertions
 * are made in the regular test suite. Images use a local PNG fixture through the production preview renderer. Real radio I/O is excluded.
 */
@EnabledIfEnvironmentVariable(named = "MESHAPP_CHAT_BENCHMARK", matches = ".+")
class ChatPerformanceTest {
    @TempDir Path home;
    private FormChat form;
    private Stage stage;
    private String fixtureUrl;
    private final boolean images = Boolean.parseBoolean(System.getenv("MESHAPP_CHAT_BENCHMARK_IMAGES"));
    private final List<String> results = new ArrayList<>();
    private final List<WeakReference<javafx.scene.Node>> discardedRows = new ArrayList<>();

    @Test
    void measureChatInteraction() throws Exception {
        TestEnvironmentSupport.setUserHome(home);
        TestEnvironmentSupport.resetSingletons();
        TestEnvironmentSupport.ensureJavaFxStarted();
        Path output = Path.of("build/reports/chat-performance");
        Files.createDirectories(output);
        String label = System.getenv("MESHAPP_CHAT_BENCHMARK").replaceAll("[^a-zA-Z0-9_-]", "_");
        try {
            var fixture = new java.awt.image.BufferedImage(640, 480, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = fixture.createGraphics();
            graphics.setColor(java.awt.Color.ORANGE);
            graphics.fillRect(0, 0, 640, 480);
            graphics.setColor(java.awt.Color.BLUE);
            graphics.fillOval(60, 30, 300, 350);
            graphics.dispose();
            Path fixturePath = home.resolve("preview.png");
            javax.imageio.ImageIO.write(fixture, "png", fixturePath.toFile());
            fixtureUrl = fixturePath.toUri().toString();
            seedHistory();
            fx(() -> {
                form = new FormChat() {
                    @Override protected javafx.scene.image.Image loadPreviewImage(String url) {
                        return new javafx.scene.image.Image(fixtureUrl, true);
                    }
                };
                form.formVisible = true;
                Scene scene = new Scene(form, 1100, 800);
                ThemeManager.applyTheme(scene, false);
                stage = new Stage();
                stage.setScene(scene);
                stage.show();
            });
            settle();
            try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
                recording.start();
                report("first_open_ready_ms", List.of(open(channel(0))));
                fx(() -> snapshot(output.resolve(label + ".png")));
                long firstScroll = System.nanoTime();
                fx(() -> scrollBy(-320));
                settle();
                report("first_scroll_ready_ms", List.of(millis(firstScroll)));
                List<Double> switches = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    int chat = (i + 1) % 2;
                    switches.add(open(channel(chat)));
                }
                report("repeat_open_ready_ms", switches);
                long warmHeap = retainedHeap();
                measureContinuousHistoryScroll();
                List<Double> pages = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    long start = System.nanoTime();
                    fx(form::loadOlderMessages);
                    settle();
                    pages.add(millis(start));
                }
                report("older_page_ready_ms", pages);
                measureScroll();
                measureIdleFrames();
                // Leave the chat in old history: returning must restore its anchor.
                fx(form::saveCurrentChatScrollState);
                List<Double> historySwitches = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    historySwitches.add(open(channel((i + 1) % 2)));
                }
                report("history_switch_ready_ms", historySwitches);
                for (int i = 0; i < 20; i++) open(channel((i + 1) % 2));
                long finalHeap = retainedHeap();
                results.add("retained_heap_after_warmup_bytes=" + warmHeap);
                results.add("retained_heap_after_20_more_switches_bytes=" + finalHeap);
                results.add("discarded_bubble_contents_still_reachable=" + discardedRows.stream()
                        .filter(reference -> reference.get() != null).count() + "/" + discardedRows.size());
                fx(() -> assertTrue(form.loadedMessages.size() <= FormChatBase.MAX_LOADED_MESSAGES));
                recording.stop();
                recording.dump(output.resolve(label + ".jfr"));
                reportStages(output.resolve(label + ".jfr"));
            }
            results.add("image_fixture_enabled=" + images);
            results.add("runtime=" + System.getProperty("java.runtime.version")
                    + ", javafx=" + System.getProperty("javafx.runtime.version"));
            Files.write(output.resolve(label + ".txt"), results);
            results.forEach(System.out::println);
        } finally {
            settle();
            fx(() -> {
                if (stage != null) {
                    form.formClose();
                    ThemeManager.unregisterScene(stage.getScene());
                    stage.close();
                }
            });
            settle();
            fx(TestEnvironmentSupport::resetSingletons);
        }
    }

    private void seedHistory() throws Exception {
        MessageDbService db = MessageDbService.getInstance();
        var connection = DatabaseProvider.getConnection();
        connection.setAutoCommit(false);
        try {
            for (int chat = 0; chat < 2; chat++) {
                for (int i = 1; i <= 5000; i++) {
                    MeshMessage message = new MeshMessage("!00000002", "!ffffffff", chat,
                            "Message " + i + " 🚀 Привет! https://example.test/message/" + i
                                    + (i % 3 == 0 ? "\nA longer message with wrapped lines. ".repeat(8) : "")
                                    + (images && i % 11 == 0 ? "\nhttps://d.privatepractice.app/test00" : ""),
                            1700000000L + i, i % 4 == 0);
                    message.setPacketId(chat * 10000 + i);
                    message.setSenderName("Test sender");
                    message.setRxRssi(-85);
                    message.setRxSnr(6);
                    if (i % 5 == 0) {
                        message.setReplyId(chat * 10000 + i - 1);
                        message.setReplyText("Previous message quoted here");
                    }
                    db.save(message, "channel", Integer.toString(chat), "");
                    if (i % 7 == 0) {
                        MessageReaction reaction = new MessageReaction(message.getPacketId(),
                                "!00000003", "👍", message.getTimestamp(), false);
                        reaction.setPacketId(100000 + chat * 10000 + i);
                        db.saveReaction(reaction, "channel", Integer.toString(chat), "");
                    }
                }
            }
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private double open(ChatItem chat) throws Exception {
        long start = System.nanoTime();
        fx(() -> {
            form.loadedMessageRows.values().forEach(row -> row.getChildren().forEach(child -> discardedRows.add(new WeakReference<>(child))));
            form.saveCurrentChatScrollState();
            form.openChat(chat);
        });
        settle();
        return millis(start);
    }

    private void measureScroll() throws Exception {
        List<Double> gaps = new ArrayList<>();
        List<Double> handlers = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        fx(() -> new AnimationTimer() {
            private long previous;
            private int frame;
            @Override public void handle(long now) {
                if (previous != 0) gaps.add((now - previous) / 1_000_000.0);
                previous = now;
                long start = System.nanoTime();
                form.messageScrollPane.setVvalue(0.5 + 0.25 * Math.sin(frame * 0.09));
                handlers.add(millis(start));
                if (++frame == 180) {
                    stop();
                    done.complete(null);
                }
            }
        }.start());
        done.get(60, TimeUnit.SECONDS);
        report("scroll_frame_gap_ms", gaps);
        report("scroll_handler_ms", handlers);
        results.add("scroll_frames_over_50ms=" + gaps.stream().filter(ms -> ms > 50).count());
    }

    private void measureContinuousHistoryScroll() throws Exception {
        List<Double> gaps = new ArrayList<>();
        List<Double> handlers = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        long[] first = new long[1];
        int[] pageChanges = {0};
        int[] peakRows = {0};
        List<Double> response = new ArrayList<>();
        long[] inputAt = {0};
        Runnable afterLayout = () -> {
            if (inputAt[0] != 0) { response.add(millis(inputAt[0])); inputAt[0] = 0; }
        };
        fx(() -> {
            stage.getScene().addPostLayoutPulseListener(afterLayout);
            first[0] = form.oldestLoadedDbId;
            new AnimationTimer() {
                private long previous;
                private long oldest = first[0];
                private int frame;
                @Override public void handle(long now) {
                    if (previous != 0) gaps.add((now - previous) / 1_000_000.0);
                    previous = now;
                    long start = System.nanoTime();
                    inputAt[0] = start;
                    peakRows[0] = Math.max(peakRows[0], form.loadedMessageRows.size());
                    assertTrue(form.loadedMessages.size() <= FormChatBase.MAX_LOADED_MESSAGES);
                    assertEquals(form.loadedMessages.size(), form.loadedMessages.stream().map(MeshMessage::getDbId).distinct().count());
                    double delta = frame >= 240 && frame < 300 ? 240 : -320;
                    scrollBy(delta);
                    handlers.add(millis(start));
                    if (oldest != form.oldestLoadedDbId) {
                        oldest = form.oldestLoadedDbId;
                        pageChanges[0]++;
                    }
                    int totalFrames = Integer.parseInt(System.getenv().getOrDefault("MESHAPP_CHAT_BENCHMARK_FRAMES", "480"));
                    if (++frame == totalFrames) { stop(); done.complete(null); }
                }
            }.start();
        });
        done.get(120, TimeUnit.SECONDS);
        settle();
        fx(() -> stage.getScene().removePostLayoutPulseListener(afterLayout));
        report("scroll_input_to_post_layout_ms", response);
        results.add("continuous_history_peak_materialized_rows=" + peakRows[0]);
        report("continuous_history_frame_gap_ms", gaps);
        report("continuous_history_handler_ms", handlers);
        results.add("continuous_history_frames_over_50ms=" + gaps.stream().filter(ms -> ms > 50).count());
        results.add("continuous_history_page_changes=" + pageChanges[0]);
        fx(() -> results.add("continuous_history_db_ids_traversed=" + (first[0] - form.oldestLoadedDbId)));
    }

    private void scrollBy(double pixels) {
        form.messageContainer.fireEvent(new javafx.scene.input.ScrollEvent(
                javafx.scene.input.ScrollEvent.SCROLL, 200, 200, 200, 200,
                false, false, false, false, false, false,
                0, -pixels, 0, -pixels,
                javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
    }

    private void reportStages(Path recording) throws Exception {
        var stages = new java.util.TreeMap<String, List<Double>>();
        try (var file = new jdk.jfr.consumer.RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                var event = file.readEvent();
                if (event.getEventType().getName().equals("com.meshapp.ChatHistoryStage")) {
                    String thread = event.getThread().getJavaName().equals("JavaFX Application Thread") ? "fx" : "worker";
                    stages.computeIfAbsent(event.getString("stage") + "_" + thread, _ -> new ArrayList<>())
                            .add(event.getDuration().toNanos() / 1_000_000.0);
                }
            }
        }
        stages.forEach((name, values) -> report("stage_" + name.replace(' ', '_') + "_ms", values));
    }

    private void report(String name, List<Double> values) {
        var sorted = values.stream().sorted().toList();
        results.add(String.format(Locale.ROOT, "%s n=%d median=%.3f p95=%.3f max=%.3f", name,
                sorted.size(), sorted.get(sorted.size() / 2),
                sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * .95))), sorted.getLast()));
    }

    private void measureIdleFrames() throws Exception {
        List<Double> gaps = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        fx(() -> new AnimationTimer() {
            private long previous;
            @Override public void handle(long now) {
                if (previous != 0) gaps.add((now - previous) / 1_000_000.0);
                previous = now;
                if (gaps.size() == 90) {
                    stop();
                    done.complete(null);
                }
            }
        }.start());
        done.get(30, TimeUnit.SECONDS);
        report("idle_frame_gap_ms", gaps);
    }

    private long retainedHeap() throws Exception {
        System.gc();
        settle();
        System.gc();
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private void snapshot(Path path) {
        var image = stage.getScene().snapshot(null);
        var buffered = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        try { javax.imageio.ImageIO.write(buffered, "png", path.toFile()); }
        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    private void settle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        boolean[] loading = {true};
        while (loading[0] && System.nanoTime() < deadline) {
            fx(() -> loading[0] = form != null && form.isHistoryLoading());
            // Bounded polling from the test thread lets the FX queue and layout pulses progress.
            if (loading[0]) {
                //noinspection BusyWait
                Thread.sleep(10);
            }
        }
        assertFalse(loading[0], "history loading timed out");
        // Drain nested runLater chains, then allow actual CSS/layout/render pulses.
        for (int i = 0; i < 14; i++) fx(() -> {});
        CompletableFuture<Void> done = new CompletableFuture<>();
        fx(() -> new AnimationTimer() {
            private int frames;
            @Override public void handle(long now) {
                if (++frames == 3) {
                    stop();
                    done.complete(null);
                }
            }
        }.start());
        done.get(30, TimeUnit.SECONDS);
        fx(() -> {});
    }

    private static ChatItem channel(int index) {
        return ChatItem.fromChannel(ChannelProtos.Channel.newBuilder().setIndex(index)
                .setRole(ChannelProtos.Channel.Role.PRIMARY)
                .setSettings(ChannelProtos.ChannelSettings.newBuilder().setName("Bench " + index))
                .build(), (MeshMessage) null, 0, false);
    }

    private static double millis(long start) { return (System.nanoTime() - start) / 1_000_000.0; }

    private static void fx(Runnable task) throws Exception {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { task.run(); result.complete(null); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        result.get(30, TimeUnit.SECONDS);
    }
}
