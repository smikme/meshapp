package com.meshtastic.client.forms;

import com.meshtastic.client.model.NodeData;

import javafx.scene.layout.HBox;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter between the chat form and the message-search component.
 *
 * <p>This class removes the anonymous nested object from {@link FormChatUi}.
 * Search receives only the operations it needs, while the form keeps the
 * internals of its inheritance hierarchy private from the controller.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class FormChatMessageSearchHost implements FormChatMessageSearchController.Host {

    private final FormChatUi form;

    FormChatMessageSearchHost(FormChatUi form) {
        this.form = form;
    }

    @Override
    public boolean hasSelectedChat() {
        return Optional.ofNullable(form.selectedChat).isPresent();
    }

    @Override
    public String currentChatType() {
        return form.currentChatType();
    }

    @Override
    public String currentChatKey() {
        return form.currentChatKey();
    }

    @Override
    public String currentOwnerNodeId() {
        return form.currentOwnerNodeId();
    }

    @Override
    public List<NodeData> listBotCommandNodes() {
        return form.listBotCommandNodes();
    }

    @Override
    public Map<Long, HBox> loadedMessageRows() {
        return form.loadedMessageRows;
    }

    @Override
    public void ensureMessageLoaded(long dbId, Runnable afterLoad) {
        form.ensureMessageLoaded(dbId, afterLoad);
    }

    @Override
    public void requestMessageViewportLayout() {
        form.requestMessageViewportLayout();
    }

    @Override
    public void scrollToMessage(long dbId, double anchorOffset) {
        form.scrollToMessage(dbId, anchorOffset);
    }

    @Override
    public void focusChatInput() {
        Optional.ofNullable(form.chatInputBar).ifPresent(bar -> bar.focusInput());
    }
}
