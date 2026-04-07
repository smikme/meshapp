package com.meshtastic.client.service;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.PacketLogEntry;
import com.meshtastic.client.model.PacketLogEntry.Direction;
import com.meshtastic.client.utils.PacketDebugFormatter;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сервис захвата и хранения LoRa mesh-пакетов для окна мониторинга.
 * Инкапсулирует приём пакетов из протокольного слоя, их сохранение в БД и
 * рассылку live-событий UI-слушателям.
 *
 * Публичные методы допускают вызов из произвольных потоков. Операции, которые
 * работают с БД и общим состоянием сервиса, синхронизированы на экземпляре.
 */
public final class PacketMonitorService {

    private static final Logger log = LoggerFactory.getLogger(PacketMonitorService.class);

    /**
     * Слушатель событий мониторинга.
     * Контракт: callbacks должны быть быстрыми и не блокировать поток вызова.
     * Переключение на JavaFX thread является обязанностью UI-слоя.
     */
    public interface Listener {
        default void onPacketLogged(PacketLogEntry entry) {}
        default void onCaptureStateChanged(boolean captureEnabled) {}
        default void onCleared() {}
    }

    private static PacketMonitorService instance;

    private final AtomicBoolean captureEnabled = new AtomicBoolean(false);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Connection dbConnection;
    private PreparedStatement insertStmt;

    private PacketMonitorService() {
        initDb();
    }

    /**
     * @return singleton-экземпляр сервиса мониторинга
     */
    public static synchronized PacketMonitorService getInstance() {
        if (instance == null) {
            instance = new PacketMonitorService();
        }
        return instance;
    }

    /**
     * @return уже инициализированный singleton или {@code null}, если сервис ещё не создавался
     */
    public static synchronized PacketMonitorService getIfInitialized() {
        return instance;
    }

    /**
     * Закрывает singleton, если он был создан.
     * После вызова следующий {@link #getInstance()} создаст новый сервис и заново
     * инициализирует JDBC-ресурсы.
     */
    public static synchronized void closeIfInitialized() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public boolean isCaptureEnabled() {
        return captureEnabled.get();
    }

    public void startCapture() {
        if (captureEnabled.compareAndSet(false, true)) {
            notifyCaptureStateChanged(true);
        }
    }

