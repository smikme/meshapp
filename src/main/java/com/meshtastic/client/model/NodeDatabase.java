package com.meshtastic.client.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory database of known Meshtastic nodes.
 * <p>
 * Nodes are indexed by numeric node id, can also be resolved by Meshtastic
 * node id, and notify registered listeners when entries are removed or changed.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class NodeDatabase {

    private static final Logger log = LoggerFactory.getLogger(NodeDatabase.class);

    /** Maps numeric node id to node data. */
    private final ConcurrentHashMap<Integer, NodeData> nodeDb = new ConcurrentHashMap<>();

    /** Listeners notified when node data changes. */
    private final CopyOnWriteArrayList<java.util.function.IntConsumer> nodeUpdateListeners = new CopyOnWriteArrayList<>();

    /**
     * Returns a node by numeric id.
     *
     * @param nodeNum numeric node id
     * @return node data, or {@code null} when it is unknown
     */
    public NodeData getNode(int nodeNum) {
        return nodeDb.get(nodeNum);
    }

    /**
     * Returns the existing node or creates it atomically.
     * {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent} guarantees
     * a single {@link NodeData} instance per {@code nodeNum}.
     *
     * @param nodeNum numeric node id
     * @return existing or newly created node data
     */
    public NodeData getOrCreateNode(int nodeNum) {
        return nodeDb.computeIfAbsent(nodeNum, NodeData::new);
    }

    /**
     * Removes a node and notifies listeners when an entry existed.
     *
     * @param nodeNum numeric node id
     */
    public void removeNode(int nodeNum) {
        NodeData removed = nodeDb.remove(nodeNum);
        if (removed != null) {
            fireNodeUpdateListeners(nodeNum);
        }
    }

    /**
     * Finds a node by Meshtastic node id.
     *
     * @param nodeId node id in the {@code !XXXXXXXX} form
     * @return node data, or {@code null} when it is unknown
     */
    public NodeData getNodeByNodeId(String nodeId) {
        if (nodeId == null) { return null; }
        for (NodeData n : nodeDb.values()) {
            if (nodeId.equals(n.getNodeId())) { return n; }
        }
        return null;
    }

    /**
     * Registers a listener for node updates.
     *
     * @param listener function receiving the changed numeric node id
     */
    public void addNodeUpdateListener(java.util.function.IntConsumer listener) {
        nodeUpdateListeners.add(listener);
    }

    /**
     * Removes a previously registered node update listener.
     *
     * @param listener listener to remove
     */
    public void removeNodeUpdateListener(java.util.function.IntConsumer listener) {
        nodeUpdateListeners.remove(listener);
    }

    /**
     * Notifies all listeners that a node changed.
     *
     * @param nodeNum numeric id of the changed node
     */
    public void fireNodeUpdateListeners(int nodeNum) {
        for (java.util.function.IntConsumer l : nodeUpdateListeners) {
            try { l.accept(nodeNum); }
            catch (Exception e) { log.error("Exception in node update listener for !{}", Integer.toHexString(nodeNum), e); }
        }
    }

    /**
     * Returns a snapshot list of all nodes for UI rendering and serialization.
     *
     * @return node list snapshot
     */
    public List<NodeData> getAllNodes() {
        return new ArrayList<>(nodeDb.values());
    }

    /**
     * Returns the number of known nodes.
     *
     * @return node count
     */
    public int getNodeCount() {
        return nodeDb.size();
    }

    /**
     * Clears all known nodes.
     */
    public void clear() {
        nodeDb.clear();
    }

    /**
     * Returns the backing node map for legacy callers.
     *
     * @return backing {@code ConcurrentHashMap<Integer, NodeData>}
     */
    public ConcurrentHashMap<Integer, NodeData> getNodeDb() {
        return nodeDb;
    }
}
