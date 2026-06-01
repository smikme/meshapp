package com.meshtastic.client.forms;

import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.utils.AppPreferences;
import com.meshtastic.client.utils.SvgIconLoader;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

import java.util.Optional;

/**
 * Small chat-form UI helpers that do not depend on the selected chat state.
 *
 * <p>The class contains only reusable control factories and safe JavaFX node
 * operations. Chat, search, and data logic remain in separate components so
 * these helpers can be reused without side effects.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class FormChatUiSupport {

    private FormChatUiSupport() {}

    /**
     * Adds an almost transparent background on Windows, where empty JavaFX nodes
     * may otherwise be skipped by window hit testing.
     */
    static void applyWindowsHitTestBackground(Node node) {
        if (OsDetect.isWindows() && !AppPreferences.isDisableEffectsEffective()) {
            node.setStyle(FormChatBase.WINDOWS_HIT_TEST_BACKGROUND);
        }
    }

    /**
     * Creates a compact header button with an SVG icon and text fallback.
     */
    static Button createHeaderIconButton(String iconPath, String tooltip, String fallbackText) {
        Button button = new Button();
        button.getStyleClass().add("chat-header-icon-btn");
        Optional.ofNullable(SvgIconLoader.load(iconPath, 17))
                .ifPresentOrElse(button::setGraphic, () -> button.setText(fallbackText));
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    /**
     * Creates a button for navigating message search results.
     */
    static Button createMessageSearchNavButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().addAll("chat-header-icon-btn", "chat-message-search-nav-btn");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    /**
     * Updates {@code visible} and {@code managed} together so hidden nodes do not
     * reserve layout space.
     */
    static void setVisibleManaged(Node node, boolean visible) {
        Optional.ofNullable(node).ifPresent(target -> {
            target.setVisible(visible);
            target.setManaged(visible);
        });
    }
}
