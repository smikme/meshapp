package com.meshtastic.client.components;

import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Модальная панель детальной информации о ноде.
 * Показывается в ModalPane (выезжает справа) с кнопкой «Назад».
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

        // === Кнопка «Назад» ===
        Button backBtn = new Button("\u2190 Назад");
        backBtn.getStyleClass().add("node-detail-back-btn");
        backBtn.setOnAction(e -> close());

        HBox backRow = new HBox(backBtn);
        backRow.setAlignment(Pos.CENTER_LEFT);
        backRow.setPadding(new Insets(4, 0, 0, 64));

        // === Единый компонент информации о ноде ===
        detailContent = new NodeDetailContent(state, node, this::close);
        VBox.setVgrow(detailContent, Priority.ALWAYS);

        getChildren().addAll(backRow, detailContent);
    }

    /** Закрыть панель: отвязать телеметрию и скрыть модалку */
    public void close() {
        detailContent.getChartPanel().unbind();
        ModalPane modal = ModalPane.getInstance();
        if (modal != null) {
            modal.hide();
        }
    }

    /**
     * Показать детальную информацию о ноде в ModalPane.
     * Универсальный метод — можно вызывать из любого места приложения.
     */
    public static void showForNode(DeviceState state, NodeData node) {
        if (node == null) return;
        ModalPane modal = ModalPane.getInstance();
        if (modal == null) return;

        NodeDetailPanel panel = new NodeDetailPanel(state, node);
        modal.setOnHidden(panel.detailContent.getChartPanel()::unbind);
        modal.show(panel);
    }
}
