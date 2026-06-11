package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.utils.ConfigDescriptionResolver;
import com.meshtastic.client.utils.ConfigHelpContent;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * Parameter-name cell with an inline help icon.
 * The cell resolves only one help article for the currently rendered item and
 * delegates popup rendering to {@link ConfigHelpPopupController}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigNameCell
    extends TreeTableCell<ConfigTreeItem, String> {

    private final Label nameLabel = new Label();
    private final Label helpIcon = new Label("?");
    private final HBox content = new HBox(6, nameLabel, helpIcon);
    private final ConfigHelpPopupController helpPopupController;
    private ConfigHelpContent helpContent;

    /**
     * Creates a cell bound to a shared popup controller.
     *
     * @param helpPopupController popup controller
     */
    public ConfigNameCell(ConfigHelpPopupController helpPopupController) {
        this.helpPopupController = helpPopupController;
        configureContent();
        configureHelpIcon();
    }

    @Override
    protected void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            helpContent = null;
            setText(null);
            setGraphic(null);
            setContentDisplay(ContentDisplay.LEFT);
            setStyle("");
            return;
        }

        TreeItem<ConfigTreeItem> treeItem = Optional
            .ofNullable(getTableRow())
            .map(row -> row.getTreeItem())
            .orElse(null);
        ConfigTreeItem data = Optional
            .ofNullable(treeItem)
            .map(TreeItem::getValue)
            .orElse(null);
        helpContent = ConfigDescriptionResolver.helpFor(data);

        nameLabel.setText(item);
        helpIcon.setAccessibleText(accessibleHelpText(helpContent));
        setText(null);
        setGraphic(content);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setStyle(data != null && data.isCategory() ? "-fx-font-weight: bold;" : "");
    }

    private void configureContent() {
        content.setAlignment(Pos.CENTER_LEFT);
        nameLabel.setMinWidth(0);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox.setHgrow(nameLabel, Priority.NEVER);
    }

    private void configureHelpIcon() {
        helpIcon.getStyleClass().add("config-help-icon");
        helpIcon.setMinSize(16, 16);
        helpIcon.setPrefSize(16, 16);
        helpIcon.setMaxSize(16, 16);
        helpIcon.setAlignment(Pos.CENTER);
        helpIcon.setFocusTraversable(true);
        helpIcon.setOnMouseEntered(e ->
            helpPopupController.show(helpIcon, helpContent)
        );
        helpIcon.setOnMouseExited(e -> helpPopupController.scheduleHide());
        helpIcon
            .focusedProperty()
            .addListener((obs, wasFocused, focused) -> {
                if (focused) {
                    helpPopupController.show(helpIcon, helpContent);
                } else {
                    helpPopupController.scheduleHide();
                }
            });
    }

    private static String accessibleHelpText(ConfigHelpContent content) {
        return I18n.t("settings.config.help.icon") +
            (
                content == null || !content.hasDetails()
                    ? ""
                    : ": " + content.plainText()
            );
    }
}
