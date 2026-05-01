package com.meshtastic.client.protocol.meshcore;

import com.google.protobuf.ByteString;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.model.TelemetryEntry;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.MeshProtos;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-состояние, собранное из MeshCore Companion packets.
 */
public class MeshCoreCompanionState {

    private final DeviceState deviceState = new DeviceState();
    private final Map<String, String> nodeIdsByPublicKeyPrefix = new ConcurrentHashMap<>();
    private final Map<String, byte[]> publicKeysByNodeId = new ConcurrentHashMap<>();

    private volatile boolean ready;
    private volatile String deviceName;
    private volatile String publicKeyHex;
    private volatile String ownerId;
    private volatile Integer advertisementType;
    private volatile Double advertisementLatitude;
    private volatile Double advertisementLongitude;
    private volatile Boolean multiAcks;
    private volatile Integer advertisementLocationPolicy;
    private volatile Integer telemetryModeBase;
    private volatile Integer telemetryModeLocation;
    private volatile Integer telemetryModeEnvironment;
    private volatile Boolean manualAddContacts;
    private volatile Double radioFrequencyKhz;
    private volatile Double radioBandwidthKhz;
    private volatile Integer radioSpreadingFactor;
    private volatile Integer radioCodingRate;
    private volatile Integer txPowerDbm;
    private volatile Integer maxTxPowerDbm;
    private volatile Integer firmwareProtocolVersion;
    private volatile Integer maxContacts;
    private volatile Integer maxChannels;
    private volatile Integer blePin;
    private volatile String firmwareBuild;
    private volatile String model;
    private volatile String firmwareVersion;
    private volatile Integer batteryMillivolts;
    private volatile Long usedStorageKb;
    private volatile Long totalStorageKb;
    private volatile String lastError;
    private volatile Integer contactCount;
    private volatile Long contactsLastModified;

    /**
     * Проверяет, получен ли обязательный {@code SELF_INFO} response.
     *
     * @return {@code true}, если Companion handshake считается успешным
     */
    public boolean isReady() {
        return ready;
    }

    void setReady(boolean ready) {
        this.ready = ready;
    }

    /**
     * Возвращает совместимое состояние устройства для существующих экранов UI.
     * <p>
     * MeshCore Companion не использует Meshtastic protobuf как wire format,
     * но Chat/Nodes/Dashboard уже работают через {@link DeviceState}. Runtime
     * заполняет этот объект контактами, каналами, сообщениями и телеметрией.
     *
     * @return bridge-состояние для UI и локальной истории
     */
    public DeviceState getDeviceState() {
        return deviceState;
    }

    /**
     * Возвращает имя MeshCore-устройства из {@code SELF_INFO}.
     *
     * @return имя устройства или {@code null}
     */
    public String getDeviceName() {
        return deviceName;
    }

