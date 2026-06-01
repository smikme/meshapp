package com.meshtastic.client.components;

import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
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
        setPrefWidth(PANEL_WIDTH);
        setMaxWidth(PANEL_WIDTH);
        setMaxHeight(Double.MAX_VALUE);
        getStyleClass().add("modal-side-panel");

        // Shared node information component.
        ProtocolHandler handler = findActiveProtocolHandler();
        detailContent = new NodeDetailContent(state, node, handler, this::close);
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
        if (node == null) { return; }
        ModalPane modal = ModalPane.getInstance();
        if (modal == null) { return; }

        NodeDetailPanel panel = new NodeDetailPanel(state, node);
        modal.setOnHidden(panel.detailContent.getChartPanel()::unbind);
        modal.show(panel);
    }
}
