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
 * Модальная панель детальной информации о ноде.
 * Показывается в ModalPane (выезжает справа).
 * Делегирует содержимое в {@link NodeDetailContent}.
 * <p>
 * Использование: {@code NodeDetailPanel.showForNode(state, node);}
 */
public class NodeDetailPanel extends VBox {

    private static final double PANEL_WIDTH = 476;

    private final NodeDetailContent detailContent;

    public NodeDetailPanel(DeviceState state, NodeData node) {
        setPrefWidth(PANEL_WIDTH);
        setMaxWidth(PANEL_WIDTH);
        setMaxHeight(Double.MAX_VALUE);
        getStyleClass().add("modal-side-panel");

        // === Единый компонент информации о ноде ===
        ProtocolHandler handler = findActiveProtocolHandler();
        detailContent = new NodeDetailContent(state, node, handler, this::close);
        VBox.setVgrow(detailContent, Priority.ALWAYS);

        getChildren().add(detailContent);
    }

    /** Закрыть панель: отвязать телеметрию и скрыть модалку */
    public void close() {
        detailContent.getChartPanel().unbind();
        ModalPane modal = ModalPane.getInstance();
        if (modal != null) {
            modal.hide();
        }
    }

    /** Найти ProtocolHandler активного соединения */
    private static ProtocolHandler findActiveProtocolHandler() {
        ConnectionManager mgr = ConnectionManager.getInstance();
        for (ConnectionEntry entry : mgr.getEntries()) {
            if (entry.isConnected()) {
                return mgr.getProtocolHandler(entry.getId());
            }
        }
        return null;
    }

    /**
     * Показать детальную информацию о ноде в ModalPane.
     * Универсальный метод — можно вызывать из любого места приложения.
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