    public void stopCapture() {
        if (captureEnabled.compareAndSet(true, false)) {
            notifyCaptureStateChanged(false);
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /**
     * Удаляет слушателя live-событий.
     * Контракт: вызов допустим даже если слушатель не был зарегистрирован.
     *
     * @param listener слушатель, который больше не должен получать события
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Регистрирует входящий пакет для конкретного подключения.
     *
     * @param connectionId идентификатор подключения в {@link ConnectionManager}
     * @param packet       protobuf-пакет
     */
    public void recordIncoming(String connectionId, MeshProtos.MeshPacket packet) {
        recordPacket(Direction.INCOMING, packet, resolveOwnerNodeId(connectionId), resolveDeviceState(connectionId));
    }

    /**
     * Регистрирует исходящий пакет для конкретного подключения.
     *
     * @param connectionId идентификатор подключения в {@link ConnectionManager}
     * @param packet       protobuf-пакет
     */
    public void recordOutgoing(String connectionId, MeshProtos.MeshPacket packet) {
        recordPacket(Direction.OUTGOING, packet, resolveOwnerNodeId(connectionId), resolveDeviceState(connectionId));
    }

    /**
     * Внутренняя точка записи пакета в журнал.
     * Контракт:
     * - если сбор выключен, пакет тихо игнорируется
     * - уведомление слушателей происходит только после успешного insert в БД
     * - порядок live-событий соответствует порядку успешных insert
     */
    synchronized void recordPacket(Direction direction,
                                   MeshProtos.MeshPacket packet,
                                   String ownerNodeId,
                                   DeviceState deviceState) {
        if (!captureEnabled.get() || packet == null) {
            return;
        }
        if (insertStmt == null) {
            log.warn("Packet monitor DB not initialized — packet dropped");
            return;
        }

        PacketDebugFormatter.PacketDetails details =
                PacketDebugFormatter.describeMeshPacket(packet, direction, deviceState);
        PacketLogEntry entry = new PacketLogEntry(
                ownerNodeId != null ? ownerNodeId : "",
                details.capturedAtMillis(),
                direction,
                details.packetType(),
                details.fromNode(),
                details.toNode(),
                details.payloadText(),
                packet.toByteArray()
        );

        try {
            insertStmt.setString(1, entry.getOwnerNodeId());
            insertStmt.setLong(2, entry.getCapturedAt());
            insertStmt.setString(3, entry.getDirection().name());
            insertStmt.setString(4, entry.getPacketType());
            insertStmt.setString(5, entry.getFromNode());
            insertStmt.setString(6, entry.getToNode());
            insertStmt.setString(7, entry.getPayloadText());
            insertStmt.setBytes(8, entry.getPacketBytes());
            insertStmt.executeUpdate();

            try (ResultSet keys = insertStmt.getGeneratedKeys()) {
                if (keys.next()) {
                    entry.setId(keys.getLong(1));
                }
            }
            notifyPacketLogged(entry);
        } catch (SQLException e) {
            log.error("Failed to persist LoRa packet log", e);
        }
    }

    /**
     * Загружает все сохранённые пакеты в порядке "новые сверху".
     * Контракт: UI может напрямую использовать результат как модель таблицы.
     *
     * @return список записей в порядке {@code captured_at DESC, id DESC}
     */
    public synchronized List<PacketLogEntry> loadAll() {
        List<PacketLogEntry> entries = new ArrayList<>();
        if (dbConnection == null) {
            return entries;
        }

        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT id, owner_node_id, captured_at, direction, packet_type, from_node, to_node, payload_text, packet_bytes
                FROM lora_packet_logs
                ORDER BY captured_at DESC, id DESC
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PacketLogEntry entry = new PacketLogEntry(
                        rs.getString("owner_node_id"),
                        rs.getLong("captured_at"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getString("packet_type"),
                        rs.getString("from_node"),
                        rs.getString("to_node"),
                        rs.getString("payload_text"),
                        rs.getBytes("packet_bytes")
                );
                entry.setId(rs.getLong("id"));
                entries.add(entry);
            }
        } catch (SQLException e) {
            log.error("Failed to load LoRa packet logs", e);
        }

        return entries;
    }

    /**
     * Полностью очищает журнал пакетов и уведомляет слушателей.
     * Контракт: после успешной очистки локальное состояние UI считается недействительным.
     */
    public synchronized void clear() {
        if (dbConnection == null) {
            return;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("DELETE FROM lora_packet_logs")) {
            ps.executeUpdate();
            notifyCleared();
        } catch (SQLException e) {
            log.error("Failed to clear LoRa packet logs", e);
        }
    }

    /**
     * Освобождает JDBC-ресурсы сервиса.
     * Используется при завершении приложения и при сбросе singleton в тестах.
     */
    public void close() {
        try {
            if (insertStmt != null) {
                insertStmt.close();
            }
        } catch (SQLException e) {
            log.error("Failed to close packet monitor statements", e);
        }
    }

    private void initDb() {
        try {
            dbConnection = DatabaseProvider.getConnection();
            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS lora_packet_logs (
                            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                            owner_node_id VARCHAR(20) NOT NULL DEFAULT '',
                            captured_at   BIGINT NOT NULL,
                            direction     VARCHAR(10) NOT NULL,
                            packet_type   VARCHAR(40) NOT NULL,
                            from_node     VARCHAR(160),
                            to_node       VARCHAR(160),
                            payload_text  CLOB,
                            packet_bytes  BLOB NOT NULL
                        )
                        """);
                stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_lora_owner_ts
                        ON lora_packet_logs (owner_node_id, captured_at)
                        """);
                stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_lora_type
                        ON lora_packet_logs (packet_type)
                        """);
            }

            insertStmt = dbConnection.prepareStatement("""
                    INSERT INTO lora_packet_logs (
                        owner_node_id, captured_at, direction, packet_type,
                        from_node, to_node, payload_text, packet_bytes
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
        } catch (Exception e) {
            log.error("Failed to initialize packet monitor DB", e);
        }
    }

    private void notifyPacketLogged(PacketLogEntry entry) {
        for (Listener listener : listeners) {
            try {
                listener.onPacketLogged(entry);
            } catch (Exception e) {
                log.error("Packet monitor listener failed onPacketLogged", e);
            }
        }
    }

    private void notifyCaptureStateChanged(boolean enabled) {
        for (Listener listener : listeners) {
            try {
                listener.onCaptureStateChanged(enabled);
            } catch (Exception e) {
                log.error("Packet monitor listener failed onCaptureStateChanged", e);
            }
        }
    }

    private void notifyCleared() {
        for (Listener listener : listeners) {
            try {
                listener.onCleared();
            } catch (Exception e) {
                log.error("Packet monitor listener failed onCleared", e);
            }
        }
    }

    private static String resolveOwnerNodeId(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return "";
        }
        String ownerNodeId = ConnectionManager.getInstance().getOwnerNodeId(connectionId);
        return ownerNodeId != null ? ownerNodeId : "";
    }

    private static DeviceState resolveDeviceState(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return null;
        }
        return ConnectionManager.getInstance().getDeviceState(connectionId);
    }
}
