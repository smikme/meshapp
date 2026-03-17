package com.meshtastic.client.service;

import com.meshtastic.client.model.DeviceState;
import org.meshtastic.proto.*;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.protocol.FromRadioListener;
import com.meshtastic.client.protocol.ProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сервис обмена конфигурацией с Meshtastic-устройством.
 * <p>
 * Реализует протокол config exchange: отправляет {@code want_config_id},
 * получает поток MyNodeInfo, NodeInfo, Channel, Config, ModuleConfig,
 * и завершается при получении {@code config_complete_id}.
 * <p>
 * Жизненный цикл:
 * <ol>
 *   <li>Создание с привязкой к {@link ProtocolHandler} и {@link com.meshtastic.client.model.DeviceState}</li>
 *   <li>{@link #startConfigExchange()} — отправка {@code want_config_id}, возврат {@link CompletableFuture}</li>
 *   <li>Приём данных через callback-и {@link com.meshtastic.client.protocol.FromRadioListener}</li>
 *   <li>Завершение — future.complete(deviceState), снятие слушателя</li>
 * </ol>
 * После завершения обновляет {@link NodeCacheService}, загружает архив телеметрии из H2.
 */
public class ConfigExchangeService implements FromRadioListener {

    private static final Logger log = LoggerFactory.getLogger(ConfigExchangeService.class);

    /** Интервал повтора want_config_id если устройство не ответило (мс).
     *  Покрывает случай когда USB-Serial мост (CH340) сбросил устройство при openPort(),
     *  и первый want_config_id был отправлен до завершения загрузки ESP32. */
    private static final int RETRY_INTERVAL_MS = 3000;
    private static final int MAX_RETRIES = 5;

    private final ProtocolHandler protocolHandler;
    private final DeviceState deviceState;
    private int sentConfigId;
    private CompletableFuture<DeviceState> future;
    private final Map<String, Boolean> favoriteFlags = new HashMap<>();
    private final AtomicBoolean receivedAny = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> retryFuture;
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "config-retry");
        t.setDaemon(true);
        return t;
    });

    public ConfigExchangeService(ProtocolHandler protocolHandler, DeviceState deviceState) {
        this.protocolHandler = protocolHandler;
        this.deviceState = deviceState;
    }

    /**
     * Запускает обмен конфигурацией. Очищает текущее состояние устройства,
     * генерирует случайный {@code want_config_id} и отправляет его на радио.
     * Регистрирует себя как {@link com.meshtastic.client.protocol.FromRadioListener}
     * для приёма потока конфигурации.
     *
     * @return {@link CompletableFuture}, который завершится с заполненным
     *         {@link DeviceState} после получения {@code config_complete_id}
     */
    public CompletableFuture<DeviceState> startConfigExchange() {
        future = new CompletableFuture<>();
        deviceState.clear();

        sentConfigId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        log.info("Starting config exchange with want_config_id={}", sentConfigId);

        protocolHandler.addListener(this);

        sendWantConfig();
        scheduleRetry(1);

        return future;
    }

    private void sendWantConfig() {
        MeshProtos.ToRadio toRadio = MeshProtos.ToRadio.newBuilder()
                .setWantConfigId(sentConfigId)
                .build();
        protocolHandler.sendToRadio(toRadio);
    }

    /**
     * Планирует повторную отправку want_config_id, если устройство не ответило.
     * Покрывает случай, когда USB-Serial мост (CH340/CH9102) сбросил ESP32 при
     * открытии порта и первый want_config_id был потерян во время загрузки.
     */
    private void scheduleRetry(int attempt) {
        if (attempt > MAX_RETRIES) return;
        retryFuture = retryScheduler.schedule(() -> {
            if (!receivedAny.get() && !future.isDone()) {
                log.info("No response from device, retrying want_config_id (attempt {}/{})", attempt, MAX_RETRIES);
                deviceState.clear();
                sentConfigId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
                sendWantConfig();
                scheduleRetry(attempt + 1);
            }
        }, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelRetry() {
        ScheduledFuture<?> f = retryFuture;
        if (f != null) {
            f.cancel(false);
            retryFuture = null;
        }
        retryScheduler.shutdownNow();
    }

    @Override
    public void onMyNodeInfo(MeshProtos.MyNodeInfo myInfo) {
        receivedAny.set(true);
        deviceState.setMyNodeNum(myInfo.getMyNodeNum());
        log.info("My node number: {}", myInfo.getMyNodeNum());
    }

    @Override
    public void onNodeInfo(MeshProtos.NodeInfo nodeInfo) {
        NodeData node = deviceState.getOrCreateNode(nodeInfo.getNum());

        if (nodeInfo.hasUser()) {
            MeshProtos.User user = nodeInfo.getUser();
            // Protobuf возвращает "" для незаполненных строковых полей —
            // пустые значения не должны затирать существующие данные
            if (!user.getLongName().isEmpty()) { node.setLongName(user.getLongName()); }
            if (!user.getShortName().isEmpty()) { node.setShortName(user.getShortName()); }
            if (!user.getId().isEmpty()) { node.setNodeId(user.getId()); }
            if (user.getRole() != ConfigProtos.Config.DeviceConfig.Role.CLIENT || node.getRole() == null) {
                node.setRole(user.getRole().name());
            }
            if (user.getHwModel() != MeshProtos.HardwareModel.UNSET || node.getHwModel() == null) {
                node.setHwModel(user.getHwModel().name());
            }
            if (!user.getPublicKey().isEmpty()) {
                node.setPublicKey(user.getPublicKey().toByteArray());
            }
        }

        if (nodeInfo.hasPosition()) {
            MeshProtos.Position pos = nodeInfo.getPosition();
            log.debug("NodeInfo position: nodeNum={}, latI={}, lonI={}, alt={}",
                    nodeInfo.getNum(), pos.getLatitudeI(), pos.getLongitudeI(), pos.getAltitude());

            // If user recently saved a fixed position for our own node,
            // use the pending values instead of potentially stale device data
            boolean isMyNode = nodeInfo.getNum() == deviceState.getMyNodeNum();
            if (isMyNode && deviceState.hasPendingFixedPosition()) {
                log.info("Using pending fixed position instead of device-reported: lat={}, lon={}, alt={}",
                        deviceState.getPendingFixedLat(), deviceState.getPendingFixedLon(),
                        deviceState.getPendingFixedAlt());
                node.setLatitude(Math.round(deviceState.getPendingFixedLat() * 1e7) * 1e-7);
                node.setLongitude(Math.round(deviceState.getPendingFixedLon() * 1e7) * 1e-7);
                node.setAltitude(deviceState.getPendingFixedAlt());
            } else {
                // Нулевые координаты означают отсутствие данных — не затираем существующие
                if (pos.getLatitudeI() != 0) { node.setLatitude(pos.getLatitudeI() * 1e-7); }
                if (pos.getLongitudeI() != 0) { node.setLongitude(pos.getLongitudeI() * 1e-7); }
                if (pos.getAltitude() != 0) { node.setAltitude(pos.getAltitude()); }
            }
        }

        if (nodeInfo.getSnr() != 0) { node.setSnr(nodeInfo.getSnr()); }

        if (nodeInfo.getLastHeard() != 0) { node.setLastHeard(nodeInfo.getLastHeard()); }

        if (nodeInfo.getHopsAway() != 0) { node.setHopsAway(nodeInfo.getHopsAway()); }

        // Запомнить флаг избранного — применим после записи нод в БД (onConfigComplete)
        if (node.getNodeId() != null) {
            favoriteFlags.put(node.getNodeId(), nodeInfo.getIsFavorite());
        }

        if (nodeInfo.hasDeviceMetrics()) {
            TelemetryProtos.DeviceMetrics dm = nodeInfo.getDeviceMetrics();
            if (dm.getBatteryLevel() != 0) { node.setBatteryLevel(dm.getBatteryLevel()); }

            if (dm.getVoltage() != 0) { node.setVoltage(dm.getVoltage()); }

            if (dm.getChannelUtilization() != 0) { node.setChannelUtilization(dm.getChannelUtilization()); }

            if (dm.getAirUtilTx() != 0) { node.setAirUtilTx(dm.getAirUtilTx()); }

            if (dm.getUptimeSeconds() != 0) { node.setUptimeSeconds(dm.getUptimeSeconds()); }


            // Сохранить начальную точку телеметрии, если есть реальные данные
            // (пропускаем полностью нулевые записи — артефакты config exchange)
            if (dm.getBatteryLevel() != 0 || dm.getChannelUtilization() != 0 || dm.getAirUtilTx() != 0) {
                long ts = nodeInfo.getLastHeard() > 0 ? nodeInfo.getLastHeard() : System.currentTimeMillis() / 1000;
                TelemetryEntry entry = new TelemetryEntry(ts, node.getNodeId());
                entry.setBatteryLevel(dm.getBatteryLevel());
                entry.setVoltage(dm.getVoltage());
                entry.setChannelUtilization(dm.getChannelUtilization());
                entry.setAirUtilTx(dm.getAirUtilTx());
                deviceState.addTelemetryEntry(entry);
                String ownerNodeId = String.format("!%08x", deviceState.getMyNodeNum());
                NodeCacheService.getInstance().persistTelemetry(entry, ownerNodeId);
            }
        }

        log.debug("Updated node: {}", node);
    }

    @Override
    public void onConfig(ConfigProtos.Config config) {
        deviceState.addConfig(config);
    }

    @Override
    public void onModuleConfig(ModuleConfigProtos.ModuleConfig moduleConfig) {
        deviceState.addModuleConfig(moduleConfig);
    }

    @Override
    public void onChannel(ChannelProtos.Channel channel) {
        deviceState.addChannel(channel);
    }

    @Override
    public void onConfigComplete(int configCompleteId) {
        if (configCompleteId == sentConfigId) {
            log.info("Config exchange complete. Nodes: {}, Channels: {}, Configs: {}",
                    deviceState.getNodeCount(),
                    deviceState.getChannels().size(),
                    deviceState.getConfigs().size());
            cancelRetry();
            protocolHandler.removeListener(this);
            deviceState.clearPendingFixedPosition();
            NodeCacheService ncs = NodeCacheService.getInstance();
            ncs.updateAll(deviceState.getNodeDb());

            // Применить флаги избранного — теперь ноды есть в БД
            for (Map.Entry<String, Boolean> e : favoriteFlags.entrySet()) {
                ncs.setFavorite(e.getKey(), e.getValue());
            }

            // Обогатить bare-ноды (только телеметрия) кэшированными именами из H2
            for (NodeData node : deviceState.getNodeDb().values()) {
                ncs.enrichFromCache(node);
            }

            // Уведомить UI об обновлённых избранных (один раз после всех нод)
            FavoriteNodeService.getInstance().fireListeners();

            // Загрузить архив телеметрии из H2 + подчистить старые записи
            String ownerNodeId = String.format("!%08x", deviceState.getMyNodeNum());
            ncs.pruneTelemetryHistory(30, ownerNodeId);
            var archived = ncs.loadTelemetryHistory(200, ownerNodeId);
            deviceState.prependTelemetryHistory(archived);
            log.info("Loaded {} archived telemetry entries from DB", archived.size());

            if (future != null) {
                future.complete(deviceState);
            }
        } else {
            log.warn("Received config_complete_id {} but expected {}", configCompleteId, sentConfigId);
        }
    }
}
