package com.meshtastic.client.forms;

import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.lua.LuaAutomationCommand;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.lua.LuaScriptEvent;
import com.meshtastic.client.lua.LuaScriptRuntimeService;
import com.meshtastic.client.lua.LuaScriptService;
import com.meshtastic.client.lua.LuaUiBotNotice;
import com.meshtastic.client.lua.LuaUiNodePickRequest;
import com.meshtastic.client.lua.LuaUiNodeSelection;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
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
import java.util.Optional;

/**
 * Handles chat actions that create protocol requests from the open conversation.
 *
 * <p>This includes replies, reactions, retries, Lua automation commands, and
 * temporary countdown bubbles.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
abstract class FormChatRequests extends FormChatMessages {

    /**
     * Creates a {@link PendingCountdown}, registers it, and attaches its UI bubble.
     */
    protected PendingCountdown createCountdown(String chatType, String chatKey, String prefix) {
        PendingCountdown pc = new PendingCountdown(chatType, chatKey, prefix, REQUEST_TIMEOUT_SECONDS);
        pendingCountdowns.add(pc);
        attachCountdownBubble(pc);
        return pc;
    }

    /** Attaches or recreates the UI bubble for a pending countdown. */
    protected void attachCountdownBubble(PendingCountdown pc) {
        MeshMessage tmp = new MeshMessage("!00000000", "!00000000", 0,
                pc.prefix + " ⏱ " + pc.remaining[0], System.currentTimeMillis() / 1000, false);
        tmp.setSystemMessage(true);
        HBox bubble = bubbleFactory.build(tmp);
        messageContainer.getChildren().add(bubble);
        scrollToBottom();
        // buildSystemBubble returns HBox(botAvatar, VBox(textLabel, timeLabel)).
        VBox content = (VBox) bubble.getChildren().get(1);
        pc.countdownLabel = (EmojiTextFlow) content.getChildren().getFirst();

        // Cancel button.
        Label cancelBtn = new Label(I18n.t("chat.cancel"));
        cancelBtn.getStyleClass().add("chat-countdown-cancel");
        cancelBtn.setCursor(Cursor.HAND);
        cancelBtn.setOnMouseClicked(e -> {
            if (!pc.done[0] && pc.cancelAction != null) {
                pc.cancelAction.run();
            }
        });
        // Insert before the time label.
        content.getChildren().add(content.getChildren().size() - 1, cancelBtn);

        pc.tempBubble = bubble;
    }

    /** Finishes a countdown by removing it from both the list and the message container. */
    protected void finishCountdown(PendingCountdown pc) {
        pc.done[0] = true;
        pendingCountdowns.remove(pc);
        messageContainer.getChildren().remove(pc.tempBubble);
    }

    /**
     * Creates the countdown timer for a pending request.
     * The visible countdown label is updated once per second.
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

    /** Restores active request bubbles when switching back to a chat. */
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

    // Replying to messages.

    /** Starts reply mode for a message. */
    protected void startReply(MeshMessage msg) {
        chatInputBar.startReply(msg, nameResolver.resolveSenderName(msg));
    }

    protected void sendReaction(MeshMessage msg, String emoji) {
        if (msg == null || emoji == null || emoji.isEmpty()) { return; }
        if (remoteRpcState != null) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.remoteManagementUnavailable"));
            return;
        }
        if (selectedChat == null || state == null || (protocolHandler == null && meshCoreCompanionRuntime == null)) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.radioNotConnected"));
            return;
        }
        if (meshCoreCompanionRuntime != null) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.reactionsMeshcoreUnavailable"));
            return;
        }
        if (msg.getPacketId() == 0) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.reactionUnavailableNoPacket"));
            return;
        }

        if (!sendReactionToSelectedChat(msg, emoji)) {
            Toast.show(Toast.Type.ERROR, I18n.t("chat.toast.reactionSaveFailed"));
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
        if (remoteRpcState != null) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.remoteManagementUnavailable"));
            return false;
        }
        if (state == null || (protocolHandler == null && meshCoreCompanionRuntime == null)) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.radioNotConnected"));
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
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.unmessagable"));
            return false;
        }
        return true;
    }

    private void showRetryFailedToast(MeshMessage msg) {
        Toast.show(
                Toast.Type.ERROR,
                isChannelMessage(msg)
                        ? I18n.t("chat.toast.retryMessageFailed")
                        : I18n.t("chat.toast.dmNodeMissing"));
    }

    // ==================== Lua automation ====================

    protected boolean handleBotCommand(ChatBotCommandHelper.ParsedBotCommand command) {
        if (command == null || !command.isCommand()) {
            return false;
        }
        if (remoteRpcState != null) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.remoteManagementUnavailable"));
            return false;
        }
        if (selectedChat == null || state == null || (protocolHandler == null && meshCoreCompanionRuntime == null)) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.radioNotConnected"));
            return false;
        }
        if (command.action() == ChatBotCommandHelper.BotAction.AUTOMATION) {
            return runLuaAutomationCommand(command);
        }
        return false;
    }

    private boolean runLuaAutomationCommand(ChatBotCommandHelper.ParsedBotCommand command) {
        Optional<LuaScript> script = LuaScriptService.getInstance().findScript(command.scriptId());
        if (script.isEmpty()
                || !script.get().isEnabled()
                || script.get().getBotType() != LuaScript.BotType.AUTOMATION_BOT) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.toast.automationNotFound", command.botHandle()));
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
                command.argumentTokens(),
                script.get().getId() + ":command:" + System.nanoTime());
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
            return;
        }
        if (event.type() == LuaScriptEvent.Type.UI_BOT_NOTICE && event.payload() instanceof LuaUiBotNotice notice) {
            Platform.runLater(() -> showTransientSystemMessageTo(notice.chatType(), notice.chatKey(), notice.text()));
        }
    }

    private void handleLuaNodePickRequest(LuaUiNodePickRequest request) {
        if (request == null) {
            return;
        }
        Platform.runLater(() -> startLuaNodePickup(request));
    }

    private void startLuaNodePickup(LuaUiNodePickRequest request) {
        if (chatInputBar == null) {
            deliverLuaNodeSelection(request, null);
            return;
        }
        chatInputBar.startNodePick(
                request.query(),
                request.prompt(),
                suggestion -> deliverLuaNodeSelection(request, resolveNodeSuggestion(suggestion).orElse(null)),
                () -> deliverLuaNodeSelection(request, null));
    }

    private Optional<NodeData> resolveNodeSuggestion(ChatBotCommandHelper.NodeSuggestion suggestion) {
        ChatBotCommandHelper.NodeResolution resolution =
                ChatBotCommandHelper.resolveTarget(suggestion.insertText(), listBotCommandNodes());
        if (resolution.status() != ChatBotCommandHelper.NodeResolutionStatus.FOUND || resolution.node() == null) {
            return Optional.empty();
        }
        return Optional.of(resolution.node());
    }

    private void deliverLuaNodeSelection(LuaUiNodePickRequest request, NodeData node) {
        LuaScriptRuntimeService.getInstance().deliverNodeSelection(
                request.scriptId(),
                node != null
                        ? LuaUiNodeSelection.selected(request, node)
                        : LuaUiNodeSelection.cancelled(request));
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

}
