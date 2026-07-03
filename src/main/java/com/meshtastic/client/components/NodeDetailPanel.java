package com.meshtastic.client.components;

import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.protocol.rpc.RemoteRpcState;
import com.meshtastic.client.service.ConnectionManager;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Side modal with detailed information about a node.
 * The panel is hosted by {@code ModalPane} and delegates its content to
 * {@link NodeDetailContent}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class NodeDetailPanel extends VBox {

    private static final double PANEL_WIDTH = 476;

    private final NodeDetailContent detailContent;

    public NodeDetailPanel(DeviceState state, NodeData node) {
        this(state, node, null);
    }

    public NodeDetailPanel(DeviceState state, NodeData node, NodeDetailContent.ActionDelegate actionDelegate) {
        this(state, node, actionDelegate, null);
    }

    public NodeDetailPanel(DeviceState state,
                           NodeData node,
                           NodeDetailContent.ActionDelegate actionDelegate,
                           RemoteRpcState remoteRpcState) {
        setPrefWidth(PANEL_WIDTH);
        setMaxWidth(PANEL_WIDTH);
        setMaxHeight(Double.MAX_VALUE);
        getStyleClass().add("modal-side-panel");

        // Shared node information component.
        ProtocolHandler handler = actionDelegate == null ? findActiveProtocolHandler() : null;
        detailContent = new NodeDetailContent(state, node, handler, this::close, actionDelegate, remoteRpcState);
        VBox.setVgrow(detailContent, Priority.ALWAYS);

        getChildren().add(detailContent);
    }

    /** Closes the panel by unbinding telemetry and hiding the modal. */
    public void close() {
        detailContent.getChartPanel().unbind();
        ModalPane modal = ModalPane.getInstance();
        if (modal != null) {
            modal.hide();
        }
    }

    /** Finds the ProtocolHandler for the selected connection. */
    private static ProtocolHandler findActiveProtocolHandler() {
        ConnectionManager mgr = ConnectionManager.getInstance();
        ConnectionEntry entry = mgr.getSelectedConnectionEntry();
        return entry != null && entry.isConnected() ? mgr.getProtocolHandler(entry.getId()) : null;
    }

    /**
     * Shows node details in the ModalPane.
     * This entry point can be called from anywhere in the application.
     */
    public static void showForNode(DeviceState state, NodeData node) {
        showForNode(state, node, null);
    }

    public static void showForNode(DeviceState state,
                                   NodeData node,
                                   NodeDetailContent.ActionDelegate actionDelegate) {
        if (node == null) { return; }
        ModalPane modal = ModalPane.getInstance();
        if (modal == null) { return; }

        NodeDetailPanel panel = new NodeDetailPanel(state, node, actionDelegate);
        modal.show(panel);
        modal.setOnHidden(panel.detailContent.getChartPanel()::unbind);
    }

    public static void showForRemoteNode(RemoteRpcState remoteRpcState, NodeData node) {
        if (node == null) { return; }
        ModalPane modal = ModalPane.getInstance();
        if (modal == null) { return; }

        NodeDetailPanel panel = new NodeDetailPanel(null, node, null, remoteRpcState);
        modal.show(panel);
        modal.setOnHidden(panel.detailContent.getChartPanel()::unbind);
    }
}
