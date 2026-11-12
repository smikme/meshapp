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
 * preferences, rate-limits notifications, and trims message bodies. Alerts for
 * the selected chat are suppressed only while the primary window is visible,
 * not minimized, and focused. It may be called from transport reader threads
 * through MessageListenerService; window activity is read from a volatile
 * snapshot maintained on the FX thread.
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
     * Volatile snapshot of whether the primary window is visible, not minimized,
     * and focused, updated by FX-thread listeners. Reader threads can check it
     * without blocking.
     */
    private volatile boolean windowFocused = true;

    /**
     * Callback that checks whether a specific chat is open in FormChat.
     * Parameters are chatType ({@code "channel"} or {@code "dm"}) and chatKey
     * (channel index or peer node id). Set externally through {@link #setActiveChatChecker}.
     */
    private volatile BiPredicate<String, String> activeChatChecker;

    /**
     * Creates a manager using the current platform's notification service and
     * starts tracking primary-window activity through {@link AppUi}.
     *
     * @param deviceState device supplying the notification owner and channel names;
     *                    {@code null} for the manager used only for raw RPC notifications
     */
    public NotificationManager(DeviceState deviceState) {
        this(deviceState, createPlatformService());
    }

    /**
     * Creates a manager with an explicit notification service, allowing tests to
     * capture notifications without invoking the operating system.
     *
     * @param deviceState device supplying the notification owner and channel names;
     *                    {@code null} when only raw RPC notifications are used
     * @param service non-null notification service, also disposed by {@link #dispose()}
     */
    NotificationManager(DeviceState deviceState, NotificationService service) {
        this.deviceState = deviceState;
        this.service = service;
        initFocusTracking();
    }

    /**
     * Called after a text message has been saved to the database.
     * Decides whether an OS notification should be shown.
     * <p>
     * May run on a transport reader thread. Notification preferences, outgoing
     * messages, and the rate limit are checked before delivery through {@link AppUi}.
     * Selecting a chat suppresses its alerts only while the primary window is
     * visible, not minimized, and focused.
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
     * Shows an RPC chat notification when that chat is not muted locally for
     * the originating RPC connection.
     */
    public static void showRemoteNotification(String title,
                                              String message,
                                              String notificationOwnerId,
                                              String chatType,
                                              String chatKey) {
        if (!areChatNotificationsEnabled(notificationOwnerId, chatType, chatKey)) {
            return;
        }
        RemoteNotificationManagerHolder.INSTANCE.showRawNotification(title, message);
    }

    // --- Private ---

    private boolean isChatActive(String chatType, String chatKey) {
        BiPredicate<String, String> checker = activeChatChecker;
        if (checker == null) { return false; }
        return checker.test(chatType, chatKey);
    }

    static boolean areChatNotificationsEnabled(String ownerId, String chatType, String chatKey) {
        return AppPreferences.isNotificationsEnabled()
                && !AppPreferences.isChatMuted(ownerId, chatType, chatKey);
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
                    if (!name.isEmpty()) {
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

    /**
     * Initializes and maintains the volatile window-activity snapshot used by
     * transport reader threads. The desktop bridge publishes visibility and
     * minimization changes as well as focus changes, including hiding to tray.
     */
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
