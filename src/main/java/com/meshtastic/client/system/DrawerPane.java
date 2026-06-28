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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.forms.FormChat;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.menu.MyDrawerBuilder;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.themes.ThemeManager;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.SvgIconLoader;
import com.meshtastic.client.utils.UnicodeTextUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class DrawerPane extends StackPane {

    public static final double TOOLBAR_WIDTH = 56;
    private static final String WINDOWS_HIT_TEST_BACKGROUND = "-fx-background-color: rgba(0,0,0,0.004);";
    private static final String ORIGINAL_TOOLTIP_KEY = "drawer.originalTooltip";
    private static final String FORM_CLASS_KEY = "drawer.formClass";
    private static final String MONOCHROME_ICON_SOURCE_KEY = "drawer.monochromeIconSource";
    private static final String NOTIFICATION_ON_ICON = "/drawer/icon/bell.svg";
    private static final String NOTIFICATION_OFF_ICON = "/drawer/icon/bell-off.svg";
    private static final double EXTENSION_ICON_SIZE = 22.0;
    private static final Map<String, Image> MONOCHROME_EMOJI_CACHE = new ConcurrentHashMap<>();

    private final ToolBar toolBar;
    private final Button themeButton;
    private final Button notifButton;
    private final Set<Class<?>> navigationBlockedItemClasses = new HashSet<>();
    private Object selectedItemKey;
    private Circle chatBadgeDot;
    private boolean chatUnreadDotVisible;

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
            if (item.monochromeTextIcon()) {
                // Extension script icons are user emoji; draw them as a graphic
                // so the global emoji renderer does not install a color image.
                btn = new Button();
                btn.setGraphic(createMonochromeTextIcon(item.iconText()));
                btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                btn.hoverProperty().addListener((obs, wasHover, isHover) -> updateMonochromeIconColor(btn));
            } else {
                // Plain text icon fallback for non-extension dynamic entries.
                btn = new Button(item.iconText() != null ? item.iconText() : "?");
            }
        }
        btn.getStyleClass().add("drawer-toolbar-button");
        btn.setTooltip(new Tooltip(item.name()));
        btn.getProperties().put(ORIGINAL_TOOLTIP_KEY, item.name());
        if (item.formClass() != null) {
            btn.getProperties().put(FORM_CLASS_KEY, item.formClass());
        }
        Object selectionKey = item.selectionKey() != null ? item.selectionKey() : item.formClass();
        if (selectionKey != null) {
            btn.setUserData(selectionKey);
        }
        updateMonochromeIconColor(btn);
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
            chatBadgeDot.setVisible(chatUnreadDotVisible);
            StackPane wrapper = new StackPane(btn.getGraphic(), chatBadgeDot);
            StackPane.setAlignment(chatBadgeDot, Pos.TOP_RIGHT);
            btn.setGraphic(wrapper);
        }

        return btn;
    }

    private Node createMonochromeTextIcon(String text) {
        String iconText = UnicodeTextUtils.sanitizeForJavaFxDisplay(text).trim();
        if (iconText.isEmpty()) {
            iconText = "?";
        }

        Image monochromeImage = monochromeEmojiImage(iconText, toolbarIconColor(false, false));
        if (monochromeImage != null) {
            ImageView imageIcon = new ImageView(monochromeImage);
            imageIcon.getProperties().put(MONOCHROME_ICON_SOURCE_KEY, iconText);
            imageIcon.setFitWidth(EXTENSION_ICON_SIZE);
            imageIcon.setFitHeight(EXTENSION_ICON_SIZE);
            imageIcon.setPreserveRatio(true);
            imageIcon.setSmooth(true);
            imageIcon.getStyleClass().add("drawer-toolbar-emoji-icon");
            imageIcon.setMouseTransparent(true);
            return imageIcon;
        }

        Text textIcon = new Text(iconText);
        textIcon.getStyleClass().add("drawer-toolbar-text-icon");
        textIcon.setMouseTransparent(true);
        return textIcon;
    }

    private void updateMonochromeIconColor(Button button) {
        if (button == null || !(button.getGraphic() instanceof ImageView imageView)) {
            return;
        }
        Object source = imageView.getProperties().get(MONOCHROME_ICON_SOURCE_KEY);
        if (!(source instanceof String iconText) || iconText.isBlank()) {
            return;
        }
        boolean selected = button.getStyleClass().contains("drawer-toolbar-button-selected");
        Color color = toolbarIconColor(selected, button.isHover());
        Image image = monochromeEmojiImage(iconText, color);
        if (image != null && imageView.getImage() != image) {
            imageView.setImage(image);
        }
    }

    private void updateMonochromeIconColors() {
        for (Node node : toolBar.getItems()) {
            if (node instanceof Button button) {
                updateMonochromeIconColor(button);
            }
        }
    }

    private static Color toolbarIconColor(boolean selected, boolean hover) {
        boolean light = !AppPreferences.isDarkMode();
        if (light) {
            if (selected) {
                return Color.rgb(0, 0, 0, 0.9);
            }
            return hover ? Color.rgb(0, 0, 0, 0.85) : Color.rgb(0, 0, 0, 0.6);
        }
        if (selected) {
            return Color.WHITE;
        }
        return hover ? Color.rgb(255, 255, 255, 0.9) : Color.rgb(255, 255, 255, 0.7);
    }

    private static Image monochromeEmojiImage(String emoji, Color color) {
        Image source = EmojiImageCache.getImage(emoji);
        if (source == null || source.getPixelReader() == null) {
            return null;
        }
        String cacheKey = emoji + "|" + colorKey(color);
        return MONOCHROME_EMOJI_CACHE.computeIfAbsent(cacheKey, ignored -> renderMonochromeEmoji(source, color));
    }

    private static Image renderMonochromeEmoji(Image source, Color color) {
        int width = Math.max(1, (int) Math.ceil(source.getWidth()));
        int height = Math.max(1, (int) Math.ceil(source.getHeight()));
        PixelReader reader = source.getPixelReader();
        WritableImage result = new WritableImage(width, height);
        PixelWriter writer = result.getPixelWriter();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Color sourceColor = reader.getColor(x, y);
                double alpha = monochromeAlpha(sourceColor, color);
                writer.setColor(x, y, alpha <= 0.0
                        ? Color.TRANSPARENT
                        : new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
            }
        }
        return result;
    }

    private static double monochromeAlpha(Color sourceColor, Color targetColor) {
        double sourceAlpha = sourceColor.getOpacity();
        if (sourceAlpha <= 0.0) {
            return 0.0;
        }
        double luminance = 0.2126 * sourceColor.getRed()
                + 0.7152 * sourceColor.getGreen()
                + 0.0722 * sourceColor.getBlue();

        // Preserve emoji interior detail: bright source pixels become stronger
        // monochrome pixels, dark strokes become transparent cuts in the mask.
        double detailMask = Math.pow(Math.max(0.0, Math.min(1.0, luminance)), 0.85);
        return sourceAlpha * targetColor.getOpacity() * detailMask;
    }

    private static String colorKey(Color color) {
        return Math.round(color.getRed() * 255)
                + ","
                + Math.round(color.getGreen() * 255)
                + ","
                + Math.round(color.getBlue() * 255)
                + ","
                + Math.round(color.getOpacity() * 255);
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
            updateMonochromeIconColors();
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
        setSelectedItemKey(cls);
    }

    public void setSelectedItemKey(Object key) {
        this.selectedItemKey = key;
        updateSelection();
        if (key == FormChat.class) {
            setChatUnreadDot(false);
        }
    }

    private void updateSelection() {
        for (Node node : toolBar.getItems()) {
            if (node instanceof Button btn) {
                boolean selected = selectedItemKey != null
                        && Objects.equals(selectedItemKey, btn.getUserData());
                if (selected) {
                    if (!btn.getStyleClass().contains("drawer-toolbar-button-selected")) {
                        btn.getStyleClass().add("drawer-toolbar-button-selected");
                    }
                } else {
                    btn.getStyleClass().remove("drawer-toolbar-button-selected");
                }
                updateMonochromeIconColor(btn);
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
        Object formClassValue = btn.getProperties().get(FORM_CLASS_KEY);
        boolean blocked = formClassValue instanceof Class<?> formClass
                && navigationBlockedItemClasses.contains(formClass);
        btn.setDisable(blocked);

        Object originalTooltip = btn.getProperties().get(ORIGINAL_TOOLTIP_KEY);
        String tooltipText = blocked
                ? I18n.t("drawer.navigationBlocked")
                : originalTooltip instanceof String text ? text : null;
        btn.setTooltip(tooltipText != null ? new Tooltip(tooltipText) : null);
    }

    public void setChatUnreadDot(boolean visible) {
        chatUnreadDotVisible = visible && selectedItemKey != FormChat.class;
        if (chatBadgeDot != null) {
            chatBadgeDot.setVisible(chatUnreadDotVisible);
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
            boolean monochromeTextIcon,
            Type type,
            Class<?> formClass,
            Object selectionKey,
            Runnable action
    ) {
        public enum Type { ITEM, LABEL, SEPARATOR }
    }
}
