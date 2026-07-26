package com.meshtastic.client.service;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.HardwareModelNames;
import com.meshtastic.client.model.NodeData;
import org.meshtastic.proto.ConnStatusProtos;
import org.meshtastic.proto.DeviceUIProtos;
import org.meshtastic.proto.MeshProtos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime state for administering one remote Meshtastic node.
 * <p>
 * The remote node gets its own {@link DeviceState} so admin responses do not
 * overwrite the locally connected radio's owner info, metadata, configs, or
 * session passkey.
 */
public final class RemoteAdminSession {

    private final int targetNodeNum;
    private final DeviceState remoteState;
    private final ConcurrentHashMap<String, QueryStatus> queryStatuses = new ConcurrentHashMap<>();
    private volatile long sessionPasskeyReceivedAtMillis;
    private volatile String cannedMessages = "";
    private volatile boolean cannedMessagesLoaded;
    private volatile DeviceUIProtos.DeviceUIConfig uiConfig;
    private volatile ConnStatusProtos.DeviceConnectionStatus connectionStatus;

    /**
     * Creates an isolated remote-admin state holder for one target node.
     *
     * @param targetNodeNum numeric node ID of the remote node
     * @param sourceNode current node-list entry used to seed display metadata
     */
    public RemoteAdminSession(int targetNodeNum, NodeData sourceNode) {
        this.targetNodeNum = targetNodeNum;
        this.remoteState = new DeviceState();
        this.remoteState.setMyNodeNum(targetNodeNum);
        copyNode(sourceNode, this.remoteState.getOrCreateNode(targetNodeNum));
    }

    /**
     * Returns the node number administered by this session.
     *
     * @return target node number
     */
    public int targetNodeNum() {
        return targetNodeNum;
    }

    /**
     * Returns the isolated device state populated from remote admin responses.
     *
     * @return remote-only device state
     */
    public DeviceState remoteState() {
        return remoteState;
    }

    /**
     * Returns the mutable node record inside the remote state.
     *
     * @return target node data
     */
    public NodeData targetNode() {
        return remoteState.getOrCreateNode(targetNodeNum);
    }

    /**
     * Returns when the current session passkey was last received.
     *
     * @return timestamp in {@link System#currentTimeMillis()} units
     */
    public long sessionPasskeyReceivedAtMillis() {
        return sessionPasskeyReceivedAtMillis;
    }

    /**
     * Marks the cached session passkey as freshly received.
     */
    public void markSessionPasskeyReceived() {
        this.sessionPasskeyReceivedAtMillis = System.currentTimeMillis();
    }

    /**
     * Clears all data loaded by a remote snapshot request while preserving the
     * target node identity.
     */
    public void clearSnapshot() {
        remoteState.getConfigStore().clear();
        remoteState.getChannelStore().clear();
        remoteState.setOwnerInfo(null);
        remoteState.setDeviceMetadata(null);
        remoteState.setChannelCatalogReady(false);
        cannedMessages = "";
        cannedMessagesLoaded = false;
        uiConfig = null;
        connectionStatus = null;
        queryStatuses.clear();
    }

    /**
     * Records that a remote query was sent.
     *
     * @param key stable query key used in status summaries
     */
    public void markQuerySent(String key) {
        queryStatuses.put(key, new QueryStatus(key, QueryState.SENT, null));
    }

    /**
     * Records that a remote query received a matching response.
     *
     * @param key stable query key used in status summaries
     */
    public void markQueryReceived(String key) {
        queryStatuses.put(key, new QueryStatus(key, QueryState.RECEIVED, null));
    }

    /**
     * Records that a remote query failed or timed out.
     *
     * @param key stable query key used in status summaries
     * @param detail human-readable failure detail
     */
    public void markQueryFailed(String key, String detail) {
        queryStatuses.put(key, new QueryStatus(key, QueryState.FAILED, detail));
    }

    /**
     * Returns a stable snapshot of query statuses sorted by key.
     *
     * @return query status list
     */
    public List<QueryStatus> queryStatuses() {
        List<QueryStatus> snapshot = new ArrayList<>(queryStatuses.values());
        snapshot.sort(Comparator.comparing(QueryStatus::key));
        return snapshot;
    }

    /**
     * Summarizes the current remote query status map.
     *
     * @return total, received, and failed query counts
     */
    public QuerySummary querySummary() {
        int total = queryStatuses.size();
        int received = 0;
        int failed = 0;
        for (QueryStatus status : queryStatuses.values()) {
            if (status.state() == QueryState.RECEIVED) {
                received++;
            } else if (status.state() == QueryState.FAILED) {
                failed++;
            }
        }
        return new QuerySummary(total, received, failed);
    }

    /**
     * Returns the loaded canned message module payload.
     *
     * @return canned message text, or an empty string when not loaded
     */
    public String getCannedMessages() {
        return cannedMessages;
    }

