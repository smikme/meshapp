package com.meshtastic.client.forms;

import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.components.chat.NodeInfoFormatter;
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
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
        if (command.hasExtraTokens()) {
            Toast.show(Toast.Type.WARNING, "Команда бота принимает только одну ноду");
            return false;
        }
        if (command.targetToken() == null || command.targetToken().isBlank()) {
            Toast.show(Toast.Type.WARNING, switch (command.action()) {
                case TRACEROUTE -> "Используйте: @tracebot имя(!nodeid)";
                case NODE_INFO -> "Используйте: @infobot имя(!nodeid)";
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
        };
    }

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
