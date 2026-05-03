package com.meshtastic.client.forms;

import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.SystemForm;

/**
 * Точка входа формы чата, зарегистрированная в боковом меню приложения.
 *
 * <p>Класс намеренно содержит только жизненный цикл формы. Отрисовка,
 * пагинация сообщений, обработка запросов и привязка данных вынесены
 * в пакетные слои, от которых он наследуется.
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
        // Загрузить сохранённые счётчики прочитанных сообщений из БД
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
                refreshLoadedMessageRows();
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
    }
}
