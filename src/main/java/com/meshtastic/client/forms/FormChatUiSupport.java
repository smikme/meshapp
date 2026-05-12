package com.meshtastic.client.forms;

import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.SvgIconLoader;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

/**
 * Малые UI-хелперы формы чата, не завязанные на состояние выбранного чата.
 *
 * <p>Класс содержит только фабрики повторяемых контролов и безопасные операции
 * над JavaFX-узлами. Логика чата, поиска и данных остаётся в отдельных
 * компонентах, чтобы эти методы можно было переиспользовать без побочных
 * эффектов.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class FormChatUiSupport {

    private FormChatUiSupport() {}

    /**
     * Добавляет почти прозрачный фон на Windows, где пустые JavaFX-узлы иначе
     * могут не участвовать в hit-test области окна.
     */
    static void applyWindowsHitTestBackground(Node node) {
        if (OsDetect.isWindows() && !AppPreferences.isDisableEffectsEffective()) {
            node.setStyle(FormChatBase.WINDOWS_HIT_TEST_BACKGROUND);
        }
    }

    /**
     * Создаёт компактную кнопку заголовка с SVG-иконкой и текстовым fallback.
     */
    static Button createHeaderIconButton(String iconPath, String tooltip, String fallbackText) {
        Button button = new Button();
        button.getStyleClass().add("chat-header-icon-btn");
        Node icon = SvgIconLoader.load(iconPath, 17);
        if (icon != null) {
            button.setGraphic(icon);
            button.setTooltip(new Tooltip(tooltip));
            return button;
        }
        button.setText(fallbackText);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    /**
     * Создаёт кнопку навигации по результатам поиска сообщений.
     */
    static Button createMessageSearchNavButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().addAll("chat-header-icon-btn", "chat-message-search-nav-btn");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    /**
     * Синхронно меняет {@code visible} и {@code managed}, чтобы скрытый узел не
     * занимал место в layout.
     */
    static void setVisibleManaged(Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
