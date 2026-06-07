package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Builds the configuration tab panel and exposes controls managed by the form.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigPanelFactory {

    private ConfigPanelFactory() {}

    /**
     * Creates the configuration editor panel.
     *
     * @param actions                  toolbar actions
     * @param helpPopupController      shared help popup controller
     * @param searchConsumer           search query consumer
     * @param repeatedEditSynchronizer repeated-field edit callback
     * @return panel controls
     */
    public static Controls create(
        ToolbarActions actions,
        ConfigHelpPopupController helpPopupController,
        Consumer<String> searchConsumer,
        Consumer<ConfigTreeItem> repeatedEditSynchronizer
    ) {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(5));

        TextField searchField = new TextField();
        searchField.setPromptText(
            I18n.t("settings.config.search.placeholder")
        );
        searchField
            .textProperty()
            .addListener((obs, oldVal, newVal) -> searchConsumer.accept(newVal));

        ConfigToolbarFactory.Controls toolbarControls =
            ConfigToolbarFactory.create(
                actions.refresh(),
                actions.syncTime(),
                actions.saveRadio(),
                actions.restart(),
                actions.shutdown(),
                actions.resetDatabase(),
                actions.exportConfig(),
                actions.importConfig(),
                actions.exportTemplate(),
                actions.importTemplate()
            );
        ToolBar toolbar = toolbarControls.toolbar();

        Label statusLabel = new Label("");
        statusLabel.getStyleClass().add("config-status-label");
        statusLabel.setWrapText(true);

        TreeTableView<ConfigTreeItem> tree = createConfigTree(
            helpPopupController,
            repeatedEditSynchronizer
        );
        VBox.setVgrow(tree, Priority.ALWAYS);

        panel.getChildren().addAll(searchField, toolbar, statusLabel, tree);
        return new Controls(
            panel,
            searchField,
            statusLabel,
            tree,
            toolbarControls.refreshButton(),
            toolbarControls.syncTimeButton(),
            toolbarControls.saveRadioButton(),
            toolbarControls.restartButton(),
            toolbarControls.shutdownButton(),
            toolbarControls.resetDatabaseButton()
        );
    }

    private static TreeTableView<ConfigTreeItem> createConfigTree(
        ConfigHelpPopupController helpPopupController,
        Consumer<ConfigTreeItem> repeatedEditSynchronizer
    ) {
        TreeTableView<ConfigTreeItem> tree = new TreeTableView<>();
        tree.setShowRoot(false);
        tree.setEditable(true);

        TreeTableColumn<ConfigTreeItem, String> nameColumn =
            new TreeTableColumn<>(
                I18n.t("settings.config.column.parameter")
            );
        nameColumn.setCellValueFactory(param ->
            new SimpleStringProperty(
                param.getValue().getValue() != null
                    ? param.getValue().getValue().getName()
                    : ""
            )
        );
        nameColumn.setPrefWidth(280);
        nameColumn.setEditable(false);
        nameColumn.setCellFactory(col ->
            new ConfigNameCell(helpPopupController)
        );

        TreeTableColumn<ConfigTreeItem, ConfigTreeItem> valueColumn =
            new TreeTableColumn<>(I18n.t("settings.config.column.value"));
        valueColumn.setCellValueFactory(param ->
            new SimpleObjectProperty<>(param.getValue().getValue())
        );
        valueColumn.setPrefWidth(300);
        valueColumn.setCellFactory(col ->
            new ConfigValueCell(repeatedEditSynchronizer)
        );

        tree.getColumns().addAll(List.of(nameColumn, valueColumn));
        tree.setColumnResizePolicy(
            TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        );
        return tree;
    }

    /**
     * Toolbar action callbacks.
     *
     * @param refresh        refresh action
     * @param syncTime       sync time action
     * @param saveRadio      save radio action
     * @param restart        restart action
     * @param shutdown       shutdown action
     * @param resetDatabase  reset database action
     * @param exportConfig   export config action
     * @param importConfig   import config action
     * @param exportTemplate export template action
     * @param importTemplate import template action
     */
    public record ToolbarActions(
        Runnable refresh,
        Runnable syncTime,
        Runnable saveRadio,
        Runnable restart,
        Runnable shutdown,
        Runnable resetDatabase,
        Runnable exportConfig,
        Runnable importConfig,
        Runnable exportTemplate,
        Runnable importTemplate
    ) {}

    /**
     * Controls managed by the settings form.
     *
     * @param panel               panel root
     * @param searchField         search field
     * @param statusLabel         status label
     * @param configTree          config tree
     * @param refreshButton       refresh button
     * @param syncTimeButton      sync time button
     * @param saveRadioButton     save radio button
     * @param restartButton       restart button
     * @param shutdownButton      shutdown button
     * @param resetDatabaseButton reset database button
     */
    public record Controls(
        VBox panel,
        TextField searchField,
        Label statusLabel,
        TreeTableView<ConfigTreeItem> configTree,
        Button refreshButton,
        Button syncTimeButton,
        Button saveRadioButton,
        Button restartButton,
        Button shutdownButton,
        Button resetDatabaseButton
    ) {}
}
