package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.NodeCacheService;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Controller for the node-cache settings tab.
 * It owns cache table construction, lazy paging, OneMesh import, cache clearing,
 * and user-facing cache status updates.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class NodeCacheSettingsController {

    private static final int PAGE_SIZE = 100;

    private final ObservableList<NodeData> cacheData =
        FXCollections.observableArrayList();
    private TableView<NodeData> cacheTable;
    private Label cacheStatusLabel;
    private int cacheOffset;
    private int cacheTotalInDb;

    /**
     * Creates the cache tab panel.
     *
     * @return JavaFX panel
     */
    public VBox createPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(5));
        panel.setMaxHeight(Double.MAX_VALUE);

        Button importButton = new Button(
            I18n.t("settings.cache.importOneMesh")
        );
        importButton.setOnAction(e -> onImportFromOneMesh(importButton));

        Button clearButton = new Button(I18n.t("settings.cache.clear"));
        clearButton.setStyle("-fx-text-fill: #E53935;");
        clearButton.setOnAction(e -> onClearCache());

        HBox buttonRow = new HBox(8, importButton, clearButton);

        cacheTable = new TableView<>(cacheData);
        cacheTable.setFixedCellSize(28);
        cacheTable.setMaxHeight(Double.MAX_VALUE);
        cacheTable
            .getColumns()
            .addAll(List.of(
                textColumn(
                    I18n.t("settings.cache.column.longName"),
                    150,
                    node -> sanitize(node.getLongName())
                ),
                textColumn(
                    I18n.t("settings.cache.column.shortName"),
                    80,
                    node -> sanitize(node.getShortName())
                ),
                textColumn(
                    I18n.t("settings.cache.column.nodeId"),
                    100,
                    node -> safeText(node.getNodeId())
                ),
                textColumn(
                    I18n.t("settings.cache.column.model"),
                    120,
                    node -> safeText(node.getHwModel())
                ),
                textColumn(
                    I18n.t("settings.cache.column.latitude"),
                    70,
                    node -> coordinateText(node.getLatitude())
                ),
                textColumn(
                    I18n.t("settings.cache.column.longitude"),
                    70,
                    node -> coordinateText(node.getLongitude())
                )
            ));
        cacheTable.setColumnResizePolicy(
            TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        );
        installLazyLoading();
        VBox.setVgrow(cacheTable, Priority.ALWAYS);

        cacheStatusLabel = new Label("");
        cacheStatusLabel.setStyle("-fx-opacity: 0.6;");

        panel.getChildren().addAll(buttonRow, cacheTable, cacheStatusLabel);
        return panel;
    }

    /**
     * Reloads cache records from the first page.
     */
    public void reload() {
        cacheOffset = 0;
        cacheData.clear();
        cacheTotalInDb = NodeCacheService.getInstance().countNodesInDb();
        loadNextPage();
        updateStatus();
    }

    /**
     * The Cache tab displays names as read-only values. Keep the general Unicode
     * normalization for user text without removing valid emoji.
     *
     * @param value raw cache text
     * @return JavaFX-safe display text
     */
    public static String sanitize(String value) {
        return CacheDisplayText.sanitize(value);
    }

    private void onImportFromOneMesh(Button button) {
        button.setDisable(true);
        cacheStatusLabel.setText(I18n.t("settings.cache.loading"));

        Thread importThread = new Thread(() -> {
            try {
                int count = NodeCacheService.getInstance().importFromOneMesh();
                Platform.runLater(() -> {
                    button.setDisable(false);
                    reload();
                    ModalPane.showInfo(
                        I18n.t("settings.cache.import.title"),
                        I18n.t("settings.cache.import.success", count)
                    );
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    button.setDisable(false);
                    reload();
                    ModalPane.showError(
                        I18n.t("settings.cache.import.error.title"),
                        I18n.t(
                            "settings.cache.import.error.message",
                            ex.getMessage()
                        )
                    );
                });
            }
        }, "onemesh-import");
        importThread.setDaemon(true);
        importThread.start();
    }

    private void onClearCache() {
        ModalPane.showConfirm(
            I18n.t("settings.cache.clear.title"),
            I18n.t("settings.cache.clear.confirm"),
            confirmed -> {
                if (confirmed) {
                    NodeCacheService.getInstance().clearAll();
                    reload();
                }
            }
        );
    }

    private void loadNextPage() {
        if (cacheOffset >= cacheTotalInDb) {
            return;
        }
        List<NodeData> page = NodeCacheService.getInstance().loadPage(
            cacheOffset,
            PAGE_SIZE
        );
        cacheData.addAll(page);
        cacheOffset += page.size();
        updateStatus();
    }

    private void updateStatus() {
        int loaded = cacheData.size();
        if (cacheTotalInDb == 0) {
            cacheStatusLabel.setText(I18n.t("settings.cache.empty"));
        } else if (loaded >= cacheTotalInDb) {
            cacheStatusLabel.setText(
                I18n.t("settings.cache.loaded", loaded, cacheTotalInDb)
            );
        } else {
            cacheStatusLabel.setText(
                I18n.t("settings.cache.loadedMore", loaded, cacheTotalInDb)
            );
        }
    }

    private void installLazyLoading() {
        cacheTable.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin == null) {
                return;
            }
            cacheTable
                .lookupAll(".scroll-bar")
                .stream()
                .filter(ScrollBar.class::isInstance)
                .map(ScrollBar.class::cast)
                .filter(scrollBar ->
                    scrollBar.getOrientation() == Orientation.VERTICAL
                )
                .findFirst()
                .ifPresent(scrollBar ->
                    scrollBar
                        .valueProperty()
                        .addListener((o, oldVal, newVal) -> {
                            if (newVal.doubleValue() > 0.9) {
                                loadNextPage();
                            }
                        })
                );
        });
    }

    private static TableColumn<NodeData, String> textColumn(
        String title,
        double width,
        java.util.function.Function<NodeData, String> valueProvider
    ) {
        TableColumn<NodeData, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cellData ->
            new SimpleStringProperty(valueProvider.apply(cellData.getValue()))
        );
        column.setPrefWidth(width);
        return column;
    }

    private static String safeText(String value) {
        return Optional.ofNullable(value).orElse("");
    }

    private static String coordinateText(double value) {
        return value != 0 ? String.format("%.3f", value) : "";
    }
}
