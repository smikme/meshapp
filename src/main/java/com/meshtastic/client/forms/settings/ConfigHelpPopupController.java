package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.utils.ConfigHelpContent;
import java.util.List;
import java.util.Optional;
import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.util.Duration;

/**
 * Dark configuration-help popup with scrollable multi-line documentation.
 * The controller owns popup positioning and delayed hiding while cells only
 * provide an anchor node and help content.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigHelpPopupController {

    private static final double POPUP_WIDTH = 460;
    private static final double POPUP_HEIGHT = 360;
    private static final double SCREEN_MARGIN = 8;
    private static final double ANCHOR_GAP = 6;
    private static final double HIDE_DELAY_MS = 220;

    private Popup popup;
    private PauseTransition hideDelay;

    /**
     * Shows help popup next to an anchor node.
     *
     * @param anchor      icon or control used as popup anchor
     * @param helpContent help content for one configuration item
     */
    public void show(Node anchor, ConfigHelpContent helpContent) {
        if (!canShow(anchor, helpContent)) {
            return;
        }

        cancelHide();
        hide();

        VBox popupContent = createPopupContent(helpContent);
        popupContent.setOnMouseEntered(e -> cancelHide());
        popupContent.setOnMouseExited(e -> scheduleHide());

        Popup nextPopup = new Popup();
        nextPopup.setAutoHide(true);
        nextPopup.setHideOnEscape(true);
        nextPopup.getContent().add(popupContent);

        Optional
            .ofNullable(anchor.localToScreen(anchor.getBoundsInLocal()))
            .map(this::positionFor)
            .ifPresent(position -> {
                popup = nextPopup;
                nextPopup.show(anchor, position.x(), position.y());
            });
    }

    /**
     * Starts delayed popup hiding.
     */
    public void scheduleHide() {
        if (hideDelay == null) {
            hideDelay = new PauseTransition(Duration.millis(HIDE_DELAY_MS));
            hideDelay.setOnFinished(e -> hide());
        }
        hideDelay.playFromStart();
    }

    /**
     * Cancels scheduled popup hiding.
     */
    public void cancelHide() {
        Optional.ofNullable(hideDelay).ifPresent(PauseTransition::stop);
    }

    /**
     * Hides the current popup.
     */
    public void hide() {
        Optional.ofNullable(popup).ifPresent(Popup::hide);
        popup = null;
    }

    private static boolean canShow(Node anchor, ConfigHelpContent helpContent) {
        return anchor != null &&
            helpContent != null &&
            helpContent.hasDetails() &&
            anchor.getScene() != null &&
            anchor.getScene().getWindow() != null;
    }

    private VBox createPopupContent(ConfigHelpContent helpContent) {
        VBox wrapper = new VBox(8);
        wrapper.getStyleClass().add("config-help-popup");
        wrapper.setPrefWidth(POPUP_WIDTH);
        wrapper.setMaxWidth(POPUP_WIDTH);
        wrapper.setPrefHeight(POPUP_HEIGHT);

        Label title = new Label(helpContent.title());
        title.getStyleClass().add("config-help-title");
        title.setWrapText(true);

        Label path = new Label(helpContent.path());
        path.getStyleClass().add("config-help-path");
        path.setWrapText(true);

        VBox body = new VBox(10);
        body.getStyleClass().add("config-help-body");
        addBlock(
            body,
            I18n.t("settings.config.help.block.summary"),
            helpContent.summary()
        );
        addBlock(
            body,
            I18n.t("settings.config.help.block.whenToUse"),
            helpContent.whenToUse()
        );
        addBlock(
            body,
            I18n.t("settings.config.help.block.defaultBehavior"),
            helpContent.defaultBehavior()
        );
        addBlock(
            body,
            I18n.t("settings.config.help.block.valueHint"),
            helpContent.valueHint()
        );
        addValuesBlock(body, helpContent.values());
        addListBlock(
            body,
            I18n.t("settings.config.help.block.notes"),
            helpContent.notes()
        );
        addBlock(
            body,
            I18n.t("settings.config.help.block.technical"),
            helpContent.technicalDetails()
        );

        ScrollPane scrollPane = new ScrollPane(body);
        scrollPane.getStyleClass().add("config-help-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPrefViewportWidth(POPUP_WIDTH - 28);
        scrollPane.setPrefViewportHeight(POPUP_HEIGHT - 82);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        wrapper.getChildren().addAll(title, path, scrollPane);
        return wrapper;
    }

    private void addBlock(VBox body, String header, String text) {
        Optional
            .ofNullable(text)
            .filter(value -> !value.isBlank())
            .ifPresent(value -> {
                VBox block = new VBox(3);
                block.getStyleClass().add("config-help-block");

                Label headerLabel = new Label(header);
                headerLabel.getStyleClass().add("config-help-block-title");

                Label textLabel = new Label(value);
                textLabel.getStyleClass().add("config-help-text");
                textLabel.setWrapText(true);

                block.getChildren().addAll(headerLabel, textLabel);
                body.getChildren().add(block);
            });
    }

    private void addValuesBlock(
        VBox body,
        List<ConfigHelpContent.ValueHelp> values
    ) {
        List<ConfigHelpContent.ValueHelp> visibleValues = Optional
            .ofNullable(values)
            .stream()
            .flatMap(List::stream)
            .toList();
        if (visibleValues.isEmpty()) {
            return;
        }

        VBox block = new VBox(5);
        block.getStyleClass().add("config-help-block");
        Label headerLabel = new Label(
            I18n.t("settings.config.help.block.values")
        );
        headerLabel.getStyleClass().add("config-help-block-title");
        block.getChildren().add(headerLabel);

        block
            .getChildren()
            .addAll(
                visibleValues
                    .stream()
                    .map(ConfigHelpPopupController::createValueRow)
                    .toList()
            );
        body.getChildren().add(block);
    }

    private void addListBlock(VBox body, String header, List<String> items) {
        List<String> visibleItems = Optional
            .ofNullable(items)
            .stream()
            .flatMap(List::stream)
            .filter(item -> item != null && !item.isBlank())
            .toList();
        if (visibleItems.isEmpty()) {
            return;
        }

        VBox block = new VBox(4);
        block.getStyleClass().add("config-help-block");
        Label headerLabel = new Label(header);
        headerLabel.getStyleClass().add("config-help-block-title");
        block.getChildren().add(headerLabel);
        block
            .getChildren()
            .addAll(
                visibleItems
                    .stream()
                    .map(item -> {
                        Label itemLabel = new Label("- " + item);
                        itemLabel.getStyleClass().add("config-help-text");
                        itemLabel.setWrapText(true);
                        return itemLabel;
                    })
                    .toList()
            );
        body.getChildren().add(block);
    }

    private Position positionFor(Bounds anchorBounds) {
        Rectangle2D screenBounds = Screen
            .getScreensForRectangle(
                anchorBounds.getMinX(),
                anchorBounds.getMinY(),
                anchorBounds.getWidth(),
                anchorBounds.getHeight()
            )
            .stream()
            .findFirst()
            .orElse(Screen.getPrimary())
            .getVisualBounds();

        double x = anchorBounds.getMaxX() + ANCHOR_GAP;
        if (x + POPUP_WIDTH > screenBounds.getMaxX()) {
            x = anchorBounds.getMinX() - POPUP_WIDTH - ANCHOR_GAP;
        }
        x = Math.max(screenBounds.getMinX() + SCREEN_MARGIN, x);

        double y = anchorBounds.getMinY();
        if (y + POPUP_HEIGHT > screenBounds.getMaxY()) {
            y = screenBounds.getMaxY() - POPUP_HEIGHT - SCREEN_MARGIN;
        }
        y = Math.max(screenBounds.getMinY() + SCREEN_MARGIN, y);
        return new Position(x, y);
    }

    private static VBox createValueRow(ConfigHelpContent.ValueHelp value) {
        VBox row = new VBox(2);
        row.getStyleClass().add("config-help-value-row");

        String title = value.value();
        if (value.title() != null && !value.title().isBlank()) {
            title += " - " + value.title();
        }

        Label valueTitle = new Label(title);
        valueTitle.getStyleClass().add("config-help-value-title");
        valueTitle.setWrapText(true);

        Label valueDescription = new Label(value.description());
        valueDescription.getStyleClass().add("config-help-text");
        valueDescription.setWrapText(true);

        row.getChildren().addAll(valueTitle, valueDescription);
        return row;
    }

    private record Position(double x, double y) {}
}
