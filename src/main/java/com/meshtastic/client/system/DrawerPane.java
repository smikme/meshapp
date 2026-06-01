package com.meshtastic.client.system;

import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.forms.FormChat;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.menu.MyDrawerBuilder;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.SvgIconLoader;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class DrawerPane extends StackPane {

    public static final double TOOLBAR_WIDTH = 56;
    private static final String WINDOWS_HIT_TEST_BACKGROUND = "-fx-background-color: rgba(0,0,0,0.004);";
    private static final String ORIGINAL_TOOLTIP_KEY = "drawer.originalTooltip";
    private static final String NOTIFICATION_ON_ICON = "/drawer/icon/bell.svg";
    private static final String NOTIFICATION_OFF_ICON = "/drawer/icon/bell-off.svg";

    private final ToolBar toolBar;
    private final Button themeButton;
    private final Button notifButton;
    private final Set<Class<?>> navigationBlockedItemClasses = new HashSet<>();
    private Class<?> selectedItemClass;
    private Circle chatBadgeDot;

    public DrawerPane() {
        getStyleClass().add("drawer-pane");
        setPrefWidth(TOOLBAR_WIDTH);
        setMinWidth(TOOLBAR_WIDTH);
        setMaxWidth(TOOLBAR_WIDTH);
        if (OsDetect.isWindows() && !AppPreferences.isDisableEffectsEffective()) {
            setStyle(WINDOWS_HIT_TEST_BACKGROUND);
        }

        toolBar = new ToolBar();
        toolBar.setOrientation(Orientation.VERTICAL);
        toolBar.getStyleClass().add("drawer-toolbar");

        // Notification and theme buttons are pinned to the bottom.
        notifButton = createNotificationButton();
        themeButton = createThemeButton();

        StackPane menuContent = new StackPane(toolBar);
        menuContent.getStyleClass().add("drawer-toolbar-scroll-content");
        menuContent.setAlignment(Pos.TOP_CENTER);

        ScrollPane menuScroll = new ScrollPane(menuContent);
        menuScroll.getStyleClass().add("drawer-toolbar-scroll");
        menuScroll.setFitToWidth(true);
        menuScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        menuScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        menuScroll.setPannable(true);
        VBox.setVgrow(menuScroll, Priority.ALWAYS);

        VBox container = new VBox(menuScroll, notifButton, themeButton);
        container.setAlignment(Pos.TOP_CENTER);
        container.setPadding(new javafx.geometry.Insets(0, 0, 8, 0));
        if (OsDetect.isWindows() && !AppPreferences.isDisableEffectsEffective()) {
            container.setStyle(WINDOWS_HIT_TEST_BACKGROUND);
        }

        getChildren().add(container);

        MyDrawerBuilder.init(this);
    }

    public void updateHeader(String shortName, String longName, String nodeId) {
        // Toolbar mode does not show the header.
    }

    public void rebuildMenu(List<DrawerMenuItem> items) {
        toolBar.getItems().clear();

        for (DrawerMenuItem item : items) {
            Node node = createMenuNode(item);
            if (node != null) {
                toolBar.getItems().add(node);
            }
        }

        updateSelection();
    }

    private Node createMenuNode(DrawerMenuItem item) {
        if (item.type() == DrawerMenuItem.Type.LABEL) {
            if (!toolBar.getItems().isEmpty()) {
                Separator sep = new Separator(Orientation.HORIZONTAL);
                sep.getStyleClass().add("drawer-toolbar-separator");
                return sep;
            }
            return null;
        }
        if (item.type() == DrawerMenuItem.Type.SEPARATOR) {
            return null;
        }

        Button btn;
        if (item.iconPath() != null) {
            // SVG icon from resources; color is controlled through CSS.
            SVGPath svgIcon = SvgIconLoader.load(item.iconPath(), 22);
            if (svgIcon != null) {
                btn = new Button();
                btn.setGraphic(svgIcon);
                btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            } else {
                btn = new Button("?");
            }
        } else {
            // Text icon used for chats and channels.
            btn = new Button(item.iconText() != null ? item.iconText() : "?");
        }
        btn.getStyleClass().add("drawer-toolbar-button");
        btn.setTooltip(new Tooltip(item.name()));
        btn.getProperties().put(ORIGINAL_TOOLTIP_KEY, item.name());
        if (item.formClass() != null) {
            btn.setUserData(item.formClass());
        }
        updateNavigationBlockState(btn);
        btn.setOnAction(e -> {
            if (item.action() != null) {
                item.action().run();
            }
        });

        // Unread dot for the Chats button.
        if (item.formClass() == FormChat.class && btn.getGraphic() != null) {
            chatBadgeDot = new Circle(4);
            chatBadgeDot.getStyleClass().add("drawer-chat-badge-dot");
            chatBadgeDot.setVisible(false);
            StackPane wrapper = new StackPane(btn.getGraphic(), chatBadgeDot);
            StackPane.setAlignment(chatBadgeDot, Pos.TOP_RIGHT);
            btn.setGraphic(wrapper);
        }

        return btn;
    }

    private Button createThemeButton() {
        Button btn = new Button();
        btn.getStyleClass().add("drawer-toolbar-button");
        btn.setTooltip(new Tooltip(I18n.t("drawer.theme.toggle")));
        updateThemeIcon(btn, AppPreferences.isDarkMode());
        btn.setOnAction(e -> {
            boolean newDark = !AppPreferences.isDarkMode();
            AppPreferences.setDarkMode(newDark);
            if (MeshApp.getPrimaryStage() != null && MeshApp.getPrimaryStage().getScene() != null) {
                ThemeManager.applyTheme(MeshApp.getPrimaryStage().getScene(), newDark);
            }
            updateThemeIcon(btn, newDark);
        });
        return btn;
    }

    private void updateThemeIcon(Button btn, boolean isDark) {
        String iconPath = isDark ? "/icons/light.svg" : "/icons/dark.svg";
        SVGPath svgIcon = SvgIconLoader.load(iconPath, 22);
        if (svgIcon != null) {
            btn.setText(null);
            btn.setGraphic(svgIcon);
            btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            btn.setGraphic(null);
            btn.setText(isDark ? "L" : "D");
            btn.setContentDisplay(ContentDisplay.TEXT_ONLY);
        }
    }

    private Button createNotificationButton() {
        Button btn = new Button();
        btn.getStyleClass().add("drawer-toolbar-button");
        updateNotifIcon(btn, AppPreferences.isNotificationsEnabled());
        btn.setOnAction(e -> {
            boolean newState = !AppPreferences.isNotificationsEnabled();
            AppPreferences.setNotificationsEnabled(newState);
            updateNotifIcon(btn, newState);
        });
        return btn;
    }

    private void updateNotifIcon(Button btn, boolean enabled) {
        String iconPath = enabled ? NOTIFICATION_ON_ICON : NOTIFICATION_OFF_ICON;
        SVGPath svgIcon = SvgIconLoader.load(iconPath, 22);
        if (svgIcon != null) {
            btn.setText(null);
            btn.setGraphic(svgIcon);
            btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            btn.setGraphic(null);
            btn.setText(enabled ? "N" : "N/");
            btn.setContentDisplay(ContentDisplay.TEXT_ONLY);
        }
        btn.setTooltip(new Tooltip(I18n.t(enabled ? "drawer.notifications.on" : "drawer.notifications.off")));
        btn.setOpacity(1.0);
    }

    public void setSelectedItemClass(Class<?> cls) {
        this.selectedItemClass = cls;
        updateSelection();
        if (cls == FormChat.class && chatBadgeDot != null) {
            chatBadgeDot.setVisible(false);
        }
    }

    private void updateSelection() {
        for (Node node : toolBar.getItems()) {
            if (node instanceof Button btn) {
                boolean selected = selectedItemClass != null
                        && selectedItemClass.equals(btn.getUserData());
                if (selected) {
                    if (!btn.getStyleClass().contains("drawer-toolbar-button-selected")) {
                        btn.getStyleClass().add("drawer-toolbar-button-selected");
                    }
                } else {
                    btn.getStyleClass().remove("drawer-toolbar-button-selected");
                }
            }
        }
    }

    public void setNavigationBlockedItemClasses(Set<Class<?>> formClasses) {
        navigationBlockedItemClasses.clear();
        if (formClasses != null) {
            navigationBlockedItemClasses.addAll(formClasses);
        }
        updateNavigationBlockState();
    }

    private void updateNavigationBlockState() {
        for (Node node : toolBar.getItems()) {
            if (node instanceof Button btn) {
                updateNavigationBlockState(btn);
            }
        }
    }

    private void updateNavigationBlockState(Button btn) {
        Object userData = btn.getUserData();
        boolean blocked = userData instanceof Class<?> formClass
                && navigationBlockedItemClasses.contains(formClass);
        btn.setDisable(blocked);

        Object originalTooltip = btn.getProperties().get(ORIGINAL_TOOLTIP_KEY);
        String tooltipText = blocked
                ? I18n.t("drawer.navigationBlocked")
                : originalTooltip instanceof String text ? text : null;
        btn.setTooltip(tooltipText != null ? new Tooltip(tooltipText) : null);
    }

    public void setChatUnreadDot(boolean visible) {
        if (chatBadgeDot != null) {
            chatBadgeDot.setVisible(visible && selectedItemClass != FormChat.class);
        }
    }

    public void setCompact(boolean compact) {
        // no-op
    }

    public boolean isCompact() {
        return false;
    }

    public record DrawerMenuItem(
            String name,
            String iconText,
            String iconPath,
            Type type,
            Class<?> formClass,
            Runnable action
    ) {
        public enum Type { ITEM, LABEL, SEPARATOR }
    }
}
