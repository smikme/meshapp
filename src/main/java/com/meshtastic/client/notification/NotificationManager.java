package com.meshtastic.client.notification;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.utils.AppPreferences;
import org.meshtastic.proto.ChannelProtos;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;

/**
 * Оркестратор OS-уведомлений для входящих сообщений.
 * <p>
 * Обязанности:
 * <ul>
 *   <li>Создаёт платформенный {@link NotificationService}</li>
 *   <li>Проверяет настройку уведомлений в {@link AppPreferences}</li>
 *   <li>Подавляет уведомления при активном чате + окне в фокусе</li>
 *   <li>Ограничивает частоту (rate-limit) между уведомлениями</li>
 *   <li>Обрезает текст для тела уведомления</li>
 * </ul>
 * Потокобезопасность: вызывается из потока чтения TCP/BLE (через MessageListenerService).
 * {@code Stage.isFocused()} обновляется через volatile-снимок из FX-потока.
 */
public class NotificationManager {

    private static final Logger log = LoggerFactory.getLogger(NotificationManager.class);

    /** Минимальный интервал между уведомлениями (мс). */
    private static final long RATE_LIMIT_MS = 3_000;

    /** Максимальная длина текста в уведомлении. */
    private static final int MAX_BODY_LENGTH = 120;

    private final NotificationService service;
    private final DeviceState deviceState;
    private final AtomicLong lastNotificationTime = new AtomicLong(0);

    /**
     * Volatile-снимок Stage.isFocused(), обновляемый слушателем на FX-потоке.
     * Позволяет потоку чтения проверять фокус без блокировки.
     */
    private volatile boolean windowFocused = true;

    /**
     * Коллбэк для проверки, открыт ли конкретный чат в FormChat.
     * Параметры: (chatType: "channel"|"dm", chatKey: channelIndex|peerNodeId).
     * Устанавливается извне через {@link #setActiveChatChecker}.
     */
    private volatile BiPredicate<String, String> activeChatChecker;

    public NotificationManager(DeviceState deviceState) {
        this.deviceState = deviceState;
        this.service = createPlatformService();
        initFocusTracking();
    }

    /**
     * Вызывается из {@code MessageListenerService.handleTextMessage()} после сохранения в БД.
     * Решает, показать ли OS-уведомление.
     *
     * @param msg      входящее сообщение
     * @param chatType "channel" или "dm"
     * @param chatKey  индекс канала (строкой) или nodeId собеседника
     */
    public void onIncomingMessage(MeshMessage msg, String chatType, String chatKey) {
        if (!AppPreferences.isNotificationsEnabled()) {
            return;
        }

        if (msg.isOutgoing()) {
            return;
        }

        if (windowFocused && isChatActive(chatType, chatKey)) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastNotificationTime.get();
        if (now - last < RATE_LIMIT_MS) {
            log.debug("Notification rate-limited ({}ms since last)", now - last);
            return;
        }
        if (!lastNotificationTime.compareAndSet(last, now)) {
            return;
        }

        String title = buildTitle(msg, chatType);
        String body = truncate(msg.getText());
        int bodyLength = body.length();
        int packetId = msg.getPacketId();

        Platform.runLater(() -> {
            try {
                service.showNotification(title, body);
                log.debug("OS notification shown for packet {} (chatType={}, bodyChars={})",
                        packetId, chatType, bodyLength);
            } catch (Throwable t) {
                log.error("Failed to show notification", t);
            }
        });
    }

    public void setActiveChatChecker(BiPredicate<String, String> checker) {
        this.activeChatChecker = checker;
    }

    public void dispose() {
        service.dispose();
    }

    // --- Private ---

    private boolean isChatActive(String chatType, String chatKey) {
        BiPredicate<String, String> checker = activeChatChecker;
        if (checker == null) { return false; }
        return checker.test(chatType, chatKey);
    }

    private String buildTitle(MeshMessage msg, String chatType) {
        String senderName = msg.getSenderName();
        if (senderName == null || senderName.isEmpty()) {
            senderName = msg.getFromNodeId();
        }
        if ("dm".equals(chatType)) {
            return senderName;
        }
        String channelName = resolveChannelName(msg.getChannelIndex());
        return senderName + " (" + channelName + ")";
    }

    private String resolveChannelName(int index) {
        List<ChannelProtos.Channel> channels = deviceState.getChannels();
        if (channels != null) {
            for (ChannelProtos.Channel ch : channels) {
                if (ch.getIndex() == index) {
                    String name = ch.getSettings().getName();
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                    break;
                }
            }
        }
        return index == 0 ? "Primary" : "Ch " + index;
    }

    private static String truncate(String text) {
        if (text == null) { return ""; }
        return text.length() > MAX_BODY_LENGTH
                ? text.substring(0, MAX_BODY_LENGTH) + "..."
                : text;
    }

    private static NotificationService createPlatformService() {
        return switch (OsDetect.current()) {
            case MACOS -> new MacOsNotificationService();
            case WINDOWS -> new WindowsNotificationService();
            case LINUX -> new LinuxNotificationService();
            default -> new NoOpNotificationService();
        };
    }

    private void initFocusTracking() {
        Platform.runLater(() -> {
            Stage stage = MeshApp.getPrimaryStage();
            if (stage != null) {
                windowFocused = stage.isFocused();
                stage.focusedProperty().addListener(
                        (obs, wasFocused, isFocused) -> windowFocused = isFocused);
            }
        });
    }

    /** Заглушка для неподдерживаемых платформ. */
    private static class NoOpNotificationService implements NotificationService {
        @Override
        public void showNotification(String title, String message) {}
    }
}
