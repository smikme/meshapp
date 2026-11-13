package com.meshtastic.client.forms;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.components.chat.EmojiTextField;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.TransportConnection;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptRuntimeService;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.model.ChatItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageDbService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the chat UI, stored Lua bots, node picker, and simulated radio replies together. */
class FormChatAutomationTest {
    @TempDir Path tempHome;

    private FormChat form;
    private Stage stage;
    private EmojiTextField input;
    private DeviceState state;
    private ProtocolHandler handler;
    private RecordingTransport transport;
    private LuaScriptService scripts;
    private LuaScriptRuntimeService runtime;

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        scripts = LuaScriptService.getInstance();
        runtime = LuaScriptRuntimeService.getInstance();
        onFx(() -> {
            ConnectionManager manager = ConnectionManager.getInstance();
            ConnectionEntry entry = new ConnectionEntry("Bot test", "127.0.0.1", 4403);
            manager.addEntry(entry);
            state = new DeviceState();
            state.setMyNodeNum(0x12345678);
            state.setOwnerInfo(MeshProtos.User.newBuilder()
                    .setId("!12345678").setLongName("Bot host").setShortName("BOT").build());
            NodeData node = new NodeData(0xBEEF);
            node.setLongName("Альфа Нода");
            node.setShortName("ALP");
            state.getNodeDb().put(node.getNodeNum(), node);
            transport = new RecordingTransport();
            handler = new ProtocolHandler(transport);
            FormChatAutomationTest.<Map<String, DeviceState>>field(manager, "deviceStates").put(entry.getId(), state);
            FormChatAutomationTest.<Map<String, ProtocolHandler>>field(manager, "protocolHandlers").put(entry.getId(), handler);
            entry.setConnected(true);
            manager.setSelectedConnectionId(entry.getId());

            form = new FormChat();
            form.state = state;
            form.protocolHandler = handler;
            form.boundConnectionId = entry.getId();
            form.selectedChat = ChatItem.fromChannel(ChannelProtos.Channel.newBuilder().setIndex(0).build(),
                    (MeshMessage) null, 0, false);
            input = field(form.chatInputBar, "messageInput");
            Region messages = new Region();
            messages.setPrefHeight(300);
            stage = new Stage();
            stage.setScene(new Scene(new VBox(form.chatHeader, messages, form.chatInputBar), 700, 450));
            stage.show();
            stage.requestFocus();
            input.requestFocus();
            return null;
        });
        await(() -> onFx(input::isFocused), "Chat input did not gain focus");
    }

    @AfterEach
    void tearDown() {
        onFx(() -> {
            if (form != null) {
                form.chatInputBar.clear();
                form.closeLuaScriptDataSource();
            }
            if (stage != null) stage.close();
            runtime.stopAll();
            if (handler != null) handler.shutdown();
            if (state != null) state.shutdown();
            return null;
        });
        onFx(() -> { TestEnvironmentSupport.resetSingletons(); return null; });
    }

    @ParameterizedTest
    @CsvSource({"InfoBot,menu", "TraceBot,menu", "InfoBot,suggestion", "TraceBot,suggestion",
            "InfoBot,command", "TraceBot,command"})
    void storedBotsCompleteThroughEveryChatEntryPoint(String botName, String entryPoint) throws Exception {
        LuaScript bot = loadBot(botName);
        for (int attempt = 0; attempt < 2; attempt++) {
            launch(bot, entryPoint);
            onFx(() -> {
                assertNotNull(field(form.chatInputBar, "activeNodePick"));
                assertTrue(input.isFocused());
                press(KeyCode.ENTER);
                assertNull(field(form.chatInputBar, "activeNodePick"));
                assertEquals("", input.getText(), "Lua selection must not insert a node name into the draft");
                return null;
            });

            MeshProtos.MeshPacket packet = transport.awaitPacket();
            assertEquals(0xBEEF, packet.getTo());
            assertEquals("InfoBot".equals(botName) ? Portnums.PortNum.NODEINFO_APP : Portnums.PortNum.TRACEROUTE_APP,
                    packet.getDecoded().getPortnum());
            if ("InfoBot".equals(botName)) {
                MeshProtos.User requester = MeshProtos.User.parseFrom(packet.getDecoded().getPayload());
                assertEquals("Bot host", requester.getLongName());
                assertTrue(packet.getDecoded().getWantResponse());
                state.getNodeDb().get(0xBEEF).setLongName("Альфа Нода Updated");
                state.fireNodeUpdateListeners(0xBEEF);
            } else {
                state.fireTracerouteListeners(0xBEEF, MeshProtos.RouteDiscovery.newBuilder()
                        .addRoute(0xCAFE).addSnrTowards(12).build());
            }
            await(() -> !runtime.isRunning(bot.getId()), "Bot did not finish after its radio reply");
            LuaScript finished = scripts.findScript(bot.getId()).orElseThrow();
            assertEquals("FINISHED", finished.getLastStatus());
            assertNull(finished.getLastError());
            List<MeshMessage> messages = MessageDbService.getInstance().loadLast("channel", "0", 20, "!12345678");
            assertTrue(messages.stream().allMatch(MeshMessage::isSystemMessage));
            assertTrue(messages.stream().anyMatch(message -> message.getText().contains(
                    "InfoBot".equals(botName) ? "Альфа Нода Updated" : "!0000cafe")),
                    "Bot result must be stored in the invoking chat");
            assertTrue(transport.writes.isEmpty(), "Bot commands and replies must not be sent as text packets");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"InfoBot", "TraceBot"})
    void cancellingNodePickerReturnsToBotWithoutRadioTraffic(String botName) throws Exception {
        LuaScript bot = loadBot(botName);
        launch(bot, "menu");
        onFx(() -> { press(KeyCode.ESCAPE); return null; });
        await(() -> !runtime.isRunning(bot.getId()), "Cancelled bot did not finish");
        assertEquals("FINISHED", scripts.findScript(bot.getId()).orElseThrow().getLastStatus());
        assertTrue(transport.writes.isEmpty());
        assertTrue(MessageDbService.getInstance().loadLast("channel", "0", 20, "!12345678").stream()
                .anyMatch(message -> message.getText().contains("отменен") || message.getText().contains("не выбрана")));
    }

    @Test
    void relaunchingBotReplacesItsPendingNodeRequest() throws Exception {
        LuaScript bot = loadBot("InfoBot");
        launch(bot, "menu");
        launch(bot, "menu");
        onFx(() -> { press(KeyCode.ENTER); return null; });
        assertEquals(0xBEEF, transport.awaitPacket().getTo());
        state.fireNodeUpdateListeners(0xBEEF);
        await(() -> !runtime.isRunning(bot.getId()), "Relaunched bot did not finish");
        assertEquals("FINISHED", scripts.findScript(bot.getId()).orElseThrow().getLastStatus());
    }

    private LuaScript loadBot(String botName) throws Exception {
        LuaScript imported = scripts.importScriptJson(Files.readString(
                Path.of("meshappstore", "ru", botName + ".meshapp-script.json"))).script();
        return scripts.saveScript(imported.getId(), imported.getName(), imported.getCode(), true);
    }

    private void launch(LuaScript bot, String entryPoint) {
        Object previousPick = onFx(() -> field(form.chatInputBar, "activeNodePick"));
        onFx(() -> {
            if ("menu".equals(entryPoint)) {
                var buildMenu = FormChatUi.class.getDeclaredMethod("buildQuickScriptMenu");
                buildMenu.setAccessible(true);
                ContextMenu menu = (ContextMenu) buildMenu.invoke(form);
                menu.show(form.chatHeader, javafx.geometry.Side.BOTTOM, 0, 0);
                try {
                    menu.getItems().stream().filter(item -> item.getText().contains(bot.getName()))
                            .findFirst().orElseThrow().fire();
                } finally {
                    menu.hide();
                }
            } else {
                String command = "/" + bot.getAutomationName().substring(1);
                input.setText("command".equals(entryPoint) ? command + " Альфа" : command);
                input.positionCaret(input.getText().length());
                input.requestFocus();
            }
            return null;
        });
        if ("suggestion".equals(entryPoint)) {
            await(() -> onFx(() -> popup().isVisible()), "Bot suggestions did not open");
            onFx(() -> { ((Button) popup().getChildren().getFirst()).fire(); return null; });
        } else if ("command".equals(entryPoint)) {
            onFx(() -> { press(KeyCode.ENTER); return null; });
        }
        await(() -> onFx(() -> field(form.chatInputBar, "activeNodePick") != null
                        && field(form.chatInputBar, "activeNodePick") != previousPick && popup().isVisible()),
                "Lua node picker did not open for " + bot.getName() + " via " + entryPoint);
    }

    private VBox popup() {
        return field(form.chatInputBar, "commandSuggestionRoot");
    }

    private void press(KeyCode code) {
        Event.fireEvent(input, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static <T> T onFx(Callable<T> action) {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("JavaFX action failed", e);
        }
    }

    // JavaFX and Lua have independent queues; bounded polling waits for both without spinning.
    @SuppressWarnings("BusyWait")
    private static void await(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(20); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        fail(message);
    }

    /** Captures framed packets without opening a network or device connection. */
    private static final class RecordingTransport implements TransportConnection {
        private final LinkedBlockingQueue<byte[]> writes = new LinkedBlockingQueue<>();
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean isConnected() { return true; }
        @Override public void sendBytes(byte[] data) { writes.add(data); }
        @Override public void setDataListener(Consumer<byte[]> listener) {}
        @Override public void setConnectionListener(ConnectionListener listener) {}

        MeshProtos.MeshPacket awaitPacket() throws Exception {
            byte[] frame = writes.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame, "Bot did not issue its radio request");
            int length = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
            return MeshProtos.ToRadio.parseFrom(Arrays.copyOfRange(frame, 4, length + 4)).getPacket();
        }
    }
}