    /**
     * Indicates whether canned messages have been loaded from the remote node.
     *
     * @return true after a canned-message response or local save
     */
    public boolean isCannedMessagesLoaded() {
        return cannedMessagesLoaded;
    }

    /**
     * Stores canned message module text in the session snapshot.
     *
     * @param cannedMessages canned message payload
     */
    public void setCannedMessages(String cannedMessages) {
        this.cannedMessages = cannedMessages != null ? cannedMessages : "";
        this.cannedMessagesLoaded = true;
    }

    /**
     * Returns the loaded Device UI config response.
     *
     * @return Device UI config, or null when not loaded
     */
    public DeviceUIProtos.DeviceUIConfig getUiConfig() {
        return uiConfig;
    }

    /**
     * Stores a Device UI config response in the session snapshot.
     *
     * @param uiConfig Device UI config response
     */
    public void setUiConfig(DeviceUIProtos.DeviceUIConfig uiConfig) {
        this.uiConfig = uiConfig;
    }

    /**
     * Returns the last loaded connection-status response.
     *
     * @return remote connection status, or null when not loaded
     */
    public ConnStatusProtos.DeviceConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    /**
     * Stores a connection-status response in the session snapshot.
     *
     * @param connectionStatus remote connection status response
     */
    public void setConnectionStatus(ConnStatusProtos.DeviceConnectionStatus connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    /**
     * Applies a remote owner response to the isolated remote state and target
     * node display record.
     *
     * @param owner owner payload returned by the remote node
     */
    public void applyOwner(MeshProtos.User owner) {
        if (owner == null) {
            return;
        }
        remoteState.setOwnerInfo(owner);
        NodeData node = targetNode();
        if (!owner.getLongName().isEmpty()) { node.setLongName(owner.getLongName()); }
        if (!owner.getShortName().isEmpty()) { node.setShortName(owner.getShortName()); }
        if (!owner.getId().isEmpty()) { node.setNodeId(owner.getId()); }
        if (owner.getRole() != null) { node.setRole(owner.getRole().name()); }
        if (owner.getHwModel() != MeshProtos.HardwareModel.UNSET) {
            node.setHwModel(HardwareModelNames.forFirmware(
                    owner.getHwModel(),
                    remoteState.getFirmwareCapabilities()));
        }
        if (!owner.getPublicKey().isEmpty()) {
            node.setPublicKey(owner.getPublicKey().toByteArray());
        }
        node.setLicensed(owner.getIsLicensed());
        if (owner.hasIsUnmessagable()) {
            node.setUnmessagable(owner.getIsUnmessagable());
        }
    }

    /**
     * Releases resources owned by the isolated remote state.
     */
    public void close() {
        remoteState.shutdown();
    }

    private static void copyNode(NodeData source, NodeData target) {
        if (source == null || target == null) {
            return;
        }
        target.setLongName(source.getLongName());
        target.setShortName(source.getShortName());
        target.setNodeId(source.getNodeId());
        target.setLatitude(source.getLatitude());
        target.setLongitude(source.getLongitude());
        target.setAltitude(source.getAltitude());
        target.setSnr(source.getSnr());
        target.setLastHeard(source.getLastHeard());
        target.setBatteryLevel(source.getBatteryLevel());
        target.setExternallyPowered(source.isExternallyPowered());
        target.setVoltage(source.getVoltage());
        target.setChannelUtilization(source.getChannelUtilization());
        target.setAirUtilTx(source.getAirUtilTx());
        target.setUptimeSeconds(source.getUptimeSeconds());
        target.setTemperature(source.getTemperature());
        target.setRelativeHumidity(source.getRelativeHumidity());
        target.setBarometricPressure(source.getBarometricPressure());
        if (source.hasHopsAway()) {
            target.setHopsAway(source.getHopsAway());
        } else {
            target.clearHopsAway();
        }
        target.setChannel(source.getChannel());
        target.setRole(source.getRole());
        target.setHwModel(source.getHwModel());
        target.setPublicKey(source.getPublicKey() != null ? source.getPublicKey().clone() : null);
        target.setUnmessagable(source.getUnmessagable());
        target.setLicensed(source.getLicensed());
    }

    /**
     * Lifecycle state for one remote admin query.
     */
    public enum QueryState {
        /**
         * Query was sent and is still awaiting a response.
         */
        SENT,
        /**
         * Query received a matching response.
         */
        RECEIVED,
        /**
         * Query failed through timeout, ACK error, or session shutdown.
         */
        FAILED
    }

    /**
     * Status of one remote admin query.
     *
     * @param key stable query key
     * @param state current query state
     * @param detail optional failure detail
     */
    public record QueryStatus(String key, QueryState state, String detail) {}

    /**
     * Aggregate counts for the current remote query set.
     *
     * @param total number of tracked queries
     * @param received queries with matching responses
     * @param failed queries that failed or timed out
     */
    public record QuerySummary(int total, int received, int failed) {}
}
