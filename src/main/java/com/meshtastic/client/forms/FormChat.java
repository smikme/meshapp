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
        rebindState();
        if (selectedChat != null) {
            suspendScrollStateSync();
            try {
                ChatScrollState savedState = getSavedScrollState(selectedChat);
                if (savedState != null && !savedState.atBottom()) {
                    restoreSavedScrollPosition(savedState);
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
