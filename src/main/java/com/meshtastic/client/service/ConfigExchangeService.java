package com.meshtastic.client.service;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.utils.ConfigDebugFormatter;
import org.meshtastic.proto.*;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.protocol.FromRadioListener;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.system.DrawerManager;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
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
    private final Map<String, Boolean> ignoredFlags = new HashMap<>();
    private final AtomicBoolean receivedAny = new AtomicBoolean(false);
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final Object deferredConfigLock = new Object();
    private final List<MeshProtos.NodeInfo> deferredNodeInfos = new ArrayList<>();
    private volatile ScheduledFuture<?> retryFuture;
    private Integer deferredConfigCompleteId;
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
        resetDeferredConfigState();

        sentConfigId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        log.info("Starting config exchange with want_config_id={}", sentConfigId);

        protocolHandler.addListener(this);

        sendWantConfig();
        scheduleRetry(1);

        return future;
    }

    private void sendWantConfig() {
        if (aborted.get()) {
            return;
        }
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
        if (attempt > MAX_RETRIES || aborted.get() || retryScheduler.isShutdown()) {
            return;
        }
        try {
            retryFuture = retryScheduler.schedule(() -> {
                if (aborted.get() || future.isDone()) {
                    return;
                }

                if (!receivedAny.get()) {
                    log.info("No response from device, retrying want_config_id (attempt {}/{})", attempt, MAX_RETRIES);
                    deviceState.clear();
                    resetDeferredConfigState();
                    sentConfigId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
                    sendWantConfig();
                    scheduleRetry(attempt + 1);
                    return;
                }

                if (deviceState.getMyNodeNum() == 0 || !deviceState.isChannelCatalogReady()) {
                    log.info("Config exchange is still incomplete (myNodeKnown={}, channelCatalogReady={}), retrying want_config_id={} (attempt {}/{})",
                            deviceState.getMyNodeNum() != 0,
                            deviceState.isChannelCatalogReady(),
                            sentConfigId,
                            attempt,
                            MAX_RETRIES);
                    sendWantConfig();
                    scheduleRetry(attempt + 1);
                }
            }, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Retry scheduler already stopped");
        }
    }

    private void cancelRetry() {
        ScheduledFuture<?> f = retryFuture;
        if (f != null) {
            f.cancel(false);
            retryFuture = null;
        }
        retryScheduler.shutdownNow();
    }

    public void abort(String reason) {
        if (!aborted.compareAndSet(false, true)) {
            return;
        }
        cancelRetry();
        protocolHandler.removeListener(this);
        resetDeferredConfigState();
        if (future != null && !future.isDone()) {
            future.completeExceptionally(new CancellationException("Config exchange aborted: " + reason));
        }
    }

    private void markReceivedAnyResponse() {
        receivedAny.set(true);
    }

    @Override
    public void onMyNodeInfo(MeshProtos.MyNodeInfo myInfo) {
        if (aborted.get()) {
            return;
        }
        markReceivedAnyResponse();
        deviceState.setMyNodeNum(myInfo.getMyNodeNum());
        log.info("My node number: {}", myInfo.getMyNodeNum());
        flushDeferredConfigState();
    }

    @Override
    public void onNodeInfo(MeshProtos.NodeInfo nodeInfo) {
        if (aborted.get()) {
            return;
        }
        markReceivedAnyResponse();
        if (deviceState.getMyNodeNum() == 0) {
            deferNodeInfo(nodeInfo);
            return;
        }
        processNodeInfo(nodeInfo);
    }

    private void processNodeInfo(MeshProtos.NodeInfo nodeInfo) {
        if (aborted.get()) {
            return;
        }
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
            node.setLicensed(user.getIsLicensed());
            if (user.hasIsUnmessagable()) {
                node.setUnmessagable(user.getIsUnmessagable());
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

        if (nodeInfo.hasHopsAway()) {
            node.setHopsAway((int) nodeInfo.getHopsAway());
        } else {
            node.clearHopsAway();
        }

        if (nodeInfo.getChannel() != 0) { node.setChannel((int) nodeInfo.getChannel()); }

        // Запомнить флаги избранного и игнорирования — применим после записи нод в БД (onConfigComplete)
        if (node.getNodeId() != null) {
            favoriteFlags.put(node.getNodeId(), nodeInfo.getIsFavorite());
            ignoredFlags.put(node.getNodeId(), nodeInfo.getIsIgnored());
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
        if (aborted.get()) {
            return;
        }
        markReceivedAnyResponse();
        if (config.getPayloadVariantCase() == ConfigProtos.Config.PayloadVariantCase.LORA) {
            log.debug("onConfig LORA ignore_incoming {}", ConfigDebugFormatter.describeIgnoreIncoming(config));
        }
        deviceState.addConfig(config);
    }

    @Override
    public void onModuleConfig(ModuleConfigProtos.ModuleConfig moduleConfig) {
        if (aborted.get()) {
            return;
        }
        markReceivedAnyResponse();
        if (moduleConfig.getPayloadVariantCase() == ModuleConfigProtos.ModuleConfig.PayloadVariantCase.MQTT) {
            log.debug("onModuleConfig MQTT {}", describeMqttConfig(moduleConfig.getMqtt()));
        }
        deviceState.addModuleConfig(moduleConfig);
    }

    @Override
    public void onChannel(ChannelProtos.Channel channel) {
        if (aborted.get()) {
            return;
        }
        markReceivedAnyResponse();
        log.debug("onChannel {}", describeChannel(channel));
        deviceState.addChannel(channel);
    }

    private static String describeMqttConfig(ModuleConfigProtos.ModuleConfig.MQTTConfig mqtt) {
        if (mqtt == null) {
            return "null";
        }
        return "enabled=" + mqtt.getEnabled()
                + ", proxyToClient=" + mqtt.getProxyToClientEnabled()
                + ", root='" + mqtt.getRoot() + "'"
                + ", address='" + mqtt.getAddress() + "'"
                + ", tls=" + mqtt.getTlsEnabled()
                + ", encryption=" + mqtt.getEncryptionEnabled()
                + ", json=" + mqtt.getJsonEnabled()
                + ", mapReporting=" + mqtt.getMapReportingEnabled()
                + ", usernameSet=" + !mqtt.getUsername().isBlank()
                + ", passwordSet=" + !mqtt.getPassword().isBlank();
    }

    private static String describeChannel(ChannelProtos.Channel channel) {
        if (channel == null) {
            return "null";
        }
        if (!channel.hasSettings()) {
            return "index=" + channel.getIndex()
                    + ", role=" + channel.getRole()
                    + ", settings=absent";
        }
        ChannelProtos.ChannelSettings settings = channel.getSettings();
        return "index=" + channel.getIndex()
                + ", role=" + channel.getRole()
                + ", name='" + settings.getName() + "'"
                + ", id=" + Integer.toUnsignedLong(settings.getId())
                + ", uplink=" + settings.getUplinkEnabled()
                + ", downlink=" + settings.getDownlinkEnabled()
                + ", muted=" + settings.getModuleSettings().getIsMuted()
                + ", pskBytes=" + settings.getPsk().size();
    }

    private void checkUnreadMessages(String ownerNodeId) {
        MessageDbService db = MessageDbService.getInstance();
        Map<String, Integer> readCounts = db.loadAllReadCounts(ownerNodeId);
        boolean hasUnread = false;

        for (ChannelProtos.Channel channel : deviceState.getChannels()) {
            if (channel.getRole() == ChannelProtos.Channel.Role.DISABLED) { continue; }
            String chKey = String.valueOf(channel.getIndex());
            int total = db.getUnreadEligibleMessageCount("channel", chKey, ownerNodeId);
            int read = readCounts.getOrDefault("ch:" + channel.getIndex(), 0);
            if (total > read) { hasUnread = true; break; }
        }

        if (!hasUnread) {
            for (String peerNodeId : db.getDistinctDmPeers(ownerNodeId)) {
                int total = db.getUnreadEligibleMessageCount("dm", peerNodeId, ownerNodeId);
                int read = readCounts.getOrDefault("dm:" + peerNodeId, 0);
                if (total > read) { hasUnread = true; break; }
            }
        }

        boolean show = hasUnread;
        Platform.runLater(() -> DrawerManager.setChatUnreadDot(show));
    }

    @Override
    public void onConfigComplete(int configCompleteId) {
        if (aborted.get()) {
            return;
        }
        if (configCompleteId != sentConfigId) {
            log.warn("Received config_complete_id {} but expected {}", configCompleteId, sentConfigId);
            return;
        }
        markReceivedAnyResponse();
        if (deviceState.getMyNodeNum() == 0) {
            deferConfigComplete(configCompleteId);
            return;
        }

        processConfigComplete(configCompleteId);
    }

    private void processConfigComplete(int configCompleteId) {
        if (aborted.get()) {
            return;
        }
        log.info("Config exchange complete. Nodes: {}, Channels: {}, Configs: {}",
                deviceState.getNodeCount(),
                deviceState.getChannels().size(),
                deviceState.getConfigs().size());
        cancelRetry();
        protocolHandler.removeListener(this);
        deviceState.setChannelCatalogReady(true);
        deviceState.clearPendingFixedPosition();
        NodeCacheService ncs = NodeCacheService.getInstance();
        ncs.updateAll(deviceState.getNodeDb());

        // Применить флаги избранного — теперь ноды есть в БД
        for (Map.Entry<String, Boolean> e : favoriteFlags.entrySet()) {
            ncs.setFavorite(e.getKey(), e.getValue());
        }

        // Применить флаги игнорирования (только true — дефолт уже FALSE)
        for (Map.Entry<String, Boolean> e : ignoredFlags.entrySet()) {
            if (e.getValue()) {
                ncs.setIgnored(e.getKey(), true);
            }
        }

        // Обогатить bare-ноды (только телеметрия) кэшированными именами из H2
        for (NodeData node : deviceState.getNodeDb().values()) {
            ncs.enrichFromCache(node);
        }

        // Уведомить UI об обновлённых избранных и игнорируемых (один раз после всех нод)
        FavoriteNodeService.getInstance().fireListeners();
        IgnoredNodeService.getInstance().fireListeners();

        // Загрузить архив телеметрии из H2 + подчистить старые записи
        String ownerNodeId = String.format("!%08x", deviceState.getMyNodeNum());
        ncs.pruneTelemetryHistory(30, ownerNodeId);
        var archived = ncs.loadTelemetryHistory(200, ownerNodeId);
        deviceState.prependTelemetryHistory(archived);
        log.info("Loaded {} archived telemetry entries from DB", archived.size());

        // Проверить наличие непрочитанных сообщений и обновить badge
        checkUnreadMessages(ownerNodeId);

        if (future != null) {
            future.complete(deviceState);
        }
    }

    private void deferNodeInfo(MeshProtos.NodeInfo nodeInfo) {
        if (aborted.get()) {
            return;
        }
        if (nodeInfo == null) {
            return;
        }
        synchronized (deferredConfigLock) {
            deferredNodeInfos.add(nodeInfo);
        }
        log.info("Deferring node info for {} until my node id is known", nodeInfo.getNum());
    }

    private void deferConfigComplete(int configCompleteId) {
        if (aborted.get()) {
            return;
        }
        synchronized (deferredConfigLock) {
            deferredConfigCompleteId = configCompleteId;
        }
        log.info("Deferring config_complete_id {} until my node id is known", configCompleteId);
    }

    private void flushDeferredConfigState() {
        if (aborted.get()) {
            return;
        }
        List<MeshProtos.NodeInfo> nodeInfos;
        Integer configCompleteId;
        synchronized (deferredConfigLock) {
            if (deferredNodeInfos.isEmpty() && deferredConfigCompleteId == null) {
                return;
            }
            nodeInfos = new ArrayList<>(deferredNodeInfos);
            deferredNodeInfos.clear();
            configCompleteId = deferredConfigCompleteId;
            deferredConfigCompleteId = null;
        }
        for (MeshProtos.NodeInfo nodeInfo : nodeInfos) {
            processNodeInfo(nodeInfo);
        }
        if (configCompleteId != null) {
            processConfigComplete(configCompleteId);
        }
    }

    private void resetDeferredConfigState() {
        synchronized (deferredConfigLock) {
            deferredNodeInfos.clear();
            deferredConfigCompleteId = null;
        }
    }
}
