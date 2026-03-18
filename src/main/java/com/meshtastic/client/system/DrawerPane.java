package com.meshtastic.client.system;

import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.forms.FormChat;
import com.meshtastic.client.menu.MyDrawerBuilder;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.SvgIconLoader;

import java.util.List;

public class DrawerPane extends StackPane {

    public static final double TOOLBAR_WIDTH = 56;

    private final ToolBar toolBar;
    private final Button themeButton;
    private final Button notifButton;
    private Class<?> selectedItemClass;
    private Circle chatBadgeDot;

    public DrawerPane() {
        getStyleClass().add("drawer-pane");
        setPrefWidth(TOOLBAR_WIDTH);
        setMinWidth(TOOLBAR_WIDTH);
        setMaxWidth(TOOLBAR_WIDTH);

        toolBar = new ToolBar();
        toolBar.setOrientation(Orientation.VERTICAL);
        toolBar.getStyleClass().add("drawer-toolbar");

        // Кнопки уведомлений и темы — прижаты к низу
        notifButton = createNotificationButton();
        themeButton = createThemeButton();

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox container = new VBox(toolBar, spacer, notifButton, themeButton);
        container.setAlignment(Pos.TOP_CENTER);
        container.setPadding(new javafx.geometry.Insets(0, 0, 8, 0));

        getChildren().add(container);

        MyDrawerBuilder.init(this);
    }

    public void updateHeader(String shortName, String longName, String nodeId) {
        // В режиме ToolBar header не отображается
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
            // SVG-иконка из ресурсов — цвет управляется через CSS
            SVGPath svgIcon = SvgIconLoader.load(item.iconPath(), 22);
            if (svgIcon != null) {
                btn = new Button();
                btn.setGraphic(svgIcon);
                btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            } else {
                btn = new Button("?");
            }
        } else {
            // Текстовая иконка (для чатов/каналов)
            btn = new Button(item.iconText() != null ? item.iconText() : "?");
        }
        btn.getStyleClass().add("drawer-toolbar-button");
        btn.setTooltip(new Tooltip(item.name()));
        if (item.formClass() != null) {
            btn.setUserData(item.formClass());
        }
        btn.setOnAction(e -> {
            if (item.action() != null) {
                item.action().run();
            }
        });

        // Красная точка для кнопки "Чаты"
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
        btn.setTooltip(new Tooltip("Переключить тему"));
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
        // ☀ для тёмной темы (переключить на светлую), ☾ для светлой (переключить на тёмную)
        btn.setText(isDark ? "\u2600" : "\u263E");
    }

    private Button createNotificationButton() {
        SVGPath svgIcon = SvgIconLoader.load("/drawer/icon/bell.svg", 22);
        Button btn = new Button();
        btn.getStyleClass().add("drawer-toolbar-button");
        if (svgIcon != null) {
            btn.setGraphic(svgIcon);
            btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            btn.setText("\uD83D\uDD14");
        }
        btn.setTooltip(new Tooltip("Уведомления"));
        updateNotifIcon(btn, AppPreferences.isNotificationsEnabled());
        btn.setOnAction(e -> {
            boolean newState = !AppPreferences.isNotificationsEnabled();
            AppPreferences.setNotificationsEnabled(newState);
            updateNotifIcon(btn, newState);
        });
        return btn;
    }

    private void updateNotifIcon(Button btn, boolean enabled) {
        btn.setOpacity(enabled ? 1.0 : 0.4);
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
