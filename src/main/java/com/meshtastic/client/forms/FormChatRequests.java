package com.meshtastic.client.forms;

import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.components.chat.NodeInfoFormatter;
import com.meshtastic.client.lua.LuaAutomationCommand;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptEvent;
import com.meshtastic.client.lua.LuaScriptRuntimeService;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.lua.LuaUiNodePickRequest;
import com.meshtastic.client.lua.LuaUiNodeSelection;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.utils.NodeUtils;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

import org.meshtastic.proto.MeshProtos;

/**
 * Обрабатывает пользовательские действия, создающие протокольные запросы из открытого чата.
 *
 * <p>Сюда относятся ответы, реакции, повторная отправка, команды tracebot/infobot
 * и временные пузыри обратного отсчёта, которые показываются во время ожидания
 * ответа от радио.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
abstract class FormChatRequests extends FormChatMessages {

    /**
     * Создать PendingCountdown, зарегистрировать и прикрепить пузырь интерфейса.
     */
    protected PendingCountdown createCountdown(String chatType, String chatKey, String prefix) {
        PendingCountdown pc = new PendingCountdown(chatType, chatKey, prefix, REQUEST_TIMEOUT_SECONDS);
        pendingCountdowns.add(pc);
        attachCountdownBubble(pc);
        return pc;
    }

    /** Прикрепить пузырь интерфейса к PendingCountdown (создать или пересоздать при переключении чата). */
    protected void attachCountdownBubble(PendingCountdown pc) {
        MeshMessage tmp = new MeshMessage("!00000000", "!00000000", 0,
                pc.prefix + " ⏱ " + pc.remaining[0], System.currentTimeMillis() / 1000, false);
        tmp.setSystemMessage(true);
        HBox bubble = bubbleFactory.build(tmp);
        messageContainer.getChildren().add(bubble);
        scrollToBottom();
        // buildSystemBubble возвращает HBox(botAvatar, VBox(textLabel, timeLabel))
        VBox content = (VBox) bubble.getChildren().get(1);
        pc.countdownLabel = (EmojiTextFlow) content.getChildren().getFirst();

        // Кнопка «Отменить»
        Label cancelBtn = new Label("Отменить");
        cancelBtn.getStyleClass().add("chat-countdown-cancel");
        cancelBtn.setCursor(Cursor.HAND);
        cancelBtn.setOnMouseClicked(e -> {
            if (!pc.done[0] && pc.cancelAction != null) {
                pc.cancelAction.run();
            }
        });
        // Вставить перед timeLabel
        content.getChildren().add(content.getChildren().size() - 1, cancelBtn);

        pc.tempBubble = bubble;
    }

    /** Завершить обратный отсчёт: удалить из списка и убрать пузырь из контейнера. */
    protected void finishCountdown(PendingCountdown pc) {
        pc.done[0] = true;
        pendingCountdowns.remove(pc);
        messageContainer.getChildren().remove(pc.tempBubble);
    }

    /**
     * Создать Timeline-таймер обратного отсчёта для PendingCountdown.
     * Обновляет текст countdownLabel каждую секунду.
     */
    protected Timeline createCountdownTimer(PendingCountdown pc, String prefix) {
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), tick -> {
            pc.remaining[0]--;
            if (pc.remaining[0] > 0 && !pc.done[0]) {
                pc.countdownLabel.setText(prefix + " ⏱ " + pc.remaining[0]);
            }
        }));
        timer.setCycleCount(REQUEST_TIMEOUT_SECONDS);
        return timer;
    }

    /** Восстановить пузыри активных запросов при переключении в чат */
    protected void restorePendingCountdowns() {
        if (selectedChat == null) { return; }
        String chatType = currentChatType();
        String chatKey = currentChatKey();
        for (PendingCountdown pc : pendingCountdowns) {
            if (!pc.done[0] && Objects.equals(pc.chatType, chatType) && Objects.equals(pc.chatKey, chatKey)) {
                attachCountdownBubble(pc);
            }
        }
    }

    // ==================== Ответ на сообщение ====================

    /** Включить режим ответа на сообщение */
    protected void startReply(MeshMessage msg) {
        chatInputBar.startReply(msg, nameResolver.resolveSenderName(msg));
    }

    protected void sendReaction(MeshMessage msg, String emoji) {
        if (msg == null || emoji == null || emoji.isEmpty()) { return; }
        if (selectedChat == null || state == null || (protocolHandler == null && meshCoreCompanionRuntime == null)) {
            Toast.show(Toast.Type.WARNING, "Нет подключения к радио");
            return;
        }
        if (meshCoreCompanionRuntime != null) {
            Toast.show(Toast.Type.WARNING, "Реакции пока недоступны для MeshCore Companion Protocol");
            return;
        }
        if (msg.getPacketId() == 0) {
            Toast.show(Toast.Type.WARNING, "Реакция недоступна: у сообщения нет packet id");
            return;
        }

        if (!sendReactionToSelectedChat(msg, emoji)) {
            Toast.show(Toast.Type.ERROR, "Не удалось сохранить реакцию локально");
            return;
        }
        refreshCurrentChatAfterLocalReaction();
    }

    private boolean sendReactionToSelectedChat(MeshMessage msg, String emoji) {
        return switch (selectedChat.getType()) {
            case CHANNEL -> MessageService.sendChannelReaction(
                    protocolHandler, state, selectedChat.getChannelIndex(), msg, emoji);
            case DIRECT_MESSAGE -> MessageService.sendDirectReaction(
                    protocolHandler, state, selectedChat.getPeerNodeId(), msg, emoji);
        };
    }

    protected boolean retryMessage(MeshMessage msg) {
        if (msg == null || !msg.isOutgoing() || msg.getStatus() != MeshMessage.DeliveryStatus.FAILED) {
            return false;
        }
        if (state == null || (protocolHandler == null && meshCoreCompanionRuntime == null)) {
            Toast.show(Toast.Type.WARNING, "Нет подключения к радио");
            return false;
        }

        if (!canRetryTarget(msg)) {
            return false;
        }
        if (meshCoreCompanionRuntime != null) {
            if (!meshCoreCompanionRuntime.retryMessage(msg)) {
                showRetryFailedToast(msg);
                return false;
            }
            reloadChatList();
            return true;
        }
        if (!MessageService.retryMessage(protocolHandler, state, msg)) {
            showRetryFailedToast(msg);
            return false;
        }

        reloadChatList();
        return true;
    }

    private boolean canRetryTarget(MeshMessage msg) {
        if (isChannelMessage(msg)) {
            return true;
        }

        NodeData peerNode = NodeUtils.resolveNode(state, msg.getToNodeId());
        if (peerNode != null && peerNode.isUnmessagable()) {
            Toast.show(Toast.Type.WARNING, "Нода объявила, что не принимает личные сообщения");
            return false;
        }
        return true;
    }

    private void showRetryFailedToast(MeshMessage msg) {
        Toast.show(
                Toast.Type.ERROR,
                isChannelMessage(msg) ? "Не удалось переотправить сообщение" : "Не удалось определить ноду для DM");
    }

    // ==================== Трассировка / информация о ноде ====================

    protected boolean handleBotCommand(ChatBotCommandHelper.ParsedBotCommand command) {
        if (command == null || !command.isCommand()) {
            return false;
        }
        if (selectedChat == null || state == null || (protocolHandler == null && meshCoreCompanionRuntime == null)) {
            Toast.show(Toast.Type.WARNING, "Нет подключения к радио");
            return false;
        }
        if (command.action() == ChatBotCommandHelper.BotAction.AUTOMATION) {
            return runLuaAutomationCommand(command);
        }
        if (command.hasExtraTokens()) {
            Toast.show(Toast.Type.WARNING, "Команда бота принимает только одну ноду");
            return false;
        }
        if (command.targetToken() == null || command.targetToken().isBlank()) {
            Toast.show(Toast.Type.WARNING, switch (command.action()) {
                case TRACEROUTE -> "Используйте: @tracebot имя(!nodeid)";
                case NODE_INFO -> "Используйте: @infobot имя(!nodeid)";
                case AUTOMATION -> "Используйте: " + command.botHandle();
            });
            return false;
        }

        ChatBotCommandHelper.NodeResolution resolution =
                ChatBotCommandHelper.resolveTarget(command.targetToken(), listBotCommandNodes());
        if (resolution.status() == ChatBotCommandHelper.NodeResolutionStatus.AMBIGUOUS) {
            Toast.show(Toast.Type.WARNING, "Найдено несколько нод. Уточните выбор через подсказку");
            return false;
        }
        if (resolution.status() != ChatBotCommandHelper.NodeResolutionStatus.FOUND || resolution.node() == null) {
            Toast.show(Toast.Type.WARNING, "Нода не найдена: " + command.targetToken());
            return false;
        }

        return switch (command.action()) {
            case TRACEROUTE -> {
                if (meshCoreCompanionRuntime != null) {
                    Toast.show(Toast.Type.WARNING, "Traceroute недоступен для MeshCore Companion Protocol");
                    yield true;
                }
                requestTraceroute(resolution.node());
                yield true;
            }
            case NODE_INFO -> {
                if (meshCoreCompanionRuntime != null) {
                    addSystemMessageTo(currentChatType(), currentChatKey(),
                            NodeInfoFormatter.format(resolution.node()));
                    yield true;
                }
                requestNodeInfo(resolution.node());
                yield true;
            }
            case AUTOMATION -> false;
        };
    }

    private boolean runLuaAutomationCommand(ChatBotCommandHelper.ParsedBotCommand command) {
        Optional<LuaScript> script = LuaScriptService.getInstance().findScript(command.scriptId());
        if (script.isEmpty()
                || !script.get().isEnabled()
                || script.get().getBotType() != LuaScript.BotType.AUTOMATION_BOT) {
            Toast.show(Toast.Type.WARNING, "Автоматизация не найдена: " + command.botHandle());
            return false;
        }

        String chatType = currentChatType();
        String chatKey = currentChatKey();
        if (chatType == null || chatKey == null) {
            return false;
        }

        LuaAutomationCommand luaCommand = new LuaAutomationCommand(
                chatType,
                chatKey,
                command.botHandle(),
                command.botHandle() + (command.arguments().isBlank() ? "" : " " + command.arguments()),
                command.arguments(),
                command.argumentTokens());
        LuaScriptRuntimeService.getInstance().runAutomationCommand(
                script.get(),
                luaCommand,
                event -> handleLuaAutomationEvent(chatType, chatKey, event),
                this::handleLuaNodePickRequest);
        return true;
    }

    private void handleLuaAutomationEvent(String chatType, String chatKey, LuaScriptEvent event) {
        if (event == null) {
            return;
        }
        if (event.type() == LuaScriptEvent.Type.ERROR || event.type() == LuaScriptEvent.Type.WARNING) {
            Platform.runLater(() -> addSystemMessageTo(chatType, chatKey, "Lua: " + event.message()));
        }
    }

    private void handleLuaNodePickRequest(LuaUiNodePickRequest request) {
        if (request == null) {
            return;
        }
        Platform.runLater(() -> showLuaNodePicker(request));
    }

    private void showLuaNodePicker(LuaUiNodePickRequest request) {
        List<NodeData> candidates = listBotCommandNodes();
        Dialog<NodeData> dialog = new Dialog<>();
        dialog.setTitle(request.prompt() != null && !request.prompt().isBlank()
                ? request.prompt()
                : "Выбор ноды");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

        TextField searchField = new TextField(request.query() != null ? request.query() : "");
        searchField.setPromptText("Имя или !nodeid");

        ListView<NodePickRow> listView = new ListView<>();
        listView.setPrefHeight(260);
        listView.setCellFactory(ignored -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(NodePickRow row, boolean empty) {
                super.updateItem(row, empty);
                setText(empty || row == null ? null : row.label());
            }
        });

        Runnable refresh = () -> {
            List<NodePickRow> rows = ChatBotCommandHelper.suggestNodes(candidates, searchField.getText(), 8)
                    .stream()
                    .map(suggestion -> nodePickRow(suggestion, candidates))
                    .flatMap(Optional::stream)
                    .toList();
            listView.getItems().setAll(rows);
            if (!rows.isEmpty()) {
                listView.getSelectionModel().select(0);
            }
        };
        searchField.textProperty().addListener((obs, oldValue, newValue) -> refresh.run());
        refresh.run();

        Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(listView.getSelectionModel().getSelectedItem() == null);
        listView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) ->
                okButton.setDisable(newValue == null));
        listView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && listView.getSelectionModel().getSelectedItem() != null) {
                dialog.setResult(listView.getSelectionModel().getSelectedItem().node());
                dialog.close();
            }
        });
        listView.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && listView.getSelectionModel().getSelectedItem() != null) {
                dialog.setResult(listView.getSelectionModel().getSelectedItem().node());
                dialog.close();
            }
        });

        dialog.getDialogPane().setContent(new VBox(8, searchField, listView));
        dialog.setResultConverter(button -> button == ButtonType.OK && listView.getSelectionModel().getSelectedItem() != null
                ? listView.getSelectionModel().getSelectedItem().node()
                : null);
        dialog.setOnShown(event -> searchField.requestFocus());
        dialog.setOnHidden(event -> LuaScriptRuntimeService.getInstance().deliverNodeSelection(
                request.scriptId(),
                dialog.getResult() != null
                        ? LuaUiNodeSelection.selected(request, dialog.getResult())
                        : LuaUiNodeSelection.cancelled(request)));
        dialog.show();
    }

    private Optional<NodePickRow> nodePickRow(ChatBotCommandHelper.NodeSuggestion suggestion, List<NodeData> candidates) {
        ChatBotCommandHelper.NodeResolution resolution =
                ChatBotCommandHelper.resolveTarget(suggestion.insertText(), candidates);
        if (resolution.status() != ChatBotCommandHelper.NodeResolutionStatus.FOUND || resolution.node() == null) {
            return Optional.empty();
        }
        String label = suggestion.primaryText()
                + (suggestion.secondaryText() != null && !suggestion.secondaryText().isBlank()
                ? " - " + suggestion.secondaryText()
                : "");
        return Optional.of(new NodePickRow(resolution.node(), label));
    }

    private record NodePickRow(NodeData node, String label) {}

    protected List<NodeData> listBotCommandNodes() {
        LinkedHashMap<String, NodeData> nodes = new LinkedHashMap<>();
        if (state != null) {
            for (NodeData node : state.getNodeDb().values()) {
                NodeCacheService.getInstance().enrichFromCache(node);
                registerBotNode(nodes, node);
            }
        }
        for (NodeData node : NodeCacheService.getInstance().getAll()) {
            registerBotNode(nodes, node);
        }
        return new ArrayList<>(nodes.values());
    }

    protected static void registerBotNode(Map<String, NodeData> nodes, NodeData node) {
        if (node == null) {
            return;
        }
        String nodeId = node.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = String.format("!%08x", node.getNodeNum());
        }
        nodes.putIfAbsent(nodeId.toLowerCase(Locale.ROOT), node);
    }

    protected static boolean isChannelMessage(MeshMessage msg) {
        return msg != null && "!ffffffff".equalsIgnoreCase(msg.getToNodeId());
    }

    protected NodeData resolveTargetNodeFromMessage(MeshMessage msg) {
        if (msg == null || state == null) {
            return null;
        }
        NodeData targetNode = state.getNodeByNodeId(msg.getFromNodeId());
        if (targetNode != null) {
            return targetNode;
        }

        String nodeId = msg.getFromNodeId();
        if (nodeId == null || nodeId.length() < 2 || !nodeId.startsWith("!")) {
            return null;
        }

        int nodeNum = (int) Long.parseUnsignedLong(nodeId.substring(1), 16);
        targetNode = state.getOrCreateNode(nodeNum);
        NodeCacheService.getInstance().enrichFromCache(targetNode);
        return targetNode;
    }

    /** Запрос трассировки до ноды — ответ показывается как системное сообщение. */
    protected void requestTraceroute(MeshMessage msg) {
        requestTraceroute(resolveTargetNodeFromMessage(msg));
    }

    /** Запрос трассировки до указанной ноды — ответ показывается как системное сообщение. */
    protected void requestTraceroute(NodeData targetNode) {
        DeviceState requestState = state;
        ProtocolHandler requestHandler = protocolHandler;
        if (requestState == null || requestHandler == null) {
            if (meshCoreCompanionRuntime != null) {
                Toast.show(Toast.Type.WARNING, "Traceroute недоступен для MeshCore Companion Protocol");
            }
            return;
        }
        if (targetNode == null) {
            return;
        }
        int targetNum = targetNode.getNodeNum();
        String name = nameResolver.resolveNodeName(targetNum);
        String prefix = "🔍 Traceroute → " + name;

        String chatType = currentChatType();
        String chatKey = currentChatKey();
        if (chatType == null) { return; }

        PendingCountdown pc = createCountdown(chatType, chatKey, prefix);

        @SuppressWarnings("unchecked")
        BiConsumer<Integer, MeshProtos.RouteDiscovery>[] holder = new BiConsumer[1];
        Timeline timer = createCountdownTimer(pc, prefix);

        holder[0] = (fromNodeNum, route) -> {
            // Фильтр: реагируем только на ответ от целевой ноды
            if (fromNodeNum != targetNum) { return; }
            requestState.removeTracerouteListener(holder[0]);
            Platform.runLater(() -> {
                timer.stop();
                finishCountdown(pc);
                addTracerouteResult(chatType, chatKey, name, route);
            });
        };

        timer.setOnFinished(e -> {
            if (!pc.done[0]) {
                requestState.removeTracerouteListener(holder[0]);
                finishCountdown(pc);
                addSystemMessageTo(chatType, chatKey, "❌ Traceroute → " + name + ": ответ не получен");
            }
        });

        pc.cancelAction = () -> {
            requestState.removeTracerouteListener(holder[0]);
            timer.stop();
            finishCountdown(pc);
        };

        requestState.addTracerouteListener(holder[0]);
        timer.play();
        MessageService.requestTraceroute(requestHandler, requestState, targetNum);
    }

    /** Запрос информации о ноде — всегда запрашивает актуальные данные по сети */
    protected void requestNodeInfo(MeshMessage msg) {
        requestNodeInfo(resolveTargetNodeFromMessage(msg));
    }

    /** Запрос информации о ноде — всегда запрашивает актуальные данные по сети */
    protected void requestNodeInfo(NodeData targetNode) {
        DeviceState requestState = state;
        ProtocolHandler requestHandler = protocolHandler;
        if (requestState == null || requestHandler == null) {
            if (meshCoreCompanionRuntime != null && targetNode != null) {
                addSystemMessageTo(currentChatType(), currentChatKey(), NodeInfoFormatter.format(targetNode));
            }
            return;
        }
        if (targetNode == null) {
            return;
        }
        int targetNum = targetNode.getNodeNum();
        String name = nameResolver.resolveNodeName(targetNum);

        String chatType = currentChatType();
        String chatKey = currentChatKey();
        if (chatType == null) { return; }
        String prefix = "📋 Запрос информации о " + name;

        PendingCountdown pc = createCountdown(chatType, chatKey, prefix);

        IntConsumer[] holder = new IntConsumer[1];
        Timeline timer = createCountdownTimer(pc, prefix);

        holder[0] = nodeNum -> {
            if (nodeNum != targetNum) { return; }
            requestState.removeNodeUpdateListener(holder[0]);
            Platform.runLater(() -> {
                timer.stop();
                finishCountdown(pc);

                NodeData n = requestState.getNodeDb().get(targetNum);
                if (n == null) {
                    addSystemMessageTo(chatType, chatKey, "📋 Нода " + name + " не найдена");
                    return;
                }
                addSystemMessageTo(chatType, chatKey, NodeInfoFormatter.format(n));
            });
        };

        timer.setOnFinished(e -> {
            if (!pc.done[0]) {
                requestState.removeNodeUpdateListener(holder[0]);
                finishCountdown(pc);
                addSystemMessageTo(chatType, chatKey, "❌ Информация о " + name + ": ответ не получен");
            }
        });

        pc.cancelAction = () -> {
            requestState.removeNodeUpdateListener(holder[0]);
            timer.stop();
            finishCountdown(pc);
        };

        requestState.addNodeUpdateListener(holder[0]);
        timer.play();
        MessageService.requestNodeInfo(requestHandler, requestState, targetNum);
    }
}
