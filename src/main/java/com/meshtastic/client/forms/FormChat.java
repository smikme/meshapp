package com.meshtastic.client.forms;

import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.SystemForm;

/**
 * Chat form entry point registered in the application's side menu.
 *
 * <p>This class intentionally keeps only the form lifecycle. Rendering, message
 * pagination, request handling, and data binding live in the package-level
 * layers it extends.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@SystemForm(name = "Чат", description = "Чаты пользователя", tags = {"чаты", "каналы"})
public class FormChat extends FormChatData {

    public FormChat() {
        initComponents();
        applyChatTypography();
    }

    @Override
    public void formInit() {
        ConnectionManager.getInstance().addListener(connectionListener);
        TypographyManager.chatFontSizeProperty().addListener(chatFontSizeListener);
        // Load persisted read counters from the database.
        lastReadCounts.putAll(MessageDbService.getInstance().loadAllReadCounts(currentOwnerNodeId()));
        rebindState();
    }

    @Override
    public void formOpen() {
        formVisible = true;
        scrollOperationGeneration++;
        rebindState();
        if (selectedChat != null) {
            suspendScrollStateSync();
            try {
                requestMessageViewportLayout();
                refreshLoadedMessageRows(true);
                ChatScrollState savedState = getSavedScrollState(selectedChat);
                if (savedState != null) {
                    if (savedState.atBottom()) {
                        scrollToBottom();
                    } else {
                        restoreSavedScrollPosition(savedState);
                    }
                    refreshUnreadTailIndicatorLater();
                    return;
                }
                int unreadCount = getUnreadCount(selectedChat);
                if (focusUnreadMessages(unreadCount)) {
                    refreshUnreadTailIndicatorLater();
                }
            } finally {
                resumeScrollStateSyncLater();
            }
        }
    }

    @Override
    public void formClose() {
        saveCurrentChatScrollState();
        scrollOperationGeneration++;
        formVisible = false;
        if (bubbleFactory != null) {
            bubbleFactory.hideOpenReactionPopup();
        }
    }

    @Override
    public void formRefresh() {
        reloadChatList();
        reopenVisibleSelectedChatIfNeeded();
    }
}
