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
 * Runtime state assembled from MeshCore Companion packets.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
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
     * Reports whether the required {@code SELF_INFO} response has been received.
 *
     * @return {@code true} once the Companion handshake is considered complete
     */
    public boolean isReady() {
        return ready;
    }

    void setReady(boolean ready) {
        this.ready = ready;
    }

    /**
     * Returns a {@link DeviceState} bridge for existing UI screens.
     * <p>
     * MeshCore Companion does not use Meshtastic protobuf as its wire format,
     * but Chat, Nodes, and Dashboard already consume {@link DeviceState}. The
     * runtime fills this object with contacts, channels, messages, and telemetry.
 *
     * @return bridge state for the UI and local history
     */
    public DeviceState getDeviceState() {
        return deviceState;
    }

    /**
     * Returns the MeshCore device name reported by {@code SELF_INFO}.
 *
     * @return device name, or {@code null}
     */
    public String getDeviceName() {
        return deviceName;
    }

    void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
        updateOwnerNode();
    }

    /**
     * Returns the device public key as HEX.
 *
     * @return public-key HEX string, or {@code null}
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
     * Returns the stable owner id used by higher-level services.
 *
     * @return {@code meshcore:<publicKeyHex>}, or {@code null}
     */
    public String getOwnerId() {
        return ownerId;
    }

    /**
     * Returns the MeshCore advertisement type for the local device.
 *
     * @return raw {@code ADV_TYPE_*}, or {@code null}
     */
    public Integer getAdvertisementType() {
        return advertisementType;
    }

    void setAdvertisementType(Integer advertisementType) {
        this.advertisementType = advertisementType;
        updateOwnerNode();
    }

    /**
     * Returns the latitude from the self advertisement.
 *
     * @return latitude, or {@code null}
     */
    public Double getAdvertisementLatitude() {
        return advertisementLatitude;
    }

    /**
     * Returns the longitude from the self advertisement.
 *
     * @return longitude, or {@code null}
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
     * Returns the device's current transmit power.
 *
     * @return TX power in dBm, or {@code null}
     */
    public Integer getTxPowerDbm() {
        return txPowerDbm;
    }

    void setTxPowerDbm(Integer txPowerDbm) {
        this.txPowerDbm = txPowerDbm;
        updateOwnerNode();
    }

    /**
     * Returns the maximum allowed transmit power.
 *
     * @return maximum TX power in dBm, or {@code null}
     */
    public Integer getMaxTxPowerDbm() {
        return maxTxPowerDbm;
    }

    void setMaxTxPowerDbm(Integer maxTxPowerDbm) {
        this.maxTxPowerDbm = maxTxPowerDbm;
    }

    /**
     * Returns the Companion firmware protocol version.
 *
     * @return protocol version, or {@code null}
     */
    public Integer getFirmwareProtocolVersion() {
        return firmwareProtocolVersion;
    }

    void setFirmwareProtocolVersion(Integer firmwareProtocolVersion) {
        this.firmwareProtocolVersion = firmwareProtocolVersion;
    }

    /**
     * Returns the maximum contact count reported by the device.
 *
     * @return maximum contact count, or {@code null}
     */
    public Integer getMaxContacts() {
        return maxContacts;
    }

    void setMaxContacts(Integer maxContacts) {
        this.maxContacts = maxContacts;
    }

    /**
     * Returns the maximum channel count reported by the device.
 *
     * @return maximum channel count, or {@code null}
     */
    public Integer getMaxChannels() {
        return maxChannels;
    }

    void setMaxChannels(Integer maxChannels) {
        this.maxChannels = maxChannels;
    }

    /**
     * Returns the BLE PIN from device info when the firmware exposes it.
 *
     * @return PIN, or {@code null}
     */
    public Integer getBlePin() {
        return blePin;
    }

    void setBlePin(Integer blePin) {
        this.blePin = blePin;
    }

    /**
     * Returns the firmware build string.
 *
     * @return build string, or {@code null}
     */
    public String getFirmwareBuild() {
        return firmwareBuild;
    }

    void setFirmwareBuild(String firmwareBuild) {
        this.firmwareBuild = firmwareBuild;
    }

    /**
     * Returns the device model.
 *
     * @return model, or {@code null}
     */
    public String getModel() {
        return model;
    }

    void setModel(String model) {
        this.model = model;
        updateOwnerNode();
    }

    /**
     * Returns the firmware version text.
 *
     * @return firmware version, or {@code null}
     */
    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    /**
     * Returns the battery voltage.
 *
     * @return voltage in millivolts, or {@code null}
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
     * Returns the amount of used device storage.
 *
     * @return used storage in KB, or {@code null}
     */
    public Long getUsedStorageKb() {
        return usedStorageKb;
    }

    /**
     * Returns the total device storage capacity.
 *
     * @return total storage in KB, or {@code null}
     */
    public Long getTotalStorageKb() {
        return totalStorageKb;
    }

    void setStorage(Long usedStorageKb, Long totalStorageKb) {
        this.usedStorageKb = usedStorageKb;
        this.totalStorageKb = totalStorageKb;
    }

    /**
     * Returns the latest MeshCore Companion error.
 *
     * @return error text, or {@code null}
     */
    public String getLastError() {
        return lastError;
    }

    void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /**
     * Returns the contact count announced at the start of a sync response.
 *
     * @return contact count, or {@code null}
     */
    public Integer getContactCount() {
        return contactCount;
    }

    void setContactCount(Integer contactCount) {
        this.contactCount = contactCount;
    }

    /**
     * Returns the timestamp of the latest contact-list change.
 *
     * @return Unix timestamp, or {@code null}
     */
    public Long getContactsLastModified() {
        return contactsLastModified;
    }

    void setContactsLastModified(Long contactsLastModified) {
        this.contactsLastModified = contactsLastModified;
    }

    /**
     * Updates a channel in the compatible {@link DeviceState}.
 *
     * @param channelIndex MeshCore channel index
     * @param channelName  channel name
     * @param enabled      whether the channel is active
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
     * Creates a basic public channel so Chat can open before the
     * {@code PACKET_CHANNEL_INFO}.
     */
    void ensureDefaultChannel() {
        if (!deviceState.hasEnabledChannel(0)) {
            updateChannel(0, "Public", true);
        }
    }

    /**
     * Adds or updates a MeshCore contact in the node database.
 *
     * @param publicKey  full or partial contact public key
     * @param type raw {@code ADV_TYPE_*}
     * @param name advertised name
     * @param lastAdvert Unix timestamp of the latest advertisement
     * @param latitude   advertised latitude, or {@code null}
     * @param longitude  advertised longitude, or {@code null}
     * @return contact node id, or {@code null}
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
     * Adds an incoming MeshCore channel message.
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
     * Adds an incoming MeshCore direct message.
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
     * Returns the first 6 bytes of a public key for sending a direct message.
 *
     * @param nodeId MeshCore contact node id
     * @return 6-byte prefix, or an empty array
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
