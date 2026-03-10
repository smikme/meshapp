package com.meshtastic.client.utils;

import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.NodeCacheService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Общие утилиты для работы с нодами — аватары, цвета, таблица деталей.
 * Используется из FormNodes, FormChat, NodeDetailPanel.
 */
public final class NodeUtils {

    private static final Logger log = LoggerFactory.getLogger(NodeUtils.class);

    private NodeUtils() {}

    /**
     * Разрешить ноду по номеру: DeviceState → обогащение из кэша если bare → fallback на кэш.
     *
     * @param state    состояние устройства (может быть {@code null})
     * @param nodeNum  номер ноды
     * @return NodeData с максимально полными данными, или {@code null} если нигде не найдена
     */
    public static NodeData resolveNode(DeviceState state, int nodeNum) {
        NodeData node = state != null ? state.getNodeDb().get(nodeNum) : null;
        if (node != null && !node.hasName()) {
            NodeCacheService.getInstance().enrichFromCache(node);
            if (node.hasName()) {
                log.debug("resolveNode: enriched {} from cache → '{}'",
                        node.getNodeId(), node.getLongName());
            }
        }
        if (node == null) {
            node = NodeCacheService.getInstance().getByNum(nodeNum);
            if (node != null) {
                log.debug("resolveNode: !{} not in DeviceState, loaded from cache → '{}'",
                        Integer.toHexString(nodeNum), node.getLongName());
            }
        }
        return node;
    }

    /**
     * Разрешить ноду по nodeId: DeviceState → обогащение из кэша если bare → fallback на кэш.
     *
     * @param state   состояние устройства (может быть {@code null})
     * @param nodeId  идентификатор ноды (например {@code "!9e755af0"})
     * @return NodeData с максимально полными данными, или {@code null} если нигде не найдена
     */
    public static NodeData resolveNode(DeviceState state, String nodeId) {
        NodeData node = state != null ? state.getNodeByNodeId(nodeId) : null;
        if (node != null && !node.hasName()) {
            NodeCacheService.getInstance().enrichFromCache(node);
            if (node.hasName()) {
                log.debug("resolveNode: enriched {} from cache → '{}'",
                        node.getNodeId(), node.getLongName());
            }
        }
        if (node == null) {
            node = NodeCacheService.getInstance().get(nodeId);
            if (node != null) {
                log.debug("resolveNode: {} not in DeviceState, loaded from cache → '{}'",
                        nodeId, node.getLongName());
            }
        }
        return node;
    }

    /** Цвет аватара по роли ноды */
    public static String roleColor(String role) {
        if (role == null) { return "#5B8DEF"; }
        return switch (role) {
            case "CLIENT"         -> "#5B8DEF";
            case "CLIENT_MUTE"    -> "#8E99A4";
            case "CLIENT_HIDDEN"  -> "#6C7A89";
            case "ROUTER"         -> "#E74C3C";
            case "ROUTER_CLIENT"  -> "#E57C23";
            case "ROUTER_LATE"    -> "#C0392B";
            case "REPEATER"       -> "#9B59B6";
            case "TRACKER"        -> "#1EA97C";
            case "SENSOR"         -> "#1ABC9C";
            case "TAK"            -> "#3498DB";
            case "TAK_TRACKER"    -> "#2980B9";
            case "LOST_AND_FOUND" -> "#F39C12";
            default               -> "#5B8DEF";
        };
    }

    /** Размер шрифта аватара в зависимости от количества символов и размера круга */
    public static double avatarFontSize(int charCount, int circleSize) {
        double base = circleSize * 0.5; // 1 символ — 50% от размера круга
        if (charCount <= 1) { return base; }
        if (charCount == 2) { return base * 0.85; }
        if (charCount == 3) { return base * 0.7; }
        return base * 0.6; // 4+
    }

    /** Заполнить строки таблицы деталей ноды (13 ключ-значение) */
    public static void fillDetailRows(ObservableList<String[]> rows, NodeData node) {
        rows.add(new String[]{"\uD83D\uDC64  Имя", node.getLongName()});
        rows.add(new String[]{"\uD83C\uDFF7  Короткое имя", node.getShortName()});
        rows.add(new String[]{"\uD83D\uDD11  ID ноды", node.getNodeId()});
        rows.add(new String[]{"\u2699  Роль", node.getRole() != null ? NodeData.translateRole(node.getRole()) : null});
        rows.add(new String[]{"\uD83D\uDCDF  Модель", node.getHwModel()});
        rows.add(new String[]{"\uD83D\uDCF6  SNR", node.getSnr() != 0 ? String.valueOf(node.getSnr()) : null});
        rows.add(new String[]{"\uD83D\uDD00  Хопы", String.valueOf(node.getHopsAway())});

        int level = node.getBatteryLevel();
        String battery = null;
        if (level > 0 && level <= 100) { battery = level + "%"; }
        else if (level == 101) { battery = "Внешнее питание"; }
        rows.add(new String[]{"\uD83D\uDD0B  Батарея", battery});

        rows.add(new String[]{"\u26A1  Напряжение", node.getVoltage() > 0 ? String.format("%.2f В", node.getVoltage()) : null});
        rows.add(new String[]{"\uD83D\uDD50  Последний", node.getLastHeard() > 0 ? NodeData.formatTime(node.getLastHeard()) : null});
        rows.add(new String[]{"\uD83C\uDF0D  Широта", node.getLatitude() != 0 ? String.format("%.6f", node.getLatitude()) : null});
        rows.add(new String[]{"\uD83C\uDF0D  Долгота", node.getLongitude() != 0 ? String.format("%.6f", node.getLongitude()) : null});
        rows.add(new String[]{"\uD83D\uDCD0  Высота", node.getAltitude() != 0 ? node.getAltitude() + " м" : null});
    }

    /** Создать TableView для деталей ноды (2 колонки, без заголовков) */
    public static TableView<String[]> createDetailTable(ObservableList<String[]> rows) {
        TableView<String[]> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setSelectionModel(null);

        TableColumn<String[], String> keyCol = new TableColumn<>();
        keyCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue()[0]));
        keyCol.setPrefWidth(180);
        keyCol.setSortable(false);

        TableColumn<String[], String> valCol = new TableColumn<>();
        valCol.setCellValueFactory(cd -> {
            String v = cd.getValue()[1];
            return new SimpleStringProperty(v != null && !v.isEmpty() ? v : "\u2014");
        });
        valCol.setSortable(false);
        valCol.setStyle("-fx-font-weight: bold;");
        valCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null || "\u2014".equals(value)) {
                    setText(empty ? null : "\u2014");
                    setStyle("-fx-font-weight: bold;");
                    setContextMenu(null);
                } else {
                    setText(value);
                    setStyle("-fx-font-weight: bold;");
                    MenuItem copyItem = new MenuItem("Копировать");
                    copyItem.setOnAction(e -> {
                        ClipboardContent cc = new ClipboardContent();
                        cc.putString(value);
                        Clipboard.getSystemClipboard().setContent(cc);
                    });
                    setContextMenu(new ContextMenu(copyItem));
                }
            }
        });

        table.getColumns().add(keyCol);
        table.getColumns().add(valCol);

        // Скрыть заголовки колонок, убрать рамку, высота по содержимому
        table.getStyleClass().addAll("no-header", "node-detail-table");
        int rowHeight = 28;
        table.setFixedCellSize(rowHeight);
        table.setPrefHeight(rows.size() * rowHeight + 2);
        table.setMinHeight(table.getPrefHeight());
        table.setMaxHeight(table.getPrefHeight());

        return table;
    }
}
