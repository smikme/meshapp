package com.meshtastic.client.notification;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.system.AppUi;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.UnicodeTextUtils;
import org.meshtastic.proto.ChannelProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;

/**
 * Coordinates operating-system notifications for incoming messages.
 * <p>
 * The manager creates the platform notification service, honors notification
 * preferences, suppresses alerts for the active focused chat, rate-limits
 * notifications, and trims message bodies. It may be called from transport
 * reader threads through MessageListenerService; window focus is read from a
 * volatile snapshot maintained on the FX thread.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class NotificationManager {

    private static final Logger log = LoggerFactory.getLogger(NotificationManager.class);

    /** Minimum interval between notifications, in milliseconds. */
    private static final long RATE_LIMIT_MS = 3_000;

    /** Maximum notification body length. */
    private static final int MAX_BODY_LENGTH = 120;

    private final NotificationService service;
    private final DeviceState deviceState;
    private final AtomicLong lastNotificationTime = new AtomicLong(0);

    /**
     * Volatile snapshot of Stage.isFocused(), updated by an FX-thread listener.
     * Reader threads can check focus without blocking.
     */
    private volatile boolean windowFocused = true;

    /**
     * Callback that checks whether a specific chat is open in FormChat.
     * Parameters are chatType ({@code "channel"} or {@code "dm"}) and chatKey
     * (channel index or peer node id). Set externally through {@link #setActiveChatChecker}.
     */
    private volatile BiPredicate<String, String> activeChatChecker;

    public NotificationManager(DeviceState deviceState) {
        this.deviceState = deviceState;
        this.service = createPlatformService();
        initFocusTracking();
    }

    /**
     * Called after a text message has been saved to the database.
     * Decides whether an OS notification should be shown.
     *
     * @param msg incoming message
     * @param chatType {@code "channel"} or {@code "dm"}
     * @param chatKey channel index as a string, or peer node id
     */
    public void onIncomingMessage(MeshMessage msg, String chatType, String chatKey) {
        if (!AppPreferences.isNotificationsEnabled()) {
            return;
        }

        if (AppPreferences.isChatMuted(currentOwnerNodeId(), chatType, chatKey)) {
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

        AppUi.runLater(() -> {
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

    /**
     * Shows a notification for an event received through MeshApp RPC.
     */
    public static void showRemoteNotification(String title, String message) {
        RemoteNotificationManagerHolder.INSTANCE.showRawNotification(title, message);
    }

    // --- Private ---

    private boolean isChatActive(String chatType, String chatKey) {
        BiPredicate<String, String> checker = activeChatChecker;
        if (checker == null) { return false; }
        return checker.test(chatType, chatKey);
    }

    private void showRawNotification(String title, String message) {
        if (!AppPreferences.isNotificationsEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastNotificationTime.get();
        if (now - last < RATE_LIMIT_MS) {
            log.debug("Remote notification rate-limited ({}ms since last)", now - last);
            return;
        }
        if (!lastNotificationTime.compareAndSet(last, now)) {
            return;
        }

        String safeTitle = title == null || title.isBlank() ? "MeshApp" : title.trim();
        String safeBody = truncate(message);
        AppUi.runLater(() -> {
            try {
                service.showNotification(safeTitle, safeBody);
            } catch (Throwable t) {
                log.error("Failed to show remote notification", t);
            }
        });
    }

    private String currentOwnerNodeId() {
        return deviceState != null && deviceState.getMyNodeNum() != 0
                ? String.format("!%08x", deviceState.getMyNodeNum())
                : "";
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
        return UnicodeTextUtils.truncateWithSuffix(text, MAX_BODY_LENGTH, "...");
    }

    private static NotificationService createPlatformService() {
        if (AppUi.isTerminal()) {
            return new NoOpNotificationService();
        }
        return switch (OsDetect.current()) {
            case MACOS -> new MacOsNotificationService();
            case WINDOWS -> new WindowsNotificationService();
            case LINUX -> new LinuxNotificationService();
            default -> new NoOpNotificationService();
        };
    }

    private void initFocusTracking() {
        windowFocused = AppUi.isPrimaryWindowFocused();
        AppUi.addPrimaryWindowFocusListener(focused -> windowFocused = focused);
    }

    private static final class RemoteNotificationManagerHolder {
        private static final NotificationManager INSTANCE = new NotificationManager(null);
    }

    /** No-op fallback for unsupported platforms. */
    private static class NoOpNotificationService implements NotificationService {
        @Override
        public void showNotification(String title, String message) {}
    }
}