    void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
        updateOwnerNode();
    }

    /**
     * Возвращает public key устройства в HEX.
     *
     * @return HEX-строка public key или {@code null}
     */
    public String getPublicKeyHex() {
        return publicKeyHex;
    }

    void setPublicKeyHex(String publicKeyHex) {
        this.publicKeyHex = publicKeyHex;
        this.ownerId = MeshCoreCompanionFrames.nodeIdFromPublicKeyHex(publicKeyHex);
        byte[] publicKey = MeshCoreCompanionFrames.hexToBytes(publicKeyHex);
        if (ownerId != null) {
            publicKeysByNodeId.put(ownerId, publicKey);
            nodeIdsByPublicKeyPrefix.put(publicKeyPrefixHex(publicKey), ownerId);
        }
        updateOwnerNode();
    }

    /**
     * Возвращает стабильный owner id для сервисов верхнего уровня.
     *
     * @return {@code meshcore:<publicKeyHex>} или {@code null}
     */
    public String getOwnerId() {
        return ownerId;
    }

    /**
     * Возвращает тип MeshCore advertisement для локального устройства.
     *
     * @return raw {@code ADV_TYPE_*} или {@code null}
     */
    public Integer getAdvertisementType() {
        return advertisementType;
    }

    void setAdvertisementType(Integer advertisementType) {
        this.advertisementType = advertisementType;
        updateOwnerNode();
    }

    /**
     * Возвращает latitude из self advertisement.
     *
     * @return широта или {@code null}
     */
    public Double getAdvertisementLatitude() {
        return advertisementLatitude;
    }

    /**
     * Возвращает longitude из self advertisement.
     *
     * @return долгота или {@code null}
     */
    public Double getAdvertisementLongitude() {
        return advertisementLongitude;
    }

    void setAdvertisementPosition(Double latitude, Double longitude) {
        this.advertisementLatitude = latitude;
        this.advertisementLongitude = longitude;
        updateOwnerNode();
    }

    public Boolean getMultiAcks() {
        return multiAcks;
    }

    void setMultiAcks(Boolean multiAcks) {
        this.multiAcks = multiAcks;
    }

    public Integer getAdvertisementLocationPolicy() {
        return advertisementLocationPolicy;
    }

    void setAdvertisementLocationPolicy(Integer advertisementLocationPolicy) {
        this.advertisementLocationPolicy = advertisementLocationPolicy;
    }

    public Integer getTelemetryModeBase() {
        return telemetryModeBase;
    }

    public Integer getTelemetryModeLocation() {
        return telemetryModeLocation;
    }

    public Integer getTelemetryModeEnvironment() {
        return telemetryModeEnvironment;
    }

    void setTelemetryModes(Integer base, Integer location, Integer environment) {
        this.telemetryModeBase = base;
        this.telemetryModeLocation = location;
        this.telemetryModeEnvironment = environment;
    }

    public Boolean getManualAddContacts() {
        return manualAddContacts;
    }

    void setManualAddContacts(Boolean manualAddContacts) {
        this.manualAddContacts = manualAddContacts;
    }

    public Double getRadioFrequencyKhz() {
        return radioFrequencyKhz;
    }

    public Double getRadioBandwidthKhz() {
        return radioBandwidthKhz;
    }

    void setRadioParameters(Double frequencyKhz, Double bandwidthKhz, Integer spreadingFactor, Integer codingRate) {
        this.radioFrequencyKhz = frequencyKhz;
        this.radioBandwidthKhz = bandwidthKhz;
        this.radioSpreadingFactor = spreadingFactor;
        this.radioCodingRate = codingRate;
    }

    public Integer getRadioSpreadingFactor() {
        return radioSpreadingFactor;
    }

    public Integer getRadioCodingRate() {
        return radioCodingRate;
    }

    /**
     * Возвращает текущую мощность передачи устройства.
     *
     * @return TX power в dBm или {@code null}
     */
    public Integer getTxPowerDbm() {
        return txPowerDbm;
    }

    void setTxPowerDbm(Integer txPowerDbm) {
        this.txPowerDbm = txPowerDbm;
        updateOwnerNode();
    }

    /**
     * Возвращает максимальную допустимую мощность передачи.
     *
     * @return max TX power в dBm или {@code null}
     */
    public Integer getMaxTxPowerDbm() {
        return maxTxPowerDbm;
    }

    void setMaxTxPowerDbm(Integer maxTxPowerDbm) {
        this.maxTxPowerDbm = maxTxPowerDbm;
    }

    /**
     * Возвращает версию Companion firmware protocol.
     *
     * @return protocol version или {@code null}
     */
    public Integer getFirmwareProtocolVersion() {
        return firmwareProtocolVersion;
    }

    void setFirmwareProtocolVersion(Integer firmwareProtocolVersion) {
        this.firmwareProtocolVersion = firmwareProtocolVersion;
    }

    /**
     * Возвращает максимальное количество контактов, которое сообщает устройство.
     *
     * @return max contacts или {@code null}
     */
    public Integer getMaxContacts() {
        return maxContacts;
    }

    void setMaxContacts(Integer maxContacts) {
        this.maxContacts = maxContacts;
    }

    /**
     * Возвращает максимальное количество каналов, которое сообщает устройство.
     *
     * @return max channels или {@code null}
     */
    public Integer getMaxChannels() {
        return maxChannels;
    }

    void setMaxChannels(Integer maxChannels) {
        this.maxChannels = maxChannels;
    }

    /**
     * Возвращает BLE PIN из device-info, если firmware его отдаёт.
     *
     * @return PIN или {@code null}
     */
    public Integer getBlePin() {
        return blePin;
    }

    void setBlePin(Integer blePin) {
        this.blePin = blePin;
    }

    /**
     * Возвращает firmware build string.
     *
     * @return build или {@code null}
     */
    public String getFirmwareBuild() {
        return firmwareBuild;
    }

    void setFirmwareBuild(String firmwareBuild) {
        this.firmwareBuild = firmwareBuild;
    }

    /**
     * Возвращает модель устройства.
     *
     * @return model или {@code null}
     */
    public String getModel() {
        return model;
    }

    void setModel(String model) {
        this.model = model;
        updateOwnerNode();
    }

    /**
     * Возвращает текстовую версию firmware.
     *
     * @return firmware version или {@code null}
     */
    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    /**
     * Возвращает напряжение батареи.
     *
     * @return напряжение в millivolts или {@code null}
     */
    public Integer getBatteryMillivolts() {
        return batteryMillivolts;
    }

    void setBatteryMillivolts(Integer batteryMillivolts) {
        this.batteryMillivolts = batteryMillivolts;
        updateOwnerNode();
        addBatteryTelemetry();
    }

    /**
     * Возвращает объём занятого storage.
     *
     * @return used storage в KB или {@code null}
     */
    public Long getUsedStorageKb() {
        return usedStorageKb;
    }

    /**
     * Возвращает общий объём storage.
     *
     * @return total storage в KB или {@code null}
     */
    public Long getTotalStorageKb() {
        return totalStorageKb;
    }

    void setStorage(Long usedStorageKb, Long totalStorageKb) {
        this.usedStorageKb = usedStorageKb;
        this.totalStorageKb = totalStorageKb;
    }

    /**
     * Возвращает последнюю ошибку MeshCore Companion.
     *
     * @return текст ошибки или {@code null}
     */
    public String getLastError() {
        return lastError;
    }

    void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /**
     * Возвращает количество контактов, объявленное началом sync-ответа.
     *
     * @return количество контактов или {@code null}
     */
    public Integer getContactCount() {
        return contactCount;
    }

    void setContactCount(Integer contactCount) {
        this.contactCount = contactCount;
    }

    /**
     * Возвращает timestamp последнего изменения списка контактов.
     *
     * @return Unix timestamp или {@code null}
     */
    public Long getContactsLastModified() {
        return contactsLastModified;
    }

    void setContactsLastModified(Long contactsLastModified) {
        this.contactsLastModified = contactsLastModified;
    }

    /**
     * Обновляет канал в совместимом {@link DeviceState}.
     *
     * @param channelIndex индекс MeshCore-канала
     * @param channelName имя канала
     * @param enabled активен ли канал
     */
    void updateChannel(int channelIndex, String channelName, boolean enabled) {
        ChannelProtos.Channel.Role role = !enabled
                ? ChannelProtos.Channel.Role.DISABLED
                : channelIndex == 0
                ? ChannelProtos.Channel.Role.PRIMARY
                : ChannelProtos.Channel.Role.SECONDARY;
        String name = channelName == null || channelName.isBlank()
                ? channelIndex == 0 ? "Public" : "Ch " + channelIndex
                : channelName;
        ChannelProtos.Channel channel = ChannelProtos.Channel.newBuilder()
                .setIndex(channelIndex)
                .setRole(role)
                .setSettings(ChannelProtos.ChannelSettings.newBuilder().setName(name))
                .build();
        deviceState.updateChannel(channel);
        deviceState.setChannelCatalogReady(true);
    }

    /**
     * Создаёт базовый публичный канал, чтобы Chat мог открыться до ответа
     * {@code PACKET_CHANNEL_INFO}.
     */
    void ensureDefaultChannel() {
        if (!deviceState.hasEnabledChannel(0)) {
            updateChannel(0, "Public", true);
        }
    }

    /**
     * Добавляет или обновляет MeshCore contact в node database.
     *
     * @param publicKey public key контакта, полный или частичный
     * @param type raw {@code ADV_TYPE_*}
     * @param name advertised name
     * @param lastAdvert Unix timestamp последнего advert
     * @param latitude advertised latitude или {@code null}
     * @param longitude advertised longitude или {@code null}
     * @return node id контакта или {@code null}
     */
    String updateContact(byte[] publicKey,
                         int type,
                         String name,
                         long lastAdvert,
                         Double latitude,
                         Double longitude) {
        if (publicKey == null || publicKey.length < 6) {
            return null;
        }
        String nodeId = MeshCoreCompanionFrames.nodeIdFromPublicKeyHex(MeshCoreCompanionFrames.hex(publicKey));
        if (nodeId == null) {
            return null;
        }
        byte[] publicKeyCopy = Arrays.copyOf(publicKey, publicKey.length);
        publicKeysByNodeId.put(nodeId, publicKeyCopy);
        nodeIdsByPublicKeyPrefix.put(publicKeyPrefixHex(publicKeyCopy), nodeId);

        NodeData node = deviceState.getOrCreateNode(nodeNumFromNodeId(nodeId));
        node.setNodeId(nodeId);
        node.setPublicKey(publicKeyCopy);
        node.setLongName(name == null || name.isBlank() ? nodeId : name);
        node.setShortName(shortName(name, nodeId));
        node.setRole(roleName(type));
        node.setHwModel("MeshCore");
        if (lastAdvert > 0 && lastAdvert <= Integer.MAX_VALUE) {
            node.setLastHeard((int) lastAdvert);
        }
        if (latitude != null && longitude != null) {
            node.setLatitude(latitude);
            node.setLongitude(longitude);
        }
        deviceState.fireNodeUpdateListeners(node.getNodeNum());
        return nodeId;
    }

    /**
     * Добавляет входящее канальное сообщение MeshCore.
     */
    void addIncomingChannelMessage(int channelIndex,
                                   String text,
                                   long timestamp,
                                   Integer pathLength,
                                   Float snr,
                                   int packetId) {
        ensureDefaultChannel();
        String fromNodeId = "mc:channel";
        ensureSyntheticNode(fromNodeId, "MeshCore Channel", "CHAN");
        MeshMessage message = new MeshMessage(
                fromNodeId,
                "!ffffffff",
                channelIndex,
                text,
                normalizeTimestamp(timestamp),
                false);
        message.setPacketId(packetId);
        message.setSenderName("MeshCore");
        if (pathLength != null && pathLength >= 0 && pathLength <= 254) {
            message.setHopStart(pathLength);
        }
        if (snr != null) {
            message.setRxSnr(snr);
        }
        deviceState.addMessage(message);
    }

    /**
     * Добавляет входящее личное сообщение MeshCore.
     */
    void addIncomingDirectMessage(byte[] publicKeyPrefix,
                                  String text,
                                  long timestamp,
                                  Integer pathLength,
                                  Float snr,
                                  int packetId) {
        String peerNodeId = resolveNodeIdFromPrefix(publicKeyPrefix);
        if (peerNodeId == null) {
            peerNodeId = updateContact(publicKeyPrefix, 1, null, timestamp, null, null);
        }
        if (peerNodeId == null) {
            return;
        }
        NodeData peerNode = deviceState.getNodeByNodeId(peerNodeId);
        MeshMessage message = new MeshMessage(
                peerNodeId,
                ownerId != null ? ownerId : "!ffffffff",
                0,
                text,
                normalizeTimestamp(timestamp),
                false);
        message.setPacketId(packetId);
        if (peerNode != null && peerNode.getLongName() != null) {
            message.setSenderName(peerNode.getLongName());
        }
        if (pathLength != null && pathLength >= 0 && pathLength <= 254) {
            message.setHopStart(pathLength);
        }
        if (snr != null) {
            message.setRxSnr(snr);
        }
        deviceState.addDirectMessage(message, peerNodeId);
    }

    /**
     * Возвращает первые 6 байт public key для отправки DM.
     *
     * @param nodeId MeshCore node id контакта
     * @return 6-байтовый prefix или пустой массив
     */
    byte[] publicKeyPrefixForNode(String nodeId) {
        byte[] publicKey = publicKeysByNodeId.get(nodeId);
        if (publicKey != null && publicKey.length >= 6) {
            return Arrays.copyOf(publicKey, 6);
        }
        return MeshCoreCompanionFrames.publicKeyPrefixFromNodeId(nodeId);
    }

    private String resolveNodeIdFromPrefix(byte[] publicKeyPrefix) {
        if (publicKeyPrefix == null || publicKeyPrefix.length < 6) {
            return null;
        }
        String prefixHex = publicKeyPrefixHex(publicKeyPrefix);
        String existing = nodeIdsByPublicKeyPrefix.get(prefixHex);
        return existing != null ? existing : MeshCoreCompanionFrames.nodeIdFromPublicKeyHex(prefixHex);
    }

    private void updateOwnerNode() {
        if (ownerId == null) {
            return;
        }
        byte[] publicKey = MeshCoreCompanionFrames.hexToBytes(publicKeyHex);
        int nodeNum = nodeNumFromNodeId(ownerId);
        deviceState.setMyNodeNum(nodeNum);

        String longName = deviceName == null || deviceName.isBlank() ? "MeshCore" : deviceName;
        String shortName = shortName(longName, ownerId);
        NodeData owner = deviceState.getOrCreateNode(nodeNum);
        owner.setNodeId(ownerId);
        owner.setLongName(longName);
        owner.setShortName(shortName);
        owner.setRole(roleName(advertisementType != null ? advertisementType : 1));
        owner.setHwModel(model != null ? model : "MeshCore");
        owner.setPublicKey(publicKey);
        owner.setLastHeard((int) (System.currentTimeMillis() / 1000));
        if (advertisementLatitude != null && advertisementLongitude != null) {
            owner.setLatitude(advertisementLatitude);
            owner.setLongitude(advertisementLongitude);
        }
        if (batteryMillivolts != null) {
            owner.setVoltage(batteryMillivolts / 1000.0f);
        }
        if (txPowerDbm != null) {
            owner.setAirUtilTx(txPowerDbm);
        }
        MeshProtos.User.Builder user = MeshProtos.User.newBuilder()
                .setId(ownerId)
                .setLongName(longName)
                .setShortName(shortName);
        if (publicKey.length > 0) {
            user.setPublicKey(ByteString.copyFrom(publicKey));
        }
        deviceState.setOwnerInfo(user.build());
        deviceState.fireOwnerInfoListeners();
        deviceState.fireNodeUpdateListeners(nodeNum);
        ensureDefaultChannel();
    }

    private void addBatteryTelemetry() {
        if (ownerId == null || batteryMillivolts == null) {
            return;
        }
        TelemetryEntry entry = new TelemetryEntry(System.currentTimeMillis() / 1000, ownerId);
        entry.setVoltage(batteryMillivolts / 1000.0f);
        deviceState.addTelemetryEntry(entry);
    }

    private void ensureSyntheticNode(String nodeId, String longName, String shortName) {
        NodeData node = deviceState.getOrCreateNode(nodeNumFromNodeId(nodeId));
        node.setNodeId(nodeId);
        node.setLongName(longName);
        node.setShortName(shortName);
        node.setRole("CLIENT");
        node.setHwModel("MeshCore");
    }

    private static String publicKeyPrefixHex(byte[] publicKey) {
        if (publicKey == null || publicKey.length < 6) {
            return "";
        }
        return MeshCoreCompanionFrames.hex(Arrays.copyOf(publicKey, 6));
    }

    static int nodeNumFromNodeId(String nodeId) {
        if (nodeId != null && nodeId.startsWith("mc:") && nodeId.length() >= 11) {
            try {
                long value = Long.parseUnsignedLong(nodeId.substring(3, 11), 16);
                int nodeNum = (int) value;
                return nodeNum != 0 ? nodeNum : 1;
            } catch (NumberFormatException ignored) {
                // Fall back to hash below.
            }
        }
        int hash = nodeId == null ? 1 : nodeId.hashCode();
        return hash != 0 ? hash : 1;
    }

    private static String shortName(String name, String fallback) {
        String source = name == null || name.isBlank() ? fallback : name;
        if (source == null || source.isBlank()) {
            return "MC";
        }
        String compact = source.replaceAll("\\s+", "");
        return compact.length() <= 4 ? compact : compact.substring(0, 4);
    }

    private static String roleName(int advertisementType) {
        return switch (advertisementType) {
            case 2 -> "REPEATER";
            case 3 -> "ROOM";
            default -> "CLIENT";
        };
    }

    private static long normalizeTimestamp(long timestamp) {
        return timestamp > 0 ? timestamp : System.currentTimeMillis() / 1000;
    }
}
