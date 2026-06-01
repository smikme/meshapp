package com.meshtastic.client.system;

import atlantafx.base.theme.Styles;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.service.MessageDbService;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.geometry.Side;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Нижняя панель вкладок активных подключений.
 * <p>
 * Отображается только когда одновременно активно больше одного подключения.
 * Каждая вкладка соответствует одному {@link ConnectionEntry}: показывает имя
 * локальной ноды, её nodeId, счётчик непрочитанных сообщений и кнопку закрытия.
 * Закрытие вкладки инициирует отключение соответствующего подключения, а выбор
 * вкладки переключает общий UI-контекст приложения на эту ноду.
 * <p>
 * Компонент намеренно обновляет существующие {@link Tab} и дочерние {@link Label}
 * точечно, чтобы входящие сообщения и изменение счётчиков не пересоздавали всю
 * панель вкладок и не вызывали визуального мерцания.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ConnectionTabsPane extends TabPane {

    private static final String WINDOWS_HIT_TEST_BACKGROUND = "-fx-background-color: rgba(0,0,0,0.004);";

    private final Runnable connectionListener = () -> Platform.runLater(this::refreshTabs);
    private final Map<DeviceState, Runnable> messageListeners = new HashMap<>();
    private final Map<String, Tab> tabsByConnectionId = new HashMap<>();
    private final Map<String, TabHeader> tabHeadersByConnectionId = new HashMap<>();
    private boolean refreshing;

    private record TabHeader(HBox container,
                             Label nameLabel,
                             Label idLabel,
                             Label unreadBadge) {}

    /**
     * Создаёт панель вкладок подключений и подписывает её на изменения
     * {@link ConnectionManager}.
     */
    public ConnectionTabsPane() {
        getStyleClass().addAll("connection-tabs-pane", Styles.DENSE);
        if (!AppPreferences.isDisableEffectsEffective()) {
            getStyleClass().add("connection-tabs-pane-transparent");
            if (OsDetect.isWindows()) {
                setStyle(WINDOWS_HIT_TEST_BACKGROUND);
            }
        }
        setSide(Side.BOTTOM);
        setTabClosingPolicy(TabClosingPolicy.ALL_TABS);
        setFocusTraversable(false);
        setVisible(false);
        setManaged(false);

        getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (refreshing || newTab == null) {
                return;
            }
            Object userData = newTab.getUserData();
            if (userData instanceof String connectionId) {
                FormManager.switchToConnection(connectionId);
            }
        });

        ConnectionManager.getInstance().addListener(connectionListener);
        refreshTabs();
    }

    private void refreshTabs() {
        ConnectionManager manager = ConnectionManager.getInstance();
        List<ConnectionEntry> activeEntries = manager.getActiveConnectionEntries();
        boolean shouldShow = activeEntries.size() > 1;
        setVisible(shouldShow);
        setManaged(shouldShow);

        if (!shouldShow) {
            clearMessageListeners();
            getTabs().clear();
            tabsByConnectionId.clear();
            tabHeadersByConnectionId.clear();
            return;
        }

        syncMessageListeners(manager, activeEntries);

        refreshing = true;
        try {
            syncTabs(manager, activeEntries);
            selectCurrentTab(manager.getSelectedConnectionId());
        } finally {
            refreshing = false;
        }
    }

    private void syncTabs(ConnectionManager manager, List<ConnectionEntry> activeEntries) {
        Set<String> activeIds = new HashSet<>();
        for (ConnectionEntry entry : activeEntries) {
            activeIds.add(entry.getId());
        }

        getTabs().removeIf(tab -> {
            Object userData = tab.getUserData();
            boolean remove = !(userData instanceof String connectionId) || !activeIds.contains(connectionId);
            if (remove && userData instanceof String connectionId) {
                tabsByConnectionId.remove(connectionId);
                tabHeadersByConnectionId.remove(connectionId);
            }
            return remove;
        });

        for (int i = 0; i < activeEntries.size(); i++) {
            ConnectionEntry entry = activeEntries.get(i);
            Tab tab = tabsByConnectionId.get(entry.getId());
            if (tab == null) {
                tab = createConnectionTab(manager, entry);
                tabsByConnectionId.put(entry.getId(), tab);
                getTabs().add(i, tab);
            } else {
                updateConnectionTab(manager, entry);
                moveTabIfNeeded(tab, i);
            }
        }
    }

    private Tab createConnectionTab(ConnectionManager manager, ConnectionEntry entry) {
        Tab tab = new Tab();
        tab.setUserData(entry.getId());
        tab.setClosable(true);
        TabHeader header = createTabHeader();
        tabHeadersByConnectionId.put(entry.getId(), header);
        tab.setGraphic(header.container());
        tab.setContent(new Region());
        tab.setOnCloseRequest(event -> {
            event.consume();
            disconnectFromTab(entry);
        });
        updateConnectionTab(manager, entry);
        return tab;
    }

    private TabHeader createTabHeader() {
        Label nameLabel = new Label();
        nameLabel.getStyleClass().add("connection-tab-name");
        nameLabel.setMaxWidth(180);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        Label idLabel = new Label();
        idLabel.getStyleClass().add("connection-tab-id");
        idLabel.setMaxWidth(140);
        idLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        Label unreadBadge = new Label();
        unreadBadge.getStyleClass().add("connection-tab-unread-badge");

        HBox header = new HBox(6, nameLabel, idLabel, unreadBadge);
        header.getStyleClass().add("connection-tab-header");
        return new TabHeader(header, nameLabel, idLabel, unreadBadge);
    }

    private void updateConnectionTab(ConnectionManager manager, ConnectionEntry entry) {
        TabHeader header = tabHeadersByConnectionId.get(entry.getId());
        if (header == null) {
            return;
        }

        setTextIfChanged(header.nameLabel(), resolveNodeName(manager, entry));
        setTextIfChanged(header.idLabel(), resolveNodeId(manager, entry));

        int unreadCount = unreadCount(manager, entry);
        setTextIfChanged(header.unreadBadge(), formatUnreadCount(unreadCount));
        setVisibleAndManaged(header.unreadBadge(), unreadCount > 0);
    }

    private void moveTabIfNeeded(Tab tab, int index) {
        int currentIndex = getTabs().indexOf(tab);
        if (currentIndex == index || currentIndex < 0) {
            return;
        }
        getTabs().remove(currentIndex);
        getTabs().add(index, tab);
    }

    private void selectCurrentTab(String selectedId) {
        if (selectedId == null) {
            return;
        }
        Tab current = getSelectionModel().getSelectedItem();
        if (current != null && Objects.equals(current.getUserData(), selectedId)) {
            return;
        }
        Tab selectedTab = tabsByConnectionId.get(selectedId);
        if (selectedTab != null) {
            getSelectionModel().select(selectedTab);
        }
    }

    private static void setTextIfChanged(Label label, String value) {
        if (!Objects.equals(label.getText(), value)) {
            label.setText(value);
        }
    }

    private static void setVisibleAndManaged(Label label, boolean visible) {
        if (label.isVisible() != visible) {
            label.setVisible(visible);
        }
        if (label.isManaged() != visible) {
            label.setManaged(visible);
        }
    }

    private void disconnectFromTab(ConnectionEntry entry) {
        Thread worker = new Thread(() -> {
            try {
                ConnectionManager.getInstance().disconnect(entry.getId());
                Platform.runLater(() ->
                        Toast.show(Toast.Type.SUCCESS, I18n.t("connection.toast.disconnected", entry.getName())));
            } catch (RuntimeException ex) {
                Platform.runLater(() ->
                        Toast.show(Toast.Type.ERROR, I18n.t("connection.toast.disconnectError", ex.getMessage())));
            }
        }, "disconnect-tab-" + entry.getId());
        worker.setDaemon(true);
        worker.start();
    }

    private void syncMessageListeners(ConnectionManager manager, List<ConnectionEntry> activeEntries) {
        Set<DeviceState> neededStates = new HashSet<>();
        for (ConnectionEntry entry : activeEntries) {
            DeviceState state = manager.getDeviceState(entry.getId());
            if (state == null) {
                continue;
            }
            neededStates.add(state);
            if (!messageListeners.containsKey(state)) {
                Runnable listener = () -> Platform.runLater(this::refreshTabs);
                state.addMessageListener(listener);
                messageListeners.put(state, listener);
            }
        }

        for (DeviceState state : Set.copyOf(messageListeners.keySet())) {
            if (!neededStates.contains(state)) {
                Runnable listener = messageListeners.remove(state);
                if (listener != null) {
                    state.removeMessageListener(listener);
                }
            }
        }
    }

    private void clearMessageListeners() {
        for (Map.Entry<DeviceState, Runnable> entry : messageListeners.entrySet()) {
            entry.getKey().removeMessageListener(entry.getValue());
        }
        messageListeners.clear();
    }

    private static int unreadCount(ConnectionManager manager, ConnectionEntry entry) {
        String ownerNodeId = manager.getOwnerNodeId(entry.getId());
        if (ownerNodeId == null || ownerNodeId.isBlank() || "?".equals(ownerNodeId)) {
            return 0;
        }
        return MessageDbService.getInstance().getTotalUnreadCount(ownerNodeId);
    }

    private static String resolveNodeName(ConnectionManager manager, ConnectionEntry entry) {
        NodeData node = findLocalNode(manager, entry);
        return firstText(
                node != null ? node.getLongName() : null,
                node != null ? node.getShortName() : null,
                entry.getName(),
                "?"
        );
    }

    private static String resolveNodeId(ConnectionManager manager, ConnectionEntry entry) {
        NodeData node = findLocalNode(manager, entry);
        return firstText(
                node != null ? node.getNodeId() : null,
                manager.getOwnerNodeId(entry.getId()),
                entry.getNodeId(),
                entry.getId()
        );
    }

    private static NodeData findLocalNode(ConnectionManager manager, ConnectionEntry entry) {
        DeviceState state = manager.getDeviceState(entry.getId());
        if (state == null || state.getMyNodeNum() == 0) {
            return null;
        }
        return state.getNodeDb().get(state.getMyNodeNum());
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"?".equals(value.trim())) {
                return value.trim();
            }
        }
        return "?";
    }

    private static String formatUnreadCount(int count) {
        return count > 99 ? "99+" : String.valueOf(count);
    }
}
