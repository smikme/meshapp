package com.meshtastic.client.components;

import com.meshtastic.client.forms.FormChat;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.FavoriteNodeService;
import com.meshtastic.client.service.IgnoredNodeService;
import com.meshtastic.client.service.MessageService;
import com.meshtastic.client.service.NodeCacheService;
import com.meshtastic.client.system.AllForms;
import com.meshtastic.client.system.FormManager;
import com.meshtastic.client.utils.NodeUtils;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Unified node details component with a vertical action toolbar on the left and
 * content on the right: header, details table, telemetry chart, and traces.
 * <p>
 * The component is used both by {@link NodeDetailPanel} as a side modal and by
 * {@code FormNodes} as an embedded split-pane detail view.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class NodeDetailContent extends HBox {

    private final TelemetryChartPanel chartPanel;
    private final ObservableList<String[]> tableData;
    private final int nodeNum;     // Used by protocol operations such as requestNodeInfo and removeNode.
    private final String nodeId;   // Used by identity-based actions such as openDirectChat and deleteNode.
    private final ProtocolHandler protocolHandler;
    private final DeviceState state;

    /**
     * @param state current device state used for telemetry, or {@code null}
     * @param node node to display
     * @param handler protocol handler for radio requests, or {@code null}
     * @param onBeforeNavigate callback invoked before navigating to a direct chat, or {@code null}
     */
    public NodeDetailContent(DeviceState state, NodeData node, ProtocolHandler handler, Runnable onBeforeNavigate) {
        this.nodeNum = node.getNodeNum();
        this.nodeId = node.getNodeId();
        this.protocolHandler = handler;
        this.state = state;

        String rawDisplayName = node.getLongName() != null && !node.getLongName().isEmpty()
                ? node.getLongName() : node.getNodeId() != null ? node.getNodeId() : "?";
        final String displayName = UnicodeTextUtils.sanitizeForJavaFxDisplay(rawDisplayName);

        // Left vertical toolbar, matching DrawerPane structure and width.
        StackPane toolbarPane = new StackPane();
        toolbarPane.setPrefWidth(56);
        toolbarPane.setMinWidth(56);
        toolbarPane.setMaxWidth(56);

        ToolBar actionToolbar = new ToolBar();
        actionToolbar.setOrientation(Orientation.VERTICAL);
        actionToolbar.getStyleClass().add("drawer-toolbar");

        SVGPath privateChatIcon = SvgIconLoader.load("/drawer/icon/chat-private.svg", 22);
        Button privateChatBtn = new Button();
        privateChatBtn.setGraphic(privateChatIcon);
        privateChatBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        privateChatBtn.getStyleClass().add("drawer-toolbar-button");
        boolean hasPublicKey = node.getPublicKey() != null && node.getPublicKey().length > 0;
        boolean canDirectMessage = hasPublicKey && !node.isUnmessagable();
        String privateChatTooltip = node.isUnmessagable()
                ? I18n.t("node.action.privateChatUnavailable")
                : hasPublicKey
                ? I18n.t("node.action.privateChat")
                : I18n.t("node.action.privateChatNoPublicKey");
        privateChatBtn.setTooltip(new Tooltip(privateChatTooltip));
        privateChatBtn.setDisable(!canDirectMessage);

        privateChatBtn.setOnAction(e -> {
            if (onBeforeNavigate != null) {
                onBeforeNavigate.run();
            }
            FormChat formChat = (FormChat) AllForms.getForm(FormChat.class);
            FormManager.showForm(formChat);
            formChat.openDirectChat(node.getNodeId(), node);
        });

        // Live traceroute to the selected node.
        SVGPath tracerouteIcon = SvgIconLoader.load("/icons/map-traces.svg", 22);
        Button tracerouteBtn = new Button();
        tracerouteBtn.setGraphic(tracerouteIcon);
        tracerouteBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        tracerouteBtn.getStyleClass().add("drawer-toolbar-button");
        tracerouteBtn.setTooltip(new Tooltip(I18n.t("node.action.traceroute")));
        tracerouteBtn.setDisable(handler == null || state == null || nodeNum == 0);
        tracerouteBtn.setOnAction(e ->
                NodeTracerouteWindow.showWindow(this.state, node, protocolHandler));

        SVGPath remoteAdminIcon = SvgIconLoader.load("/drawer/icon/setting.svg", 22);
        Button remoteAdminBtn = new Button();
        remoteAdminBtn.setGraphic(remoteAdminIcon);
        remoteAdminBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        remoteAdminBtn.getStyleClass().add("drawer-toolbar-button");
        boolean isLocalNode = state != null && nodeNum == state.getMyNodeNum();
        boolean canRemoteAdmin = handler != null
                && state != null
                && nodeNum != 0
                && !isLocalNode
                && hasPublicKey;
        String remoteAdminTooltip = !hasPublicKey
                ? I18n.t("node.action.remoteAdminNoPublicKey")
                : I18n.t("node.action.remoteAdmin");
        remoteAdminBtn.setTooltip(new Tooltip(remoteAdminTooltip));
        remoteAdminBtn.setDisable(!canRemoteAdmin);
        remoteAdminBtn.setOnAction(e ->
                RemoteAdminPanel.showForNode(this.state, node, protocolHandler));

        // Refresh node information over radio and exchange User payloads.
        SVGPath refreshIcon = SvgIconLoader.load("/drawer/icon/refresh-node.svg", 22);
        Button refreshBtn = new Button();
        refreshBtn.setGraphic(refreshIcon);
        refreshBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        refreshBtn.getStyleClass().add("drawer-toolbar-button");
        refreshBtn.setTooltip(new Tooltip(I18n.t("node.action.refresh")));
        refreshBtn.setDisable(handler == null || state == null);
        refreshBtn.setOnAction(e -> {
            if (protocolHandler != null && this.state != null) {
                ModalPane.showConfirm(
                        I18n.t("node.confirm.refresh.title"),
                        I18n.t("node.confirm.refresh.message", displayName),
                        confirmed -> {
                            if (confirmed) {
                                MessageService.exchangeNodeUserInfo(protocolHandler, this.state, nodeNum);
                            }
                        }
                );
            }
        });

        // Delete the node from the current state and cache.
        SVGPath deleteIcon = SvgIconLoader.load("/drawer/icon/delete-node.svg", 22);
        Button deleteBtn = new Button();
        deleteBtn.setGraphic(deleteIcon);
        deleteBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        deleteBtn.getStyleClass().add("drawer-toolbar-button");
        deleteBtn.setTooltip(new Tooltip(I18n.t("node.action.delete")));
        deleteBtn.setDisable(state == null);
        deleteBtn.setOnAction(e -> {
            if (this.state != null) {
                ModalPane.showConfirm(
                        I18n.t("node.confirm.delete.title"),
                        I18n.t("node.confirm.delete.message", displayName),
                        confirmed -> {
                            if (confirmed) {
                                String ownerNodeId = this.state.getOwnerNodeId();
                                this.state.removeNode(nodeNum);
                                NodeCacheService.getInstance().deleteNode(nodeId, ownerNodeId);
                                if (onBeforeNavigate != null) {
                                    onBeforeNavigate.run();
                                }
                            }
                        }
                );
            }
        });

        // Toggle favorite state.
        SVGPath favoriteIcon = SvgIconLoader.load("/icons/favorite.svg", 22);
        Button favoriteBtn = new Button();
        favoriteBtn.setGraphic(favoriteIcon);
        favoriteBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        favoriteBtn.getStyleClass().add("drawer-toolbar-button");

        FavoriteNodeService favService = FavoriteNodeService.getInstance();
        String ownerNodeId = currentOwnerNodeId();
        boolean initFav = !ownerNodeId.isBlank() && favService.isFavorite(nodeId, ownerNodeId);
        if (initFav) {
            favoriteBtn.getStyleClass().add("favorite-btn-active");
        }
        favoriteBtn.setTooltip(new Tooltip(favoriteTooltip(initFav)));

        favoriteBtn.setOnAction(e -> {
            String currentOwnerNodeId = currentOwnerNodeId();
            if (currentOwnerNodeId.isBlank()) { return; }
            boolean nowFav = favService.toggleFavorite(nodeId, currentOwnerNodeId);
            if (nowFav) {
                favoriteBtn.getStyleClass().add("favorite-btn-active");
            } else {
                favoriteBtn.getStyleClass().remove("favorite-btn-active");
            }
            favoriteBtn.getTooltip().setText(favoriteTooltip(nowFav));
        });

        // Toggle ignored state.
        SVGPath ignoredIcon = SvgIconLoader.load("/icons/eye-off.svg", 22);
        Button ignoredBtn = new Button();
        ignoredBtn.setGraphic(ignoredIcon);
        ignoredBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        ignoredBtn.getStyleClass().add("drawer-toolbar-button");

        IgnoredNodeService ignService = IgnoredNodeService.getInstance();
        boolean initIgn = !ownerNodeId.isBlank() && ignService.isIgnored(nodeId, ownerNodeId);
        if (initIgn) {
            ignoredBtn.getStyleClass().add("ignored-btn-active");
        }
        ignoredBtn.setTooltip(new Tooltip(ignoredTooltip(initIgn)));

        ignoredBtn.setOnAction(e -> {
            String currentOwnerNodeId = currentOwnerNodeId();
            if (currentOwnerNodeId.isBlank()) { return; }
            boolean nowIgn = ignService.toggleIgnored(nodeId, currentOwnerNodeId);
            if (nowIgn) {
                ignoredBtn.getStyleClass().add("ignored-btn-active");
            } else {
                ignoredBtn.getStyleClass().remove("ignored-btn-active");
            }
            ignoredBtn.getTooltip().setText(ignoredTooltip(nowIgn));
        });

        actionToolbar.getItems().addAll(
                privateChatBtn,
                tracerouteBtn,
                favoriteBtn,
                ignoredBtn,
                refreshBtn,
                remoteAdminBtn,
                deleteBtn);

        VBox toolbarContainer = new VBox(actionToolbar);
        toolbarContainer.setAlignment(Pos.TOP_CENTER);
        toolbarContainer.setPadding(new Insets(0, 0, 8, 0));

        toolbarPane.getChildren().add(toolbarContainer);

        // Header: large avatar, display name, and node id.
        String avatarText;
        if (node.getShortName() != null && !node.getShortName().isEmpty()) {
            avatarText = UnicodeTextUtils.sanitize(node.getShortName()).toUpperCase(java.util.Locale.ROOT);
        } else {
            avatarText = UnicodeTextUtils.prefixByCodePoints(displayName, 4).toUpperCase(java.util.Locale.ROOT);
        }
        String safeAvatarText = UnicodeTextUtils.sanitizeForJavaFxDisplay(avatarText);
        String color = NodeUtils.roleColor(node.getRole());

        StackPane bigAvatar = new StackPane();
        bigAvatar.setMinSize(56, 56);
        bigAvatar.setMaxSize(56, 56);
        bigAvatar.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 28;");
        Label avatarLabel = new Label(safeAvatarText);
        avatarLabel.setFont(Font.font("Roboto", FontWeight.BOLD,
                NodeUtils.avatarFontSize(safeAvatarText, 56)));
        avatarLabel.setStyle("-fx-text-fill: white; -fx-padding: 0;");
        bigAvatar.getChildren().add(avatarLabel);

        Label nameLabel = new Label(displayName);
        nameLabel.getStyleClass().add("node-detail-name-label");

        Label nodeIdLabel = new Label(UnicodeTextUtils.sanitizeForJavaFxDisplay(
                node.getNodeId() != null ? node.getNodeId() : ""));
        nodeIdLabel.setStyle("-fx-opacity: 0.6;");

        VBox headerText = new VBox(2, nameLabel, nodeIdLabel);
        headerText.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(12, bigAvatar, headerText);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 10, 0));

        Separator sep = new Separator();

        // Details table.
        tableData = FXCollections.observableArrayList();
        NodeUtils.fillDetailRows(tableData, node);
        TableView<String[]> table = NodeUtils.createDetailTable(tableData);

        // Telemetry chart.
        chartPanel = new TelemetryChartPanel(true);
        VBox.setVgrow(chartPanel, Priority.ALWAYS);
        if (state != null) {
            chartPanel.bind(state, node.getNodeId());
        }

        VBox infoPane = new VBox(10, table, chartPanel);
        VBox.setVgrow(infoPane, Priority.ALWAYS);

        NodeTracerouteHistoryPanel tracesPanel = new NodeTracerouteHistoryPanel(this.state, node, onBeforeNavigate);
        Tab infoTab = new Tab(I18n.t("node.tab.info"), infoPane);
        Tab tracesTab = new Tab(I18n.t("node.tab.traces"), tracesPanel);
        TabPane tabPane = new TabPane(infoTab, tracesTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, selectedTab) -> {
            if (selectedTab == tracesTab) {
                tracesPanel.refresh();
            }
        });
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        VBox contentPane = new VBox(10, header, sep, tabPane);
        contentPane.setPadding(new Insets(4, 8, 8, 0));
        HBox.setHgrow(contentPane, Priority.ALWAYS);

        getChildren().addAll(toolbarPane, contentPane);
    }

    /** Constructor without navigation callback, used by inline FormNodes content. */
    public NodeDetailContent(DeviceState state, NodeData node, ProtocolHandler handler) {
        this(state, node, handler, null);
    }

    /** Refreshes only table data without rebuilding the UI. */
    public void updateTableData(NodeData node) {
        ObservableList<String[]> refreshedRows = FXCollections.observableArrayList();
        NodeUtils.fillDetailRows(refreshedRows, node);
        tableData.setAll(refreshedRows);
    }

    public TelemetryChartPanel getChartPanel() {
        return chartPanel;
    }

    public int getNodeNum() {
        return nodeNum;
    }

    private static String favoriteTooltip(boolean favorite) {
        return I18n.t(favorite ? "node.menu.removeFavorite" : "node.menu.addFavorite");
    }

    private static String ignoredTooltip(boolean ignored) {
        return I18n.t(ignored ? "node.menu.removeIgnored" : "node.menu.addIgnored");
    }

    private String currentOwnerNodeId() {
        return state != null && state.getOwnerNodeId() != null ? state.getOwnerNodeId() : "";
    }
}
