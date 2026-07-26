package com.meshtastic.client.service;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.HardwareModelNames;
import com.meshtastic.client.utils.ConfigDebugFormatter;
import org.meshtastic.proto.*;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import com.meshtastic.client.protocol.FromRadioListener;
import com.meshtastic.client.protocol.ProtocolHandler;
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
 * Service that exchanges configuration with a Meshtastic device.
 * <p>
 * Implements the config-exchange protocol: sends {@code want_config_id},
 * receives a stream of MyNodeInfo, NodeInfo, Channel, Config, and ModuleConfig
 * messages, and completes when {@code config_complete_id} arrives.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>Create with a {@link ProtocolHandler} and {@link com.meshtastic.client.model.DeviceState}</li>
 *   <li>{@link #startConfigExchange()} sends {@code want_config_id} and returns a {@link CompletableFuture}</li>
 *   <li>Receive data through {@link com.meshtastic.client.protocol.FromRadioListener} callbacks</li>
 *   <li>Complete the future with deviceState and remove the listener</li>
 * </ol>
 * After completion, updates {@link NodeCacheService} and loads archived telemetry from H2.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ConfigExchangeService implements FromRadioListener {

    private static final Logger log = LoggerFactory.getLogger(ConfigExchangeService.class);

    /** Retry interval for want_config_id when the device does not respond, in milliseconds.
     *  Covers the case where a USB-Serial bridge such as CH340 resets the device
     *  during openPort(), and the first want_config_id is sent before the ESP32 finishes booting. */
    private static final int RETRY_INTERVAL_MS = 3000;
    private static final int MAX_RETRIES = 5;
    private static final int AUTO_TIME_SYNC_ACK_TIMEOUT_MS = 10_000;

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
    private volatile MeshProtos.LoRaRegionPresetMap pendingRegionPresetMap;
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
     * Starts config exchange. Clears current device state, generates a random
     * {@code want_config_id}, and sends it to the radio. Registers this service
     * as a {@link com.meshtastic.client.protocol.FromRadioListener} to receive
     * the configuration stream.
 *
     * @return {@link CompletableFuture} completed with populated {@link DeviceState}
     *         after {@code config_complete_id} is received
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
     * Schedules another want_config_id send when the device does not respond.
     * Covers the case where a USB-Serial bridge (CH340/CH9102) reset the ESP32
     * during port open and the first want_config_id was lost during boot.
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
            // Protobuf returns "" for unset string fields; blank values should
            // not overwrite existing data.
            if (!user.getLongName().isEmpty()) { node.setLongName(user.getLongName()); }
            if (!user.getShortName().isEmpty()) { node.setShortName(user.getShortName()); }
            if (!user.getId().isEmpty()) { node.setNodeId(user.getId()); }
            if (user.getRole() != ConfigProtos.Config.DeviceConfig.Role.CLIENT || node.getRole() == null) {
                node.setRole(user.getRole().name());
            }
            if (user.getHwModel() != MeshProtos.HardwareModel.UNSET || node.getHwModel() == null) {
                node.setHwModel(HardwareModelNames.forFirmware(
                        user.getHwModel(),
                        deviceState.getFirmwareCapabilities()));
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
                // Zero coordinates mean "no data"; keep existing values.
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

        // Remember favorite/ignored flags and apply them after nodes are written to the database.
        if (node.getNodeId() != null) {
            boolean favorite = nodeInfo.getIsFavorite();
            boolean ignored = nodeInfo.getIsIgnored();
            favoriteFlags.put(node.getNodeId(), favorite);
            ignoredFlags.put(node.getNodeId(), ignored);
            String ownerNodeId = deviceState.getOwnerNodeId();
            if (ownerNodeId != null && !ownerNodeId.isBlank()) {
                FavoriteNodeService.getInstance().setFavoriteQuiet(node.getNodeId(), ownerNodeId, favorite);
                IgnoredNodeService.getInstance().setIgnoredQuiet(node.getNodeId(), ownerNodeId, ignored);
            }
        }

        if (nodeInfo.hasDeviceMetrics()) {
            TelemetryProtos.DeviceMetrics dm = nodeInfo.getDeviceMetrics();
            applyBatteryLevel(dm.getBatteryLevel(), node, null);

            if (dm.getVoltage() != 0) { node.setVoltage(dm.getVoltage()); }

            if (dm.getChannelUtilization() != 0) { node.setChannelUtilization(dm.getChannelUtilization()); }

            if (dm.getAirUtilTx() != 0) { node.setAirUtilTx(dm.getAirUtilTx()); }

            if (dm.getUptimeSeconds() != 0) { node.setUptimeSeconds(dm.getUptimeSeconds()); }


            // Store the initial telemetry point when real data is present.
            // Fully zero records are config-exchange artifacts and are skipped.
            if (dm.getBatteryLevel() != 0 || dm.getChannelUtilization() != 0 || dm.getAirUtilTx() != 0) {
                long ts = nodeInfo.getLastHeard() > 0 ? nodeInfo.getLastHeard() : System.currentTimeMillis() / 1000;
                TelemetryEntry entry = new TelemetryEntry(ts, node.getNodeId());
                entry.setTelemetryVariant(TelemetryProtos.Telemetry.VariantCase.DEVICE_METRICS.name());
                applyBatteryLevel(dm.getBatteryLevel(), node, entry);
                entry.setVoltage(dm.getVoltage());
                entry.setChannelUtilization(dm.getChannelUtilization());
                entry.setAirUtilTx(dm.getAirUtilTx());
                if (dm.hasUptimeSeconds()) {
                    entry.setDeviceUptimeSeconds(Integer.toUnsignedLong(dm.getUptimeSeconds()));
                }
                deviceState.addTelemetryEntry(entry);
                String ownerNodeId = String.format("!%08x", deviceState.getMyNodeNum());
                NodeCacheService.getInstance().persistTelemetry(entry, ownerNodeId);
            }
        }

        log.debug("Updated node: {}", node);
        deviceState.fireNodeUpdateListeners(node.getNodeNum());
    }

    private static void applyBatteryLevel(int rawBatteryLevel, NodeData node, TelemetryEntry entry) {
        if (rawBatteryLevel > 100) {
            node.setExternallyPowered(true);
            if (entry != null) { entry.setExternallyPowered(true); }
        } else if (rawBatteryLevel > 0) {
            node.setBatteryLevel(rawBatteryLevel);
            node.setExternallyPowered(false);
            if (entry != null) { entry.setBatteryLevel(rawBatteryLevel); }
        }
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
    public void onDeviceMetadata(MeshProtos.DeviceMetadata metadata) {
        if (aborted.get()) {
            return;
        }
        markReceivedAnyResponse();
        deviceState.setDeviceMetadata(metadata);
        if (deviceState.getFirmwareCapabilities().firmware28OrNewer()
                && pendingRegionPresetMap != null) {
            deviceState.setRegionPresetMap(pendingRegionPresetMap);
        } else if (!deviceState.getFirmwareCapabilities().firmware28OrNewer()) {
            pendingRegionPresetMap = null;
        }
        deviceState.fireDeviceMetadataListeners();
        log.debug("onDeviceMetadata firmwareVersion='{}', excludedModules={}",
                metadata.getFirmwareVersion(), metadata.getExcludedModules());
    }

    @Override
    public void onRegionPresets(MeshProtos.LoRaRegionPresetMap regionPresetMap) {
        if (aborted.get()) {
            return;
        }
        markReceivedAnyResponse();
        pendingRegionPresetMap = regionPresetMap;
        if (deviceState.getFirmwareCapabilities().firmware28OrNewer()) {
            deviceState.setRegionPresetMap(regionPresetMap);
        }
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
        String ownerNodeId = String.format("!%08x", deviceState.getMyNodeNum());
        ncs.updateAll(deviceState.getNodeDb());

        // Apply favorite flags now that nodes exist in the database.
        for (Map.Entry<String, Boolean> e : favoriteFlags.entrySet()) {
            FavoriteNodeService.getInstance().setFavoriteQuiet(e.getKey(), ownerNodeId, e.getValue());
        }

        // Apply ignored flags now that nodes exist in the database.
        for (Map.Entry<String, Boolean> e : ignoredFlags.entrySet()) {
            IgnoredNodeService.getInstance().setIgnoredQuiet(e.getKey(), ownerNodeId, e.getValue());
        }

        // Enrich bare telemetry-only nodes with cached names from H2.
        for (NodeData node : deviceState.getNodeDb().values()) {
            ncs.enrichFromCache(node);
        }

        // Notify the UI about updated favorites and ignored nodes once after all nodes are processed.
        FavoriteNodeService.getInstance().fireListeners();
        IgnoredNodeService.getInstance().fireListeners();

        // Load archived telemetry from H2 and prune old records.
        ncs.pruneTelemetryHistory(30, ownerNodeId);
        var archived = ncs.loadTelemetryHistory(200, ownerNodeId);
        deviceState.prependTelemetryHistory(archived);
        log.info("Loaded {} archived telemetry entries from DB", archived.size());

        if (future != null) {
            future.complete(deviceState);
        }

        autoSyncTimeAfterConfigComplete();
    }

    private void autoSyncTimeAfterConfigComplete() {
        if (aborted.get() || deviceState.getMyNodeNum() == 0) {
            return;
        }
        long epochSeconds = System.currentTimeMillis() / 1000L;
        try {
            MessageService.sendPhoneTimePosition(protocolHandler, deviceState, epochSeconds);
            MessageService.setTimeOnly(protocolHandler, deviceState, epochSeconds)
                    .completeOnTimeout(MeshProtos.Routing.Error.TIMEOUT,
                            AUTO_TIME_SYNC_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .whenComplete((routingError, throwable) -> {
                        if (throwable != null) {
                            log.debug("Automatic node time sync failed", throwable);
                        } else if (routingError == MeshProtos.Routing.Error.NONE) {
                            log.debug("Automatic node time sync ACK received");
                        } else {
                            log.debug("Automatic node time sync completed with {}", routingError);
                        }
                    });
            log.debug("Automatic node time sync sent: epochSeconds={}", epochSeconds);
        } catch (Exception e) {
            log.debug("Automatic node time sync send failed", e);
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
            pendingRegionPresetMap = null;
        }
    }
}
