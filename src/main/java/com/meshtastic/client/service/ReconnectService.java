package com.meshtastic.client.service;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.menu.MyDrawerBuilder;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Сервис автоматического переподключения соединений (singleton).
 * <p>
 * При разрыве соединения (TCP или Serial) запускает цикл повторных попыток
 * с экспоненциальным backoff (2с → 4с → 8с → 16с → 30с max).
 * Показывает статусные Toast-уведомления и обновляет состояние
 * {@link ConnectionEntry#isReconnecting()}.
 */
public final class ReconnectService {

    private static final Logger log = LoggerFactory.getLogger(ReconnectService.class);

    private static final long INITIAL_DELAY_SECONDS = 2;
    private static final long MAX_DELAY_SECONDS = 30;

    private static ReconnectService instance;

    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> pendingReconnects = new ConcurrentHashMap<>();
    private final Map<String, Integer> attemptCounts = new ConcurrentHashMap<>();

    private ReconnectService() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "reconnect");
            t.setDaemon(true);
            return t;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(tf);
    }

    public static synchronized ReconnectService getInstance() {
        if (instance == null) {
            instance = new ReconnectService();
        }
        return instance;
    }

    /**
     * Запускает цикл переподключения для указанного соединения.
     * Идемпотентен — повторный вызов для того же id игнорируется.
     */
    public void startReconnect(String id) {
        if (pendingReconnects.containsKey(id)) {
            return;
        }

        ConnectionEntry entry = ConnectionManager.getInstance().findEntry(id);
        if (entry == null) {
            return;
        }

        log.info("Starting auto-reconnect for '{}'", entry.getName());
        entry.setReconnecting(true);
        attemptCounts.put(id, 0);
        ConnectionManager.getInstance().fireChanged();
        scheduleNextAttempt(id);
    }

    /**
     * Отменяет цикл переподключения для указанного соединения.
     */
    public void cancelReconnect(String id) {
        ScheduledFuture<?> future = pendingReconnects.remove(id);
        if (future != null) {
            future.cancel(false);
        }
        attemptCounts.remove(id);

        ConnectionEntry entry = ConnectionManager.getInstance().findEntry(id);
        if (entry != null && entry.isReconnecting()) {
            entry.setReconnecting(false);
            ConnectionManager.getInstance().fireChanged();
        }
    }

    private void scheduleNextAttempt(String id) {
        int attempt = attemptCounts.getOrDefault(id, 0);
        long delaySec = Math.min(INITIAL_DELAY_SECONDS * (1L << attempt), MAX_DELAY_SECONDS);

        ConnectionEntry entry = ConnectionManager.getInstance().findEntry(id);
        String name = entry != null ? entry.getName() : id;

        Platform.runLater(() ->
                Toast.show(Toast.Type.WARNING,
                        "Соединение потеряно: " + name + ". Переподключение через " + delaySec + "с..."));

        ScheduledFuture<?> future = scheduler.schedule(
                () -> attemptReconnect(id), delaySec, TimeUnit.SECONDS);
        pendingReconnects.put(id, future);
    }

    private void attemptReconnect(String id) {
        if (!pendingReconnects.containsKey(id)) {
            return;
        }

        ConnectionEntry entry = ConnectionManager.getInstance().findEntry(id);
        if (entry == null) {
            cancelReconnect(id);
            return;
        }

        // Не реконнектить если пользователь подключил другое устройство
        ConnectionManager mgr = ConnectionManager.getInstance();
        if (mgr.hasActiveConnection()) {
            log.info("Another connection is active, cancelling reconnect for '{}'", entry.getName());
            cancelReconnect(id);
            return;
        }

        int attempt = attemptCounts.getOrDefault(id, 0) + 1;
        log.info("Reconnect attempt #{} for '{}'", attempt, entry.getName());

        try {
            mgr.connect(id);

            // Успех — очищаем состояние reconnect
            pendingReconnects.remove(id);
            attemptCounts.remove(id);
            entry.setReconnecting(false);

            Platform.runLater(() ->
                    Toast.show(Toast.Type.SUCCESS, "Переподключено: " + entry.getName()));

            handlePostReconnectConfigExchange(id, entry);

        } catch (ConnectionException e) {
            log.warn("Reconnect failed for '{}': {}", entry.getName(), e.getMessage());
            attemptCounts.put(id, attempt);
            scheduleNextAttempt(id);
        }
    }

    @SuppressWarnings("PMD.UnusedFormalParameter") // entry kept for future use / consistent API
    private void handlePostReconnectConfigExchange(String id, ConnectionEntry entry) {
        CompletableFuture<DeviceState> future = ConnectionManager.getInstance().getConfigFuture(id);
        if (future == null) {
            return;
        }
        future.whenComplete((state, ex) -> {
            if (state != null) {
                NodeData myNode = state.getNodeDb().get(state.getMyNodeNum());
                if (myNode != null) {
                    String shortName = myNode.getShortName() != null ? myNode.getShortName() : "?";
                    String longName = myNode.getLongName() != null ? myNode.getLongName() : "?";
                    String nodeId = myNode.getNodeId() != null ? myNode.getNodeId() : "?";
                    Platform.runLater(() ->
                            MyDrawerBuilder.updateHeader(shortName, longName, nodeId));
                }
            }
        });
    }
}
