package com.meshtastic.client.utils;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.NodeCacheService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared node presentation helpers: lookup, avatar styling, and the node
 * details table used by the main node list and chat-related views.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class NodeUtils {

    private static final Logger log = LoggerFactory.getLogger(NodeUtils.class);

    private NodeUtils() {}

    /**
     * Resolves a node by numeric id, preferring the current {@link DeviceState}
     * and enriching sparse entries from the persistent cache.
     *
     * @param state current device state, or {@code null}
     * @param nodeNum numeric node id
     * @return the richest available {@link NodeData}, or {@code null} when no source has it
     */
    public static NodeData resolveNode(DeviceState state, int nodeNum) {
        NodeData node = state != null ? state.getNodeDb().get(nodeNum) : null;
        if (node != null) {
            NodeCacheService.getInstance().enrichFromCache(node);
            if (node.hasName()) {
                log.trace("resolveNode: enriched {} from cache → '{}'",
                        node.getNodeId(), node.getLongName());
            }
        }
        if (node == null) {
            node = NodeCacheService.getInstance().getByNum(nodeNum);
            if (node != null) {
                log.trace("resolveNode: !{} not in DeviceState, loaded from cache → '{}'",
                        Integer.toHexString(nodeNum), node.getLongName());
            }
        }
        return node;
    }

    /**
     * Resolves a node by Meshtastic node id, preferring the current
     * {@link DeviceState} and falling back to the cache.
     *
     * @param state current device state, or {@code null}
     * @param nodeId node id, for example {@code "!9e755af0"}
     * @return the richest available {@link NodeData}, or {@code null} when no source has it
     */
    public static NodeData resolveNode(DeviceState state, String nodeId) {
        NodeData node = state != null ? state.getNodeByNodeId(nodeId) : null;
        if (node != null) {
            NodeCacheService.getInstance().enrichFromCache(node);
            if (node.hasName()) {
                log.trace("resolveNode: enriched {} from cache → '{}'",
                        node.getNodeId(), node.getLongName());
            }
        }
        if (node == null) {
            node = NodeCacheService.getInstance().get(nodeId);
            if (node != null) {
                log.trace("resolveNode: {} not in DeviceState, loaded from cache → '{}'",
                        nodeId, node.getLongName());
            }
        }
        return node;
    }

    /** Returns the avatar color associated with a node role. */
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

    /** Calculates avatar font size from text length and circle diameter. */
    public static double avatarFontSize(int charCount, int circleSize) {
        double base = circleSize * 0.5; // One character uses half of the circle diameter.
        if (charCount <= 1) { return base; }
        if (charCount == 2) { return base * 0.85; }
        if (charCount == 3) { return base * 0.7; }
        return base * 0.6; // 4+
    }

    /**
     * Returns a stable avatar font size for chat lists and chat headers.
     * It is slightly larger than the general rule and avoids layout-bound
     * measurement so selection and focus changes do not make the text jump.
     */
    public static double chatAvatarFontSize(int charCount, int circleSize) {
        double base = circleSize * 0.5;
        if (charCount <= 1) { return base * 1.05; }
        if (charCount == 2) { return base * 0.92; }
        if (charCount == 3) { return base * 0.78; }
        return base * 0.675; // 4+
    }

    /**
     * Calculates a safe chat avatar font size without JavaFX text measurement.
     */
    public static double chatAvatarFontSize(String text, int circleSize) {
        String sanitized = UnicodeTextUtils.sanitizeForJavaFxDisplay(text);
        if (sanitized.isEmpty()) {
            return chatAvatarFontSize(1, circleSize);
        }

        int codePointCount = sanitized.codePointCount(0, sanitized.length());
        double size = chatAvatarFontSize(codePointCount, circleSize);
        double minSize = Math.max(8.0, circleSize * 0.24);
        double targetWidth = circleSize * 0.78;
        double estimatedWidth = estimateAvatarWidthUnits(sanitized) * size;

        if (estimatedWidth <= targetWidth) {
            return size;
        }

        double scaledSize = size * (targetWidth / estimatedWidth);
        return Math.max(minSize, roundDownToHalfStep(scaledSize));
    }

    /**
     * Estimates avatar font size from glyph widths without JavaFX text measurement.
     */
    public static double avatarFontSize(String text, int circleSize) {
        String sanitized = UnicodeTextUtils.sanitize(text);
        if (sanitized.isEmpty()) {
            return avatarFontSize(1, circleSize);
        }

        int codePointCount = sanitized.codePointCount(0, sanitized.length());
        double size = avatarFontSize(codePointCount, circleSize);
        double minSize = Math.max(8.0, circleSize * 0.24);
        double targetWidth = circleSize * 0.78;
        double estimatedWidth = estimateAvatarWidthUnits(sanitized) * size;

        if (estimatedWidth <= targetWidth) {
            return size;
        }

        double scaledSize = size * (targetWidth / estimatedWidth);
        return Math.max(minSize, roundDownToHalfStep(scaledSize));
    }

    private static double estimateAvatarWidthUnits(String text) {
        double units = 0.0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            units += estimateGlyphWidthUnit(codePoint);
            offset += Character.charCount(codePoint);
        }
        return units;
    }

    private static double estimateGlyphWidthUnit(int codePoint) {
        if (Character.isWhitespace(codePoint)) {
            return 0.3;
        }
        if (isAsciiNarrowGlyph(codePoint)) {
            return 0.38;
        }
        if (isAsciiWideGlyph(codePoint)) {
            return 0.78;
        }
        if (codePoint > Character.MAX_VALUE) {
            return 0.9;
        }
        if (isEastAsianGlyph(codePoint)) {
            return 0.88;
        }
        if (Character.isUpperCase(codePoint) || Character.isDigit(codePoint)) {
            return 0.64;
        }
        if (Character.isLowerCase(codePoint)) {
            return 0.58;
        }
        return 0.5;
    }

    private static boolean isAsciiNarrowGlyph(int codePoint) {
        return ".,:;!|`'ijlI1 ".indexOf(codePoint) >= 0;
    }

    private static boolean isAsciiWideGlyph(int codePoint) {
        return "MW@#%&QO08".indexOf(codePoint) >= 0;
    }

    private static boolean isEastAsianGlyph(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }

    private static double roundDownToHalfStep(double value) {
        return Math.floor(value * 2.0) / 2.0;
    }

    /**
     * Populates the node details table rows.
     * Each row is {@code String[]{emoji, label, value}}; the emoji is rendered as a PNG image.
     */
    public static void fillDetailRows(ObservableList<String[]> rows, NodeData node) {
        rows.add(new String[]{"\uD83D\uDC64", I18n.t("node.detail.name"), node.getLongName()});
        rows.add(new String[]{"\uD83C\uDFF7", I18n.t("node.detail.shortName"), node.getShortName()});
        rows.add(new String[]{"\uD83D\uDD11", I18n.t("node.detail.nodeId"), node.getNodeId()});
        rows.add(new String[]{"\u2699", I18n.t("node.detail.role"),
                node.getRole() != null ? NodeData.translateRole(node.getRole()) : null});
        rows.add(new String[]{"\uD83D\uDCDF", I18n.t("node.detail.model"), node.getHwModel()});
        rows.add(new String[]{"\uD83D\uDCAC", I18n.t("node.detail.directMessages"),
                node.getUnmessagable() == null
                        ? null
                        : node.isUnmessagable()
                        ? I18n.t("node.detail.directMessages.unavailable")
                        : I18n.t("node.detail.directMessages.available")});
        rows.add(new String[]{"\uD83D\uDCF6", I18n.t("node.detail.snr"),
                node.getSnr() != 0 ? String.valueOf(node.getSnr()) : null});
        rows.add(new String[]{"\uD83D\uDD00", I18n.t("node.detail.hops"),
                node.hasHopsAway() ? String.valueOf(node.getHopsAway()) : null});

        int level = node.getBatteryLevel();
        String battery = null;
        if (BatteryLevelEstimator.hasBatteryPercent(level, node.getVoltage())) {
            battery = BatteryLevelEstimator.effectivePercent(level, node.getVoltage()) + "%";
        }
        rows.add(new String[]{"\uD83D\uDD0B", I18n.t("node.detail.battery"), battery});

        rows.add(new String[]{"\u26A1", I18n.t("node.detail.voltage"),
                node.getVoltage() > 0 ? I18n.t("node.detail.voltageValue", node.getVoltage()) : null});
        rows.add(new String[]{"\uD83D\uDD50", I18n.t("node.detail.last"),
                node.getLastHeard() > 0 ? NodeData.formatTime(node.getLastHeard()) : null});
        rows.add(new String[]{"\uD83C\uDF0D", I18n.t("node.detail.latitude"),
                node.getLatitude() != 0 ? String.format("%.6f", node.getLatitude()) : null});
        rows.add(new String[]{"\uD83C\uDF0D", I18n.t("node.detail.longitude"),
                node.getLongitude() != 0 ? String.format("%.6f", node.getLongitude()) : null});
        rows.add(new String[]{"\uD83D\uDCD0", I18n.t("node.detail.altitude"),
                node.getAltitude() != 0 ? I18n.t("node.detail.altitudeValue", node.getAltitude()) : null});
    }

    /**
     * Creates the two-column, headerless node details table.
     * Rows follow the {@code String[]{emoji, label, value}} shape.
     */
    public static TableView<String[]> createDetailTable(ObservableList<String[]> rows) {
        TableView<String[]> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setSelectionModel(null);

        // Key column: PNG icon plus label text.
        TableColumn<String[], String> keyCol = new TableColumn<>();
        keyCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue()[1]));
        keyCol.setPrefWidth(180);
        keyCol.setSortable(false);
        keyCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String label, boolean empty) {
                super.updateItem(label, empty);
                if (empty || label == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                String[] row = getTableRow() != null ? getTableRow().getItem() : null;
                if (row == null || row.length < 2) {
                    setText(label);
                    setGraphic(null);
                    return;
                }
                String emoji = row[0];
                ImageView iv = EmojiImageCache.createImageView(emoji, 16);
                if (iv != null) {
                    HBox box = new HBox(6, iv, new Label(label));
                    box.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(box);
                    setText(null);
                } else {
                    // Fallback to plain text when the icon cannot be rendered.
                    setText(label);
                    setGraphic(null);
                }
            }
        });

        // Value column.
        TableColumn<String[], String> valCol = new TableColumn<>();
        valCol.setCellValueFactory(cd -> {
            String v = cd.getValue()[2];
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
                    MenuItem copyItem = new MenuItem(I18n.t("common.copy"));
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

        // Hide headers, remove the frame, and size the table to its content.
        table.getStyleClass().addAll("no-header", "node-detail-table");
        int rowHeight = 28;
        table.setFixedCellSize(rowHeight);
        table.setPrefHeight(rows.size() * rowHeight + 2);
        table.setMinHeight(table.getPrefHeight());
        table.setMaxHeight(table.getPrefHeight());

        return table;
    }
}
